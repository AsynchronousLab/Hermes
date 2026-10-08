package com.hermes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.core.model.ModelProvider
import com.hermes.android.core.model.ReasoningLevel

/** Provider → model browser fed by `model.options`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ModelProvider>,
    current: String?,
    currentProvider: String?,
    onPick: (ModelProvider, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var expandedProvider by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("选择模型", style = MaterialTheme.typography.titleMedium)
            Text(
                "${providers.sumOf { it.totalModels }} 个模型 · ${providers.size} 个供应商",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            providers.forEach { p ->
                val isOpen = expandedProvider == p.slug
                item(key = "p-${p.slug}") {
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            expandedProvider = if (isOpen) null else p.slug
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name.ifBlank { p.slug },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (p.isCurrent || p.slug == currentProvider) Tag("当前")
                            Text(
                                "${p.models.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            p.slug,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (isOpen) {
                    items(
                        count = p.models.size,
                        key = { i -> "m-${p.slug}-${p.models[i]}" },
                    ) { i ->
                        val model = p.models[i]
                        val selected = model == current
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(p, model) }
                                .padding(start = 28.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                model,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Reasoning effort picker; the wire values mirror the dashboard exactly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningPickerSheet(
    current: ReasoningLevel,
    onPick: (ReasoningLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("推理强度", style = MaterialTheme.typography.titleMedium)
            Text(
                "越高越慢越细致，适合复杂分析；关闭思考速度最快。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ReasoningLevel.entries.forEach { level ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onPick(level) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(level.label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    Text(
                        level.wire,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (level == current) {
                        Spacer(Modifier.width(8.dp))
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier.padding(end = 6.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}