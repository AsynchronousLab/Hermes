package com.hermes.android

import android.app.Application
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.store.SettingsStore

/**
 * Process-wide singletons.
 *
 * The repository owns the WebSocket, so it must outlive any single screen; a
 * chat in progress keeps streaming while the user browses the sessions list.
 */
class HermesApp : Application() {

    val repository: HermesRepository by lazy { HermesRepository() }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}