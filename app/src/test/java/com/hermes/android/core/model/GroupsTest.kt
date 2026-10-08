package com.hermes.android.core.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupsTest {
    private fun event(vararg pairs: Pair<String, Any?>) = RoomEvent(
        payload = buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    null -> put(k, JsonNull)
                    is String -> put(k, v)
                    is Int -> put(k, v)
                }
            }
        },
    )

    @Test fun multilineTextKeepsRealNewlinesNotEscapes() {
        val ev = event("text" to "第一行\n第二行 \"引号\" 反斜杠\\")
        assertEquals("第一行\n第二行 \"引号\" 反斜杠\\", ev.displayText())
    }

    @Test fun jsonNullFallsThroughToTheNextField() {
        val ev = event("text" to null, "message" to "fallback")
        assertEquals("fallback", ev.displayText())
    }

    @Test fun allNullishFieldsYieldNullNotTheStringNull() {
        assertNull(event("text" to null, "message" to null, "name" to null).displayText())
    }

    @Test fun nonStringPrimitiveReadsItsLiteral() {
        assertEquals("42", event("text" to 42).displayText())
    }
}
