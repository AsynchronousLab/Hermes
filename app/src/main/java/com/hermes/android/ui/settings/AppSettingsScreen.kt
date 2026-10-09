package com.hermes.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermes.android.core.store.AppAppearance
import com.hermes.android.core.store.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    appearance: AppAppearance,
    onTheme: (ThemeMode) -> Unit,
    onFontScale: (Float) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("程序设置") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(
                    selected = appearance.theme == mode, role = Role.RadioButton,
                    onClick = { onTheme(mode) },
                ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = appearance.theme == mode, onClick = null)
                    Text(mode.label, Modifier.padding(start = 12.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("字号大小", style = MaterialTheme.typography.titleMedium)
            Text("在系统字号基础上调整，立即生效", style = MaterialTheme.typography.bodySmall)
            listOf("小" to 0.85f, "标准" to 1f, "大" to 1.15f, "特大" to 1.3f).forEach { (label, scale) ->
                Row(Modifier.fillMaxWidth().selectable(
                    selected = appearance.fontScale == scale, role = Role.RadioButton,
                    onClick = { onFontScale(scale) },
                ).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = appearance.fontScale == scale, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("文字预览", style = MaterialTheme.typography.titleMedium)
                    Text("你好，这里是 Hermes。字号设置会应用到消息和其他页面。")
                }
            }
        }
    }
}
