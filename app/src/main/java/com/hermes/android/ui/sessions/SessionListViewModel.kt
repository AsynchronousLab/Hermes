package com.hermes.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.model.SessionSummary
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.core.net.RpcFailure
import com.hermes.android.core.store.SettingsStore
import com.hermes.android.ui.chat.friendly
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionListUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val rooms: List<com.hermes.android.core.model.Group> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val query: String = "",
    val connection: RpcConnectionState = RpcConnectionState.Disconnected,
    val error: String? = null,
    val needsSetup: Boolean = false,
    val stats: SessionStats? = null,
    /** Ids with a delete in flight; their rows stay disabled until it settles. */
    val deleting: Set<String> = emptySet(),
    val notice: String? = null,
) {
    /** Chats and rooms interleaved by recency — the IM-style single list. */
    val conversations: List<Conversation>
        get() = (sessions.map { Conversation.Chat(it) } + rooms.map { Conversation.Room(it) })
            .sortedByDescending { it.stamp ?: 0.0 }

    val visible: List<Conversation>
        get() {
            val q = query.trim()
            return if (q.isEmpty()) conversations else conversations.filter {
                it.title.contains(q, true) || it.preview.contains(q, true) || it.id.contains(q, true)
            }
        }
}

data class SessionStats(val total: Int, val messages: Int, val bySource: Map<String, Int>)

/**
 * One row of the unified conversation list.
 *
 * Rooms live here too, the way an IM app puts DMs, groups and bots in one
 * list — the group is a conversation, not a separate destination. Only chats
 * can be deleted or resumed; a room is opened through its own route.
 */
sealed interface Conversation {
    val id: String
    val title: String
    val preview: String
    val stamp: Double?

    data class Chat(val summary: SessionSummary) : Conversation {
        override val id: String get() = summary.id
        override val title: String get() = summary.displayTitle
        override val preview: String
            get() = summary.preview?.takeIf { it.isNotBlank() } ?: "（无预览）"
        override val stamp: Double? get() = summary.startedAt
    }

    data class Room(val room: com.hermes.android.core.model.Group) : Conversation {
        override val id: String get() = room.roomId
        override val title: String get() = room.name.ifBlank { room.roomId }
        override val preview: String
            get() = room.members.joinToString("、") { it.handle.ifBlank { it.memberId } }
        override val stamp: Double? get() = room.updatedAt ?: room.createdAt
    }
}

/** Conversation list: the landing surface, plus live refresh on server events. */
class SessionListViewModel(
    private val repo: HermesRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionListUiState())
    val state: StateFlow<SessionListUiState> = _state.asStateFlow()

    private var watchJob: Job? = null

    init {
        observeConnection()
        observeSessionChanges()
        // Pick up saved settings and dial the backend, so the list fills itself.
        viewModelScope.launch {
            val cfg = settings.config.first()
            repo.updateConfig(cfg)
            if (cfg.isComplete) {
                repo.connect().onFailure { t -> _state.update { it.copy(error = t.friendly()) } }
                refresh()
            } else {
                _state.update { it.copy(loading = false, needsSetup = true) }
            }
        }
    }

    private fun observeConnection() = viewModelScope.launch {
        repo.connection.collect { c ->
            _state.update { it.copy(connection = c) }
            if (c == RpcConnectionState.Connected) refresh()
        }
    }

    /** `sessions.changed` fires whenever a title or new session lands. */
    private fun observeSessionChanges() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            repo.events.collect { ev ->
                if (ev.type == "sessions.changed") refresh(silent = true)
            }
        }
    }

    fun refresh(silent: Boolean = false) = viewModelScope.launch {
        // Without a backend there is nothing to list; stop the spinner and say so
        // instead of spinning forever.
        if (!repo.config.isComplete) {
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    needsSetup = true,
                    error = null,
                )
            }
            return@launch
        }
        _state.update { it.copy(refreshing = !silent && !it.loading, error = null, needsSetup = false) }
        // Rooms join the same list. A gateway without group support answers
        // `groups.list` with an error, which must not take the whole list down.
        runCatching { repo.listAllGroups() }.onSuccess { rooms ->
            _state.update { it.copy(rooms = rooms) }
        }
        runCatching { repo.listSessions() }.fold(
            onSuccess = { list ->
                _state.update { it.copy(sessions = list, loading = false, refreshing = false) }
                loadStats()
            },
            onFailure = { t ->
                _state.update {
                    it.copy(loading = false, refreshing = false, error = t.friendly())
                }
            },
        )
    }

    private fun loadStats() = viewModelScope.launch {
        runCatching {
            val body = repo.rest.getJson("/api/sessions/stats")
            val total = (body["total"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val messages = (body["messages"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val bySource = (body["by_source"] as? kotlinx.serialization.json.JsonObject)
                ?.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
                ?: emptyMap()
            SessionStats(total, messages, bySource)
        }.onSuccess { s -> _state.update { it.copy(stats = s) } }
    }

    fun search(q: String) = _state.update { it.copy(query = q) }

    fun newSession(onCreated: (String) -> Unit) = viewModelScope.launch {
        runCatching { repo.createSession() }.fold(
            onSuccess = { c ->
                settings.rememberSession(c.sessionId, c.storedSessionId)
                onCreated(c.sessionId)
                refresh(silent = true)
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    /** Open a stored session: the gateway wants the durable id for `session.resume`. */
    fun open(s: SessionSummary, navigate: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.resumeSession(s.id) }.fold(
                onSuccess = { c ->
                    settings.rememberSession(c.sessionId, c.storedSessionId)
                    navigate("resume:${s.id}")
                },
                onFailure = { t ->
                    // Stay on the list instead of navigating. The chat screen
                    // opens `resume:<id>` by issuing the very same
                    // `session.resume`, so navigating here only replayed the
                    // failure and surfaced the error in two places at once.
                    // Leaving the user here lets them retry or pick another
                    // session with the reason visible.
                    val detail = (t as? RpcFailure.Rpc)?.message?.take(60)
                    _state.update {
                        it.copy(error = "该会话无法恢复" + (detail?.let { d -> "：$d" } ?: ""))
                    }
                },
            )
        }
    }


    /**
     * Deletes a stored session.
     *
     * The row is dropped locally on success rather than waiting for the next
     * `session.list` sweep, so the list does not visibly lag the tap.
     */
    fun delete(s: SessionSummary) = viewModelScope.launch {
        if (s.id in _state.value.deleting) return@launch
        _state.update { it.copy(deleting = it.deleting + s.id, error = null) }
        runCatching { repo.deleteSession(s.id) }.fold(
            onSuccess = {
                // If this was the session the app resumes on launch, drop the
                // pointer too — otherwise the next start tries to resume a
                // session that no longer exists.
                val forgot = runCatching { settings.forgetSession(s.id) }.getOrDefault(false)
                _state.update { st ->
                    st.copy(
                        deleting = st.deleting - s.id,
                        sessions = st.sessions.filterNot { it.id == s.id },
                        stats = st.stats?.copy(total = (st.stats.total - 1).coerceAtLeast(0)),
                        notice = if (forgot) "已删除会话，并清除启动时恢复的会话记录" else "已删除会话",
                    )
                }
            },
            onFailure = { t ->
                _state.update {
                    it.copy(deleting = it.deleting - s.id, error = "删除失败：${t.friendly()}")
                }
            },
        )
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** One dismiss handler for both banner styles. */
    fun clearBanner() = _state.update { it.copy(error = null, notice = null) }

    override fun onCleared() {
        watchJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val repo: HermesRepository,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SessionListViewModel(repo, settings) as T
    }
}
