package com.quickssh.app.ui.screens

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quickssh.app.R
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.TUNNEL_DIRECTION_LOCAL
import com.quickssh.app.data.TUNNEL_DIRECTION_REMOTE
import com.quickssh.app.data.TunnelHostGroup
import com.quickssh.app.data.isRemoteForward
import com.quickssh.app.data.serverNodeLabel
import com.quickssh.app.data.tunnelHostGroups
import com.quickssh.app.data.tunnelPresetDefaultName
import com.quickssh.app.service.EmbeddedSshServer
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
    direction: String,
    autoStart: Boolean,
    /** Built-in SSH server port when it is enabled; offered as the reverse tunnel's phone-side target. */
    embeddedSshPort: Int?,
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
    onDirectionChange: (String) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
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
    val language = LocalQuickSshLanguage.current
    val activeTunnels = tunnelStates.filter { it.status == TunnelStatus.CONNECTING || it.status == TunnelStatus.RUNNING }
    val remoteForward = direction == TUNNEL_DIRECTION_REMOTE
    val portsValid = remotePort.toIntOrNull()?.let { it in 1..65535 } == true &&
        (!remoteForward || localPort.toIntOrNull()?.let { it in 1..65535 } == true)

    Scaffold(
        topBar = {
            QuickSshPageHeader(
                title = language.text("SSH 隧道", "SSH tunnels"),
                subtitle = language.text("本地 / 反向端口转发与内网服务", "Local / reverse port forwarding and private services")
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
                    language = language,
                    modifier = Modifier.weight(1f)
                )
                ResponsiveTunnelOutlinedButton(
                    onClick = onNewPreset,
                    enabled = selectedConfig != null,
                    text = language.text("新建预设", "New preset"),
                    leadingIcon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { onPresetNameChange(it.take(80)) },
                    label = { Text(language.text("预设名称", "Preset name")) },
                    placeholder = { Text("例：New API") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = presetNote,
                    onValueChange = { onPresetNoteChange(it.take(160)) },
                    label = { Text(language.text("备注", "Note")) },
                    placeholder = { Text(language.text("用途、账号、环境", "Purpose, account, environment")) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            TunnelDirectionSelector(direction = direction, onDirectionChange = onDirectionChange, language = language)

            OutlinedTextField(
                value = remoteHost,
                onValueChange = onRemoteHostChange,
                label = {
                    Text(
                        if (remoteForward) language.text("服务器监听地址", "Server bind address")
                        else language.text("远端服务地址", "Remote service address")
                    )
                },
                placeholder = { Text("127.0.0.1") },
                supportingText = if (remoteForward) {
                    { Text(language.text("127.0.0.1 = 只有服务器本机可连（最安全）", "127.0.0.1 = reachable only on the server itself (safest)")) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { onRemotePortChange(it.filter(Char::isDigit).take(5)) },
                    label = { Text(if (remoteForward) language.text("服务器端口", "Server port") else language.text("远端端口", "Remote port")) },
                    placeholder = { Text(if (remoteForward) "2226" else "3000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { onLocalPortChange(it.filter(Char::isDigit).take(5)) },
                    label = { Text(if (remoteForward) language.text("手机目标端口", "Phone target port") else language.text("手机端口", "Local port")) },
                    placeholder = { Text(if (remoteForward) (embeddedSshPort?.toString() ?: "8025") else language.text("自动", "Auto")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            if (remoteForward) {
                ReverseTunnelHint(
                    serverLabel = selectedConfig?.let { "${it.username}@${it.host}" },
                    serverPort = remotePort.toIntOrNull(),
                    embeddedSshPort = embeddedSshPort,
                    targetPort = localPort.toIntOrNull(),
                    onUseEmbeddedSshPort = { port -> onLocalPortChange(port.toString()) },
                    language = language
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(language.text("常驻", "Resident"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        language.text(
                            "保存后立即启动；打开应用、开机后自动连接，断线无限重连",
                            "Starts on save, with the app and at boot; reconnects without limit"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
            }
            if (autoStart) {
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        language.text(
                            "小米/红米等系统还需允许 QuickSSH「自启动」，否则开机后无法自动恢复。",
                            "On Xiaomi/Redmi and similar ROMs also allow QuickSSH to auto-start, or it cannot come back after a reboot."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    FeedbackTextButton(onClick = { openAutoStartSettings(context) }) {
                        Text(language.text("去开启", "Open"))
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResponsiveTunnelOutlinedButton(
                    onClick = onSavePreset,
                    enabled = selectedConfig != null && portsValid,
                    modifier = Modifier.weight(1f),
                    text = if (selectedPresetId == null) language.text("保存预设", "Save preset") else language.text("更新预设", "Update preset")
                )
                ResponsiveTunnelOutlinedButton(
                    onClick = onDeletePreset,
                    enabled = selectedPresetId != null,
                    modifier = Modifier.weight(1f),
                    text = language.text("删除预设", "Delete preset")
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResponsiveTunnelButton(
                    onClick = onStartTunnel,
                    enabled = selectedConfig != null && portsValid,
                    modifier = Modifier.weight(1f),
                    text = language.text("启动隧道", "Start tunnel")
                )
                ResponsiveTunnelOutlinedButton(
                    onClick = onStopAllTunnels,
                    enabled = activeTunnels.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    text = language.text("停止全部", "Stop all")
                )
            }

            Text(text = tunnelStatusLabel(statusText, language), style = MaterialTheme.typography.bodyMedium)

            if (tunnelStates.isNotEmpty()) {
                Text(text = language.text("隧道状态", "Tunnel status"), style = MaterialTheme.typography.titleMedium)
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

/** Local (`ssh -L`) vs reverse (`ssh -R`) forwarding, with a one-line explanation of the selected mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelDirectionSelector(direction: String, onDirectionChange: (String) -> Unit, language: AppLanguage) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = direction != TUNNEL_DIRECTION_REMOTE,
                onClick = { onDirectionChange(TUNNEL_DIRECTION_LOCAL) },
                label = { Text(language.text("本地转发 -L", "Local -L")) }
            )
            FilterChip(
                selected = direction == TUNNEL_DIRECTION_REMOTE,
                onClick = { onDirectionChange(TUNNEL_DIRECTION_REMOTE) },
                label = { Text(language.text("反向转发 -R", "Reverse -R")) }
            )
        }
        Text(
            text = if (direction == TUNNEL_DIRECTION_REMOTE) {
                language.text(
                    "让服务器的端口通到手机：别人连服务器端口，就连到了手机上的服务（如内置 SSH）。",
                    "Expose a phone port on the server: connecting to the server port reaches the phone service (e.g. built-in SSH)."
                )
            } else {
                language.text(
                    "在手机上打开服务器那边的服务：连手机端口，就连到了服务器能访问的地址。",
                    "Reach a service on the server side from the phone: the phone port leads to the remote address."
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** Explains what a reverse tunnel exposes and offers the built-in SSH server as its target. */
@Composable
private fun ReverseTunnelHint(
    serverLabel: String?,
    serverPort: Int?,
    embeddedSshPort: Int?,
    targetPort: Int?,
    onUseEmbeddedSshPort: (Int) -> Unit,
    language: AppLanguage
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                embeddedSshPort == null -> Text(
                    language.text(
                        "提示：在 设置 → 内置 SSH 服务端 打开后，把手机目标端口设为它的端口，就能从任何地方经服务器登录这台手机（不需要 Termux）。",
                        "Tip: enable Settings → Built-in SSH server and use its port as the target to log in to this phone through the server from anywhere (no Termux needed)."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                targetPort != embeddedSshPort -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        language.text("常用：把内置 SSH 服务端暴露到服务器", "Common: expose the built-in SSH server"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    FeedbackTextButton(onClick = { onUseEmbeddedSshPort(embeddedSshPort) }) {
                        Text(language.text("填入 $embeddedSshPort", "Use $embeddedSshPort"))
                    }
                }
                else -> {
                    val port = serverPort ?: 2226
                    val jump = serverLabel ?: language.text("服务器", "server")
                    Text(
                        language.text(
                            "启动后，在电脑上这样登录手机（密码或公钥见 设置 → 内置 SSH 服务端）：",
                            "Once running, log in to the phone from a computer (password or key: Settings → Built-in SSH server):"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "ssh -J $jump -p $port ${EmbeddedSshServer.LOGIN_USER}@127.0.0.1",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
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
    language: AppLanguage,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.id == selectedPresetId }
    Box(modifier = modifier) {
        ResponsiveTunnelOutlinedButton(
            onClick = { expanded = true },
            enabled = presets.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            text = selected?.presetLabel(language) ?: if (presets.isEmpty()) language.text("暂无隧道预设", "No tunnel presets") else language.text("选择隧道预设", "Select tunnel preset"),
            trailingIcon = { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = language.text("展开", "Expand")) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(preset.presetLabel(language), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            QuickSshPageHeader(
                title = url,
                subtitle = "Tunnel web view",
                navigationIcon = {
                    FeedbackIconButton(
                        imageVector = Icons.Default.Close,
                    contentDescription = LocalQuickSshLanguage.current.text("关闭", "Close"),
                        onClick = onBackClicked,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                trailing = {
                    FeedbackIconButton(
                        drawableResId = R.drawable.ic_open_in_browser,
                        contentDescription = LocalQuickSshLanguage.current.text("外部打开", "Open externally"),
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
            title = { Text(LocalQuickSshLanguage.current.text("HTTP 登录验证", "HTTP authentication")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = request.host + request.realm.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { authUsername = it },
                        label = { Text(LocalQuickSshLanguage.current.text("用户名", "Username")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { authPassword = it },
                        label = { Text(LocalQuickSshLanguage.current.text("密码", "Password")) },
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
                    Text(LocalQuickSshLanguage.current.text("登录", "Sign in"))
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
                    Text(LocalQuickSshLanguage.current.text("取消", "Cancel"))
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
    val language = LocalQuickSshLanguage.current
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
                text = listOfNotNull(
                    tunnel.serverLabel,
                    language.text("反向", "reverse").takeIf { tunnel.remoteForward },
                    language.text("常驻", "resident").takeIf { tunnel.persistent }
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (tunnel.remoteForward) {
                    language.text(
                        "服务器 ${tunnel.remoteHost}:${tunnel.remotePort} → 手机 ${tunnel.localEndpoint}",
                        "server ${tunnel.remoteHost}:${tunnel.remotePort} → phone ${tunnel.localEndpoint}"
                    )
                } else {
                    tunnel.routeText
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = tunnel.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            if (tunnel.status == TunnelStatus.RUNNING && !tunnel.remoteForward) {
                Text(
                    text = tunnel.browserUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!tunnel.remoteForward) {
                    FeedbackTextButton(
                        onClick = { onOpenInternal(tunnel.browserUrl) },
                        enabled = tunnel.status == TunnelStatus.RUNNING
                    ) {
                        Text(language.text("内置打开", "Open in app"))
                    }
                    FeedbackTextButton(
                        onClick = { onOpenExternal(tunnel.browserUrl) },
                        enabled = tunnel.status == TunnelStatus.RUNNING
                    ) {
                        Text(language.text("外部打开", "Open externally"))
                    }
                }
                FeedbackTextButton(
                    onClick = { onStopTunnel(tunnel.tunnelId) },
                    enabled = tunnel.status == TunnelStatus.RUNNING || tunnel.status == TunnelStatus.CONNECTING
                ) {
                    Text(language.text("停止", "Stop"))
                }
            }
        }
    }
}

/** Picks a remote host; workspaces sharing its IP are one entry, and the host's first workspace is selected. */
@Composable
private fun TunnelServerSelector(configs: List<SshConfig>, selectedConfig: SshConfig?, onConfigSelected: (SshConfig) -> Unit) {
    val language = LocalQuickSshLanguage.current
    var expanded by remember { mutableStateOf(false) }
    val groups by remember(configs) { derivedStateOf { tunnelHostGroups(configs) } }
    val selectedGroup = groups.firstOrNull { group -> group.workspaces.any { it.id == selectedConfig?.id } }
    Box(modifier = Modifier.fillMaxWidth()) {
        ResponsiveTunnelOutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            text = selectedGroup?.let { tunnelHostLabel(it, language) } ?: language.text("选择主机", "Select host"),
            trailingIcon = { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = language.text("展开", "Expand")) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(group.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                tunnelHostDetail(group, language),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onConfigSelected(group.primary)
                    }
                )
            }
            if (groups.isEmpty()) {
                DropdownMenuItem(text = { Text(language.text("暂无远程主机", "No remote hosts")) }, onClick = { expanded = false })
            }
        }
    }
}

internal fun tunnelHostLabel(group: TunnelHostGroup, language: AppLanguage): String =
    "${group.label} · ${tunnelHostDetail(group, language)}"

/** Server names on that host and how many workspaces share it. */
internal fun tunnelHostDetail(group: TunnelHostGroup, language: AppLanguage): String {
    val servers = group.workspaces.map { it.serverNodeLabel() }.distinct().joinToString(", ")
    val count = group.workspaces.size
    return servers + language.text(" · $count 个工作区", " · $count workspace${if (count == 1) "" else "s"}")
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

/** MIUI/HyperOS keeps a separate auto-start switch; fall back to the app's system settings page elsewhere. */
private fun openAutoStartSettings(context: Context) {
    val miuiAutoStart = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    )
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    listOf(miuiAutoStart, appDetails).firstOrNull { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

internal fun parseTunnelPort(text: String, allowAuto: Boolean): Int? {
    if (allowAuto && text.isBlank()) return 0
    return text.toIntOrNull()?.takeIf { it in 1..65535 }
}

internal fun SshTunnelPreset.presetLabel(language: AppLanguage = AppLanguage.ZH): String {
    val base = name.trim().ifBlank { tunnelPresetDefaultName(remoteHost, remotePort) }
    val resident = if (autoStart) language.text(" · 常驻", " · resident") else ""
    if (isRemoteForward) {
        return "$base · " + language.text("反向", "reverse") + " $remoteHost:$remotePort <- $localPort$resident"
    }
    val local = if (localPort > 0) localPort.toString() else language.text("自动", "auto")
    return "$base · $remoteHost:$remotePort -> $local$resident"
}

internal fun tunnelStatusLabel(statusText: String, language: AppLanguage): String {
    if (language == AppLanguage.ZH) return statusText
    return when {
        statusText == "等待启动隧道" -> "Waiting to start tunnel"
        statusText == "已删除隧道预设" -> "Tunnel preset deleted"
        statusText.startsWith("已保存隧道预设：") -> statusText.replaceFirst("已保存隧道预设：", "Saved tunnel preset: ")
        statusText.startsWith("已保存并启动常驻隧道：") -> statusText.replaceFirst("已保存并启动常驻隧道：", "Saved and started resident tunnel: ")
        statusText.startsWith("失败：反向隧道需要填写手机目标端口") -> statusText.replaceFirst("失败：反向隧道需要填写手机目标端口", "Failed: a reverse tunnel needs a phone target port")
        statusText.startsWith("失败：请先选择服务器") -> statusText.replaceFirst("失败：请先选择服务器", "Failed: select a server first")
        statusText.startsWith("失败：远端端口必须是") -> statusText.replaceFirst("失败：远端端口必须是", "Failed: remote port must be")
        statusText.startsWith("失败：手机端口必须是") -> statusText.replaceFirst("失败：手机端口必须是", "Failed: local port must be")
        statusText.startsWith("正在启动隧道：") -> statusText.replaceFirst("正在启动隧道：", "Starting tunnel: ")
        statusText.startsWith("隧道已启动：") -> statusText.replaceFirst("隧道已启动：", "Tunnel started: ")
        statusText.startsWith("反向隧道已启动：") -> statusText.replaceFirst("反向隧道已启动：", "Reverse tunnel started: ")
            .replace("服务器 ", "server ").replace("手机 ", "phone ")
        statusText.startsWith("正在连接：") -> statusText.replaceFirst("正在连接：", "Connecting: ")
        statusText.startsWith("正在重连：") -> statusText.replaceFirst("正在重连：", "Reconnecting: ")
        statusText.startsWith("无法打开浏览器：") -> statusText.replaceFirst("无法打开浏览器：", "Unable to open browser: ")
        else -> statusText
    }
}
