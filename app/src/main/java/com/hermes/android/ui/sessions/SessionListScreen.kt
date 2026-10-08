package com.hermes.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.core.model.Group
import com.hermes.android.core.model.SessionSummary
import com.hermes.android.core.net.RpcConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Feishu-style conversation list: search field, grouped rows, floating new-chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: SessionListUiState,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onOpenRoom: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (SessionSummary) -> Unit,
) {
    // Deleting is irreversible, so the row's trash button only arms a
    // confirmation; nothing is sent until the dialog is accepted.
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    val target = pendingDelete

    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = {
                Text("「${target.displayTitle}」将被永久删除，无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Hermes 会话", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                SearchField(state.query, onSearch)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSession,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("新建会话") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionChip(state.connection, state.stats)

            state.error?.let { Banner(it, isError = true, onDismiss = onDismiss) }
            state.notice?.let { Banner(it, isError = false, onDismiss = onDismiss) }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.needsSetup -> SetupPrompt(onOpenSettings)

                state.visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (state.query.isBlank()) "还没有会话，点右下角新建" else "没有匹配的会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.visible, key = { it.id }) { c ->
                        when (c) {
                            is Conversation.Chat -> SessionRow(
                                summary = c.summary,
                                deleting = c.id in state.deleting,
                                onClick = { onOpen(c.summary) },
                                onDelete = { pendingDelete = c.summary },
                            )

                            is Conversation.Room -> RoomRow(
                                room = c.room,
                                onClick = { onOpenRoom(c.room.roomId) },
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Tappable dismiss strip for a transient error (red) or confirmation (neutral). */
@Composable
private fun Banner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.fillMaxWidth()
            .background(bg)
            .clickable(onClick = onDismiss)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = fg)
    }
}

@Composable
private fun SetupPrompt(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text("还没有配置后端", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "填写 Hermes 网关地址后即可拉取会话并开始对话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
            Text("去设置")
        }
    }
}

@Composable
private fun SearchField(query: String, onSearch: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = { Text("搜索会话", fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ConnectionChip(state: RpcConnectionState, stats: SessionStats?) {
    val (dot, label) = when (state) {
        RpcConnectionState.Connected -> Color(0xFF12B76A) to
            (stats?.let { "已连接 · 共 ${it.total} 个会话 · ${it.messages} 条消息" } ?: "已连接")
        RpcConnectionState.Connecting -> Color(0xFFF59E0B) to "连接中…"
        RpcConnectionState.Reconnecting -> Color(0xFFF59E0B) to "重连中…"
        RpcConnectionState.Failed -> MaterialTheme.colorScheme.error to "未连接，请在设置中配置"
        RpcConnectionState.Disconnected -> MaterialTheme.colorScheme.outline to "未连接"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    deleting: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !deleting, onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                .background(
                    if (summary.source == "cron") MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // One icon per conversation kind, the way an IM app distinguishes
            // a direct chat from a scheduled run.
            Icon(
                imageVector = when (summary.source) {
                    "cron" -> Icons.Default.Schedule
                    else -> Icons.Default.ChatBubble
                },
                contentDescription = null,
                tint = if (summary.source == "cron") MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                summary.source?.let { SourceTag(it) }
            }
            Text(
                summary.preview?.takeIf { it.isNotBlank() } ?: "（无预览）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatTime(summary.startedAt) +
                    (summary.messageCount?.takeIf { it > 0 }?.let { " · $it 条" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (deleting) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除会话",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RoomRow(room: Group, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                room.name.ifBlank { room.roomId },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                room.members.joinToString("、") { it.handle.ifBlank { it.memberId } }
                    .ifBlank { "暂无成员" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("${room.memberCount} 人")
                    val s = room.updatedAt ?: room.createdAt
                    if (s != null) append(" · " + formatTime(s))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SourceTag(source: String) {
    val label = when (source) {
        "android" -> "Android"
        "cron" -> "定时"
        "desktop" -> "桌面"
        "feishu" -> "飞书"
        "qqbot" -> "QQ"
        "weixin" -> "微信"
        "telegram" -> "TG"
        "tui" -> "TUI"
        "cli" -> "CLI"
        else -> source
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun formatTime(epochSeconds: Double?): String {
    if (epochSeconds == null || epochSeconds <= 0) return "—"
    val d = Date((epochSeconds * 1000).toLong())
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return fmt.format(d)
}
