package com.hermes.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownLinkTest {
    @Test fun linkTokenSplitsIntoLabelAndUrl() {
        assertEquals("文档" to "https://example.com/docs", linkLabelUrl("[文档](https://example.com/docs)"))
    }

    @Test fun nonLinkTokenIsNotAPair() {
        assertNull(linkLabelUrl("plain text"))
        assertNull(linkLabelUrl("[unclosed](https://example.com"))
    }

    @Test fun emptyUrlIsRejectedSoItCannotRegisterADeadTap() {
        assertNull(linkLabelUrl("[label]()"))
    }
}
