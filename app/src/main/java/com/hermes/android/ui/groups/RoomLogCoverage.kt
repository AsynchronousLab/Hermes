package com.hermes.android.ui.groups

import com.hermes.android.core.model.RoomLog

/** No cursor is accepted by this gateway: expose missing coverage without inventing a fetch API. */
internal fun roomLogWarning(page: RoomLog, known: Long?): String? {
    val seqs = page.events.mapNotNull { it.seq }.distinct().sorted()
    val newest = maxOf(seqs.lastOrNull() ?: 0L, known ?: 0L)
    if (page.latestSeq?.let { it > newest } == true) {
        return "房间还有未读取的消息，当前网关返回的记录不完整；显示内容可能不是最新。"
    }
    val gap = seqs.zipWithNext().any { (a, b) -> b > a + 1 } ||
        seqs.firstOrNull()?.let { it > (known ?: 0L) + 1 } == true
    return when {
        page.hasMore == true || gap -> "部分房间记录未加载，当前显示的历史可能不完整。"
        page.events.size >= 500 -> "房间记录已达到单次读取上限，显示的历史可能不完整。"
        else -> null
    }
}
