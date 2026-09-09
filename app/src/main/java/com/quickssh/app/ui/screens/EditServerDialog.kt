package com.quickssh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quickssh.app.data.SshServerGroup
import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import com.quickssh.app.service.AUTH_TYPE_PRIVATE_KEY

@Composable
fun EditServerDialog(
    serverGroup: SshServerGroup,
    initialDecryptedPassword: String?,
    isTestingConnection: Boolean,
    connectionTestStatus: String,
    onDismiss: () -> Unit,
    onTestConnection: (host: String, port: Int, username: String, authType: String, password: String, privateKey: String) -> Unit,
    onSave: (serverNodeId: Long, displayName: String, host: String, port: Int, username: String, authType: String, password: String, privateKey: String) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    val repConfig = serverGroup.workspaces.firstOrNull()
    val serverNodeId = serverGroup.serverNodeId.takeIf { it > 0L } ?: repConfig?.serverNodeId ?: 0L

    var displayName by remember(serverGroup.key) {
        val initialName = if (serverGroup.displayName != serverGroup.hostLabel) {
            serverGroup.displayName
        } else {
            repConfig?.serverDisplayName.orEmpty()
        }
        mutableStateOf(initialName)
    }
    var host by remember(serverGroup.key) { mutableStateOf(repConfig?.host ?: "") }
    var port by remember(serverGroup.key) { mutableStateOf(repConfig?.port?.toString() ?: "22") }
    var username by remember(serverGroup.key) { mutableStateOf(repConfig?.username ?: "root") }
    var authType by remember(serverGroup.key) { mutableStateOf(repConfig?.authType ?: AUTH_TYPE_PASSWORD) }
    var password by remember(serverGroup.key, initialDecryptedPassword) {
        mutableStateOf(initialDecryptedPassword ?: "")
    }
    var privateKey by remember(serverGroup.key) { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }

    val parsedPort = port.toIntOrNull()
    val hasSavedPassword = !repConfig?.encryptedPassword.isNullOrBlank()
    val hasSavedPrivateKey = !repConfig?.encryptedPrivateKey.isNullOrBlank()
    val passwordMissing = sshPasswordCredentialMissing(authType, password, hasSavedPassword)
    val privateKeyMissing = sshPrivateKeyCredentialMissing(authType, privateKey, hasSavedPrivateKey)

    val hostError = showErrors && host.isBlank()
    val portError = showErrors && (parsedPort == null || parsedPort !in 1..65535)
    val usernameError = showErrors && username.isBlank()
    val passwordError = showErrors && passwordMissing
    val privateKeyError = showErrors && privateKeyMissing
    val canSave = host.isNotBlank() && username.isNotBlank() && parsedPort in 1..65535 &&
        !passwordMissing && !privateKeyMissing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = language.text("编辑服务器配置", "Edit Server Configuration"),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = language.text(
                        "修改将同步应用到此卡片下的所有工作区（共 ${serverGroup.workspaces.size} 个）",
                        "Changes will sync to all ${serverGroup.workspaces.size} workspace(s) under this server"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(language.text("服务器备注 / 名称", "Server remark / name")) },
                    placeholder = { Text(language.text("例如：生产环境、测试节点", "e.g. Production, Test Node")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim() },
                        label = { Text(language.text("主机 / IP", "Host / IP")) },
                        isError = hostError,
                        supportingText = { if (hostError) Text(language.text("请输入主机地址", "Host is required")) },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(language.text("端口", "Port")) },
                        isError = portError,
                        supportingText = { if (portError) Text("1-65535") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text(language.text("用户名", "Username")) },
                    isError = usernameError,
                    supportingText = { if (usernameError) Text(language.text("请输入用户名", "Username is required")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeedbackButton(
                        onClick = { authType = AUTH_TYPE_PASSWORD },
                        modifier = Modifier.weight(1f),
                        enabled = authType != AUTH_TYPE_PASSWORD,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(language.text("密码认证", "Password"))
                    }
                    FeedbackOutlinedButton(
                        onClick = { authType = AUTH_TYPE_PRIVATE_KEY },
                        modifier = Modifier.weight(1f),
                        enabled = authType != AUTH_TYPE_PRIVATE_KEY
                    ) {
                        Text(language.text("私钥认证", "Private key"))
                    }
                }

                if (authType == AUTH_TYPE_PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(
                                if (hasSavedPassword) {
                                    language.text("密码（留空保持已保存密码）", "Password (blank keeps saved)")
                                } else {
                                    language.text("密码", "Password")
                                }
                            )
                        },
                        isError = passwordError,
                        supportingText = { if (passwordError) Text(language.text("请输入密码", "Password is required")) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = {
                            Text(
                                if (hasSavedPrivateKey) {
                                    language.text("私钥（留空保持已保存私钥）", "Private key (blank keeps saved)")
                                } else {
                                    language.text("私钥", "Private key")
                                }
                            )
                        },
                        placeholder = { Text("Paste OpenSSH private key") },
                        isError = privateKeyError,
                        supportingText = { if (privateKeyError) Text(language.text("请输入私钥", "Private key is required")) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                FeedbackOutlinedButton(
                    onClick = {
                        showErrors = true
                        if (canSave && parsedPort != null) {
                            onTestConnection(host.trim(), parsedPort, username.trim(), authType, password, privateKey)
                        }
                    },
                    enabled = canSave && !isTestingConnection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isTestingConnection) {
                            language.text("正在测试连接...", "Testing connection...")
                        } else {
                            language.text("测试连接", "Test Connection")
                        }
                    )
                }

                if (connectionTestStatus.isNotBlank()) {
                    Text(
                        text = connectionTestStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connectionTestStatus.contains("passed", ignoreCase = true) || connectionTestStatus.contains("成功")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        },
        confirmButton = {
            FeedbackButton(
                onClick = {
                    showErrors = true
                    if (canSave && parsedPort != null) {
                        onSave(
                            serverNodeId,
                            displayName.trim(),
                            host.trim(),
                            parsedPort,
                            username.trim(),
                            authType,
                            password,
                            privateKey
                        )
                    }
                }
            ) {
                Text(language.text("保存并同步所有工作区", "Save & Sync All"))
            }
        },
        dismissButton = {
            FeedbackOutlinedButton(onClick = onDismiss) {
                Text(language.text("取消", "Cancel"))
            }
        }
    )
}
