package com.hermes.android.ui.chat

import androidx.lifecycle.ViewModelStore
import com.hermes.android.core.HermesRepository
import com.hermes.android.core.model.SessionCreated
import com.hermes.android.core.model.SessionEventsResult
import com.hermes.android.core.model.SessionMessagesResult
import com.hermes.android.core.net.JsonRpcClient
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.core.net.RpcEvent
import com.hermes.android.core.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInitializationTest {
    @Test fun openingNewlyCreatedSessionWithBufferedEventsDoesNotCrash() = open("runtime")

    @Test fun openingStoredSessionWithBufferedEventsDoesNotCrash() = open("resume:stored")

    private fun open(target: String) = runTest {
        // Main.immediate on Android runs replay collectors inside the constructor.
        // A queued dispatcher would hide this initialization-order bug.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repo = mock(HermesRepository::class.java)
            val rpc = mock(JsonRpcClient::class.java)
            val settings = mock(SettingsStore::class.java)
            val events = MutableSharedFlow<RpcEvent>(replay = 4)
            events.tryEmit(RpcEvent("message.start", "runtime", JsonObject(emptyMap()), JsonObject(emptyMap())))
            `when`(repo.connection).thenReturn(MutableStateFlow(RpcConnectionState.Connected))
            `when`(repo.events).thenReturn(events)
            `when`(repo.rpc).thenReturn(rpc)
            `when`(repo.isConnected).thenReturn(true)
            `when`(rpc.eventLossVersion).thenReturn(MutableStateFlow(0L))
            `when`(settings.reasoningLevel).thenReturn(flowOf("medium"))
            `when`(repo.resumeSession("stored")).thenReturn(SessionCreated("runtime", "stored"))
            `when`(repo.history("runtime")).thenReturn(SessionMessagesResult())
            `when`(repo.sessionEvents()).thenReturn(SessionEventsResult())
            `when`(repo.modelOptions()).thenReturn(emptyList())
            `when`(repo.commands()).thenReturn(emptyList())

            val vm = ChatViewModel(repo, settings, target)
            store.put("chat", vm)
            // Verify replay made it through binding, not just that it was dropped.
            assertEquals("runtime", vm.state.value.sessionId)
            assertEquals(1, vm.state.value.messages.size)
            advanceUntilIdle()
            assertFalse(vm.state.value.loading)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
