package com.hermes.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermes.android.core.ConnectionTestResult
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.net.HermesConfig
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.core.store.SettingsStore
import com.hermes.android.ui.chat.friendly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val testing: Boolean = false,
    val result: ConnectionTestResult? = null,
    val connection: RpcConnectionState = RpcConnectionState.Disconnected,
    val saved: Boolean = false,
    val error: String? = null,
    val profiles: List<String> = emptyList(),
    val profileInfo: String? = null,
    /** Set once the user edits any field, so async load never clobbers typing. */
    val dirty: Boolean = false,
) {
    val config: HermesConfig get() = HermesConfig(baseUrl, username, password)
    val canTest: Boolean
        get() = !testing && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** Settings: enter the backend address, then verify it end to end. */
class SettingsViewModel(
    private val repo: HermesRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = runCatching { settings.config.first() }.getOrElse {
                _state.update { s -> s.copy(error = "读取本地配置失败") }
                return@launch
            }
            runCatching { repo.updateConfig(cfg) }
            // Only seed fields the user has not already typed into: loading is
            // async, and writing back unconditionally wiped fresh input.
            _state.update { s ->
                if (s.dirty) s.copy(connection = repo.connection.value)
                else s.copy(
                    baseUrl = cfg.baseUrl,
                    username = cfg.username,
                    password = cfg.password,
                    connection = repo.connection.value,
                )
            }
            // Only connect if the shared socket is not already up. This screen is opened
            // from 「更多」 while the chat screen may be streaming: reconnecting
            // here tore down the live connection other screens depend on.
            if (cfg.isComplete && !repo.isConnected) autoConnect()
            else if (cfg.isComplete) runCatching { repo.profiles() }
                .onSuccess { list -> applyProfiles(list) }
        }
        viewModelScope.launch {
            repo.connection.collect { c -> _state.update { s -> s.copy(connection = c) } }
        }
    }

    private fun autoConnect() = viewModelScope.launch {
        repo.connect().onFailure { t ->
            _state.update { it.copy(error = t.friendly()) }
        }
        runCatching { repo.profiles() }.onSuccess { list -> applyProfiles(list) }
    }

    /** Fills the profile picker from a `profiles.list` response. */
    private fun applyProfiles(list: List<com.hermes.android.core.model.Profile>) {
        val defaultProfile = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
        _state.update {
            it.copy(
                profiles = list.map { p -> p.name },
                profileInfo = defaultProfile?.let { p ->
                    "${p.name} · ${p.model ?: "?"} · ${p.skillCount ?: 0} 个技能"
                },
            )
        }
    }

    fun onBaseUrl(v: String) = _state.update {
        it.copy(baseUrl = v, result = null, saved = false, dirty = true, error = null)
    }

    fun onUsername(v: String) = _state.update {
        it.copy(username = v, result = null, saved = false, dirty = true, error = null)
    }

    fun onPassword(v: String) = _state.update {
        it.copy(password = v, result = null, saved = false, dirty = true, error = null)
    }

    fun togglePassword() = _state.update { it.copy(showPassword = !it.showPassword) }

    fun save() = viewModelScope.launch {
        val cfg = _state.value.config
        _state.update { it.copy(saved = false, error = null) }
        val ok = runCatching { settings.save(cfg) }
            .onFailure { t -> _state.update { s -> s.copy(error = "保存失败：${t.message}") } }
            .getOrDefault(false)
        runCatching { repo.updateConfig(cfg) }
        _state.update {
            it.copy(
                saved = ok,
                error = if (ok) null
                else (it.error ?: "密码未能安全保存（设备密钥库不可用），重启后需重新输入"),
            )
        }
    }

    /** Save, then run health → login → ticket → WebSocket → ping. */
    fun testConnection() = viewModelScope.launch {
        // Flip the flag up front so the button cannot be re-entered while the
        // save and the handshake are still running.
        if (_state.value.testing) return@launch
        val cfg = _state.value.config
        _state.update { it.copy(testing = true, result = null, saved = false, error = null) }
        try {
            val saved = runCatching { settings.save(cfg) }.getOrDefault(false)
            runCatching { repo.updateConfig(cfg) }
            if (!saved) {
                _state.update {
                    it.copy(
                        testing = false,
                        error = "密码未能安全保存（设备密钥库不可用），本次仅临时使用",
                    )
                }
                return@launch
            }
            _state.update { it.copy(saved = true) }

            val res = runCatching { repo.testConnection() }
                .getOrElse { t -> ConnectionTestResult.Failed(t.friendly()) }
            _state.update { it.copy(result = res) }

            if (res is ConnectionTestResult.Success) {
                runCatching { repo.profiles() }.onSuccess { list -> applyProfiles(list) }
            }
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.friendly()) }
        } finally {
            // Always clear the spinner, otherwise the button stays disabled.
            _state.update { it.copy(testing = false) }
        }
    }

    fun clearResult() = _state.update { it.copy(result = null) }

    class Factory(
        private val repo: HermesRepository,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repo, settings) as T
    }
}