package com.hermes.android.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState

/** Includes trailing padding so the final bubble is fully above the composer. */
internal fun remainingScrollToEnd(
    itemOffset: Int,
    itemSize: Int,
    viewportEnd: Int,
    afterContentPadding: Int,
): Float = (itemOffset.toLong() + itemSize + afterContentPadding - viewportEnd)
    .coerceAtLeast(0L).toFloat()

internal suspend fun LazyListState.animateScrollToEnd() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    // First bring the tail into view. For a bubble taller than the viewport,
    // this only reaches its top, so scroll the remaining bottom edge as well.
    animateScrollToItem(lastIndex)
    val info = layoutInfo
    val lastItem = info.visibleItemsInfo.lastOrNull()
        ?.takeIf { it.index == info.totalItemsCount - 1 } ?: return
    val remaining = remainingScrollToEnd(
        lastItem.offset, lastItem.size, info.viewportEndOffset, info.afterContentPadding,
    )
    if (remaining > 0) animateScrollBy(remaining)
}
