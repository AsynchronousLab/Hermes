package com.hermes.android.ui.tools

import androidx.lifecycle.ViewModelStore
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.model.Skill
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.core.store.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class ToolsConnectionTest {
    @Test fun successfulConnectionLoadsSkillsWithoutLeavingThePage() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repo = mock(HermesRepository::class.java)
            val connection = MutableStateFlow(RpcConnectionState.Disconnected)
            `when`(repo.connection).thenReturn(connection)
            `when`(repo.configEpoch).thenReturn(MutableStateFlow(0L))
            `when`(repo.isConnected).thenAnswer { connection.value == RpcConnectionState.Connected }
            `when`(repo.skills()).thenReturn(listOf(Skill("writer")))
            val vm = ToolsViewModel(repo, mock(SettingsStore::class.java))
            store.put("tools", vm)
            vm.load()
            verify(repo, never()).skills()
            connection.value = RpcConnectionState.Connected
            advanceUntilIdle()
            assertEquals(listOf("writer"), vm.state.value.skills.map { it.name })
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun staleBackendResponseCannotOverwriteTheNewBackend() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repo = mock(HermesRepository::class.java)
            val connection = MutableStateFlow(RpcConnectionState.Connected)
            val epoch = MutableStateFlow(0L)
            `when`(repo.connection).thenReturn(connection)
            `when`(repo.configEpoch).thenReturn(epoch)
            `when`(repo.isConnected).thenReturn(true)
            val old = CompletableDeferred<List<Skill>>()
            var calls = 0
            // Mockito's coroutine answer must return through the supplied continuation.
            `when`(repo.skills()).thenAnswer { invocation ->
                if (++calls == 1) {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<List<Skill>>
                    backgroundScope.launch { continuation.resumeWith(Result.success(old.await())) }
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                } else listOf(Skill("new-backend"))
            }
            val vm = ToolsViewModel(repo, mock(SettingsStore::class.java))
            store.put("tools", vm)
            epoch.value = 1
            old.complete(listOf(Skill("old-backend")))
            advanceUntilIdle()
            assertEquals(listOf("new-backend"), vm.state.value.skills.map { it.name })
            assertFalse(vm.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
