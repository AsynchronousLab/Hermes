package com.hermes.android.core

import com.hermes.android.core.model.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupPagingTest {
    private val rooms = listOf(Group(roomId = "r1"), Group(roomId = "r2"))

    @Test fun numericForwardCursorAdvancesTheWalk() {
        assertEquals(50, nextGroupPage(0, rooms, "50"))
        assertEquals(100, nextGroupPage(50, rooms, " 100 "))
    }

    @Test fun emptyPageEndsTheWalk() {
        assertNull(nextGroupPage(0, emptyList(), "50"))
    }

    @Test fun nullCursorEndsTheWalk() {
        assertNull(nextGroupPage(0, rooms, null))
    }

    @Test fun opaqueCursorEndsTheWalkInsteadOfGuessing() {
        assertNull(nextGroupPage(0, rooms, "cursor-abc"))
    }

    @Test fun nonAdvancingCursorEndsTheWalk() {
        assertNull(nextGroupPage(50, rooms, "50"))
        assertNull(nextGroupPage(50, rooms, "0"))
    }
}
