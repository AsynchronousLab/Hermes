package com.hermes.android.core.net

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferLatestTest {
    @Test fun overflowSignalsRecoveryEvenThoughTheNewestEventWasAccepted() {
        val ch = Channel<Int>(capacity = 1)
        var losses = 0
        offerLatest(ch, 1) { losses++ }
        assertEquals(0, losses)
        assertTrue(offerLatest(ch, 2) { losses++ })
        assertEquals(1, losses)
        assertEquals(2, ch.tryReceive().getOrNull())
    }
    @Test fun roomAvailableEnqueuesInOrder() {
        val ch = Channel<Int>(capacity = 4)
        assertTrue(offerLatest(ch, 1))
        assertTrue(offerLatest(ch, 2))
        assertEquals(1, ch.tryReceive().getOrNull())
        assertEquals(2, ch.tryReceive().getOrNull())
    }

    @Test fun fullQueueEvictsTheOldestAndKeepsTheNewest() {
        val ch = Channel<Int>(capacity = 2)
        offerLatest(ch, 1)
        offerLatest(ch, 2)
        // The queue is full: the newest frame must still land, by evicting the
        // oldest — losing frame 1, never frame 3.
        assertTrue(offerLatest(ch, 3))
        assertEquals(2, ch.tryReceive().getOrNull())
        assertEquals(3, ch.tryReceive().getOrNull())
        assertTrue(ch.tryReceive().isFailure)
    }

    @Test fun closedQueueReportsFailureInsteadOfThrowing() {
        val ch = Channel<Int>(capacity = 2)
        ch.close()
        assertFalse(offerLatest(ch, 1))
    }
}
