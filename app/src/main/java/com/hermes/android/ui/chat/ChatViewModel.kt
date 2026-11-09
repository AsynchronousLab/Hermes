package com.hermes.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.android.core.CommandEntry
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.isStaleSession
import com.hermes.android.core.model.ModelProvider
import com.hermes.android.core.model.ReasoningLevel
import com.hermes.android.core.model.SessionCreated
import com.hermes.android.core.model.SessionInfoPayload
import com.hermes.android.core.model.SessionMessage
import com.hermes.android.core.model.Usage
import com.hermes.android.core.net.HermesHttp
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.core.net.RpcEvent
import com.hermes.android.core.net.RpcFailure
import com.hermes.android.core.store.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Drives one conversation.
 *
 * Stream contract (verified against the gateway and mirrored from the
 * `hermes-lan-chat` client):
 *
 * ```
 * message.start      → open a segment
 * reasoning.delta    → reasoning text for the current segment
 * tool.start         → seal the segment, open a tool row
 * tool.complete      → close the tool row (matched by tool_id)
 * message.interim    → tool boundary; later deltas open a NEW segment
 * message.delta      → append to the current segment
 * message.complete   → authoritative final text + usage
 * session.info       → live model / reasoning / tools
 * approval.request   → server is blocked on the user
 * ```
 */
/**
 * Marker the composer writes for a staged attachment.
 *
 * Matches what `send` appends. A forked history has no uploaded bytes behind
 * it, so the markers are stripped when seeding a branch.
 */
/**
 * Removes the `@file:` attachment markers `send` appends to user text.
 *
 * Markers live on their own lines, so only whole leading-marker lines go. Lines
 * inside a fenced code block are left alone — a message quoting one is
 * talking *about* the syntax, and rewriting it would be silent data loss.
 * Blank lines are preserved for the same reason.
 */
private fun stripAttachmentMarkers(text: String): String {
    val out = StringBuilder()
    var fence: String? = null
    for (line in text.lines()) {
        val tick = line.trimStart().takeWhile { it == '`' || it == '~' }
        if (fence == null && tick.length >= 3) {
            fence = tick
        } else if (fence != null && tick.length >= fence.length) {
            fence = null
        }
        val insideFence = fence != null && tick.length < 3
        if (insideFence || !line.trimStart().startsWith("@file:")) {
            out.append(line).append('\n')
        }
    }
    return out.toString().trimEnd('\n')
}

