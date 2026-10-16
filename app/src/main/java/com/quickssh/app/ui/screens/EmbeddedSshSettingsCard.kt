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
    onSaveAuthorizedKeys: (String) -> String?
) {
    val context = LocalContext.current
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var keysText by remember(authorizedKeys) { mutableStateOf(authorizedKeys) }
    var keysMessage by remember { mutableStateOf<String?>(null) }

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
                hosts.forEach { host ->
                    val command = "ssh -p ${state.port} ${EmbeddedSshServer.LOGIN_USER}@$host"
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        FeedbackTextButton(onClick = {
                            copy("quickssh_ssh_command", command, language.text("已复制 SSH 登录命令", "Copied SSH command"))
                        }) {
                            Text(language.text("复制", "Copy"))
                        }
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

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.text("允许密码登录", "Allow password login"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = language.text("密码: ", "Password: ") + password.take(3) + "…" + password.takeLast(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
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
