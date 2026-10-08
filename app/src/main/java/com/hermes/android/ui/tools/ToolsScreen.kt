package com.hermes.android.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.core.model.CronJob
import com.hermes.android.core.model.FileEntry
import com.hermes.android.core.model.Skill
import com.hermes.android.core.model.Toolset
import com.hermes.android.ui.sessions.formatTime

/** Skills, toolsets, artifacts, cron jobs and background processes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    state: ToolsUiState,
    onTab: (ToolsUiState.Tab) -> Unit,
    onReload: () -> Unit,
    onBrowse: (String) -> Unit,
    onExpandToolset: (String) -> Unit,
    onToggleToolset: (Toolset) -> Unit,
    onOpenSkill: (String) -> Unit,
    onToggleSkill: (Skill) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSkill: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("更多", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        TextButton(onClick = onOpenSettings) { Text("后端设置") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                TabRow(
                    selectedTabIndex = state.tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    ToolsUiState.Tab.entries.forEach { t ->
                        Tab(
                            selected = state.tab == t,
                            onClick = { onTab(t) },
                            text = { Text(t.label, fontSize = 13.sp) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { MessageStrip(it, true, onClear) }
            state.notice?.let { MessageStrip(it, false, onClear) }

            val skillDetail = state.skillDetail
            if (skillDetail != null) {
                SkillDetailView(skillDetail, onCloseSkill)
                return@Column
            }

            when (state.tab) {
                ToolsUiState.Tab.SKILLS -> SkillList(state.skills, onOpenSkill, onToggleSkill)
                ToolsUiState.Tab.TOOLS -> ToolsetList(
                    state.toolsets, state.expandedToolset, onExpandToolset, onToggleToolset
                )

                ToolsUiState.Tab.FILES -> FileList(
                    state.filePath, state.files, onBrowse
                )

                ToolsUiState.Tab.CRON -> CronList(state.cron)
                ToolsUiState.Tab.AGENTS -> AgentList(state.agents)
            }
        }
    }
}

@Composable
private fun MessageStrip(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .clickable(onClick = onDismiss).padding(10.dp),
    ) {
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SkillList(skills: List<Skill>, onOpen: (String) -> Unit, onToggle: (Skill) -> Unit) {
    if (skills.isEmpty()) {
        Empty("没有技能")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(skills, key = { it.name }) { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(s.name) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.description?.takeIf { it.isNotBlank() } ?: "（无描述）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.category?.let { Tag(it) }
                        s.usage?.takeIf { it > 0 }?.let { Tag("用过 $it 次") }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = s.enabled, onCheckedChange = { onToggle(s) })
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ToolsetList(
    toolsets: List<Toolset>,
    expanded: String?,
    onExpand: (String) -> Unit,
    onToggle: (Toolset) -> Unit,
) {
    if (toolsets.isEmpty()) {
        Empty("没有工具集")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(toolsets, key = { it.name }) { t ->
            Column(Modifier.fillMaxWidth().clickable { onExpand(t.name) }.padding(14.dp, 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${t.toolCount} 个工具",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = t.enabled,
                        onCheckedChange = { onToggle(t) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    t.description?.takeIf { it.isNotBlank() } ?: "（无描述）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded == t.name && t.tools.isNotEmpty()) {
                    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
                        t.tools.forEach { tool ->
                            Text(
                                "· $tool",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun FileList(path: String, entries: List<FileEntry>, onBrowse: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { onBrowse(parentOf(path)) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ArrowBack, "返回上级", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (entries.isEmpty()) {
            Empty("该目录为空")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (e.isDirectory) onBrowse(e.path) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (e.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        null, Modifier.size(18.dp),
                        tint = if (e.isDirectory) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                e.isDirectory.takeIf { it }?.let { "目录" },
                                e.size?.takeIf { !e.isDirectory }?.let { formatSize(it) },
                                formatTime(e.mtime).takeIf { it != "—" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (e.isDirectory) {
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CronList(jobs: List<CronJob>) {
    if (jobs.isEmpty()) {
        Empty("没有定时任务")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(jobs, key = { it.id }) { j ->
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        j.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(
                                if (j.paused) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.secondaryContainer
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (j.paused) "已停用" else "启用",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "计划：${j.scheduleLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
                j.prompt?.let {
                    Text(
                        it.replace(Regex("\\s+"), " ").take(160),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                j.profileName?.let {
                    Text(
                        "Profile：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                j.nextRunAt?.let {
                    Text(
                        "下次运行：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                j.lastStatus?.let {
                    Text(
                        "上次：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                j.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it.take(120),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AgentList(agents: List<com.hermes.android.core.model.AgentProcess>) {
    if (agents.isEmpty()) {
        Empty("没有后台进程")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(agents, key = { it.sessionId ?: it.hashCode().toString() }) { a ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.sessionId ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    Tag(a.status ?: "未知")
                }
                Text(
                    a.command?.replace(Regex("\\s+"), " ")?.take(120) ?: "（无命令）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SkillDetailView(detail: SkillDetail, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClose).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ArrowBack, "返回", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(detail.name, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    detail.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(14.dp).horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun parentOf(path: String): String {
    if (path.isBlank() || path == "/") return "/"
    val p = path.trimEnd('/')
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / 1024 / 1024} MB"
}
