package com.hermes.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScrollTest {
    @Test fun tallFinalBubbleRequiresScrollingPastItsTop() {
        assertEquals(1412f, remainingScrollToEnd(0, 2000, 600, 12), 0f)
    }

    @Test fun finalBubbleAndTrailingPaddingAlreadyVisibleNeedNoScroll() {
        assertEquals(0f, remainingScrollToEnd(100, 480, 600, 12), 0f)
    }

    @Test fun remainingDistanceIncludesTrailingPadding() {
        assertEquals(12f, remainingScrollToEnd(100, 500, 600, 12), 0f)
    }

    @Test fun negativeItemOffsetDoesNotOverScroll() {
        assertEquals(212f, remainingScrollToEnd(-200, 1000, 600, 12), 0f)
    }
}
