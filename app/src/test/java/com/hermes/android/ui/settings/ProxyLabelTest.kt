package com.hermes.android.ui.settings

import com.hermes.android.core.net.HermesProxy
import com.hermes.android.core.net.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyLabelTest {
    @Test fun directIsNamedPlainly() {
        assertEquals("直连", proxyLabel(HermesProxy()))
    }

    @Test fun webProxyShowsHostAndPort() {
        assertEquals("HTTP 127.0.0.1:7890", proxyLabel(HermesProxy(ProxyType.WEB, "127.0.0.1", 7890)))
    }

    @Test fun socksProxyIsLabelled() {
        assertEquals("SOCKS5 10.0.0.2:1080", proxyLabel(HermesProxy(ProxyType.SOCKS, "10.0.0.2", 1080)))
    }

    @Test fun unsupportedTypeDoesNotPretendToWork() {
        assertEquals("配置无效", proxyLabel(HermesProxy(ProxyType.UNSUPPORTED, "h", 1)))
    }
}
