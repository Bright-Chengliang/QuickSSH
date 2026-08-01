package com.quickssh.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quickssh.app.R
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.groupedSshServers
import com.quickssh.app.data.tunnelPresetDefaultName
import com.quickssh.app.data.transferContextLabel
import com.quickssh.app.data.workspaceLabel
import com.quickssh.app.service.TunnelServiceState
import com.quickssh.app.service.TunnelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelScreen(
    configs: List<SshConfig>,
    selectedConfig: SshConfig?,
    remoteHost: String,
    remotePort: String,
    localPort: String,
    presetName: String,
    presetNote: String,
    selectedPresetId: Long?,
    tunnelPresets: List<SshTunnelPreset>,
    statusText: String,
    tunnelStates: List<TunnelServiceState>,
    bottomBar: @Composable () -> Unit,
    onConfigSelected: (SshConfig) -> Unit,
    onRemoteHostChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onLocalPortChange: (String) -> Unit,
    onPresetNameChange: (String) -> Unit,
    onPresetNoteChange: (String) -> Unit,
    onPresetSelected: (SshTunnelPreset) -> Unit,
    onNewPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onDeletePreset: () -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: (String) -> Unit,
    onStopAllTunnels: () -> Unit,
    onOpenInternal: (String) -> Unit,
    onOpenExternal: (String) -> Unit
) {
    val activeTunnels = tunnelStates.filter { it.status == TunnelStatus.CONNECTING || it.status == TunnelStatus.RUNNING }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH 隧道") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TunnelServerSelector(configs, selectedConfig, onConfigSelected)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TunnelPresetSelector(
                    presets = tunnelPresets,
                    selectedPresetId = selectedPresetId,
                    onPresetSelected = onPresetSelected,
                    modifier = Modifier.weight(1f)
                )
                ResponsiveTunnelOutlinedButton(
                    onClick = onNewPreset,
                    enabled = selectedConfig != null,
                    text = "\u65B0\u5EFA\u9884\u8BBE",
                    leadingIcon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { onPresetNameChange(it.take(80)) },
                    label = { Text("预设名称") },
                    placeholder = { Text("例：New API") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = presetNote,
                    onValueChange = { onPresetNoteChange(it.take(160)) },
                    label = { Text("备注") },
                    placeholder = { Text("用途、账号、环境") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = remoteHost,
                onValueChange = onRemoteHostChange,
                label = { Text("远端服务地址") },
                placeholder = { Text("127.0.0.1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { onRemotePortChange(it.filter(Char::isDigit).take(5)) },
                    label = { Text("远端端口") },
                    placeholder = { Text("3000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { onLocalPortChange(it.filter(Char::isDigit).take(5)) },
                    label = { Text("手机端口") },
                    placeholder = { Text("自动") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResponsiveTunnelOutlinedButton(
                    onClick = onSavePreset,
                    enabled = selectedConfig != null && remotePort.toIntOrNull()?.let { it in 1..65535 } == true,
                    modifier = Modifier.weight(1f),
                    text = if (selectedPresetId == null) "保存预设" else "更新预设"
                )
                ResponsiveTunnelOutlinedButton(
                    onClick = onDeletePreset,
                    enabled = selectedPresetId != null,
                    modifier = Modifier.weight(1f),
                    text = "删除预设"
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResponsiveTunnelButton(
                    onClick = onStartTunnel,
                    enabled = selectedConfig != null && remotePort.toIntOrNull()?.let { it in 1..65535 } == true,
                    modifier = Modifier.weight(1f),
                    text = "启动隧道"
                )
                ResponsiveTunnelOutlinedButton(
                    onClick = onStopAllTunnels,
                    enabled = activeTunnels.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    text = "停止全部"
                )
            }

            Text(text = statusText, style = MaterialTheme.typography.bodyMedium)

            if (tunnelStates.isNotEmpty()) {
                Text(text = "隧道状态", style = MaterialTheme.typography.titleMedium)
                tunnelStates.forEach { tunnel ->
                    TunnelStateRow(
                        tunnel = tunnel,
                        onStopTunnel = onStopTunnel,
                        onOpenInternal = onOpenInternal,
                        onOpenExternal = onOpenExternal
                    )
                }
            }
        }
    }
}

@Composable
private fun TunnelPresetSelector(
    presets: List<SshTunnelPreset>,
    selectedPresetId: Long?,
    onPresetSelected: (SshTunnelPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.id == selectedPresetId }
    Box(modifier = modifier) {
        ResponsiveTunnelOutlinedButton(
            onClick = { expanded = true },
            enabled = presets.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            text = selected?.presetLabel() ?: if (presets.isEmpty()) "暂无隧道预设" else "选择隧道预设",
            trailingIcon = { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "展开") }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(preset.presetLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            preset.note?.takeIf { it.isNotBlank() }?.let { note ->
                                Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onPresetSelected(preset)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelWebScreen(
    url: String,
    onBackClicked: () -> Unit,
    onOpenExternal: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    FeedbackIconButton(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        onClick = onBackClicked,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    FeedbackIconButton(
                        drawableResId = R.drawable.ic_open_in_browser,
                        contentDescription = "外部打开",
                        onClick = { onOpenExternal(url) },
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            )
        }
    ) { innerPadding ->
        TunnelWebView(
            url = url,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TunnelWebView(url: String, modifier: Modifier = Modifier) {
    var pendingAuth by remember { mutableStateOf<PendingHttpAuth?>(null) }
    var authUsername by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            pendingAuth?.handler?.cancel()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView,
                        handler: HttpAuthHandler,
                        host: String,
                        realm: String
                    ) {
                        pendingAuth?.handler?.cancel()
                        val savedCredentials = view.getHttpAuthUsernamePassword(host, realm)
                        authUsername = savedCredentials?.getOrNull(0).orEmpty()
                        authPassword = savedCredentials?.getOrNull(1).orEmpty()
                        pendingAuth = PendingHttpAuth(
                            webView = view,
                            handler = handler,
                            host = host,
                            realm = realm
                        )
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                val webView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) webView.loadUrl(url)
        }
    )

    pendingAuth?.let { request ->
        AlertDialog(
            onDismissRequest = {
                request.handler.cancel()
                pendingAuth = null
                authPassword = ""
            },
            title = { Text("HTTP 登录验证") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = request.host + request.realm.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { authUsername = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { authPassword = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        request.webView.setHttpAuthUsernamePassword(
                            request.host,
                            request.realm,
                            authUsername,
                            authPassword
                        )
                        request.handler.proceed(authUsername, authPassword)
                        pendingAuth = null
                        authPassword = ""
                    }
                ) {
                    Text("登录")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        request.handler.cancel()
                        pendingAuth = null
                        authPassword = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

private data class PendingHttpAuth(
    val webView: WebView,
    val handler: HttpAuthHandler,
    val host: String,
    val realm: String
)

@Composable
private fun TunnelStateRow(
    tunnel: TunnelServiceState,
    onStopTunnel: (String) -> Unit,
    onOpenInternal: (String) -> Unit,
    onOpenExternal: (String) -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (tunnel.status) {
            TunnelStatus.RUNNING -> MaterialTheme.colorScheme.primary
            TunnelStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
            TunnelStatus.FAILED -> MaterialTheme.colorScheme.error
            TunnelStatus.STOPPED -> MaterialTheme.colorScheme.outline
        },
        label = "tunnelStatusColor"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = tunnel.serverLabel,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${tunnel.localEndpoint} -> ${tunnel.remoteHost}:${tunnel.remotePort}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = tunnel.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            if (tunnel.status == TunnelStatus.RUNNING) {
                Text(
                    text = tunnel.browserUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackTextButton(
                    onClick = { onOpenInternal(tunnel.browserUrl) },
                    enabled = tunnel.status == TunnelStatus.RUNNING
                ) {
                    Text("内置打开")
                }
                FeedbackTextButton(
                    onClick = { onOpenExternal(tunnel.browserUrl) },
                    enabled = tunnel.status == TunnelStatus.RUNNING
                ) {
                    Text("外部打开")
                }
                FeedbackTextButton(
                    onClick = { onStopTunnel(tunnel.tunnelId) },
                    enabled = tunnel.status == TunnelStatus.RUNNING || tunnel.status == TunnelStatus.CONNECTING
                ) {
                    Text("停止")
                }
            }
        }
    }
}

@Composable
private fun TunnelServerSelector(configs: List<SshConfig>, selectedConfig: SshConfig?, onConfigSelected: (SshConfig) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val groups by remember(configs) { derivedStateOf { groupedSshServers(configs) } }
    Box(modifier = Modifier.fillMaxWidth()) {
        ResponsiveTunnelOutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            text = selectedConfig?.transferContextLabel() ?: "选择服务器 / 工作区",
            trailingIcon = { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "展开") }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.displayName, style = MaterialTheme.typography.labelLarge) },
                    enabled = false,
                    onClick = {}
                )
                group.workspaces.forEach { config ->
                    DropdownMenuItem(
                        text = { Text("  ${config.workspaceLabel()}") },
                        onClick = {
                            expanded = false
                            onConfigSelected(config)
                        }
                    )
                }
            }
            if (configs.isEmpty()) {
                DropdownMenuItem(text = { Text("暂无服务器配置") }, onClick = { expanded = false })
            }
        }
    }
}

@Composable
private fun ResponsiveTunnelButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    text: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "tunnelButtonPressScale")
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.scale(scale)
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResponsiveTunnelOutlinedButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    text: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "tunnelOutlinedPressScale")
    val containerColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color.Transparent,
        label = "tunnelOutlinedPressColor"
    )
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = containerColor),
        modifier = modifier.scale(scale)
    ) {
        leadingIcon?.invoke()
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        trailingIcon?.invoke()
    }
}

internal fun parseTunnelPort(text: String, allowAuto: Boolean): Int? {
    if (allowAuto && text.isBlank()) return 0
    return text.toIntOrNull()?.takeIf { it in 1..65535 }
}

internal fun SshTunnelPreset.presetLabel(): String {
    val base = name.trim().ifBlank { tunnelPresetDefaultName(remoteHost, remotePort) }
    val local = if (localPort > 0) localPort.toString() else "自动"
    return "$base · $remoteHost:$remotePort -> $local"
}
