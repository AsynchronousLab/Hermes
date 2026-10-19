package com.hermes.android.core.net

import com.hermes.android.ui.groups.isUnknownOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class RpcDeliveryTest {
    private class Socket(private val accepted: Boolean = true) : WebSocket {
        val sent = CompletableDeferred<Unit>()
        var cancelled = false
        override fun request() = Request.Builder().url("http://localhost/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.complete(Unit); return accepted }
        override fun send(bytes: ByteString) = accepted
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    private suspend fun withClient(socket: Socket?, block: suspend (JsonRpcClient) -> Unit) {
        val http = OkHttpClient()
        val rpc = JsonRpcClient(http, HermesAuth(SessionCookieJar(), http))
        JsonRpcClient::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(rpc, socket)
        try { block(rpc) } finally {
            rpc.disconnect()
            for (name in listOf("consumerJob", "connJob")) {
                (JsonRpcClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(rpc) as Job).cancel()
            }
        }
    }

    @Test fun disconnectAfterAcceptedSendKeepsDeliveryUnknown() = runBlocking {
        val socket = Socket()
        withClient(socket) { rpc ->
            val result = async { runCatching { rpc.rpcRaw("groups.send") } }
            withTimeout(2_000) { socket.sent.await() }
            rpc.disconnect()
            val failure = withTimeout(2_000) { result.await() }.exceptionOrNull()!!
            assertTrue(failure is RpcFailure.ConnectionClosed)
            assertTrue(isUnknownOutcome(failure))
        }
    }

    @Test fun normalRemoteCloseAlsoKeepsDeliveryUnknown() = runBlocking {
        val socket = Socket()
        withClient(socket) { rpc ->
            // Exercise the real listener/connection-command path without a network server.
            val handshakeType = Class.forName("com.hermes.android.core.net.Handshake")
            val handshake = handshakeType.getDeclaredConstructor(Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(1L)
            @Suppress("UNCHECKED_CAST")
            val holder = handshakeType.getDeclaredField("socket").apply { isAccessible = true }
                .get(handshake) as java.util.concurrent.atomic.AtomicReference<WebSocket?>
            holder.set(socket)
            JsonRpcClient::class.java.getDeclaredField("closedByUser").apply { isAccessible = true }.set(rpc, true)
            val listener = JsonRpcClient::class.java.getDeclaredMethod("makeListener", Long::class.javaPrimitiveType, handshakeType)
                .apply { isAccessible = true }.invoke(rpc, 0L, handshake) as WebSocketListener
            val result = async { runCatching { rpc.rpcRaw("groups.send") } }
            withTimeout(2_000) { socket.sent.await() }
            listener.onClosed(socket, 1000, "normal close")
            val failure = withTimeout(2_000) { result.await() }.exceptionOrNull()!!
            assertTrue(failure is RpcFailure.ConnectionClosed)
            assertTrue(isUnknownOutcome(failure))
        }
    }

    @Test fun missingSocketIsStillDefinitivelyUnsent() = runBlocking {
        withClient(null) { rpc ->
            val failure = runCatching { rpc.rpcRaw("groups.send") }.exceptionOrNull()!!
            assertTrue(failure is RpcFailure.NotConnected)
            assertFalse(isUnknownOutcome(failure))
        }
    }

    @Test fun refusedFrameIsStillDefinitivelyUnsent() = runBlocking {
        val socket = Socket(accepted = false)
        withClient(socket) { rpc ->
            val failure = runCatching { rpc.rpcRaw("groups.send") }.exceptionOrNull()!!
            assertTrue(failure is RpcFailure.Rpc)
            assertFalse(isUnknownOutcome(failure))
            assertEquals(RpcConnectionState.Reconnecting, rpc.state.value)
            assertTrue(socket.cancelled)
        }
    }

    @Test fun silentHalfOpenSocketIsInvalidatedAfterProbeTimeout() = runBlocking {
        val socket = Socket()
        withClient(socket) { rpc ->
            @Suppress("UNCHECKED_CAST")
            val state = JsonRpcClient::class.java.getDeclaredField("_state")
                .apply { isAccessible = true }.get(rpc) as kotlinx.coroutines.flow.MutableStateFlow<RpcConnectionState>
            state.value = RpcConnectionState.Connected
            assertFalse(rpc.probeAndRecover(timeoutMs = 100))
            assertEquals(RpcConnectionState.Reconnecting, rpc.state.value)
            assertFalse(rpc.isConnected)
            assertTrue(socket.cancelled)
        }
    }

    @Test fun orphanedReconnectingStateGetsANewRetryLoop() = runBlocking {
        withClient(null) { rpc ->
            val cfg = HermesConfig("http://127.0.0.1:1", "u", "p")
            rpc.ensureReconnect(cfg)
            val retryField = JsonRpcClient::class.java.getDeclaredField("reconnectJob")
                .apply { isAccessible = true }
            val first = retryField.get(rpc) as Job
            assertTrue(first.isActive)
            assertEquals(RpcConnectionState.Reconnecting, rpc.state.value)
            rpc.ensureReconnect(cfg)
            assertSame(first, retryField.get(rpc))
        }
    }
}
