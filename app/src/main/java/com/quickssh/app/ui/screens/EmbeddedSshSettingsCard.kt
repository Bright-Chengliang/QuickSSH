// QuickSSH - Built-in SSH server settings
// Copyright (c) 2026 Chengliang Liu
// Author: https://github.com/Bright-Chengliang
// License: MIT License (see LICENSE)

package com.quickssh.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quickssh.app.service.EmbeddedSshServer
import com.quickssh.app.service.EmbeddedSshState
import com.quickssh.app.service.EmbeddedSshStatus

/**
 * Settings card for [EmbeddedSshServer]: on/off, port, connect hint, password and authorized_keys.
 * [onSaveAuthorizedKeys] returns an error message, or null when the keys were saved.
 */
@Composable
fun EmbeddedSshSettingsCard(
    language: AppLanguage,
    enabled: Boolean,
    port: Int,
    state: EmbeddedSshState,
    password: String,
    passwordAuthEnabled: Boolean,
    authorizedKeys: String,
    lanAddresses: List<String>,
    onEnabledChange: (Boolean) -> Unit,
    onPortChange: (Int) -> Unit,
    onPasswordAuthChange: (Boolean) -> Unit,
    onRegeneratePassword: () -> Unit,
    onSaveAuthorizedKeys: (String) -> String?,
    /** Opens the tunnel page prefilled with a reverse tunnel to this server, for access from other networks. */
    onSetUpReverseTunnel: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var keysText by remember(authorizedKeys) { mutableStateOf(authorizedKeys) }
    var keysMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    fun copy(label: String, text: String, toast: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.text("内置 SSH 服务端", "Built-in SSH Server"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = language.text(
                            "让电脑或其他设备直接 SSH 登录 QuickSSH 的内置终端环境（含 quickssh 命令与 Agent skill），无需 Termux。",
                            "Let computers and other devices SSH into QuickSSH's built-in terminal environment (with the quickssh CLI and agent skill), no Termux needed."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            state.errorMessage?.let { message ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (!enabled) return@Column

            OutlinedTextField(
                value = portText,
                onValueChange = { input ->
                    portText = input.filter { it.isDigit() }
                    portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onPortChange)
                },
                label = { Text(language.text("SSH 端口", "SSH Port")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true
            )

            val running = state.status == EmbeddedSshStatus.RUNNING
            Text(
                text = when (state.status) {
                    EmbeddedSshStatus.RUNNING -> language.text("状态: 运行中 (0.0.0.0:${state.port})", "Status: Running (0.0.0.0:${state.port})")
                    EmbeddedSshStatus.STARTING -> language.text("状态: 启动中...", "Status: Starting...")
                    EmbeddedSshStatus.STOPPED -> language.text("状态: 已停止", "Status: Stopped")
                    EmbeddedSshStatus.PORT_OCCUPIED -> language.text("状态: 端口冲突", "Status: Port Conflict")
                    EmbeddedSshStatus.ERROR -> language.text("状态: 发生异常", "Status: Error")
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    running -> MaterialTheme.colorScheme.primary
                    state.status == EmbeddedSshStatus.ERROR || state.status == EmbeddedSshStatus.PORT_OCCUPIED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
            )

            if (running) {
                val hosts = lanAddresses.ifEmpty { listOf("<phone-ip>") }
                val firstHost = hosts.first()
                GuideStep(
                    number = 1,
                    title = language.text("在电脑终端运行（电脑和手机需在同一 Wi-Fi）", "On your computer, run (same Wi-Fi as the phone)")
                )
                hosts.forEach { host ->
                    val command = "ssh -p ${state.port} ${EmbeddedSshServer.LOGIN_USER}@$host"
                    CommandRow(command = command, copyLabel = language.text("复制", "Copy")) {
                        copy("quickssh_ssh_command", command, language.text("已复制 SSH 登录命令", "Copied SSH command"))
                    }
                }
                GuideStep(
                    number = 2,
                    title = if (passwordAuthEnabled) {
                        language.text(
                            "首次连接问 yes/no 时输入 yes；问 password 时粘贴下方密码（输入时不显示字符，属正常）",
                            "Answer yes to the host-key question, then paste the password below at the password prompt (it stays invisible while typing)"
                        )
                    } else {
                        language.text("密码登录已关闭，只能用第 3 步加入的公钥登录", "Password login is off; only keys added in step 3 can log in")
                    }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.text("允许用密码登录这台手机", "Allow password login to this phone"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = language.text("密码: ", "Password: ") +
                            if (passwordVisible) password else password.take(3) + "…" + password.takeLast(3),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (passwordVisible) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                FeedbackTextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) language.text("隐藏", "Hide") else language.text("显示", "Show"))
                }
                FeedbackTextButton(onClick = {
                    copy("quickssh_ssh_password", password, language.text("已复制 SSH 登录密码", "Copied SSH password"))
                }) {
                    Text(language.text("复制", "Copy"))
                }
                FeedbackTextButton(onClick = onRegeneratePassword) {
                    Text(language.text("重置", "Reset"))
                }
                Switch(checked = passwordAuthEnabled, onCheckedChange = onPasswordAuthChange)
            }
            Text(
                text = language.text(
                    "这个密码只用于电脑等设备登录本手机，与你在 QuickSSH 里连接服务器用的密码无关。",
                    "This password only lets other devices log in to this phone; it has nothing to do with the passwords QuickSSH uses to reach your servers."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            if (running) {
                val host = lanAddresses.firstOrNull() ?: "<phone-ip>"
                // `cat` is Get-Content in PowerShell, so the same line works there and in bash/zsh.
                val addKeyCommand = "cat ~/.ssh/id_ed25519.pub | ssh -p ${state.port} ${EmbeddedSshServer.LOGIN_USER}@$host " +
                    "\"mkdir -p ~/.ssh && cat >> ~/${EmbeddedSshServer.AUTHORIZED_KEYS_HOME_PATH}\""
                GuideStep(
                    number = 3,
                    title = language.text(
                        "免密码（推荐）：在电脑运行下面一行，输入一次密码后，以后都免密登录",
                        "Passwordless (recommended): run this once on the computer, enter the password once, never again"
                    )
                )
                CommandRow(command = addKeyCommand, copyLabel = language.text("复制", "Copy")) {
                    copy("quickssh_ssh_add_key", addKeyCommand, language.text("已复制添加公钥命令", "Copied add-key command"))
                }
                Text(
                    text = language.text(
                        "电脑上没有 ~/.ssh/id_ed25519.pub 时，先运行 ssh-keygen -t ed25519 一路回车生成。也可以直接把公钥粘贴到下面的框里保存。",
                        "No ~/.ssh/id_ed25519.pub yet? Run ssh-keygen -t ed25519 and press Enter throughout. You can also paste a key into the box below."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                GuideStep(
                    number = 4,
                    title = language.text(
                        "不在同一网络？在 隧道 页加一条「反向转发」常驻隧道，把本端口挂到你的服务器上",
                        "Different network? Add a resident reverse tunnel on the Tunnels page to expose this port on your server"
                    )
                )
                onSetUpReverseTunnel?.let { action ->
                    FeedbackTextButton(onClick = action) {
                        Text(language.text("去设置反向隧道", "Set up a reverse tunnel"))
                    }
                }
                state.hostKeyFingerprint?.let { fingerprint ->
                    Text(
                        text = language.text("主机指纹: ", "Host key: ") + fingerprint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            OutlinedTextField(
                value = keysText,
                onValueChange = {
                    keysText = it
                    keysMessage = null
                },
                label = { Text(language.text("公钥 (authorized_keys)", "Public keys (authorized_keys)")) },
                placeholder = { Text("ssh-ed25519 AAAA... user@pc") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                isError = keysMessage != null && keysMessage!!.startsWith("!")
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = keysMessage?.removePrefix("!") ?: language.text(
                        "推荐用公钥登录：粘贴电脑上 ~/.ssh/id_ed25519.pub 的内容，每行一个。",
                        "Key login recommended: paste ~/.ssh/id_ed25519.pub from your computer, one key per line."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (keysMessage?.startsWith("!") == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                FeedbackTextButton(onClick = {
                    keysMessage = onSaveAuthorizedKeys(keysText)?.let { "!$it" }
                        ?: language.text("已保存", "Saved")
                }) {
                    Text(language.text("保存", "Save"))
                }
            }
        }
    }
}

@Composable
private fun GuideStep(number: Int, title: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
        Text(text = title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CommandRow(command: String, copyLabel: String, onCopy: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        FeedbackTextButton(onClick = onCopy) {
            Text(copyLabel)
        }
    }
}
