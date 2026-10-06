// QuickSSH - Settings
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickssh.app.BuildConfig
import com.quickssh.app.R
import com.quickssh.app.service.AgentBridgeService
import com.quickssh.app.service.AgentBridgeState
import com.quickssh.app.service.AgentBridgeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    language: AppLanguage,
    autoWrapEnabled: Boolean,
    privacyModeEnabled: Boolean,
    biometricUnlockEnabled: Boolean,
    strictHostKeyVerificationEnabled: Boolean,
    biometricAvailable: Boolean,
    downloadDirectoryLabel: String,
    serverProfileCount: Int,
    backupStatusText: String,
    terminalRunProfileEnabled: Boolean = true,
    agentBridgeEnabled: Boolean = false,
    agentBridgePort: Int = AgentBridgeService.DEFAULT_BRIDGE_PORT,
    termuxSshPort: Int = AgentBridgeService.DEFAULT_TERMUX_SSH_PORT,
    agentBridgeState: AgentBridgeState = AgentBridgeState(),
    bottomBar: @Composable () -> Unit,
    onAutoWrapChange: (Boolean) -> Unit,
    onPrivacyModeChange: (Boolean) -> Unit,
    onBiometricUnlockChange: (Boolean) -> Unit,
    onStrictHostKeyVerificationChange: (Boolean) -> Unit,
    onTerminalRunProfileChange: (Boolean) -> Unit = {},
    onChooseDownloadDirectory: () -> Unit,
    onExportConfigs: (String?) -> Unit,
    onImportConfigs: (String?) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onAgentBridgeEnabledChange: (Boolean) -> Unit = {},
    onAgentBridgePortChange: (Int) -> Unit = {},
    onTermuxSshPortChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var bridgePortText by remember(agentBridgePort) { mutableStateOf(agentBridgePort.toString()) }
    var termuxPortText by remember(termuxSshPort) { mutableStateOf(termuxSshPort.toString()) }

    Scaffold(
        topBar = {
            QuickSshPageHeader(
                title = language.text("设置", "Settings"),
                subtitle = language.text("安全、终端和备份偏好", "Security, terminal and backup preferences")
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = language.text("界面语言", "Interface language"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = language == AppLanguage.ZH,
                    onClick = { onLanguageChange(AppLanguage.ZH) },
                    label = { Text("中文") }
                )
                FilterChip(
                    selected = language == AppLanguage.EN,
                    onClick = { onLanguageChange(AppLanguage.EN) },
                    label = { Text("English") }
                )
            }
            SettingsSwitchRow(
                title = language.text("终端自动换行", "Terminal auto wrap"),
                detail = language.text("关闭后，长终端行可横向滚动。", "Turn off to scroll horizontally for long terminal lines."),
                checked = autoWrapEnabled,
                onCheckedChange = onAutoWrapChange
            )
            SettingsSwitchRow(
                title = language.text("本地终端启动执行 Profile", "Run Profile on Local Terminal Startup"),
                detail = language.text("启动本地终端时自动执行 Profile 脚本 (~/.quickssh_profile 或 ~/.profile) 进行环境变量与别名初始化。", "Automatically run profile script (~/.quickssh_profile or ~/.profile) to initialize environment variables and aliases on local terminal startup."),
                checked = terminalRunProfileEnabled,
                onCheckedChange = onTerminalRunProfileChange
            )
            SettingsSwitchRow(
                title = language.text("隐私模式", "Privacy mode"),
                detail = language.text("阻止敏感页面截图和最近任务预览。", "Block screenshots and app previews on sensitive screens."),
                checked = privacyModeEnabled,
                onCheckedChange = onPrivacyModeChange
            )
            SettingsSwitchRow(
                title = language.text("生物识别解锁", "Biometric unlock"),
                detail = if (biometricAvailable) language.text("连接或编辑凭据前需要生物识别确认。", "Require biometric confirmation before connecting or editing credentials.") else language.text("此设备没有可用的生物识别器。", "No biometric authenticator is available on this device."),
                checked = biometricUnlockEnabled,
                enabled = biometricAvailable,
                onCheckedChange = onBiometricUnlockChange
            )
            SettingsSwitchRow(
                title = language.text("严格主机密钥验证", "Strict host-key verification"),
                detail = language.text("默认自动信任。开启后会拒绝未知或变化的 SSH 主机密钥。", "Default stays automatic trust. Enable this to reject unknown or changed SSH host keys unless they are already trusted."),
                checked = strictHostKeyVerificationEnabled,
                onCheckedChange = onStrictHostKeyVerificationChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isBatteryIgnored = remember(context) { com.quickssh.app.utils.BatteryOptimizationHelper.isIgnored(context) }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.text("后台运行保活 (Termux 机制)", "Background Execution (Termux)"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isBatteryIgnored) {
                            language.text("已豁免电池优化，CPU/Wi-Fi 唤醒保活已生效，锁屏不断连。", "Battery optimization ignored. Persistent background execution active.")
                        } else {
                            language.text("建议开启以允许后台长期连接，防止系统在锁屏或切换应用时杀死进程。", "Recommended to prevent Android from killing connections in background.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isBatteryIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                if (!isBatteryIgnored) {
                    FeedbackButton(onClick = {
                        com.quickssh.app.utils.BatteryOptimizationHelper.requestIgnore(context)
                    }) {
                        Text(language.text("开启保活", "Allow"))
                    }
                }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = language.text("Termux & Agent 互通网关", "Termux & Agent Interop Gateway"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = language.text(
                                    "启动本机回环服务，允许 Termux 与 AI Agent 访问 QuickSSH 终端及配置。",
                                    "Run loopback gateway allowing Termux and AI Agents to interoperate with QuickSSH."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = agentBridgeEnabled,
                            onCheckedChange = onAgentBridgeEnabledChange
                        )
                    }

                    if (agentBridgeState.errorMessage != null || agentBridgeState.status == AgentBridgeStatus.PORT_CONFLICT) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = agentBridgeState.errorMessage
                                    ?: language.text("端口冲突：指定端口已被占用，请更改端口！", "Port conflict: Selected port is already in use!"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (agentBridgeEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = bridgePortText,
                                onValueChange = { input ->
                                    bridgePortText = input.filter { it.isDigit() }
                                    val port = bridgePortText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        onAgentBridgePortChange(port)
                                    }
                                },
                                label = { Text(language.text("网关端口", "Gateway Port")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = bridgePortText.toIntOrNull()?.let { it !in 1024..65535 } ?: true
                            )

                            OutlinedTextField(
                                value = termuxPortText,
                                onValueChange = { input ->
                                    termuxPortText = input.filter { it.isDigit() }
                                    val port = termuxPortText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        onTermuxSshPortChange(port)
                                    }
                                },
                                label = { Text(language.text("Termux SSH端口", "Termux SSH Port")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = termuxPortText.toIntOrNull()?.let { it !in 1024..65535 } ?: true
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (agentBridgeState.status) {
                                    AgentBridgeStatus.RUNNING -> language.text(
                                        "状态: 运行中 (127.0.0.1:${agentBridgeState.port})",
                                        "Status: Running (127.0.0.1:${agentBridgeState.port})"
                                    )
                                    AgentBridgeStatus.STARTING -> language.text("状态: 启动中...", "Status: Starting...")
                                    AgentBridgeStatus.STOPPED -> language.text("状态: 已停止", "Status: Stopped")
                                    AgentBridgeStatus.PORT_CONFLICT, AgentBridgeStatus.PORT_OCCUPIED -> language.text("状态: 端口冲突", "Status: Port Conflict")
                                    AgentBridgeStatus.ERROR -> language.text("状态: 发生异常", "Status: Error")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (agentBridgeState.status == AgentBridgeStatus.RUNNING) {
                                    MaterialTheme.colorScheme.primary
                                } else if (agentBridgeState.status == AgentBridgeStatus.PORT_CONFLICT || agentBridgeState.status == AgentBridgeStatus.PORT_OCCUPIED || agentBridgeState.status == AgentBridgeStatus.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )

                            FeedbackButton(
                                onClick = {
                                    val cmd = "curl -s http://127.0.0.1:${agentBridgePort}/install.sh | bash"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("quickssh_install", cmd))
                                    Toast.makeText(
                                        context,
                                        language.text("已复制 Termux 一键配置命令到剪贴板", "Copied Termux setup command to clipboard"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Text(language.text("复制 Termux 配置命令", "Copy Termux Setup Cmd"))
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.text("下载目录", "Download directory"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = downloadDirectoryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                FeedbackButton(onClick = onChooseDownloadDirectory) {
                    Text(language.text("选择", "Choose"))
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = language.text("服务器备份", "Server backup"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeedbackOutlinedButton(
                        onClick = {
                            exportPassword = ""
                            showExportDialog = true
                        },
                        enabled = serverProfileCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_upload),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(language.text("导出", "Export"), modifier = Modifier.padding(start = 8.dp))
                    }
                    FeedbackButton(
                        onClick = {
                            importPassword = ""
                            showImportDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(language.text("导入", "Import"), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (backupStatusText.isNotBlank()) {
                    Text(
                        text = backupStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = language.text("关于", "About"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "QuickSSH ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Author: Chengliang Liu (Bright-Chengliang)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "GitHub: https://github.com/Bright-Chengliang",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Licensed under MIT License.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    if (showExportDialog) {
        BackupPasswordDialog(
            title = language.text("导出服务器备份", "Export server backup"),
            detail = language.text("可选：输入密码加密备份；留空则直接导出。", "Optional: enter a password to encrypt this backup, or leave it blank to export directly."),
            password = exportPassword,
            confirmText = language.text("导出", "Export"),
            onPasswordChange = { exportPassword = it },
            onConfirm = {
                showExportDialog = false
                onExportConfigs(exportPassword.takeIf { it.isNotEmpty() })
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showImportDialog) {
        BackupPasswordDialog(
            title = language.text("导入服务器备份", "Import server backup"),
            detail = language.text("如果备份受保护，请输入密码；旧版或未加密备份可留空。", "Enter the backup password if the file is protected. Leave blank for older or unprotected backups."),
            password = importPassword,
            confirmText = language.text("导入", "Import"),
            onPasswordChange = { importPassword = it },
            onConfirm = {
                showImportDialog = false
                onImportConfigs(importPassword.takeIf { it.isNotEmpty() })
            },
            onDismiss = { showImportDialog = false }
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    detail: String,
    password: String,
    confirmText: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val language = LocalQuickSshLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(language.text("备份密码", "Backup password")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            FeedbackTextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            FeedbackTextButton(onClick = onDismiss) {
                Text(language.text("取消", "Cancel"))
            }
        }
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
