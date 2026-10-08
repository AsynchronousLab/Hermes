package com.hermes.android.ui.groups

import com.hermes.android.core.net.RpcFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RoomTranscriptTest {
    private fun message(seq: Long, key: String = "event-$seq") = RoomMessage(
        key, seq, "message.user", "desktop", "text-$seq", mine = true, system = false,
    )

    @Test fun pollBackfillsBeforeAnAlreadyConfirmedSend() {
        val merged = mergeRoomMessages(
            listOf(message(100), message(103)),
            listOf(message(101), message(102), message(103)),
        )
        assertEquals(listOf(100L, 101L, 102L, 103L), merged.map { it.seq })
    }

    @Test fun confirmationAndPollShareOneLazyColumnKey() {
        val first = message(103)
        val merged = mergeRoomMessages(listOf(message(100), first), listOf(first, first))
        assertEquals(listOf("event-100", "event-103"), merged.map { it.key })
    }

    @Test fun pendingEchoesStayAfterBackfilledEventsInSendOrder() {
        val firstEcho = message(Long.MAX_VALUE, "local-1")
        val secondEcho = message(Long.MAX_VALUE, "local-2")
        val merged = mergeRoomMessages(
            listOf(message(100), firstEcho, secondEcho),
            listOf(message(102), message(101)),
        )
        assertEquals(
            listOf("event-100", "event-101", "event-102", "local-1", "local-2"),
            merged.map { it.key },
        )
    }

    @Test fun rpcRejectionAndMissingSocketAreDefinitive() {
        assertFalse(isUnknownOutcome(RpcFailure.Rpc(5112, "thread_id 不得为空")))
        assertFalse(isUnknownOutcome(RpcFailure.Rpc(-1, "WebSocket 发送失败")))
        assertFalse(isUnknownOutcome(RpcFailure.NotConnected("groups.send")))
    }

    @Test fun timeoutAndRawSocketDeathLeaveTheOutcomeUnknown() {
        assertTrue(isUnknownOutcome(RpcFailure.Timeout("groups.send")))
        assertTrue(isUnknownOutcome(IOException("connection reset")))
    }

    @Test fun settlingAnEchoKeepsExactlyOneAuthoritativeRow() {
        val echo = RoomMessage(
            "local-1", Long.MAX_VALUE, "message", "default", "hi",
            mine = true, system = false,
            sendState = RoomSendState.Sending, eventId = "send-k1",
        )
        val confirmed = RoomMessage(
            "user:hash", 9L, "message.user", "desktop", "hi",
            mine = true, system = false,
        )
        // Mirrors settleSend: drop the echo first, then merge the authoritative
        // row in — the result must keep every other row and add exactly one.
        val settled = mergeRoomMessages(
            listOf(message(8), echo).filterNot { it.key == echo.key },
            listOf(confirmed, confirmed),
        )
        assertEquals(listOf("event-8", "user:hash"), settled.map { it.key })
    }

    @Test fun unknownEchoCarriesTheIdempotencyKeyItMustRetryWith() {
        val echo = RoomMessage(
            "local-1", Long.MAX_VALUE, "message", "default", "hi",
            mine = true, system = false,
            sendState = RoomSendState.Unknown, eventId = "send-k1",
        )
        assertEquals("send-k1", echo.eventId)
    }

    // An unknown-outcome send is not settled by settleSend: the call failed, so
    // there was no confirmation to swap in. The log echoes the *server's*
    // event_id, never the client's, so nothing can pair them by key — and text
    // is NOT a sound substitute: a later identical post would consume the echo
    // and silently discard its retry entry. The echo survives (even next to its
    // own delivered copy) until the id-reusing retry settles it.
    @Test fun sameTextDoesNotRetireAnUnknownEcho() {
        val echo = RoomMessage(
            "local-1", Long.MAX_VALUE, "message", "default", "hi",
            mine = true, system = false,
            sendState = RoomSendState.Unknown, eventId = "send-k1",
        )
        val delivered = RoomMessage(
            "user:hash", 9L, "message.user", "desktop", "hi",
            mine = true, system = false,
        )
        val merged = mergeRoomMessages(listOf(echo), listOf(delivered))
        assertEquals(listOf("user:hash", "local-1"), merged.map { it.key })
        assertEquals(RoomSendState.Unknown, merged.last().sendState)
    }

    // Two identical unknown posts stay retryable independently: nothing pairs
    // them off against same-text rows, so neither loses its id.
    @Test fun identicalUnknownEchoesBothStayRetryable() {
        val first = RoomMessage(
            "local-1", Long.MAX_VALUE, "message", "default", "hi",
            mine = true, system = false,
            sendState = RoomSendState.Unknown, eventId = "send-k1",
        )
        val second = RoomMessage(
            "local-2", Long.MAX_VALUE, "message", "default", "hi",
            mine = true, system = false,
            sendState = RoomSendState.Unknown, eventId = "send-k2",
        )
        val merged = mergeRoomMessages(
            listOf(first, second),
            listOf(
                RoomMessage("user:h1", 9L, "message.user", "desktop", "hi",
                    mine = true, system = false),
                RoomMessage("user:h2", 10L, "message.user", "desktop", "hi",
                    mine = true, system = false),
            ),
        )
        assertEquals(listOf("user:h1", "user:h2", "local-1", "local-2"), merged.map { it.key })
    }
}
