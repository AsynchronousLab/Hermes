package com.hermes.android.ui.sessions

import com.hermes.android.core.model.Group
import com.hermes.android.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListOrderTest {

    private fun chat(id: String, startedAt: Double) =
        SessionSummary(id = id, title = id, startedAt = startedAt)

    private fun room(id: String, updatedAt: Double) =
        Group(roomId = id, name = id, updatedAt = updatedAt)

    @Test fun chatsKeepTheServerOrderNotStartTime() {
        // `session.list` sorts by last activity; a session created earlier but
        // just answered arrives first. Sorting by start time inverted this.
        val state = SessionListUiState(
            sessions = listOf(chat("recent-activity", startedAt = 100.0), chat("older", startedAt = 900.0)),
        )
        assertEquals(listOf("recent-activity", "older"), state.conversations.map { it.id })
    }

    @Test fun roomIsSlottedByItsOwnActivity() {
        val state = SessionListUiState(
            sessions = listOf(chat("a", 300.0), chat("b", 100.0)),
            rooms = listOf(room("r", 200.0)),
        )
        assertEquals(listOf("a", "r", "b"), state.conversations.map { it.id })
    }

    @Test fun newestRoomGoesFirst() {
        val state = SessionListUiState(
            sessions = listOf(chat("a", 100.0)),
            rooms = listOf(room("r", 500.0)),
        )
        assertEquals(listOf("r", "a"), state.conversations.map { it.id })
    }

    @Test fun roomWithoutStampsStillAppears() {
        val state = SessionListUiState(
            sessions = listOf(chat("a", 100.0)),
            rooms = listOf(Group(roomId = "r", name = "r")),
        )
        assertEquals(2, state.conversations.size)
    }

    @Test fun searchFiltersOnTitlePreviewAndId() {
        val state = SessionListUiState(
            sessions = listOf(
                SessionSummary(id = "s1", title = "可转债下修", preview = "第 3 条"),
                SessionSummary(id = "s2", title = "别的", preview = "无关"),
            ),
        )
        assertEquals(listOf("s1"), state.copy(query = "可转债").visible.map { it.id })
        assertEquals(listOf("s1"), state.copy(query = "第 3").visible.map { it.id })
        assertEquals(listOf("s2"), state.copy(query = "S2").visible.map { it.id })
    }
}
