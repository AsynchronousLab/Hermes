package com.hermes.android.ui.settings

import com.hermes.android.core.net.ProxyType
import org.junit.Assert.*
import org.junit.Test

class ProxySettingsTest {
    private val ready = SettingsUiState(
        baseUrl = "https://backend.example",
        username = "user",
        password = "password",
    )

    @Test fun blankProxySettingsPreserveDirectConnection() {
        assertTrue(ready.canTest)
        assertEquals(ProxyType.DIRECT, ready.config.proxy.type)
        assertNull(ready.config.proxy.validationError())
    }

    @Test fun enabledProxyRequiresHostAndValidPort() {
        val web = ready.copy(proxyType = ProxyType.WEB)
        assertFalse(web.canTest)
        assertFalse(web.config.isComplete)
        assertFalse(web.copy(proxyHost = "proxy.local", proxyPort = "0").canTest)
        assertFalse(web.copy(proxyHost = "proxy.local", proxyPort = "65536").canTest)
        assertFalse(web.copy(proxyHost = "proxy.local:7890", proxyPort = "7890").canTest)
        assertTrue(web.copy(proxyHost = "proxy.local", proxyPort = "7890").canTest)
        assertTrue(web.copy(proxyType = ProxyType.SOCKS, proxyHost = "::1", proxyPort = "1080").canTest)
    }

    @Test fun switchingBackToDirectIgnoresDormantInputs() {
        val state = ready.copy(proxyHost = "proxy.local", proxyPort = "bad")
        assertTrue(state.canTest)
        assertNull(state.config.proxy.validationError())
    }
}
