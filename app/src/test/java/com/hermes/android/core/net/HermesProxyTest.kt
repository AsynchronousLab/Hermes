package com.hermes.android.core.net

import com.hermes.android.core.HermesRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class HermesProxyTest {
    @Test fun changingOnlyProxyInvalidatesTheSharedConnectionGeneration() = runBlocking {
        val repo = HermesRepository()
        val original = repo.configEpoch.value
        repo.updateConfig(HermesConfig(proxy = HermesProxy(ProxyType.WEB, " proxy.local ", 8080)))
        assertEquals(original + 1, repo.configEpoch.value)
        assertEquals("proxy.local", repo.config.proxy.host)
        repo.updateConfig(HermesConfig(proxy = HermesProxy(ProxyType.WEB, "proxy.local", 8080)))
        assertEquals(original + 1, repo.configEpoch.value)
        repo.updateConfig(HermesConfig())
        assertEquals(original + 2, repo.configEpoch.value)
        repo.disconnect()
    }

    @Test fun directIsDefaultAndInvalidProxyNeverFallsBackToDirect() {
        var proxy = HermesProxy()
        val selector = HermesProxySelector { proxy }
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://example.invalid"))[0])
        assertNull(proxy.validationError())

        proxy = HermesProxy(ProxyType.WEB, "", 8080)
        assertNotNull(proxy.validationError())
        try {
            selector.select(URI("https://example.invalid"))
            fail("An invalid enabled proxy must not leak traffic through a direct route")
        } catch (_: IllegalArgumentException) { }
        assertFalse(HermesConfig("https://example.invalid", "user", "pass", proxy).isComplete)
        assertNotNull(HermesProxy(ProxyType.UNSUPPORTED).validationError())
    }

    @Test fun selectsWebAndSocksRoutesFromTheCurrentSettings() {
        var proxy = HermesProxy(ProxyType.WEB, " proxy.local ", 7890)
        val selector = HermesProxySelector { proxy }
        val url = URI("https://example.invalid")
        assertEquals(Proxy.Type.HTTP, selector.select(url)[0].type())
        assertEquals("proxy.local", proxy.normalized().host)
        proxy = HermesProxy(ProxyType.SOCKS, "127.0.0.1", 1080)
        assertEquals(Proxy.Type.SOCKS, selector.select(url)[0].type())
        proxy = HermesProxy()
        assertEquals(Proxy.NO_PROXY, selector.select(url)[0])
    }

    @Test fun webProxyActuallyCarriesTheRequest() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5_000
            val requestLine = AtomicReference<String>()
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    requestLine.set(reader.readLine())
                    while (reader.readLine().isNullOrEmpty().not()) Unit
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
                    )
                }
            }.apply { start() }
            val client = HermesHttp.client(
                SessionCookieJar(),
                proxySelector = HermesProxySelector {
                    HermesProxy(ProxyType.WEB, "127.0.0.1", server.localPort)
                },
            )
            client.newCall(Request.Builder().url("http://example.invalid/probe").build())
                .execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body?.string())
                }
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals("GET http://example.invalid/probe HTTP/1.1", requestLine.get())
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun socksProxyActuallyCarriesTheRequest() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5_000
            val requestLine = AtomicReference<String>()
            val worker = Thread {
                server.accept().use { socket -> serveSocks(socket, requestLine) }
            }.apply { start() }
            val client = HermesHttp.client(
                SessionCookieJar(),
                proxySelector = HermesProxySelector {
                    HermesProxy(ProxyType.SOCKS, "127.0.0.1", server.localPort)
                },
            )
            client.newCall(Request.Builder().url("http://example.invalid/probe").build())
                .execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body?.string())
                }
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals("GET /probe HTTP/1.1", requestLine.get())
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun serveSocks(socket: Socket, requestLine: AtomicReference<String>) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        assertEquals(5, input.read())
        input.readNBytes(input.read()).also { assertTrue(it.contains(0.toByte())) }
        output.write(byteArrayOf(5, 0)) // SOCKS5, no authentication
        assertEquals(5, input.read())
        assertEquals(1, input.read()) // CONNECT
        input.read() // reserved
        when (input.read()) {
            1 -> input.readNBytes(4)
            3 -> input.readNBytes(input.read())
            4 -> input.readNBytes(16)
            else -> fail("Unexpected SOCKS address type")
        }
        input.readNBytes(2) // target port
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
        val reader = input.bufferedReader()
        requestLine.set(reader.readLine())
        while (reader.readLine().isNullOrEmpty().not()) Unit
        output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
    }
}
