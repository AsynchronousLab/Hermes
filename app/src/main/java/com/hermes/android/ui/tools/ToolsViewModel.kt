package com.hermes.android.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.model.*
import com.hermes.android.core.store.SettingsStore
import com.hermes.android.ui.chat.friendly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A contact is a Hermes **profile** — a distinct persona with its own model,
 * skills and config. Starting a chat with one binds the session to it.
 */
data class Contact(
    val id: String,
    val name: String,
    val detail: String,
    val model: String?,
    val skillCount: Int?,
    val kind: Kind,
    val isDefault: Boolean = false,
) {
    enum class Kind(val label: String) { PROFILE("Profile") }
}

data class SkillDetail(val name: String, val content: String)

data class ToolsUiState(
    val tab: Tab = Tab.SKILLS,
    val skills: List<Skill> = emptyList(),
    val toolsets: List<Toolset> = emptyList(),
    val expandedToolset: String? = null,
    val skillDetail: SkillDetail? = null,
    val files: List<FileEntry> = emptyList(),
    val filePath: String = "/",
    val cron: List<CronJob> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val agents: List<AgentProcess> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    enum class Tab(val label: String) {
        // 联系人 moved to its own bottom-nav destination.
        SKILLS("技能"), TOOLS("工具"), FILES("产物"), CRON("定时"), AGENTS("进程")
    }
}

/** Backs the "更多" screen: skills, toolsets, artifacts, cron and contacts. */
class ToolsViewModel(
    private val repo: HermesRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init {
        load(ToolsUiState.Tab.SKILLS)
        // An account/backend switch invalidates everything cached here: the
        // session id belongs to the old backend, and the lists are the old
        // account's. Drop both and reload rather than showing stale data.
        viewModelScope.launch {
            repo.configEpoch.drop(1).collect {
                cachedSessionId = null
                _state.update {
                    it.copy(
                        skills = emptyList(),
                        toolsets = emptyList(),
                        files = emptyList(),
                        cron = emptyList(),
                        contacts = emptyList(),
                        agents = emptyList(),
                        skillDetail = null,
                    )
                }
                load(_state.value.tab)
            }
        }
    }

    fun selectTab(tab: ToolsUiState.Tab) {
        _state.update { it.copy(tab = tab) }
        load(tab)
    }

    fun load(tab: ToolsUiState.Tab = _state.value.tab) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            when (tab) {
                ToolsUiState.Tab.SKILLS -> {
                    val skills = repo.skills()
                    _state.update { it.copy(skills = skills) }
                }

                ToolsUiState.Tab.TOOLS -> {
                    val ts = repo.toolsDetailed()
                    _state.update { it.copy(toolsets = ts) }
                }

                ToolsUiState.Tab.FILES -> {
                    val listing = repo.listFiles(_state.value.filePath)
                    _state.update { it.updateFiles(listing) }
                }

                ToolsUiState.Tab.CRON -> {
                    val jobs = repo.cronJobs()
                    _state.update { it.copy(cron = jobs) }
                }

                ToolsUiState.Tab.AGENTS -> {
                    val agents = runCatching { repo.agents() }.getOrDefault(emptyList())
                    _state.update { it.copy(agents = agents) }
                }
            }
        }.onFailure { t -> _state.update { it.copy(error = t.friendly()) } }
        _state.update { it.copy(loading = false) }
    }

    /** Monotonic request version: only the newest browse may write state. */
    private var browseSeq = 0L

    fun browse(path: String) {
        val mySeq = ++browseSeq
        _state.update { it.copy(filePath = path) }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repo.listFiles(path) }.fold(
                onSuccess = { l ->
                    // Rapid browsing starts several listings; a late older
                    // response used to switch the path and files back to a
                    // directory the user already left.
                    if (mySeq == browseSeq) {
                        _state.update { it.updateFiles(l) }
                    }
                },
                onFailure = { t -> if (mySeq == browseSeq) _state.update { it.copy(error = t.friendly()) } },
            )
            if (mySeq == browseSeq) _state.update { it.copy(loading = false) }
        }
    }

    /**
     * Toggling a toolset goes through the slash-command surface, which needs a
     * live session; we lazily create one and reuse it for the other slash
     * driven actions on this screen.
     */
    fun toggleToolset(toolset: Toolset) = viewModelScope.launch {
        val target = !toolset.enabled
        val action = if (target) "enable" else "disable"
        runCatching {
            val sid = ensureSession()
            repo.slash(sid, "/tools $action ${toolset.name}").output
        }.fold(
            onSuccess = { output ->
                _state.update { s ->
                    s.copy(
                        toolsets = s.toolsets.map {
                            if (it.name == toolset.name) it.copy(enabled = target) else it
                        },
                        notice = output.lineSequence().firstOrNull { it.isNotBlank() }
                            ?: "${toolset.name} 已${if (target) "启用" else "停用"}",
                    )
                }
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    private var cachedSessionId: String? = null

    private suspend fun ensureSession(): String {
        cachedSessionId?.let { return it }
        // `session.resume` wants the durable stored id; `lastSessionId` is the
        // runtime one, which the gateway rejects — the silent createSession()
        // fallback then applied tool toggles to a session the user was never
        // in.
        val last = runCatching { settings.lastStoredSessionId.first() }.getOrNull()
        val resumed = if (!last.isNullOrBlank()) {
            runCatching { repo.resumeSession(last) }.getOrNull()
        } else null
        val sid = resumed?.sessionId ?: repo.createSession().sessionId
        cachedSessionId = sid
        return sid
    }

    fun expandToolset(name: String?) {
        _state.update { it.copy(expandedToolset = if (it.expandedToolset == name) null else name) }
    }

    fun openSkill(name: String) = viewModelScope.launch {
        runCatching { repo.skillContent(name) }.fold(
            onSuccess = { content ->
                _state.update { it.copy(skillDetail = SkillDetail(name, content)) }
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    fun toggleSkill(skill: Skill) = viewModelScope.launch {
        // Capture the target now: deriving it from the response-time state meant
        // two quick taps both sent "enable" yet flipped the UI twice, ending up
        // showing the old value.
        val target = !skill.enabled
        runCatching { repo.setSkillEnabled(skill.name, target) }.fold(
            onSuccess = {
                _state.update { s ->
                    s.copy(
                        skills = s.skills.map { if (it.name == skill.name) it.copy(enabled = target) else it },
                        notice = "${skill.name} 已${if (target) "启用" else "停用"}",
                    )
                }
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    fun clearSkillDetail() = _state.update { it.copy(skillDetail = null) }
    fun clearMessage() = _state.update { it.copy(error = null, notice = null) }

    class Factory(
        private val repo: HermesRepository,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ToolsViewModel(repo, settings) as T
    }
}

private fun ToolsUiState.updateFiles(l: FileListing): ToolsUiState =
    copy(filePath = l.path, files = l.entries.filterNot { it.name.startsWith(".") })