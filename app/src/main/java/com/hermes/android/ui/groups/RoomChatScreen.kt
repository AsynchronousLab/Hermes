package com.hermes.android.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.ui.markdown.MarkdownText

/**
 * A room transcript.
 *
 * Mirrors the chat surface's shape — top bar, scrollback, composer — so the two
 * do not feel like different apps, but renders [RoomMessage] rows instead of
 * streaming chat turns. Nothing here streams: the room transcript is refilled
 * wholesale from `groups.log` by the ViewModel's cursor poll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomChatScreen(
    state: RoomUiState,
    onBack: () -> Unit,
    onSend: (String) -> Boolean,
    onRetry: (String) -> Unit,
    onHold: () -> Unit,
    onDismiss: () -> Unit,
    onConsumeRestoredDraft: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Only follow the tail while the reader is already at it. Unconditionally
    // scrolling on every new event yanked someone back down whenever they were
    // reading history — the chat screen gates this on its own autoFollow.
    //
    // `firstVisibleItemIndex >= total - 2` is not that test: with 10 items it
    // stops following while items 7-10 are still on screen, and with one tall
    // item it never stops even while the reader is halfway up. Decide from the
    // last visible item's bottom edge instead, matching `atBottom` in ChatScreen
    // and `remainingScrollToEnd` in ChatScroll.kt.
    var autoFollow by remember { mutableStateOf(true) }
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            // Before the first layout there is nothing to judge, and an empty
            // list must not flip following off — it is the state the initial
            // history load scrolls from.
            if (!primed) {
                if (info.totalItemsCount == 0 || lastVisible != null) primed = true
                return@collect
            }
            autoFollow = lastVisible != null &&
                lastVisible.index >= info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 1
        }
    }
    LaunchedEffect(state.messages.size) {
        if (autoFollow && state.messages.isNotEmpty()) {
            // Same two-step scroll as ChatScroll.animateScrollToEnd, which is
            // internal to the chat package: land on the last item, then push out
            // whatever of it is still below the fold.
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) {
                listState.animateScrollToItem(last)
                val info = listState.layoutInfo
                val lastItem = info.visibleItemsInfo.lastOrNull()
                    ?.takeIf { it.index == info.totalItemsCount - 1 }
                if (lastItem != null) {
                    val remaining = (lastItem.offset + lastItem.size +
                        info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0)
                    if (remaining > 0) listState.animateScrollBy(remaining.toFloat())
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.room.name.ifBlank { state.room.roomId.ifBlank { "群聊" } },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append("${state.room.memberCount} 个成员")
                                    val d = state.driver
                                    if (d?.running == true) append(" · 驱动运行中")
                                    if (d?.blocked == true) append(" · 已阻塞")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = onHold) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = "暂停",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            state.error?.let { RoomBanner(it, isError = true, onDismiss = onDismiss) }
            state.notice?.let { RoomBanner(it, isError = false, onDismiss = onDismiss) }

            when {
                // weight(1f), not fillMaxSize(): a fillMaxSize child eats the
                // whole Column and leaves the composer with no height at all,
                // so an empty (or still-loading) room had no input box.
                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.messages.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        "还没有消息，说点什么吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.key }) { m -> RoomRow(m, onRetry) }
                }
            }

            RoomComposer(
                enabled = state.supported != false && !state.sending,
                sending = state.sending,
                restore = state.restoreDraft,
                onRestoreConsumed = onConsumeRestoredDraft,
                onSend = onSend,
            )
        }
    }
}

/**
 * Human-readable label for a room-machinery event.
 *
 * These have no `text` in their payload, so showing the raw event name is all
 * there is — but map the known ones so the transcript does not read like a
 * debug log.
 */
private fun systemLabel(m: RoomMessage): String = when (m.kind) {
    "turn.settled" -> "本轮讨论已结束"
    "turn.started" -> "开始新一轮讨论"
    "room.activity" -> "房间活动"
    "room.renamed" -> m.text.takeIf { it.isNotBlank() && it != m.kind }?.let { "房间改名为 $it" }
        ?: "房间已改名"
    else -> m.kind
}

@Composable
private fun RoomRow(m: RoomMessage, onRetry: (String) -> Unit) {
    if (m.system) {
        // Room machinery, not conversation: `room.renamed`, `turn.settled`,
        // `room.activity`. A quiet centre line — these carry no text, so
        // rendering them as chat bubbles both read as noise and printed the
        // event name twice.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                systemLabel(m),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 3.dp, horizontal = 16.dp),
            )
        }
        return
    }

    // An unknown-outcome bubble wears the error palette so it reads as needing
    // attention; tapping it retries under the original event id, which the
    // gateway collapses onto the first delivery — the tap cannot double-send.
    val retryable = m.sendState == RoomSendState.Unknown
    val container = when {
        retryable -> MaterialTheme.colorScheme.errorContainer
        m.mine -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        retryable -> MaterialTheme.colorScheme.onErrorContainer
        m.mine -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val body = when {
        retryable -> MaterialTheme.colorScheme.onErrorContainer
        m.mine -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (m.mine) 14.dp else 4.dp,
                    bottomEnd = if (m.mine) 4.dp else 14.dp))
                .background(container)
                .then(if (retryable) Modifier.clickable { onRetry(m.key) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                m.actorId ?: "—",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = fg,
            )
            Spacer(Modifier.height(3.dp))
            MarkdownText(
                text = m.text,
                color = body,
            )
            Spacer(Modifier.height(3.dp))
            // The echo has no server sequence yet, so printing it would show
            // #9223372036854775807 to the user.
            when (m.sendState) {
                RoomSendState.Sending -> Text("发送中…", fontSize = 10.sp, color = fg)
                RoomSendState.Unknown -> Text("状态未知 · 点击重试", fontSize = 10.sp, color = fg)
                RoomSendState.Settled -> Text(
                    "#${m.seq}",
                    fontSize = 10.sp,
                    color = if (m.mine) fg else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun RoomComposer(
    enabled: Boolean,
    sending: Boolean,
    restore: String?,
    onRestoreConsumed: () -> Unit,
    onSend: (String) -> Boolean,
) {
    var draft by remember { mutableStateOf("") }
    // A refused or definitively-failed send hands the text back: retry is one
    // tap instead of a retype.
    LaunchedEffect(restore) {
        if (restore != null) {
            draft = restore
            onRestoreConsumed()
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().padding(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "发到群里…" else "只读模式", fontSize = 14.sp) },
            enabled = enabled,
            maxLines = 4,
            shape = RoundedCornerShape(18.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(8.dp))
        if (sending) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(
                onClick = {
                    // Only clear once the ViewModel accepted the draft, so a
                    // busy or read-only room does not silently swallow it.
                    if (onSend(draft)) draft = ""
                },
                enabled = enabled && draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (enabled && draft.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun RoomBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onDismiss).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = fg)
    }
}