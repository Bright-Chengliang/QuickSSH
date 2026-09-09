package com.quickssh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.quickssh.app.data.PERSISTENT_SESSION_AUTO
import com.quickssh.app.data.PERSISTENT_SESSION_NONE
import com.quickssh.app.data.PERSISTENT_SESSION_SCREEN
import com.quickssh.app.data.PERSISTENT_SESSION_TMUX
import com.quickssh.app.data.SshConfig
import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import com.quickssh.app.service.AUTH_TYPE_PRIVATE_KEY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshAddScreen(
    configToEdit: SshConfig? = null,
    isCopyMode: Boolean = false,
    decryptedPassword: String? = null,
    connectionTestStatus: String = "",
    isTestingConnection: Boolean = false,
    onBackClicked: () -> Unit,
    onTestConnectionClicked: (name: String, host: String, port: Int, user: String, authType: String, password: String, privateKey: String, workDirectory: String, postConnectCommand: String, terminalFontSizeSp: Int, terminalWrapEnabled: Boolean?, terminalTerm: String, terminalShortcuts: String, persistentSessionMode: String) -> Unit,
    onSaveClicked: (name: String, host: String, port: Int, user: String, authType: String, password: String, privateKey: String, workDirectory: String, postConnectCommand: String, terminalFontSizeSp: Int, terminalWrapEnabled: Boolean?, terminalTerm: String, terminalShortcuts: String, persistentSessionMode: String, serverDisplayName: String) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    val isEditing = configToEdit != null && !isCopyMode
    val isWorkspaceCopy = configToEdit != null && isCopyMode && configToEdit.id == 0L
    val lockedServerFields = isWorkspaceCopy
    val lockedFieldColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = MaterialTheme.colorScheme.onSurface,
        disabledLabelColor = MaterialTheme.colorScheme.outline,
        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    )

    var serverDisplayName by remember(configToEdit?.id, configToEdit?.serverDisplayName, isCopyMode) {
        mutableStateOf(configToEdit?.serverDisplayName ?: "")
    }
    var name by remember(configToEdit?.id, configToEdit?.name, isCopyMode) {
        mutableStateOf(configToEdit?.name ?: "")
    }
    var host by remember(configToEdit?.id, configToEdit?.host, isCopyMode) {
        mutableStateOf(configToEdit?.host ?: "")
    }
    var port by remember(configToEdit?.id, configToEdit?.port, isCopyMode) {
        mutableStateOf(configToEdit?.port?.toString() ?: "22")
    }
    var username by remember(configToEdit?.id, configToEdit?.username, isCopyMode) {
        mutableStateOf(configToEdit?.username ?: "root")
    }
    var authType by remember(configToEdit?.id, configToEdit?.authType, isCopyMode) {
        mutableStateOf(configToEdit?.authType ?: AUTH_TYPE_PASSWORD)
    }
    var password by remember(configToEdit?.id, decryptedPassword, isCopyMode) {
        mutableStateOf(decryptedPassword ?: "")
    }
    var privateKey by remember(configToEdit?.id, isCopyMode) {
        mutableStateOf("")
    }
    var workDirectory by remember(configToEdit?.id, configToEdit?.workDirectory, isCopyMode) {
        mutableStateOf(configToEdit?.workDirectory ?: "")
    }
    var postConnectCommand by remember(configToEdit?.id, configToEdit?.postConnectCommand, isCopyMode) {
        mutableStateOf(configToEdit?.postConnectCommand ?: "")
    }
    var terminalFontSize by remember(configToEdit?.id, configToEdit?.terminalFontSizeSp, isCopyMode) {
        mutableStateOf((configToEdit?.terminalFontSizeSp ?: 12).coerceIn(10, 20).toString())
    }
    var terminalWrapChoice by remember(configToEdit?.id, configToEdit?.terminalWrapEnabled, isCopyMode) {
        mutableStateOf(configToEdit?.terminalWrapEnabled)
    }
    var terminalTerm by remember(configToEdit?.id, configToEdit?.terminalTerm, isCopyMode) {
        mutableStateOf(configToEdit?.terminalTerm ?: "xterm-256color")
    }
    var terminalShortcuts by remember(configToEdit?.id, configToEdit?.terminalShortcuts, isCopyMode) {
        mutableStateOf(configToEdit?.terminalShortcuts ?: "")
    }
    var persistentSessionMode by remember(configToEdit?.id, configToEdit?.persistentSessionMode, isCopyMode) {
        mutableStateOf(configToEdit?.persistentSessionMode ?: PERSISTENT_SESSION_NONE)
    }
    var showErrors by remember { mutableStateOf(false) }

    val parsedPort = port.toIntOrNull()
    val parsedTerminalFontSize = terminalFontSize.toIntOrNull()
    val hasSavedPassword = !configToEdit?.encryptedPassword.isNullOrBlank()
    val hasSavedPrivateKey = !configToEdit?.encryptedPrivateKey.isNullOrBlank()
    val passwordMissing = sshPasswordCredentialMissing(authType, password, hasSavedPassword)
    val privateKeyMissing = sshPrivateKeyCredentialMissing(authType, privateKey, hasSavedPrivateKey)
    val nameError = showErrors && name.isBlank()
    val hostError = showErrors && host.isBlank()
    val portError = showErrors && (parsedPort == null || parsedPort !in 1..65535)
    val terminalFontSizeError = showErrors && (parsedTerminalFontSize == null || parsedTerminalFontSize !in 10..20)
    val terminalTermError = showErrors && terminalTerm.trim().isBlank()
    val usernameError = showErrors && username.isBlank()
    val passwordError = showErrors && passwordMissing
    val privateKeyError = showErrors && privateKeyMissing
    val canSave = !nameError && !hostError && !portError && !usernameError && !passwordError && !privateKeyError && !terminalFontSizeError && !terminalTermError &&
        name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && parsedPort in 1..65535 &&
        parsedTerminalFontSize in 10..20 && terminalTerm.trim().isNotBlank() &&
        !passwordMissing && !privateKeyMissing

    Scaffold(
        topBar = {
            QuickSshPageHeader(
                title = when {
                    isWorkspaceCopy -> language.text("添加工作区", "Add Workspace")
                    isCopyMode -> language.text("复制工作区", "Copy Workspace")
                    isEditing -> language.text("编辑 SSH 服务器", "Edit SSH Server")
                    else -> language.text("添加 SSH 服务器", "Add SSH Server")
                },
                subtitle = language.text("连接、凭据与终端默认设置", "Connection, credentials and terminal defaults"),
                navigationIcon = {
                    FeedbackIconButton(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBackClicked,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!lockedServerFields) {
                OutlinedTextField(
                    value = serverDisplayName,
                    onValueChange = { serverDisplayName = it },
                    label = { Text(language.text("服务器备注 / 名称", "Server remark / name")) },
                    placeholder = { Text(language.text("例如：生产服务器、开发测试机", "e.g. Production server, Test box")) },
                    supportingText = {
                        Text(
                            language.text(
                                "对此服务器卡片生效，留空时默认显示 用户名@主机:端口",
                                "Applies to this server card, defaults to user@host:port"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(language.text("工作区标签 / 名称", "Workspace label")) },
                placeholder = { Text(language.text("例如：默认工作区、发布部署", "e.g. Default workspace, Production deploy")) },
                isError = nameError,
                supportingText = { if (nameError) Text(language.text("工作区标签不能为空", "Workspace label is required")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (isEditing && !lockedServerFields) {
                Text(
                    text = language.text(
                        "服务器连接配置（修改将同步应用到此卡片下所有工作区）",
                        "Server credentials (changes sync to all workspaces in this card)"
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { if (!lockedServerFields) host = it.trim() },
                    label = { Text("Host") },
                    isError = hostError,
                    supportingText = { if (hostError) Text("Host is required") },
                    modifier = Modifier.weight(2f),
                    enabled = !lockedServerFields,
                    colors = lockedFieldColors,
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (!lockedServerFields) port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    isError = portError,
                    supportingText = { if (portError) Text("1-65535") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    enabled = !lockedServerFields,
                    colors = lockedFieldColors,
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { if (!lockedServerFields) username = it.trim() },
                label = { Text("Username") },
                isError = usernameError,
                supportingText = { if (usernameError) Text("Username is required") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !lockedServerFields,
                colors = lockedFieldColors,
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedbackButton(
                    onClick = { authType = AUTH_TYPE_PASSWORD },
                    modifier = Modifier.weight(1f),
                    enabled = !lockedServerFields && authType != AUTH_TYPE_PASSWORD,
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f), disabledContentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Password")
                }
                FeedbackOutlinedButton(
                    onClick = { authType = AUTH_TYPE_PRIVATE_KEY },
                    modifier = Modifier.weight(1f),
                    enabled = !lockedServerFields && authType != AUTH_TYPE_PRIVATE_KEY
                ) {
                    Text("Private key")
                }
            }

            if (authType == AUTH_TYPE_PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            when {
                                isCopyMode -> "Password (blank keeps original)"
                                isEditing -> "Password (blank keeps saved password)"
                                else -> "Password"
                            }
                        )
                    },
                    isError = passwordError,
                    supportingText = { if (passwordError) Text("Password is required for password authentication") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !lockedServerFields,
                    colors = lockedFieldColors,
                    singleLine = true
                )
            } else {
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text(if (isEditing || isCopyMode) "Private key (blank keeps saved key)" else "Private key") },
                    placeholder = { Text("Paste OpenSSH private key") },
                    isError = privateKeyError,
                    supportingText = { if (privateKeyError) Text("Private key is required for key authentication") },
                    minLines = 4,
                    enabled = !lockedServerFields,
                    colors = lockedFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = workDirectory,
                onValueChange = { workDirectory = it },
                label = { Text("Default work directory") },
                placeholder = { Text("Example: /var/www/html") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = postConnectCommand,
                onValueChange = { postConnectCommand = it },
                label = { Text("Post-connect commands") },
                placeholder = { Text("Example: cd /home/me/project") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Terminal preferences", style = MaterialTheme.typography.titleSmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = terminalFontSize,
                    onValueChange = { terminalFontSize = it.filter(Char::isDigit).take(2) },
                    label = { Text("Font size") },
                    isError = terminalFontSizeError,
                    supportingText = { if (terminalFontSizeError) Text("10-20 sp") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = terminalTerm,
                    onValueChange = { terminalTerm = it.take(32) },
                    label = { Text("TERM") },
                    isError = terminalTermError,
                    supportingText = { if (terminalTermError) Text("TERM is required") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = terminalWrapChoice == null,
                    onClick = { terminalWrapChoice = null },
                    label = { Text("Use setting") }
                )
                FilterChip(
                    selected = terminalWrapChoice == true,
                    onClick = { terminalWrapChoice = true },
                    label = { Text("Wrap on") }
                )
                FilterChip(
                    selected = terminalWrapChoice == false,
                    onClick = { terminalWrapChoice = false },
                    label = { Text("Wrap off") }
                )
            }

            OutlinedTextField(
                value = terminalShortcuts,
                onValueChange = { terminalShortcuts = it },
                label = { Text("Shortcut commands") },
                placeholder = { Text("One command per line") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                language.text("持久会话（防断线）", "Persistent session (anti-disconnect)"),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                language.text(
                    "开启后，远端命令在 tmux/screen 中运行，SSH 断开后不会中断。仅限 Linux/macOS 远程。",
                    "When enabled, remote commands run inside tmux/screen and survive SSH disconnections. Linux/macOS remote only."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = persistentSessionMode == PERSISTENT_SESSION_NONE,
                    onClick = { persistentSessionMode = PERSISTENT_SESSION_NONE },
                    label = { Text(language.text("关闭", "Off")) }
                )
                FilterChip(
                    selected = persistentSessionMode == PERSISTENT_SESSION_AUTO,
                    onClick = { persistentSessionMode = PERSISTENT_SESSION_AUTO },
                    label = { Text(language.text("自动", "Auto")) }
                )
                FilterChip(
                    selected = persistentSessionMode == PERSISTENT_SESSION_TMUX,
                    onClick = { persistentSessionMode = PERSISTENT_SESSION_TMUX },
                    label = { Text("tmux") }
                )
                FilterChip(
                    selected = persistentSessionMode == PERSISTENT_SESSION_SCREEN,
                    onClick = { persistentSessionMode = PERSISTENT_SESSION_SCREEN },
                    label = { Text("screen") }
                )
            }

            FeedbackOutlinedButton(
                onClick = {
                    showErrors = true
                    if (canSave && parsedPort != null) {
                        onTestConnectionClicked(
                            name.trim(),
                            host.trim(),
                            parsedPort,
                            username.trim(),
                            authType,
                            password,
                            privateKey,
                            workDirectory.trim(),
                            postConnectCommand.trim(),
                            parsedTerminalFontSize ?: 12,
                            terminalWrapChoice,
                            terminalTerm.trim(),
                            terminalShortcuts.trim(),
                            persistentSessionMode
                        )
                    }
                },
                enabled = canSave && !isTestingConnection,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (isTestingConnection) "Testing connection..." else "Test Connection")
            }

            if (connectionTestStatus.isNotBlank()) {
                Text(
                    text = connectionTestStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            FeedbackButton(
                onClick = {
                    showErrors = true
                    if (canSave && parsedPort != null) {
                        onSaveClicked(
                            name.trim(),
                            host.trim(),
                            parsedPort,
                            username.trim(),
                            authType,
                            password,
                            privateKey,
                            workDirectory.trim(),
                            postConnectCommand.trim(),
                            parsedTerminalFontSize ?: 12,
                            terminalWrapChoice,
                            terminalTerm.trim(),
                            terminalShortcuts.trim(),
                            persistentSessionMode,
                            serverDisplayName.trim()
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (isCopyMode) "Save Workspace" else "Save Securely")
            }
        }
    }
}

internal fun sshPasswordCredentialMissing(
    authType: String,
    password: String,
    hasSavedPassword: Boolean
): Boolean {
    return authType == AUTH_TYPE_PASSWORD && password.isEmpty() && !hasSavedPassword
}

internal fun sshPrivateKeyCredentialMissing(
    authType: String,
    privateKey: String,
    hasSavedPrivateKey: Boolean
): Boolean {
    return authType == AUTH_TYPE_PRIVATE_KEY && privateKey.isBlank() && !hasSavedPrivateKey
}







