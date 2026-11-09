package com.hermes.android.ui.chat

import com.hermes.android.core.isStaleSession
import com.hermes.android.core.net.RpcFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigatorTest {

    private fun user(key: Long, text: String) = ChatMessage(key = key, role = ChatMessage.Role.User, text = text)
    private fun ai(key: Long, text: String) = ChatMessage(key = key, role = ChatMessage.Role.Assistant, text = text)

    @Test fun outlineListsOnlyQuestionsWithTheirOrdinal() {
        val rows = listOf(user(1, "第一问"), ai(2, "答"), ai(3, "再答"), user(4, "第二问"), ai(5, "答"))
        val outline = outlineOf(rows)
        assertEquals(2, outline.size)
        assertEquals(listOf(0, 3), outline.map { it.index })
        assertEquals(listOf(1, 2), outline.map { it.turn })
        assertEquals("第一问", outline[0].label)
    }

    @Test fun outlineIgnoresSystemRows() {
        val rows = listOf(
            ChatMessage(key = 1, role = ChatMessage.Role.System, text = "/model x", isSlashInput = true),
            user(2, "问题"),
        )
        val outline = outlineOf(rows)
        assertEquals(1, outline.size)
        assertEquals(1, outline[0].index)
        assertEquals(1, outline[0].turn)
    }

    @Test fun searchIsCaseInsensitiveAndReturnsTheOwningTurn() {
        val rows = listOf(user(1, "聊聊 DeepSeek"), ai(2, "deepseek 是一家公司"), user(3, "换个话题"))
        val hits = matchesOf(rows, "DEEPSEEK")
        assertEquals(listOf(0, 1), hits.map { it.index })
        assertEquals(1, hits[0].turn)
        assertEquals(1, hits[1].turn)
    }

    @Test fun blankSearchMatchesNothing() {
        val rows = listOf(user(1, "abc"))
        assertTrue(matchesOf(rows, "   ").isEmpty())
    }

    @Test fun oneLineCollapsesWhitespaceAndTruncates() {
        assertEquals("第一行", oneLine("第一行\n第二行"))
        assertEquals("（空消息）", oneLine(""))
        val long = "x".repeat(80)
        assertTrue(oneLine(long, limit = 10).endsWith("…"))
    }

    @Test fun snippetKeepsTheHitInsideTheWindow() {
        val text = "a".repeat(50) + "needle" + "b".repeat(50)
        val snippet = snippetAround(text, "needle")
        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length < text.length)
    }

    @Test fun quotePrefixesEveryLineAndKeepsTheDraft() {
        val composed = composeWithQuote("第一行\n第二行", "我的问题")
        assertEquals("> 第一行\n> 第二行\n\n我的问题", composed)
    }

    @Test fun quoteWithoutDraftLeavesTrailingSeparator() {
        assertEquals("> 只有引用\n\n", composeWithQuote("只有引用", ""))
    }

    @Test fun noQuoteReturnsTheDraftUntouched() {
        assertEquals("原样", composeWithQuote(null, "原样"))
        assertEquals("原样", composeWithQuote("   ", "原样"))
    }

    @Test fun quoteExcerptCapsBothLinesAndCharacters() {
        val manyLines = (1..20).joinToString("\n") { "line$it" }
        assertEquals(6, quoteExcerpt(manyLines).lines().size)
        val huge = "y".repeat(1000)
        assertTrue(quoteExcerpt(huge).length <= 241)
    }

    @Test fun staleSessionErrorsAreRecognised() {
        assertTrue(RpcFailure.Rpc(4001, "session not found").isStaleSession())
        assertTrue(RpcFailure.Rpc(4007, "session not found").isStaleSession())
        assertFalse(RpcFailure.Rpc(4009, "busy").isStaleSession())
        assertFalse(RpcFailure.NotConnected("prompt.submit").isStaleSession())
    }
}
