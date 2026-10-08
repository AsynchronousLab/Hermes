package com.hermes.android.ui.chat

import com.hermes.android.core.model.OpenRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read again if live state changed during the read; never join overlapping snapshots by text. */
internal suspend fun <T> readStableSnapshot(
    isCurrent: () -> Boolean,
    revision: () -> Long,
    quietMillis: Long = 250,
    canRead: () -> Boolean = { true },
    read: suspend () -> T,
): T? {
    while (isCurrent()) {
        val before = revision()
        delay(quietMillis)
        if (!isCurrent()) return null
        if (revision() != before || !canRead()) continue
        val result = read()
        // RPC replies bypass the push queue. Let already queued events run
        // before accepting the snapshot as stable.
        delay(quietMillis)
        if (!isCurrent()) return null
        if (revision() == before && canRead()) return result
    }
    return null
}

/** History owns delivered rows. Keep local-only drafts and commands, even across reconnects. */
internal fun mergeHistorySnapshot(history: List<ChatMessage>, current: List<ChatMessage>): List<ChatMessage> =
    history + current.filter { !it.delivered || it.isSlashInput || it.isSlashOutput }

internal fun requestCard(method: String, id: String, payload: JsonObject): ServerRequest? {
    if (id.isBlank()) return null
    fun field(name: String) = (payload[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return when (method) {
        "approval.request" -> ServerRequest(id, ServerRequest.Kind.APPROVAL,
            field("command") ?: field("description") ?: field("reason") ?: "工具请求执行操作")
        "clarify.request" -> ServerRequest(id, ServerRequest.Kind.CLARIFY,
            field("question") ?: field("message") ?: "Hermes 想确认一下")
        else -> null
    }
}

internal fun recoverRequest(requests: List<OpenRequest>, sessionId: String, currentId: String?): ServerRequest? {
    val cards = requests.filter { it.session_id == sessionId }.mapNotNull {
        requestCard(it.method, it.id, it.payload as? JsonObject ?: JsonObject(emptyMap()))
    }
    return cards.firstOrNull { it.id == currentId } ?: cards.firstOrNull()
}
