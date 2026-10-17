package com.hermes.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.android.core.ConnectionTestResult
import com.hermes.android.core.net.RpcConnectionState
import com.hermes.android.core.net.ProxyType

/**
 * Backend configuration with a real verification path: the test button walks
 * health → login → ws-ticket → WebSocket → `gateway.ping` and reports each leg.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBaseUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onProxyType: (ProxyType) -> Unit,
    onProxyHost: (String) -> Unit,
    onProxyPort: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后端设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Hermes 后端", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "填写你自己部署的 Hermes 网关地址。首次使用点“测试连接”，" +
                            "会依次验证健康检查、登录、WebSocket 握手和 ping。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrl,
                label = { Text("后端地址") },
                placeholder = { Text("http://192.168.1.10:9119") },
                supportingText = { Text("例如 http://192.168.1.10:9119，使用正斜杠 /，支持 http:// 或 https://") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(12.dp),
            )

            OutlinedTextField(
                value = state.username,
                onValueChange = onUsername,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = onPassword,
                label = { Text("密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (state.showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePassword) {
                        Icon(
                            if (state.showPassword) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = "显示/隐藏密码",
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )

            HorizontalDivider()
            Text("网络代理（可选）", style = MaterialTheme.typography.titleMedium)
            Text(
                "默认直连。启用后，健康检查、登录、文件请求和 WebSocket 都通过代理连接后端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                ProxyType.DIRECT to "不使用代理",
                ProxyType.WEB to "Web（HTTP/HTTPS 代理）",
                ProxyType.SOCKS to "SOCKS5 代理",
            ).forEach { (type, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onProxyType(type) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.proxyType == type, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.proxyType != ProxyType.DIRECT) {
                OutlinedTextField(
                    value = state.proxyHost,
                    onValueChange = onProxyHost,
                    label = { Text("代理主机") },
                    placeholder = { Text("proxy.example.com") },
                    supportingText = { Text("只填域名或 IP，不含协议、路径和端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = state.proxyPort,
                    onValueChange = onProxyPort,
                    label = { Text("代理端口") },
                    placeholder = { Text("7890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    "当前支持无需账号密码的代理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("保存") }

                Button(
                    onClick = onTest,
                    enabled = state.canTest,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Text("测试连接")
                    }
                }
            }

            if (state.saved && !state.testing) {
                Text(
                    "已保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            state.result?.let { TestResultCard(it) }

            state.error?.let {
                ErrorStrip(it)
            }

            ConnectionCard(state)

            HorizontalDivider()
            Text(
                "未启用代理时直接连接后端；启用后经你配置的代理转发。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun TestResultCard(result: ConnectionTestResult) {
    when (result) {
        is ConnectionTestResult.Success -> Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("连接成功", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                // Column, not Row: each DetailLine fills the width, so a row
                // squeezed everything but the first one out of view.
                Column {
                    DetailLine("版本", result.version)
                    DetailLine("账号", result.displayName ?: result.username)
                    DetailLine("HTTP 耗时", "${result.httpMs} ms")
                    DetailLine("WS", result.wsPath)
                    DetailLine("票据", result.ticketHint)
                    DetailLine("gateway.ping", if (result.pingOk) "ok" else "未返回")
                }
            }
        }

        is ConnectionTestResult.Failed -> ErrorStrip(result.message)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label, fontSize = 12.sp, modifier = Modifier.width(88.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ErrorStrip(message: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Default.Error, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(
            message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ConnectionCard(state: SettingsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("连接状态", style = MaterialTheme.typography.titleMedium)
            val label = when (state.connection) {
                RpcConnectionState.Connected -> "WebSocket 已连接"
                RpcConnectionState.Connecting -> "连接中"
                RpcConnectionState.Reconnecting -> "重连中"
                RpcConnectionState.Failed -> "连接失败"
                RpcConnectionState.Disconnected -> "未连接"
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
            state.profileInfo?.let {
                Text(
                    "当前 Profile：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.profiles.isNotEmpty()) {
                Text(
                    "可用 Profile：${state.profiles.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