class ChatViewModel(
    private val repo: HermesRepository,
    private val settings: SettingsStore,
    private val sessionToOpen: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /**
     * Profile this conversation is bound to, when we opened it from a profile.
     *
     * `session.list` does not expose a profile field, so a resumed session's
     * profile cannot be recovered — forking one falls back to the gateway
     * default rather than guessing.
     */
    @Volatile private var boundProfile: String? = null

    private var nextKey = 1L
    private var nextAttachId = 1L
    private var eventJob: Job? = null

    /** True while a tool call means no text segment is open. */
    private var segmentOpen = false

    /**
     * Incremented on every session switch. Async results captured for an older
     * generation are discarded instead of being written into the new session.
     */
    @Volatile private var sessionGeneration = 0L

    /**
     * `profile:<name>` opens a chat bound to that Hermes profile — each profile
     * is a separate persona with its own model and skills.
     */
    private enum class Target { NEW, PROFILE, STORED, RUNTIME }

/**
 * Decides how to open the requested session.
 *
 * The three explicit prefixes are unambiguous. A bare value is a *runtime*
 * session id from a freshly created session — treating it as a profile name
 * made "new session" immediately create a second session instead of opening
 * the one that was just made.
 */
private fun parseTarget(raw: String?): Triple<Target, String?, String?> {
    if (raw.isNullOrBlank()) return Triple(Target.NEW, null, null)
    return when {
        raw.startsWith("resume:") -> Triple(Target.STORED, null, raw.removePrefix("resume:"))
        raw.startsWith("profile:") -> Triple(Target.PROFILE, raw.removePrefix("profile:"), null)
        else -> Triple(Target.RUNTIME, null, raw)
    }
}

    init {
        observeConnection()
        observeEvents()
        viewModelScope.launch {
            val reasoningWire = runCatching { settings.reasoningLevel.first() }.getOrNull()
            _state.update { it.copy(reasoning = ReasoningLevel.from(reasoningWire)) }
            openSessionWhenConnected()
        }
    }

    /** In-flight session opens, single-slot: the initial open and every recovery retry share it. */
    private var openJob: Job? = null

    /**
     * Opens the session once a connection exists.
     *
     * A process-death restore lands here before anything has connected:
     * creating a session over a dead socket failed once and left the screen
     * dead for good, because the recovery path saw `sessionId == null` and
     * returned instead of retrying. Waiting for the link (and retrying on
     * every later recovery) keeps the screen alive.
     */
    private fun openSessionWhenConnected() {
        if (openJob?.isActive == true) return
        openJob = viewModelScope.launch {
            repo.connection.first { it == RpcConnectionState.Connected }
            openSession()
            // Model/command inventories need a live socket. Loading them in the
            // same coroutine that waited for the link is what fixes a chat that
            // opened showing "无模型" and a picker with nothing to tap.
            refreshModels()
            loadCommands()
        }
    }

    private fun loadCommands() = viewModelScope.launch {
        runCatching { repo.commands() }
            .onSuccess { list -> _state.update { s -> s.copy(commands = list) } }
    }

    /**
     * Loads the model inventory.
     *
     * Safe to call repeatedly: the picker triggers it when it opens, so an
     * inventory that failed while the socket was down is recoverable without
     * leaving the screen.
     */
    fun refreshModels() = viewModelScope.launch {
        if (_state.value.modelsLoading) return@launch
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        val gen = sessionGeneration
        val result = runCatching { repo.modelOptions() }
        if (gen != sessionGeneration) return@launch
        result.fold(
            onSuccess = { snap ->
                _state.update { s ->
                    s.copy(
                        models = snap.providers.ifEmpty { s.models },
                        modelsLoading = false,
                        // A profile pick beats "nothing selected": the header used
                        // to say 未选择模型 until the first session.info arrived.
                        model = s.model ?: snap.model,
                        provider = s.provider ?: snap.provider,
                        modelsError = if (snap.providers.isEmpty()) "服务端未返回模型列表" else null,
                    )
                }
            },
            onFailure = { t ->
                _state.update { it.copy(modelsLoading = false, modelsError = t.friendly()) }
            },
        )
    }

    private fun observeConnection() = viewModelScope.launch {
        // Seeded from the live value so the *first* Connected we observe is not
        // mistaken for a recovery and does not trigger a redundant history load.
        var wasConnected = repo.connection.value == RpcConnectionState.Connected
        repo.connection.collect { c ->
            val connected = c == RpcConnectionState.Connected
            val recovered = connected && !wasConnected
            wasConnected = connected
            _state.update { it.copy(connection = c) }
            if (recovered) onReconnected()
        }
    }

    /**
     * Repairs the transcript after the socket comes back.
     *
     * Everything the server produced while the link was down never arrived as
     * events, so the UI silently lost it. A turn that was mid-stream when the
     * socket dropped is worse: its `message.complete` is never delivered, so the
     * bubble would sit on "正在输入" for the rest of the process lifetime.
     */
    private fun onReconnected() {
        val sid = _state.value.sessionId
        if (sid == null) {
            // The initial open never landed — it ran while disconnected, or
            // the connection died underneath it. Retry now that the link is
            // back instead of leaving the screen dead for the process lifetime.
            openSessionWhenConnected()
            return
        }
        segmentOpen = false
        _state.update { s ->
            s.copy(
                streaming = false,
                messages = s.messages.map { if (it.streaming) it.copy(streaming = false) else it },
            )
        }
        // A new socket is a NEW transport: the gateway still has the session
        // bound to the dead one, so every frame of the next turn would be
        // published into the void. Re-attaching is not optional — it is what
        // makes the conversation reachable again.
        viewModelScope.launch { reattach() }
    }

    private fun observeEvents() {
        eventJob?.cancel()
        eventJob = viewModelScope.launch { repo.events.collect { handleEvent(it) } }
    }

    // ------------------------------------------------------------- session ---

    /**
     * Serialises every re-registration of this conversation.
     *
     * A socket drop, a stale-id retry and the user's own "new session" can all
     * land at once; without the lock they raced and the loser overwrote the
     * winner's runtime id with one the gateway had already reaped.
     */
    private val repairMutex = Mutex()

    private suspend fun openSession() {
        val (kind, profileName, id) = parseTarget(sessionToOpen)
        try {
            when (kind) {
                Target.PROFILE -> {
                    // Contacts: bind the new session to the chosen profile.
                    val name = profileName.orEmpty()
                    val c = repo.createSession(name)
                    boundProfile = name.takeIf { it.isNotBlank() }
                    bindSession(c, title = name.ifBlank { "会话" })
                    settings.rememberSession(c.sessionId, c.durableId)
                }

                Target.STORED -> {
                    val r = repo.resumeSession(id.orEmpty())
                    bindSession(r, title = r.info?.profileName?.takeIf { it.isNotBlank() } ?: "会话")
                    settings.rememberSession(r.sessionId, r.durableId)
                    applyResume(r)
                }

                Target.RUNTIME -> {
                    // A live session id from a session we just created: bind to
                    // it directly rather than creating another one. It has no
                    // known durable id, so a reconnect must re-create it.
                    boundProfile = null
                    val runtime = id.orEmpty()
                    bindSession(
                        SessionCreated(sessionId = runtime),
                        title = "新会话",
                    )
                    loadHistory(runtime)
                }

                Target.NEW -> {
                    boundProfile = null
                    val c = repo.createSession()
                    bindSession(c, title = "新会话")
                    settings.rememberSession(c.sessionId, c.durableId)
                }
            }
        } catch (t: Throwable) {
            val reason = if (t.isStaleSession()) {
                "该会话不存在或已被服务端清理，请返回列表刷新后重试"
            } else {
                t.friendly()
            }
            _state.update { it.copy(loading = false, error = reason) }
            return
        }
        _state.update { it.copy(loading = false) }
    }

    /**
     * Brings the conversation back under a fresh runtime id.
     *
     * Called after a socket drop and whenever a session-scoped RPC answers
     * 4001/4007 — the runtime id was detached on disconnect and orphan-reaped
     * ~20s later, so the id we hold is simply gone while the stored transcript
     * is not. Resuming the durable id is the documented recovery.
     */
    private suspend fun repairSession(failedSid: String? = null): String? = repairMutex.withLock {
        val st = _state.value
        val current = st.sessionId
        // Someone else already repaired it while we waited for the lock.
        if (failedSid != null && current != null && current != failedSid) return@withLock current

        val stored = st.storedSessionId
        if (stored.isNullOrBlank()) {
            // Never persisted: the gateway creates the DB row on the first
            // prompt, so a session that was never spoken to has nothing to
            // resume. Re-mint one and keep whatever is on screen.
            if (st.messages.any { it.role == ChatMessage.Role.User && it.delivered }) {
                // Something WAS sent, so this is not an empty draft. Silently
                // minting a new session would look like the context survived
                // when it did not — say so instead.
                _state.update {
                    it.copy(
                        streaming = false,
                        error = "会话已在服务端结束且没有可恢复的记录，请新建会话",
                    )
                }
                return@withLock null
            }
            val gen = sessionGeneration
            val fresh = runCatching { repo.createSession() }.getOrElse { t ->
                _state.update { it.copy(error = "会话已失效，且无法新建：${t.friendly()}") }
                return@withLock null
            }
            if (gen != sessionGeneration) return@withLock null
            bindIdentity(fresh)
            settings.rememberSession(fresh.sessionId, fresh.durableId)
            return@withLock fresh.sessionId
        }

        val gen = sessionGeneration
        val r = runCatching { repo.resumeSession(stored) }.getOrElse { t ->
            _state.update {
                it.copy(
                    streaming = false,
                    error = if (t.isStaleSession()) {
                        "会话已在服务端结束（$stored 不存在），请返回列表重新打开或新建会话"
                    } else {
                        "恢复会话失败：${t.friendly()}"
                    },
                )
            }
            return@withLock null
        }
        if (gen != sessionGeneration) return@withLock null
        bindIdentity(r)
        applyResume(r)
        r.sessionId
    }

    /** Re-attach after a reconnect, keeping the transcript already on screen. */
    private suspend fun reattach(): Boolean {
        val before = _state.value.sessionId
        val fresh = repairSession(failedSid = null) ?: return false
        if (fresh == before && _state.value.messages.isEmpty()) loadHistory(fresh)
        return true
    }

    /**
     * Runs [block] against a live runtime id, repairing once if the gateway
     * says the id is stale.
     *
     * This is what stops a message typed after a drop from being silently
     * rejected with "session not found".
     */
    private suspend fun <T> onLiveSession(block: suspend (String) -> T): T {
        val sid = _state.value.sessionId
        return try {
            if (sid == null) throw RpcFailure.NotConnected("*")
            block(sid)
        } catch (t: Throwable) {
            if (!t.isStaleSession()) throw t
            val fresh = repairSession(failedSid = sid)
            if (fresh == null || fresh == sid) throw t
            block(fresh)
        }
    }

    /**
     * Applies a resume/reattach reply: transcript plus the still-running flag.
     *
     * The gateway answers a resume with the full transcript, so a reconnect
     * lands the messages produced while the socket was down in one round-trip
     * instead of racing a second `session.history` call.
     */
    private suspend fun applyResume(r: SessionCreated) {
        val gen = sessionGeneration
        // Never re-enter `loadHistory` from here: this runs while the repair
        // lock is held, and that path may ask for another repair.
        val inline = r.messages
        val rows = when {
            inline != null -> inline
            // Ancient gateway: ask for the transcript directly, best effort —
            // the copy already on screen stays if this fails.
            else -> runCatching { repo.history(r.sessionId).messages }.getOrNull()
        }
        if (rows != null && (rows.isNotEmpty() || (r.messageCount ?: 0) == 0)) {
            // A repair must not drop what the server has not accepted yet: an
            // undelivered user bubble is mid-retry, and a streaming row is a
            // turn whose completion we are about to receive.
            applyHistory(rows) { m -> (m.role == ChatMessage.Role.User && !m.delivered) || m.streaming }
        }
        if (gen != sessionGeneration) return
        r.info?.let { info ->
            _state.update { s ->
                s.copy(
                    model = info.model?.takeIf { it.isNotBlank() } ?: s.model,
                    provider = info.provider?.takeIf { it.isNotBlank() } ?: s.provider,
                )
            }
        }
        if (r.running == true) {
            // A turn survived the gap: keep the typing affordance instead of
            // showing an idle chat that will suddenly grow text.
            segmentOpen = true
            _state.update { s ->
                s.copy(
                    streaming = true,
                    messages = if (s.messages.any { it.streaming }) s.messages
                    else s.messages + ChatMessage(
                        key = nextKey++,
                        role = ChatMessage.Role.Assistant,
                        text = "",
                        streaming = true,
                    ),
                )
            }
        }
    }

    private suspend fun loadHistory(sessionId: String, seed: List<SessionMessage>? = null): Boolean {
        val gen = sessionGeneration
        // Snapshot what is already on screen. Anything that shows up from here
        // on arrived live and has to survive the merge below.
        val keysAtRequest = _state.value.messages.mapTo(HashSet()) { it.key }
        val (rows, queried) = runCatching {
            (seed ?: repo.history(sessionId).messages) to sessionId
        }.recoverCatching { t ->
            // A reaped runtime id must not read as "the transcript is gone":
            // resume once and retry against the fresh id.
            if (!t.isStaleSession()) throw t
            val fresh = repairSession(failedSid = sessionId) ?: throw t
            repo.history(fresh).messages to fresh
        }.getOrElse { t ->
            // Swallowing this left the caller reporting success over an empty
            // transcript: bindSession had already cleared the messages, and
            // the fork path then showed "已从此处创建分支会话" over a blank
            // branch with no way to tell it had failed.
            _state.update { it.copy(error = "读取历史失败：${t.friendly()}") }
            return false
        }
        // A resume that landed meanwhile replaced the identity; these rows
        // belong to a runtime id this screen no longer uses.
        if (gen != sessionGeneration || _state.value.sessionId != queried) return true
        applyHistory(rows) { m -> m.key !in keysAtRequest }
        return true
    }

    /**
     * Replaces the transcript with server rows, keeping the local rows [keep]
     * selects.
     *
     * `loadHistory` keeps everything that arrived live while the request was in
     * flight; a repair keeps only rows the server does not know about yet (an
     * undelivered bubble being retried, a turn still streaming).
     */
    private fun applyHistory(rows: List<SessionMessage>, keep: (ChatMessage) -> Boolean = { false }) {
        val mapped = rows.map { m ->
            ChatMessage(
                key = nextKey++,
                role = when {
                    m.isUser -> ChatMessage.Role.User
                    m.isSystem -> ChatMessage.Role.System
                    else -> ChatMessage.Role.Assistant
                },
                text = m.body,
                reasoning = m.reasoning.orEmpty(),
                timestamp = ((m.timestamp ?: 0.0) * 1000).toLong(),
            )
        }
        _state.update { s -> s.copy(messages = mapped + s.messages.filter(keep)) }
    }

    /** Events seen before a session was bound; replayed right after binding. */
    private val preBindEvents = ArrayDeque<RpcEvent>()

    private fun bindSession(created: SessionCreated, title: String) {
        sessionGeneration++
        segmentOpen = false
        _state.update {
            // Reset every session-scoped field: carrying messages or a pending
            // approval across a switch showed the previous chat's content.
            ChatUiState(
                sessionId = created.sessionId,
                storedSessionId = created.durableId,
                title = title,
                loading = false,
                models = it.models,
                modelsLoading = it.modelsLoading,
                modelsError = it.modelsError,
                commands = it.commands,
                connection = it.connection,
                reasoning = it.reasoning,
                speakReplies = it.speakReplies,
                model = it.model,
                provider = it.provider,
            )
        }
        created.info?.model?.takeIf { it.isNotBlank() }?.let { m ->
            _state.update { it.copy(model = m) }
        }
        created.info?.provider?.takeIf { it.isNotBlank() }?.let { p ->
            _state.update { it.copy(provider = p) }
        }
        rememberDurable(created)
        // The binding now exists, so the held events can finally be filtered
        // and applied. Re-entrant safe: handleEvent no longer buffers once
        // `sessionId` is set.
        while (preBindEvents.isNotEmpty()) handleEvent(preBindEvents.removeFirst())
        loadParentLink(created.durableId)
    }

    /**
     * Rebinds the identity of the *same* conversation after a resume.
     *
     * Deliberately does not touch [sessionGeneration] or the transcript: this
     * is the same chat on a new transport, not a session switch.
     */
    private fun bindIdentity(created: SessionCreated) {
        val stored = created.durableId
        _state.update {
            it.copy(
                sessionId = created.sessionId,
                storedSessionId = stored ?: it.storedSessionId,
            )
        }
        rememberDurable(created)
    }

    private fun rememberDurable(created: SessionCreated) {
        val stored = created.durableId ?: return
        viewModelScope.launch { settings.rememberSession(created.sessionId, stored) }
    }

    /** Records the branch parent so the header can offer a jump to it. */
    private fun loadParentLink(stored: String?) {
        if (stored.isNullOrBlank()) return
        viewModelScope.launch {
            val parent = runCatching { repo.sessionDetail(stored).parentSessionId }.getOrNull()
            if (!parent.isNullOrBlank()) {
                _state.update { if (it.storedSessionId == stored) it.copy(parentSessionId = parent) else it }
            }
        }
    }

    // ------------------------------------------------------------- streaming ---

    private fun handleEvent(ev: RpcEvent) {
        val sid = _state.value.sessionId
        if (ev.type.endsWith(".changed")) return

        // The subscription starts before create/resume returns, so the first
        // `session.info`, approval or streaming frame can beat the binding.
        // Consuming it here meant it was gone for good — hold it (bounded) and
        // replay once a session is bound, where the id filter below applies.
        if (sid == null) {
            if (preBindEvents.size < PRE_BIND_EVENT_LIMIT) preBindEvents.addLast(ev)
            return
        }
        if (ev.sessionId.isNotEmpty() && ev.sessionId != sid) return

        when (ev.type) {
            "message.start" -> {
                segmentOpen = true
                _state.update { s ->
                    s.copy(
                        streaming = true,
                        messages = s.messages + ChatMessage(
                            key = nextKey++,
                            role = ChatMessage.Role.Assistant,
                            text = "",
                            streaming = true,
                        ),
                    )
                }
            }

            "message.delta" -> {
                val chunk = ev.payload.string("text")
                if (chunk.isNotEmpty()) appendToSegment(chunk)
            }

            // The gateway names live reasoning `reasoning.delta`;
            // `thinking.delta` was observed on an older build.
            "reasoning.delta", "thinking.delta" -> {
                val chunk = ev.payload.string("text").ifEmpty { ev.payload.string("delta") }
                if (chunk.isNotEmpty()) {
                    ensureSegment()
                    appendReasoning(chunk)
                }
            }

            "reasoning.available" -> {
                val text = ev.payload.string("text")
                if (text.isNotBlank()) {
                    _state.update { s -> s.copy(messages = s.messages.mapLastStreaming { it.copy(reasoning = text) }) }
                }
            }

            "tool.start" -> onToolStart(ev.payload)

            "tool.complete" -> onToolComplete(ev.payload)

            // Seals the current segment so post-tool deltas open a fresh one,
            // keeping the transcript in true chronological order.
            "message.interim" -> {
                val text = ev.payload.string("text")
                val alreadyStreamed = ev.payload.bool("already_streamed")
                if (text.isNotBlank() && !alreadyStreamed) {
                    _state.update { s ->
                        s.copy(messages = s.messages.mapLastStreaming { it.copy(text = text, streaming = false) })
                    }
                }
                // Always close, even when the text already arrived via deltas,
                // so the next delta opens a new segment instead of appending to
                // the sealed one.
                closeSegment()
            }

            "message.complete" -> {
                val text = ev.payload.string("text")
                val usage = ev.payload["usage"]?.let {
                    runCatching { HermesHttp.json.decodeFromJsonElement(Usage.serializer(), it) }.getOrNull()
                }
                val failed = ev.payload.string("error").isNotEmpty() ||
                    ev.payload.string("status") == "error"

                // Land the authoritative text on the open segment. If none is
                // open (a turn that only called tools), append a fresh message
                // rather than overwriting an earlier, already-finished one.
                segmentOpen = false
                _state.update { s ->
                    val hasOpen = s.messages.any { it.streaming }
                    val updated = when {
                        hasOpen -> s.messages.mapLastStreaming {
                            it.copy(
                                text = text.ifEmpty { it.text },
                                streaming = false,
                                usage = usage ?: it.usage,
                                failed = failed,
                            )
                        }

                        text.isNotBlank() -> s.messages + ChatMessage(
                            key = nextKey++,
                            role = ChatMessage.Role.Assistant,
                            text = text,
                            usage = usage,
                            failed = failed,
                        )

                        else -> s.messages.mapLastAssistant {
                            it.copy(usage = usage ?: it.usage, failed = failed)
                        }
                    }
                    s.copy(
                        streaming = false,
                        usage = usage ?: s.usage,
                        // Deliberately does NOT clear `pendingRequest`.
                        //
                        // An approval blocks its turn, so the server cannot send
                        // this turn's `message.complete` while the card is still
                        // open — anything pending at this point belongs to a
                        // later turn, and clearing it would dismiss a request
                        // the user can no longer answer. `respondToRequest`
                        // already clears the card, and only when it is still the
                        // same request.
                        messages = updated,
                    )
                }
            }

            "session.info" -> {
                val info = runCatching {
                    HermesHttp.json.decodeFromJsonElement(SessionInfoPayload.serializer(), ev.payload)
                }.getOrNull()
                _state.update { s ->
                    s.copy(
                        model = info?.model ?: s.model,
                        provider = info?.provider ?: s.provider,
                        reasoning = info?.reasoningEffort?.let { ReasoningLevel.from(it) } ?: s.reasoning,
                    )
                }
                info?.reasoningEffort?.let { e ->
                    viewModelScope.launch { settings.setReasoning(e) }
                }
            }

            "session.title" -> ev.payload.stringOrNull("title")?.let { t ->
                _state.update { s -> s.copy(title = t) }
            }

            "approval.request" -> _state.update {
                it.copy(
                    pendingRequest = ServerRequest(
                        id = ev.payload.stringOrNull("request_id") ?: ev.payload.stringOrNull("id").orEmpty(),
                        kind = ServerRequest.Kind.APPROVAL,
                        text = ev.payload.stringOrNull("command")
                            ?: ev.payload.stringOrNull("description")
                            ?: ev.payload.stringOrNull("reason")
                            ?: "工具请求执行操作",
                    )
                )
            }

            "clarify.request" -> _state.update {
                it.copy(
                    pendingRequest = ServerRequest(
                        id = ev.payload.stringOrNull("request_id") ?: ev.payload.stringOrNull("id").orEmpty(),
                        kind = ServerRequest.Kind.CLARIFY,
                        text = ev.payload.stringOrNull("question")
                            ?: ev.payload.stringOrNull("message")
                            ?: "Hermes 想确认一下",
                    )
                )
            }

            "error", "turn.error" -> {
                val msg = ev.payload.stringOrNull("message") ?: ev.payload.stringOrNull("error")
                    ?: "Hermes 执行出错"
                _state.update { s ->
                    s.copy(
                        error = msg,
                        streaming = false,
                        // A dead turn must not leave bubbles stuck "typing" or
                        // tools spinning forever — the same repair the
                        // reconnect path applies. A streaming row can never be
                        // forked and later merges treat it as a live message.
                        messages = s.messages.map { m ->
                            val tools = m.tools.map { t ->
                                if (t.status == ToolCall.Status.RUNNING) {
                                    t.copy(
                                        status = ToolCall.Status.FAILED,
                                        endedAt = System.currentTimeMillis(),
                                    )
                                } else t
                            }
                            if (m.streaming || tools != m.tools) {
                                m.copy(streaming = false, tools = tools)
                            } else m
                        },
                    )
                }
                segmentOpen = false
            }
        }
    }

    private fun onToolStart(p: JsonObject) {
        // Seal any open text segment so later deltas appear after the tool rows.
        closeSegment()
        val call = ToolCall(
            id = p.stringOrNull("tool_id"),
            name = p.stringOrNull("name") ?: p.stringOrNull("tool") ?: "工具",
            context = p.stringOrNull("context").orEmpty(),
            args = p["args"]?.let { it.compact() },
        )
        _state.update { s ->
            s.copy(
                messages = s.messages.mapLastAssistant { it.copy(tools = it.tools + call) }
            )
        }
    }

    private fun onToolComplete(p: JsonObject) {
        val id = p.stringOrNull("tool_id")
        val err = p.stringOrNull("error")
        val output = p["result"]?.let { it.compact() }
            ?: p["output"]?.let { it.compact() }
            ?: p["text"]?.let { it.compact() }
            ?: p["preview"]?.let { it.compact() }

        _state.update { s ->
            // Search every assistant message of the current turn, not just the
            // last one: after a tool boundary a new segment exists, and the
            // matching row can live in the earlier message.
            val msgIdx = s.messages.indexOfLast { msg ->
                msg.role == ChatMessage.Role.Assistant && msg.tools.any { t ->
                    if (id != null) t.id == id
                    else t.id == null && t.status == ToolCall.Status.RUNNING
                }
            }
            if (msgIdx < 0) return@update s

            val msg = s.messages[msgIdx]
            // With an id, match exactly. Without one, settle the *oldest*
            // running tool — indexOfLast would have closed the newest instead.
            val toolIdx = if (id != null) msg.tools.indexOfLast { it.id == id }
            else msg.tools.indexOfFirst { it.id == null && it.status == ToolCall.Status.RUNNING }
            if (toolIdx < 0) return@update s

            val copy = msg.tools.toMutableList()
            copy[toolIdx] = copy[toolIdx].copy(
                status = if (!err.isNullOrBlank()) ToolCall.Status.FAILED else ToolCall.Status.DONE,
                endedAt = System.currentTimeMillis(),
                output = output,
            )
            val messages = s.messages.toMutableList()
            messages[msgIdx] = msg.copy(tools = copy)
            s.copy(messages = messages)
        }
    }

    /**
     * Ensures an open assistant segment exists.
     *
     * A tool boundary closes the current segment, so a later `message.delta`
     * must start a *new* message rather than hunting for the last streaming one
     * — otherwise post-tool text was silently dropped, and `message.complete`
     * had nowhere to write its authoritative final text.
     */
    private fun ensureSegment() {
        if (segmentOpen) return
        segmentOpen = true
        _state.update { s ->
            s.copy(
                streaming = true,
                messages = s.messages + ChatMessage(
                    key = nextKey++,
                    role = ChatMessage.Role.Assistant,
                    text = "",
                    streaming = true,
                ),
            )
        }
    }

    /** Closes the open segment so the next text event opens a new one. */
    private fun closeSegment() {
        if (!segmentOpen) return
        segmentOpen = false
        _state.update { s -> s.copy(messages = s.messages.mapLastStreaming { it.copy(streaming = false) }) }
    }

    private fun appendToSegment(chunk: String) {
        ensureSegment()
        _state.update { s -> s.copy(messages = s.messages.mapLastStreaming { it.copy(text = it.text + chunk) }) }
    }

    private fun appendReasoning(chunk: String) = _state.update { s ->
        s.copy(messages = s.messages.mapLastStreaming { it.copy(reasoning = it.reasoning + chunk) })
    }

    // ------------------------------------------------------------------ input ---

    /**
     * Accepts the draft, or reports false so the composer can keep it.
     *
     * Returning false matters when the session is not ready yet: the composer
     * used to clear the text unconditionally and the message was silently
     * dropped because there was no session id to submit against.
     */
    fun send(text: String): Boolean {
        if (_state.value.sessionId == null) {
            _state.update { it.copy(error = "会话尚未就绪，请稍候再发送") }
            return false
        }
        val trimmed = text.trim()
        val pending = _state.value.pending
        if (trimmed.startsWith("/")) {
            runSlash(trimmed)
            return true
        }
        if (trimmed.isEmpty() && pending.isEmpty()) return false

        val refs = pending.map { AttachmentRef(it.name, it.kind, uploaded = true) }
        val composed = buildString {
            if (trimmed.isNotEmpty()) append(trimmed)
            if (pending.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(pending.joinToString("\n") { "@file:${it.name}" })
            }
        }

        // Rendered before the upload/submit round trip so the text appears
        // immediately. `delivered = false` until the gateway has it, so a failed
        // send reads as "未发送" instead of looking like a delivered message.
        val bubbleKey = nextKey++
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    key = bubbleKey,
                    role = ChatMessage.Role.User,
                    text = composed,
                    attachments = refs,
                    delivered = false,
                ),
                error = null,
            )
        }

        viewModelScope.launch {
            val gen = sessionGeneration
            val batchIds = pending.map { it.id }
            runCatching {
                // Runs against a repaired runtime id: a message typed after a
                // socket drop used to be rejected with "session not found" and
                // only surfaced as a red banner, with the prompt lost.
                onLiveSession { liveSid -> attemptSend(liveSid, composed, pending, gen) }
            }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is SendOutcome.Sent -> {
                            markDelivered(bubbleKey)
                            // The task is complete: drop exactly this batch from
                            // staging — the user may have staged new files while
                            // the send was still in flight.
                            _state.update { s ->
                                s.copy(pending = s.pending.filterNot { it.id in batchIds })
                            }
                        }

                        is SendOutcome.UploadFailed -> _state.update {
                            // The whole task stays staged — uploaded ones flagged,
                            // failed ones not — and the words go back to the
                            // composer, so the retry re-submits the *complete*
                            // prompt, not just the files that failed.
                            it.copy(
                                error = "附件上传失败，已保留待发送附件：\n" +
                                    outcome.failures.joinToString("\n"),
                                restoreDraft = trimmed,
                            )
                        }

                        // A session switch happened mid-send; the bubbles belong
                        // to the previous conversation.
                        SendOutcome.Abandoned -> Unit
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            error = t.friendly(),
                            streaming = false,
                            restoreDraft = trimmed,
                            messages = it.messages.map { m ->
                                if (m.key == bubbleKey) m.copy(delivered = false) else m
                            },
                        )
                    }
                },
            )
        }
        return true
    }

    /** One send attempt against a specific (already live) runtime id. */
    private suspend fun attemptSend(
        sessionId: String,
        composed: String,
        pending: List<PendingAttachment>,
        gen: Long,
    ): SendOutcome {
        // Attachments first: the marker in the text only resolves once the
        // bytes are on the backend. If any upload fails, do NOT submit — the
        // prompt would reference files the server never received, and the user
        // loses the chance to retry.
        val failures = uploadPending(sessionId, pending)
        if (gen != sessionGeneration) return SendOutcome.Abandoned
        if (failures.isNotEmpty()) return SendOutcome.UploadFailed(failures)
        repo.submitPrompt(sessionId, composed)
        return SendOutcome.Sent
    }

    /** Flips a user bubble to its delivered state once the gateway accepted it. */
    private fun markDelivered(key: Long) = _state.update { s ->
        s.copy(messages = s.messages.map { if (it.key == key) it.copy(delivered = true) else it })
    }

    private suspend fun uploadPending(
        sessionId: String,
        pending: List<PendingAttachment>,
    ): List<String> {
        if (pending.isEmpty()) return emptyList()
        _state.update { it.copy(uploading = true) }
        val errors = mutableListOf<String>()
        val uploadedIds = mutableSetOf<Long>()
        for (a in pending) {
            // Already landed in an earlier attempt of this same task: sending
            // the bytes again would duplicate the file server-side.
            if (a.uploaded) continue
            val kind = when (a.kind) {
                AttachmentRef.Kind.IMAGE -> com.hermes.android.core.AttachmentKind.IMAGE
                AttachmentRef.Kind.PDF -> com.hermes.android.core.AttachmentKind.PDF
                AttachmentRef.Kind.FILE -> com.hermes.android.core.AttachmentKind.FILE
            }
            val result = runCatching {
                repo.attach(sessionId, a.name, kind, a.mime, a.base64)
            }
            result.onSuccess { uploadedIds.add(a.id) }
                .onFailure { e -> errors.add("${a.name}: ${e.friendly()}") }
        }
        // Flag what landed; nothing leaves the staging list here. Evicting the
        // successful uploads on a partial failure is what broke the retry: the
        // re-send then referenced files it no longer carried. The task is only
        // complete when the prompt itself is accepted.
        _state.update { s ->
            s.copy(
                uploading = false,
                pending = s.pending.map { att ->
                    if (att.id in uploadedIds) att.copy(uploaded = true) else att
                },
            )
        }
        return errors
    }

    /**
     * Stages attachments, enforcing the per-message count and total-size caps.
 *
     * The individual file limit is applied during the read; this guards the
     * aggregate, which previously had no limit at all.
 */
