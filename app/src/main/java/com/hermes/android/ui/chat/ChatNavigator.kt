package com.hermes.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One place the reader can jump to inside a conversation.
 *
 * [index] is the position in the *visible* transcript, which is what
 * `LazyListState.animateScrollToItem` consumes; [key] identifies the row so a
 * highlight can follow it through recomposition.
 */
internal data class JumpTarget(
    val index: Int,
    val key: Long,
    val role: ChatMessage.Role,
    /** Question ordinal (only set for user turns). */
    val turn: Int,
    val label: String,
    val stamp: Long,
)

/**
 * The outline of a conversation: every question the reader asked.
 *
 * Answers are deliberately not listed — a long tool-heavy turn produces several
 * assistant rows, and a per-row outline stops being an outline. Jumping to a
 * question is what "where was that exchange" actually means.
 */
internal fun outlineOf(rows: List<ChatMessage>): List<JumpTarget> {
    var turn = 0
    val out = mutableListOf<JumpTarget>()
    rows.forEachIndexed { i, m ->
        if (m.role != ChatMessage.Role.User) return@forEachIndexed
        turn++
        out += JumpTarget(
            index = i,
            key = m.key,
            role = m.role,
            turn = turn,
            label = oneLine(m.text),
            stamp = m.timestamp,
        )
    }
    return out
}

/**
 * In-conversation search.
 *
 * Matches on the rendered text only, so a hit is always somewhere the reader can
 * be taken to; tool output lives in the collapsed strip and is not addressable.
 */
internal fun matchesOf(rows: List<ChatMessage>, query: String): List<JumpTarget> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    var turn = 0
    val out = mutableListOf<JumpTarget>()
    rows.forEachIndexed { i, m ->
        if (m.role == ChatMessage.Role.User) turn++
        if (!m.text.contains(q, ignoreCase = true)) return@forEachIndexed
        out += JumpTarget(
            index = i,
            key = m.key,
            role = m.role,
            turn = turn,
            label = snippetAround(m.text, q),
            stamp = m.timestamp,
        )
    }
    return out
}

/** First line of a message, collapsed to a single row of [limit] characters. */
internal fun oneLine(text: String, limit: Int = 60): String {
    val flat = text.split('\n').firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val body = flat.ifBlank { text.trim() }
    if (body.isEmpty()) return "（空消息）"
    return if (body.length <= limit) body else body.take(limit).trimEnd() + "…"
}

/** A window of [text] around the first case-insensitive hit on [query]. */
internal fun snippetAround(text: String, query: String, radius: Int = 18): String {
    val flat = text.replace('\n', ' ').trim()
    val at = flat.indexOf(query, ignoreCase = true)
    if (at < 0) return oneLine(flat)
    val from = (at - radius).coerceAtLeast(0)
    val to = (at + query.length + radius).coerceAtMost(flat.length)
    return buildString {
        if (from > 0) append('…')
        append(flat.substring(from, to).trim())
        if (to < flat.length) append('…')
    }
}

/** `HH:mm`, or an empty string for a row without a usable stamp. */
internal fun jumpTime(stamp: Long): String {
    if (stamp <= 0) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(stamp))
}

/**
 * The in-conversation navigator: question outline, or search results.
 *
 * Both live in one sheet because they answer the same question — "take me to
 * that part of the conversation".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatJumpSheet(
    rows: List<ChatMessage>,
    onJump: (JumpTarget) -> Unit,
    onJumpLatest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val outline = remember(rows) { outlineOf(rows) }
    var query by remember { mutableStateOf("") }
    val results = remember(rows, query) { matchesOf(rows, query) }
    val searching = query.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("跳转", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onJumpLatest(); onDismiss() }) { Text("回到最新") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("在本对话中查找…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (searching) {
                Text(
                    if (results.isEmpty()) "没有匹配的文字" else "找到 ${results.size} 处",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        val items = if (searching) results else outline
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(items, key = { "j-${it.index}-${it.key}" }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onJump(t); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(
                                if (t.role == ChatMessage.Role.User)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (searching) "·" else "${t.turn}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (t.role == ChatMessage.Role.User)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val whenText = jumpTime(t.stamp)
                        if (whenText.isNotEmpty() || searching) {
                            Text(
                                buildString {
                                    if (t.role == ChatMessage.Role.Assistant) append("Hermes")
                                    else append("我")
                                    if (whenText.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(whenText)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 52.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
