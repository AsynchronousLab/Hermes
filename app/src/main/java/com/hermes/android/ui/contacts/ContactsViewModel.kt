package com.hermes.android.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.model.Group
import com.hermes.android.core.model.GroupLimits
import com.hermes.android.core.model.Profile
import com.hermes.android.core.model.SessionCreated
import com.hermes.android.core.store.SettingsStore
import com.hermes.android.ui.chat.friendly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class ContactsUiState(
    val tab: Tab = Tab.CONTACTS,
    val profiles: List<Profile> = emptyList(),
    val groups: List<Group> = emptyList(),
    val loading: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Profiles ticked for the group being built. */
    val draftMembers: Set<String> = emptySet(),
    val draftName: String = "",
    val showCreateSheet: Boolean = false,
) {
    enum class Tab(val label: String) { CONTACTS("联系人"), GROUPS("群组") }

    val draftValid: Boolean get() = draftMembers.size in GroupLimits.MIN_MEMBERS..GroupLimits.MAX_MEMBERS

    /** Human-readable profile title lookup used for member labels. */
    fun titleOf(profile: String): String? = profiles.firstOrNull { it.name == profile }?.title
}

class ContactsViewModel(
    private val repo: HermesRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun selectTab(tab: ContactsUiState.Tab) {
        _state.update { it.copy(tab = tab) }
        when (tab) {
            ContactsUiState.Tab.CONTACTS -> if (_state.value.profiles.isEmpty()) refresh()
            ContactsUiState.Tab.GROUPS -> if (_state.value.groups.isEmpty()) refresh()
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        // Both calls report failures. Swallowing the profiles error left the
        // contacts tab showing "没有可用的 profile" with no explanation at all
        // when the RPC was simply not connected yet.
        runCatching { repo.profiles() }.onSuccess { list ->
            _state.update { it.copy(profiles = list) }
        }.onFailure { t ->
            _state.update { it.copy(error = "读取联系人失败：${t.friendly()}") }
        }
        runCatching { repo.listGroups() }.onSuccess { res ->
            _state.update { it.copy(groups = res.rooms) }
        }.onFailure { t ->
            _state.update { it.copy(error = "读取群组失败：${t.friendly()}") }
        }
        _state.update { it.copy(loading = false) }
    }

    // ------------------------------------------------------------- groups ---

    fun openCreateSheet() = _state.update {
        it.copy(showCreateSheet = true, draftName = "", draftMembers = emptySet())
    }

    fun closeCreateSheet() = _state.update { it.copy(showCreateSheet = false) }

    fun setDraftName(v: String) = _state.update { it.copy(draftName = v) }

    /** Ticking beyond the server's 6-member cap is refused up front. */
    fun toggleMember(profile: String) = _state.update { s ->
        val next = s.draftMembers.toMutableSet()
        when {
            profile in next -> next.remove(profile)
            next.size >= GroupLimits.MAX_MEMBERS -> s
            else -> next.add(profile)
        }
        s.copy(draftMembers = next, error = null)
    }

    fun createGroup(onCreated: (Group) -> Unit) = viewModelScope.launch {
        val s = _state.value
        if (!s.draftValid) {
            _state.update {
                it.copy(
                    error = "请选择 ${GroupLimits.MIN_MEMBERS}-${GroupLimits.MAX_MEMBERS} 个 profile",
                )
            }
            return@launch
        }
        _state.update { it.copy(creating = true, error = null) }

        // The server takes the room id from us. Keep it ASCII-only (the gateway
        // is happiest with plain ids) and append a base36 timestamp so two
        // groups with the same name never collide.
        val slug = s.draftName.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(24)
            .ifBlank { "room" }
        val roomId = "hermes-$slug-${System.currentTimeMillis().toString(36)}"

        runCatching { repo.createGroup(roomId, s.draftName.trim(), s.draftMembers.toList()) }
            .onSuccess { created ->
                _state.update {
                    it.copy(
                        creating = false,
                        showCreateSheet = false,
                        draftMembers = emptySet(),
                        draftName = "",
                        groups = listOf(created.room) + it.groups,
                        tab = ContactsUiState.Tab.GROUPS,
                        notice = "群组「${created.room.name}」已创建",
                    )
                }
                onCreated(created.room)
            }
            .onFailure { t ->
                _state.update { it.copy(creating = false, error = t.friendly()) }
            }
    }

    fun renameGroup(group: Group, name: String) = viewModelScope.launch {
        runCatching { repo.renameGroup(group.roomId, name) }.fold(
            onSuccess = {
                _state.update { s ->
                    s.copy(
                        groups = s.groups.map { if (it.roomId == group.roomId) it.copy(name = name) else it },
                        notice = "已重命名",
                    )
                }
            },
            onFailure = { t -> _state.update { it.copy(error = t.friendly()) } },
        )
    }

    // ---------------------------------------------------------- profiles ---

    /** Opens a chat bound to the chosen profile. */
    fun openProfile(profile: String, onReady: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repo.createSession(profile) }.fold(
            onSuccess = { c: SessionCreated ->
                settings.rememberSession(c.sessionId, c.storedSessionId)
                _state.update { it.copy(loading = false) }
                onReady(c.sessionId)
            },
            onFailure = { t -> _state.update { it.copy(loading = false, error = t.friendly()) } },
        )
    }

    fun clearMessage() = _state.update { it.copy(error = null, notice = null) }

    class Factory(
        private val repo: HermesRepository,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ContactsViewModel(repo, settings) as T
    }
}