fun addAttachments(items: List<PendingAttachment>) {
        val current = _state.value.pending
        val errors = mutableListOf<String>()

        val room = MAX_ATTACHMENT_COUNT - current.size
        if (room <= 0) {
            errors.add("每条消息最多 $MAX_ATTACHMENT_COUNT 个附件")
        }
        val accepted = items.take(room.coerceAtLeast(0))
        if (accepted.size < items.size) {
            errors.add("超出数量上限，仅保留前 $room 个")
        }

        // Start from what is already staged and only add a file's size once it is
        // accepted. Seeding this with `accepted.sumOf { it.size }` made the
        // first attachment count twice, so a single 9 MiB file was rejected
        // against the 16 MiB total.
        var total = current.sumOf { it.size }
        val fitting = mutableListOf<PendingAttachment>()
        for (a in accepted) {
            if (total + a.size > MAX_TOTAL_ATTACHMENT_BYTES) {
                errors.add("${a.name}：附件总大小超过 ${MAX_TOTAL_ATTACHMENT_BYTES / 1024 / 1024} MB")
            } else {
                total += a.size
                fitting.add(a)
            }
        }

        _state.update { s ->
            s.copy(
                pending = s.pending + fitting,
                error = if (errors.isEmpty()) s.error else errors.joinToString("\n"),
            )
        }
    }

    fun showError(message: String) = _state.update { it.copy(error = message) }

    fun removeAttachment(id: Long) = _state.update { s ->
        s.copy(pending = s.pending.filterNot { it.id == id })
    }

    fun nextAttachmentId(): Long = nextAttachId++

    private fun runSlash(command: String) {
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    key = nextKey++,
                    role = ChatMessage.Role.System,
                    text = command,
                    // The command line is UI chrome, not something the agent
                    // said. Only the output was marked before, so `/model` and
                    // friends were being seeded into a fork as real history.
                    isSlashInput = true,
                ),
                error = null,
            )
        }
        viewModelScope.launch {
            // Capture the generation: a slash command can still be in flight when
            // the user switches session, and its output must not land in the new one.
            val gen = sessionGeneration
            runCatching { onLiveSession { sid -> repo.slash(sid, command) } }.fold(
                onSuccess = { res ->
                    if (gen != sessionGeneration) return@fold
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                key = nextKey++,
                                role = ChatMessage.Role.Assistant,
                                text = res.output.ifBlank { "(无输出)" },
                                isSlashOutput = true,
                            )
                        )
                    }
                },
                onFailure = { t ->
                    if (gen == sessionGeneration) _state.update { it.copy(error = t.friendly()) }
                },
            )
        }
    }

    fun insertCommand(name: String) {
        if (_state.value.sessionId == null) return
        runSlash(name)
    }

    // ------------------------------------------------------------- controls ---

    fun selectModel(provider: ModelProvider, model: String) = viewModelScope.launch {
        val gen = sessionGeneration
        _state.update { it.copy(notice = "正在切换模型…") }
        runCatching { onLiveSession { sid -> repo.switchModel(sid, model, provider.slug) } }.fold(
            onSuccess = { res ->
                if (gen != sessionGeneration) return@fold
                _state.update {
                    it.copy(
                        model = model,
                        provider = provider.slug,
                        notice = res.output.ifBlank { "已切换到 $model" },
                    )
                }
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    fun selectReasoning(level: ReasoningLevel) = viewModelScope.launch {
        val gen = sessionGeneration
        settings.setReasoning(level.wire)
        _state.update { it.copy(reasoning = level) }
        runCatching { onLiveSession { sid -> repo.setReasoning(sid, level) } }.onFailure { t ->
            if (gen == sessionGeneration) _state.update { it.copy(error = t.friendly()) }
        }
    }

    /**
 * Answers an `approval.request` / `clarify.request`.
 *
 * Only clears the card on success, and only when it is still the same request —
 * a late reply for request A used to dismiss a newer request B, leaving the
 * user unable to retry.
 */
fun respondToRequest(request: ServerRequest, choice: String) = viewModelScope.launch {
        runCatching { repo.serverResponse(request.id, choice) }.fold(
            onSuccess = {
                _state.update { s ->
                    if (s.pendingRequest?.id == request.id) s.copy(pendingRequest = null)
                    else s
                }
            },
            onFailure = { t ->
                _state.update { it.copy(error = "回应失败：${t.friendly()}，请重试") }
            },
        )
    }


    /**
 * Forks the conversation from a single assistant reply.
 *
 * `session.branch` takes only `session_id` — the gateway has no message-level
 * fork (every candidate parameter is rejected as an extra input). The way to
 * branch at a point is to seed a *new* session with the history prefix, which
 * `session.create` supports via `messages` + `parent_session_id`.
 *
 * Two sharp edges, both verified live:
 *  - the text field is `content`; `body`/`text` are silently dropped
 *  - an unrecognised `role` is silently dropped too — no error, just fewer
 *    messages. Everything unrecognised is dropped before the call so the seed
 *    cannot silently come up short.
 */
    fun forkFrom(message: ChatMessage) = viewModelScope.launch {
        val sid = _state.value.sessionId ?: return@launch
        val cut = _state.value.messages.indexOfFirst { it.key == message.key }
        if (cut < 0) return@launch

        val prefix = _state.value.messages.take(cut + 1)
            .mapNotNull { m ->
                // Slash-command output, the command line itself, and streaming
                // placeholders are UI-only rows with no server-side counterpart;
                // seeding them would turn local chrome into fake history.
                if (m.isSlashOutput || m.isSlashInput || m.streaming) return@mapNotNull null
                val role = when (m.role) {
                    ChatMessage.Role.User -> "user"
                    ChatMessage.Role.Assistant -> "assistant"
                    ChatMessage.Role.System -> "system"
                }
                // A user bubble that never made it to the gateway must not
                // become real history in the branch.
                if (m.role == ChatMessage.Role.User && !m.delivered) return@mapNotNull null
                // Attachment markers are written by `send` into *user* text and
                // point at bytes uploaded to the original session, which the
                // branch has none of. Strip them from user bubbles only: an
                // assistant reply can legitimately contain a line starting with
                // `@file:` — inside a code block, for instance — and deleting
                // those would silently rewrite the model's own words. Fenced
                // blocks are skipped so even a user message quoting one keeps it.
                val text = if (m.role == ChatMessage.Role.User) {
                    stripAttachmentMarkers(m.text)
                } else {
                    m.text
                }.trim()
                if (text.isEmpty()) null else (role to text)
            }
        if (prefix.isEmpty()) {
            _state.update { it.copy(error = "这条消息之前没有可分叉的内容") }
            return@launch
        }

        _state.update { it.copy(notice = "正在创建分支…") }
        val gen = sessionGeneration
        // Link the branch to the DURABLE parent: a runtime id dies with the
        // socket, and the branch would then point at nothing.
        val parent = _state.value.storedSessionId ?: sid
        runCatching { repo.forkSessionFrom(parent, prefix, boundProfile) }.fold(
            onSuccess = { c ->
                if (gen != sessionGeneration) return@fold
                // Move to the branch rather than leaving the user in the old
                // one: the point of "继续从这里" is to work in the branch. It
                // also keeps `rememberSession` coherent — switching session and
                // pointing the resume pointer at it is self-consistent, whereas
                // not switching while rewriting the pointer made the next
                // launch resume a session the user had left.
                bindSession(c, title = "${_state.value.title} · 分支")
                // The branch exists either way, but claiming success over an
                // empty transcript hides a load failure behind a cheerful toast.
                val seeded = c.messages?.takeIf { it.isNotEmpty() }
                val loaded = if (seeded != null) {
                    applyHistory(seeded); true
                } else {
                    loadHistory(c.sessionId)
                }
                _state.update {
                    if (it.sessionId != c.sessionId) it
                    else if (loaded) it.copy(notice = "已从此处创建分支会话")
                    else it.copy(notice = "分支已创建，但历史加载失败")
                }
            },
            onFailure = { t ->
                if (gen == sessionGeneration) {
                    _state.update { it.copy(error = "创建分支失败：${t.friendly()}") }
                }
            },
        )
    }

    fun newSession() = viewModelScope.launch {
        val gen = sessionGeneration
        // A fresh session is unbound: leaving the previous chat's profile
        // attached meant a fork from here silently ran under that profile.
        boundProfile = null
        runCatching { repo.createSession() }.fold(
            onSuccess = { c ->
                // Same late-response race as fork(): a session switch that
                // started after this one must not be overwritten.
                if (gen != sessionGeneration) return@fold
                bindSession(c, title = "新会话")
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    fun clearNotice() = _state.update { it.copy(notice = null, error = null) }
    fun setSpeakReplies(v: Boolean) = _state.update { it.copy(speakReplies = v) }
    fun setListening(v: Boolean) = _state.update { it.copy(listening = v) }

    /** The composer applied a restored draft; drop the hand-off copy. */
    fun consumeRestoreDraft() = _state.update { it.copy(restoreDraft = null) }

    override fun onCleared() {
        eventJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val repo: HermesRepository,
        private val settings: SettingsStore,
        private val sessionToOpen: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repo, settings, sessionToOpen) as T
    }
}

// ------------------------------------------------------------------ helpers ---

/** How one send attempt finished; keeps "upload failed" distinct from "sent". */
private sealed interface SendOutcome {
    data object Sent : SendOutcome
    data class UploadFailed(val failures: List<String>) : SendOutcome
    data object Abandoned : SendOutcome
}

/** Maps the currently streaming assistant message, if any. */
private fun List<ChatMessage>.mapLastStreaming(block: (ChatMessage) -> ChatMessage): List<ChatMessage> {
    val idx = indexOfLast { it.streaming }
    if (idx < 0) return this
    return toMutableList().also { it[idx] = block(it[idx]) }
}

/** Maps the most recent assistant message, whether or not it is streaming. */
private fun List<ChatMessage>.mapLastAssistant(block: (ChatMessage) -> ChatMessage): List<ChatMessage> {
    val idx = indexOfLast { it.role == ChatMessage.Role.Assistant }
    if (idx < 0) return this
    return toMutableList().also { it[idx] = block(it[idx]) }
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

private fun kotlinx.serialization.json.JsonElement.compact(): String? = when (this) {
    is kotlinx.serialization.json.JsonPrimitive -> content
    else -> toString().takeIf { it != "{}" && it != "null" }
}

internal fun Throwable.friendly(): String = when (this) {
    is RpcFailure.Rpc -> message
    is RpcFailure.NotConnected -> "尚未连接到后端，请先在设置中测试连接"
    is RpcFailure.Timeout -> "请求超时：$message"
    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误"
}
