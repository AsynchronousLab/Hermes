package com.hermes.android.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.core.model.Group
import com.hermes.android.core.model.GroupLimits
import com.hermes.android.core.model.Profile

/**
 * Top-level 联系人 destination: two panes — individual bots (Hermes profiles)
 * and groups (rooms) that combine several of them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    onTab: (ContactsUiState.Tab) -> Unit,
    onRefresh: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenGroup: (Group) -> Unit,
    onOpenCreate: () -> Unit,
    onToggleMember: (String) -> Unit,
    onDraftName: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onCloseSheet: () -> Unit,
    onClear: () -> Unit,
) {
    if (state.showCreateSheet) {
        CreateGroupSheet(
            state = state,
            onName = onDraftName,
            onToggle = onToggleMember,
            onCreate = onCreateGroup,
            onDismiss = onCloseSheet,
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("联系人", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                    actions = {
                        when (state.tab) {
                            ContactsUiState.Tab.GROUPS -> {
                                TextButton(onClick = onOpenCreate) { Text("新建群组") }
                            }

                            ContactsUiState.Tab.CONTACTS -> {
                                TextButton(onClick = onRefresh) { Text("刷新") }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                TabRow(
                    selectedTabIndex = state.tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    ContactsUiState.Tab.entries.forEach { t ->
                        Tab(
                            selected = state.tab == t,
                            onClick = { onTab(t) },
                            text = { Text(t.label, fontSize = 14.sp) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Strip(it, true, onClear) }
            state.notice?.let { Strip(it, false, onClear) }

            when (state.tab) {
                ContactsUiState.Tab.CONTACTS -> ProfileList(state.profiles, onOpenProfile)
                ContactsUiState.Tab.GROUPS -> GroupList(state, onOpenGroup)
            }
        }
    }
}

@Composable
private fun Strip(text: String, isError: Boolean, onDismiss: () -> Unit) {
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
private fun ProfileList(profiles: List<Profile>, onOpen: (String) -> Unit) {
    if (profiles.isEmpty()) {
        Empty("没有可用的 profile")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(profiles, key = { it.name }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p.name) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("☤", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        if (p.isDefault) {
                            Spacer(Modifier.width(6.dp))
                            Tag("默认")
                        }
                    }
                    Text(
                        p.description?.takeIf { it.isNotBlank() }
                            ?: "${p.model ?: "默认模型"} · ${p.skillCount ?: 0} 技能",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    p.model?.let {
                        Text(
                            it, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupList(state: ContactsUiState, onOpenGroup: (Group) -> Unit) {
    if (state.groups.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(
                "还没有群组", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { },
                enabled = false,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("新建群组") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.groups, key = { it.roomId }) { g ->
            Column(
                Modifier.fillMaxWidth().clickable { onOpenGroup(g) }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Groups, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(g.name.ifBlank { g.roomId }, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        "${g.memberCount} 人",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.members.forEach { m ->
                        Tag(state.titleOf(m.profile) ?: m.handle.ifBlank { m.memberId })
                    }
                }
                Text(
                    "room_id: ${g.roomId}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateGroupSheet(
    state: ContactsUiState,
    onName: (String) -> Unit,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("新建群组", style = MaterialTheme.typography.titleLarge)
            Text(
                "选择 ${GroupLimits.MIN_MEMBERS}-${GroupLimits.MAX_MEMBERS} 个 profile 组成群组",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.draftName,
                onValueChange = onName,
                label = { Text("群组名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("成员", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.draftMembers.size}/${GroupLimits.MAX_MEMBERS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            state.profiles.forEach { p ->
                val checked = p.name in state.draftMembers
                val disabled = !checked && state.draftMembers.size >= GroupLimits.MAX_MEMBERS
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !disabled) { onToggle(p.name) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle(p.name) },
                        enabled = !disabled,
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (disabled) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${p.model ?: "?"} · ${p.skillCount ?: 0} 技能",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = onCreate,
                    enabled = state.draftValid && state.draftName.isNotBlank() && !state.creating,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.creating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("创建")
                }
            }
            Spacer(Modifier.height(24.dp))
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