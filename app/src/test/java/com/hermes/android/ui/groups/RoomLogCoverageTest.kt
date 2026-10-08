package com.hermes.android.ui.groups

import com.hermes.android.core.model.RoomEvent
import com.hermes.android.core.model.RoomLog
import org.junit.Assert.*
import org.junit.Test

class RoomLogCoverageTest {
    private fun page(range: LongRange, latest: Long? = null, more: Boolean? = null) =
        RoomLog(events = range.map { RoomEvent(seq = it) }, latestSeq = latest, hasMore = more)

    @Test fun fixedFirstPageReportsUnreachableNewMessages() {
        assertNotNull(roomLogWarning(page(1L..500L, 600, true), 500))
    }
    @Test fun rollingTailReportsMissingHistory() {
        assertNotNull(roomLogWarning(page(101L..600L, 600, false), null))
    }
    @Test fun pollThatSkipsEventsReportsGap() {
        assertNotNull(roomLogWarning(page(200L..205L, 205), 150))
    }
    @Test fun completeAndRepeatedSmallPagesDoNotWarn() {
        assertNull(roomLogWarning(page(1L..20L, 20, false), null))
        assertNull(roomLogWarning(page(1L..20L, 20, false), 20))
        assertNull(roomLogWarning(RoomLog(), 20))
    }
    @Test fun metadataIsCheckedEvenWhenThereAreNoFreshRows() {
        assertNotNull(roomLogWarning(RoomLog(latestSeq = 501), 500))
    }
    @Test fun fullPageWithoutMetadataStillDisclosesTheLimit() {
        assertNotNull(roomLogWarning(page(1L..500L), 500))
    }
}
