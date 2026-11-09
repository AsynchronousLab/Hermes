package com.hermes.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlSuffixTest {
    @Test fun bareHostAndPortHasNoSuffix() {
        assertNull(baseUrlPathSuffix("http://192.168.1.10:9119"))
    }

    @Test fun trailingSlashIsNotAPath() {
        assertNull(baseUrlPathSuffix("http://192.168.1.10:9119/"))
        assertNull(baseUrlPathSuffix("  https://hermes.example.com/  "))
    }

    @Test fun pathSuffixIsReported() {
        assertEquals("hermes", baseUrlPathSuffix("http://host:9119/hermes"))
        assertEquals("hermes/api", baseUrlPathSuffix("https://host/hermes/api/"))
    }

    @Test fun emptyInputIsIgnored() {
        assertNull(baseUrlPathSuffix(""))
        assertNull(baseUrlPathSuffix("   "))
    }
}
