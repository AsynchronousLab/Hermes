package com.hermes.android.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.android.core.net.HermesConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/**
 * Persisted connection settings.
 *
 * The gateway authenticates with a username/password pair rather than a bearer
 * token, so the password has to survive process death for auto-reconnect to
 * work. It is encrypted with a Keystore-held key (see [PasswordCipher]) and the
 * app opts out of backup, so the ciphertext does not travel off the device.
 */
class SettingsStore(private val context: Context) {

    private val cipher = PasswordCipher()

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyUsername = stringPreferencesKey("username")
    private val keyPassword = stringPreferencesKey("password_enc")

    /** Pre-encryption key from older builds; dropped on first load. */
    private val keyLegacyPassword = stringPreferencesKey("password")
    private val keyLastSession = stringPreferencesKey("last_session_id")
    private val keyLastStored = stringPreferencesKey("last_stored_session_id")
    private val keyReasoning = stringPreferencesKey("reasoning_level")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
 * One-shot migration off the plaintext password key.
 *
 * Runs before any credential read so `config` can never observe a half-migrated
 * store (plaintext key gone, ciphertext not yet written → empty password).
 */
    private val migration = scope.async(start = CoroutineStart.LAZY) {
        context.dataStore.edit { p ->
            val legacy = p[keyLegacyPassword]
            if (p.contains(keyLegacyPassword)) {
                if (legacy != null && !p.contains(keyPassword)) {
                    cipher.encrypt(legacy)?.let { p[keyPassword] = it }
                }
                p.remove(keyLegacyPassword)
            }
        }
    }

    init {
        // Declared *after* `migration` on purpose: Kotlin runs property
        // initialisers and `init` blocks in source order, so an `init` placed
        // above would dereference `migration` while it is still null — which
        // threw NullPointerException from the IO dispatcher on most starts.
        // Kick the migration off eagerly; readers still await it.
        scope.launch { migration.start() }
    }

    private suspend fun awaitMigration() {
        runCatching { migration.await() }
    }

    val config: Flow<HermesConfig> = flow {
        awaitMigration()
        emitAll(
            context.dataStore.data.map { p ->
                HermesConfig(
                    baseUrl = p[keyBaseUrl].orEmpty(),
                    username = p[keyUsername].orEmpty(),
                    // An unreadable payload (lost keystore key) yields an empty
                    // password, which surfaces in Settings as "fill it in again".
                    password = p[keyPassword]?.let { cipher.decrypt(it) }.orEmpty(),
                )
            }
        )
    }

    val lastSessionId: Flow<String?> = context.dataStore.data.map { it[keyLastSession] }
    val lastStoredSessionId: Flow<String?> = context.dataStore.data.map { it[keyLastStored] }
    val reasoningLevel: Flow<String> = context.dataStore.data.map { it[keyReasoning] ?: "medium" }

    /**
 * Persists the settings.
 *
 * All-or-nothing on purpose: if the password cannot be encrypted we must not
 * persist a *new* address/username alongside the *old* password, which would
 * leave a mismatched pair that fails on next launch while the current run
 * still works — a confusing half-saved state.
 *
 * Returns false when the password could not be encrypted.
 */
    suspend fun save(cfg: HermesConfig): Boolean {
        val encPassword =
            if (cfg.password.isEmpty()) "" else cipher.encrypt(cfg.password).orEmpty()
        if (cfg.password.isNotEmpty() && encPassword.isEmpty()) return false

        context.dataStore.edit { p ->
            p[keyBaseUrl] = cfg.baseUrl.trim()
            p[keyUsername] = cfg.username
            if (encPassword.isEmpty()) p.remove(keyPassword) else p[keyPassword] = encPassword
            p.remove(keyLegacyPassword)
        }
        return true
    }

    suspend fun rememberSession(sessionId: String, storedSessionId: String?) {
        context.dataStore.edit { p ->
            p[keyLastSession] = sessionId
            if (storedSessionId != null) p[keyLastStored] = storedSessionId
        }
    }

    /**
     * Forgets the remembered session once it has been deleted.
     *
     * `lastStoredSessionId` is what the app resumes on launch, so deleting that
     * session while leaving the pointer behind made the next start resume a
     * session that no longer exists. Returns true when something was cleared.
     */
    suspend fun forgetSession(storedSessionId: String): Boolean {
        var cleared = false
        context.dataStore.edit { p ->
            if (p[keyLastStored] == storedSessionId) {
                p.remove(keyLastStored)
                p.remove(keyLastSession)
                cleared = true
            }
        }
        return cleared
    }

    suspend fun setReasoning(wire: String) {
        context.dataStore.edit { it[keyReasoning] = wire }
    }

    /** Blocking read for the one caller that needs the config before composing. */
    suspend fun currentConfig(): HermesConfig = config.first()
}