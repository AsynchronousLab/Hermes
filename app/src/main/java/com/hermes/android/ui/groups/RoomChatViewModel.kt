package com.hermes.android.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.model.Group
import com.hermes.android.core.model.GroupSendResult
import com.hermes.android.core.model.MAIN_THREAD_ID
import com.hermes.android.core.model.RoomDriverStatus
import com.hermes.android.core.model.RoomEvent
import com.hermes.android.core.model.RoomLog
import com.hermes.android.ui.chat.friendly
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomUiState(
    val room: Group = Group(),
    val driver: RoomDriverStatus? = null,
    val messages: List<RoomMessage> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val supported: Boolean? = null,
    val error: String? = null,
    val notice: String? = null,
    val historyWarning: String? = null,
    /**
     * Text handed back to the composer after a refused or definitively-failed
     * send, so a retry is one tap instead of a retype. Cleared once applied.
     */
    val restoreDraft: String? = null,
)

/**
 * One room, driven by repeated `groups.log` reads.
 *
 * The gateway has **no incremental log fetch** — every cursor parameter is
 * rejected as an extra input — so every poll refetches the page and this class
 * diffs locally by `seq`. That is not a workaround: the Desktop documents the
 * same catch-up-by-log behaviour. The log is capped at `max_log_limit` (500).
 *
 * Nothing here touches the shared chat socket, so a busy or reconnecting
 * session cannot stall the room.
 *
 * Event `kind`s render generically. Only `room.renamed` was observed live; the
 * gateway advertises `typed_events` but the message-event name is unverified,
 * so the transcript shows whatever arrives instead of guessing.
 */
