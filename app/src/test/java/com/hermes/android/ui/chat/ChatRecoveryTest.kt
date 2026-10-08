package com.hermes.android.ui.chat

import com.hermes.android.core.model.OpenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ChatRecoveryTest {
    @Test fun waitsForSendAcknowledgementBeforeReadingHistory() = runBlocking {
        var checks = 0
        var reads = 0
        val result = readStableSnapshot({ true }, { 0L }, 0, canRead = { ++checks >= 3 }) {
            reads++
            "acknowledged"
        }
        assertEquals("acknowledged", result)
        assertEquals(1, reads)
        assertTrue(checks >= 3)
    }
    @Test fun completionDuringHistoryReadCausesAnotherReadInsteadOfDuplicateRows() = runBlocking {
        var revision = 0L
        var reads = 0
        val answer = ChatMessage(1, ChatMessage.Role.Assistant, "same text")
        val history = readStableSnapshot({ true }, { revision }, 0) {
            reads++
            if (reads == 1) revision++ // message.complete arrived during RPC
            listOf(answer)
        }!!
        assertEquals(2, reads)
        assertEquals(listOf(answer), mergeHistorySnapshot(history, listOf(answer.copy(key = 2))))
    }

    @Test fun sessionSwitchDiscardsOldSnapshot() = runBlocking {
        var current = true
        val result = readStableSnapshot({ current }, { 0L }, 0) {
            current = false
            "old session"
        }
        assertNull(result)
    }

    @Test fun answeredRequestInvalidatesAnInFlightRecoverySnapshot() = runBlocking {
        var revision = 0L
        var reads = 0
        val result = readStableSnapshot({ true }, { revision }, 0) {
            if (++reads == 1) {
                revision++
                listOf(request("old"))
            } else emptyList()
        }!!
        assertNull(recoverRequest(result, "session", "old"))
        assertEquals(2, reads)
    }

    @Test fun cancellationStopsRecovery() = runBlocking {
        try {
            readStableSnapshot<String>({ true }, { 0L }, 0) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun identicalRealMessagesAndLocalDraftsSurviveSnapshotReplacement() {
        val first = ChatMessage(1, ChatMessage.Role.User, "again")
        val second = first.copy(key = 2)
        val draft = first.copy(key = 3, delivered = false)
        val command = first.copy(key = 4, isSlashInput = true)
        val merged = mergeHistorySnapshot(listOf(first, second), listOf(first, second, draft, command))
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.key })
    }

    private fun request(id: String, session: String? = "session", method: String = "approval.request") =
        OpenRequest(id, method, session, JsonObject(mapOf("command" to JsonPrimitive("echo hello"))))

    @Test fun recoveryFiltersOtherSessionsAndRetainsCurrentOpenCard() {
        val requests = listOf(request("foreign", "other"), request("unscoped", null),
            request("first"), request("current"), request("unknown", method = "unknown"))
        assertEquals("current", recoverRequest(requests, "session", "current")?.id)
        assertEquals("first", recoverRequest(requests, "session", "resolved")?.id)
        assertNull(recoverRequest(emptyList(), "session", "resolved"))
    }

    @Test fun clarifySnapshotUsesQuestionAndRejectsMissingIds() {
        val payload = JsonObject(mapOf("question" to JsonPrimitive("Which file?")))
        val card = recoverRequest(listOf(OpenRequest("q", "clarify.request", "session", payload)), "session", null)
        assertEquals(ServerRequest("q", ServerRequest.Kind.CLARIFY, "Which file?"), card)
        assertNull(requestCard("approval.request", "", payload))
    }
}