class RoomChatViewModel(
    private val repo: HermesRepository,
    private val roomId: String,
    /**
     * Profile named on `groups.send`. It does **not** decide which side a
     * bubble sits on — the log stamps the sender's transport (`desktop`, kind
     * `user`), which is what identifies "mine". Kept only as the send
     * parameter.
     */
    private val selfProfile: String,
    /** Poll cadence; overridable so behaviour tests can run polls in milliseconds. */
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(RoomUiState())
    val state: StateFlow<RoomUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Highest `seq` rendered, so a refetched page adds nothing twice. */
    private var topSeq: Long? = null

    init {
        viewModelScope.launch {
            when (val caps = runCatching { repo.groupCapabilities() }.getOrNull()) {
                null -> {
                    // A failed probe is a transport hiccup, not a verdict on
                    // the gateway: the room still loads and polls, sending
                    // stays enabled (a truly unsupported gateway answers the
                    // send itself, loudly), and the poll re-probes until it
                    // resolves. Hard-failing here left a spinner forever and
                    // no way into the room.
                    _state.update { it.copy(supported = null, error = PROBE_ERROR) }
                }
                else -> {
                    val hasSend = caps.methods.contains("groups.send")
                    _state.update { it.copy(supported = hasSend) }
                    if (!hasSend) {
                        _state.update { it.copy(error = "网关未提供 groups.send，只能查看") }
                    }
                }
            }
            load()
            startPolling()
        }
    }

    private suspend fun load() {
        val st = runCatching { repo.groupState(roomId) }
        val room = st.getOrNull()?.room ?: Group()
        _state.update {
            it.copy(
                room = room,
                driver = st.getOrNull()?.driverStatus,
                loading = false,
                error = st.exceptionOrNull()?.let { t -> "读取房间失败：${t.friendly()}" },
            )
        }
        drain()
    }

    /** Refetches the log page and appends whatever `seq` we have not shown yet. */
    private suspend fun drain() {
        val page: RoomLog = runCatching { repo.groupLog(roomId) }
            .getOrElse { t ->
                _state.update { it.copy(error = "读取消息失败：${t.friendly()}") }
                return
            }
        val known = topSeq
        val warning = roomLogWarning(page, known)
        _state.update { it.copy(historyWarning = warning ?: it.historyWarning) }
        val fresh = page.events
            .filter { it.seq != null && (known == null || it.seq > known) }
            .mapNotNull { it.toMessage() }
        if (fresh.isEmpty()) return
        topSeq = page.events.maxOf { it.seq ?: 0L }
        _state.update { s ->
            // Send confirmations can arrive ahead of older events in this page.
            // Deduplicate by event id and restore wire order after each merge.
            s.copy(messages = mergeRoomMessages(s.messages, fresh))
        }
    }

    /**
     * Classifies one log entry.
     *
     * The side a bubble sits on is decided by `actor.kind`, not by comparing
     * `actor.id` to a profile name. Captured live from a real room:
     *
     *   message.user    actor.kind=user    actor.id=desktop   ← the human
     *   message.member  actor.kind=member  actor.id=default|writer
     *   turn.settled    actor.kind=gateway actor.id=install:…
     *   room.activity   actor.kind=gateway
     *   room.renamed    actor.kind=system  actor.id=room-control
     *
     * Comparing against a profile named "default" put the human's own message
     * on the left and a member's reply on the right — the transport, not the
     * profile, is what identifies the sender.
     */
    private fun RoomEvent.toMessage(): RoomMessage? {
        val seq = seq ?: return null
        val actorKind = actor?.kind.orEmpty()
        val actorId = actor?.id
        // Only kind == "user" is us. Members are agents; gateway and system
        // actors are room machinery, not conversation.
        val mine = actorKind == ACTOR_USER
        val system = actorKind != ACTOR_USER && actorKind != ACTOR_MEMBER
        return RoomMessage(
            key = eventId ?: "seq-$seq",
            seq = seq,
            kind = kind.orEmpty(),
            actorId = actorId,
            text = displayText() ?: kind.orEmpty(),
            mine = mine,
            system = system,
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(pollIntervalMs)
                if (_state.value.sending) continue
                if (_state.value.supported == null) probeCapabilities()
                // The log page says nothing about the room around it: without
                // this, a hold, a block, a resume or an outside rename never
                // reached the header after the first load.
                if (++tick % STATE_REFRESH_POLLS == 0) refreshRoomState()
                drain()
            }
        }
    }

    /** Re-probes `groups.send` support; a transient failure stays "unknown". */
    private suspend fun probeCapabilities() {
        val caps = runCatching { repo.groupCapabilities() }.getOrNull() ?: return
        val hasSend = caps.methods.contains("groups.send")
        _state.update { s ->
            s.copy(
                supported = hasSend,
                error = when {
                    !hasSend -> "网关未提供 groups.send，只能查看"
                    s.error == PROBE_ERROR -> null
                    else -> s.error
                },
            )
        }
    }

    private suspend fun refreshRoomState() {
        val st = runCatching { repo.groupState(roomId) }.getOrNull() ?: return
        _state.update { s -> s.copy(room = st.room, driver = st.driverStatus) }
    }

    /**
     * Accepts the draft, or reports false so the composer can keep it.
     *
     * The sync guards (empty, busy, read-only room) return false and the draft
     * survives untouched. An async refusal or definitive failure removes the
     * echo but hands the text back via [RoomUiState.restoreDraft] — a refused
     * post used to vanish from both the transcript and the composer, so a long
     * message had to be retyped from scratch.
     */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return false
        if (_state.value.supported == false) {
            _state.update { it.copy(error = "网关未提供 groups.send，只能查看") }
            return false
        }
        // One logical post, one idempotency key, minted before the first wire
        // attempt and held on the echo so every retry of this post reuses it.
        val eventId = repo.newSendEventId()
        val echoKey = "local-${System.nanoTime()}"
        _state.update { s ->
            s.copy(
                sending = true,
                error = null,
                // Echo immediately so the composer feels instant.
                messages = s.messages + RoomMessage(
                    key = echoKey,
                    seq = Long.MAX_VALUE,
                    kind = "message",
                    actorId = selfProfile,
                    text = trimmed,
                    mine = true,
                    system = false,
                    sendState = RoomSendState.Sending,
                    eventId = eventId,
                ),
            )
        }
        viewModelScope.launch { dispatchSend(echoKey, trimmed, eventId) }
        return true
    }

    /**
     * Re-attempts one post whose outcome is unknown, under its original
     * idempotency key.
     *
     * If the first attempt actually landed, the gateway answers the retry with
     * the original confirmation; if it never arrived, the retry delivers it —
     * one copy either way, because the key is the same. This is offered only
     * for unknown outcomes: an explicit refusal never reached the room, so
     * there the user just re-composes.
     */
    fun retry(key: String) {
        if (_state.value.sending) return
        val echo = _state.value.messages
            .firstOrNull { it.key == key && it.sendState == RoomSendState.Unknown } ?: return
        _state.update { s ->
            s.copy(
                sending = true,
                error = null,
                messages = s.messages.map { m ->
                    if (m.key == key) m.copy(sendState = RoomSendState.Sending) else m
                },
            )
        }
        viewModelScope.launch {
            dispatchSend(key, echo.text, echo.eventId ?: repo.newSendEventId())
        }
    }

    /** One wire attempt and every way it can settle the echo. */
    private suspend fun dispatchSend(echoKey: String, text: String, eventId: String) {
        runCatching { repo.sendToGroup(roomId, selfProfile, text, MAIN_THREAD_ID, eventId) }
            .onSuccess { sent -> settleSend(echoKey, text, sent) }
            .onFailure { t ->
                if (isUnknownOutcome(t)) {
                    // The post may already be in the log. The gateway cannot
                    // be asked "did you take it?" — the log does not echo the
                    // client event_id — so the bubble stays, marked retryable
                    // under the same key; a fresh key here is exactly what
                    // double-delivers.
                    _state.update { s ->
                        s.copy(
                            sending = false,
                            error = "发送结果未知：消息可能已送达，点击该消息可安全重试（不会重复发送）",
                            messages = s.messages.map { m ->
                                if (m.key == echoKey) m.copy(sendState = RoomSendState.Unknown) else m
                            },
                        )
                    }
                } else {
                    // Definitive: the gateway answered "no", or the frame
                    // never left the phone. Nothing was delivered, so the
                    // words go back to the composer instead of vanishing with
                    // the echo.
                    _state.update { s ->
                        s.copy(
                            sending = false,
                            error = "发送失败：${t.friendly()}",
                            restoreDraft = text,
                            messages = s.messages.filterNot { m -> m.key == echoKey },
                        )
                    }
                }
            }
    }

    private fun settleSend(echoKey: String, text: String, sent: GroupSendResult) {
        // `groups.send` answers with the authoritative event, so the echo can
        // be settled right here instead of waiting for the poll to notice a
        // matching row. The event we *sent* is not the key the log uses — the
        // gateway mints its own ("user:<hash>") — so pairing has to use this one.
        val confirmed = sent.event?.toMessage()
        // A successful RPC is not a successful post. A room on hold answers
        // `accepted: false`, and a refusal carries no event at all.
        val refused = sent.accepted == false || confirmed == null
        _state.update { s ->
            // The poll may have delivered this event while the send was still
            // in flight. Replacing the echo then would put the same key in the
            // list twice — and the transcript's LazyColumn keys on it, so that
            // is a crash, not just a duplicated bubble. Keep exactly one copy.
            val messages = when {
                refused -> s.messages.filterNot { it.key == echoKey }
                else -> mergeRoomMessages(
                    s.messages.filterNot { it.key == echoKey },
                    listOfNotNull(confirmed),
                )
            }
            s.copy(
                sending = false,
                error = if (refused) refusalReason(sent) else s.error,
                // A refusal never reached the room: give the words back rather
                // than deleting them from both the transcript and the composer.
                restoreDraft = if (refused) text else s.restoreDraft,
                messages = messages,
            )
        }
    }

    /**
     * Why the gateway would not take the post.
     *
     * `accepted` is tri-state on the wire — absent on a build that never sends
     * it — so only an explicit `false` is read as a refusal, and the other
     * branch covers the "no event came back" shape, where the gateway simply
     * does not report a reason.
     */
    private fun refusalReason(sent: GroupSendResult): String =
        if (sent.accepted == false) "房间未接收消息，可能已暂停" else "网关未返回确认事件"

    /** Holds the room: members stop taking new turns. */
    fun hold() = viewModelScope.launch {
        runCatching { repo.stopGroup(roomId) }.fold(
            onSuccess = { _state.update { it.copy(notice = "已暂停") } },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** The composer applied a restored draft; drop the hand-off copy. */
    fun consumeRestoreDraft() = _state.update { it.copy(restoreDraft = null) }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val repo: HermesRepository,
        private val roomId: String,
        private val selfProfile: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RoomChatViewModel(repo, roomId, selfProfile) as T
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_500L

        /** Room state (driver, name, members) refresh cadence, in polls. */
        const val STATE_REFRESH_POLLS = 4

        /** Banner shown while the capability probe is failing transitively. */
        const val PROBE_ERROR = "能力探测失败，稍后自动重试"

        /** `actor.kind` values, captured from a live room's log. */
        const val ACTOR_USER = "user"
        const val ACTOR_MEMBER = "member"
    }
}
