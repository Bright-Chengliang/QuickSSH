// QuickSSH - Settings
// Copyright (c) 2026 Chengliang Liu
// Author: https://github.com/Bright-Chengliang
// License: MIT License (see LICENSE)

package com.quickssh.app.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.quickssh.app.BuildConfig
import com.quickssh.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    autoWrapEnabled: Boolean,
    privacyModeEnabled: Boolean,
    biometricUnlockEnabled: Boolean,
    strictHostKeyVerificationEnabled: Boolean,
    biometricAvailable: Boolean,
    downloadDirectoryLabel: String,
    serverProfileCount: Int,
    backupStatusText: String,
    bottomBar: @Composable () -> Unit,
    onAutoWrapChange: (Boolean) -> Unit,
    onPrivacyModeChange: (Boolean) -> Unit,
    onBiometricUnlockChange: (Boolean) -> Unit,
    onStrictHostKeyVerificationChange: (Boolean) -> Unit,
    onChooseDownloadDirectory: () -> Unit,
    onExportConfigs: (String?) -> Unit,
    onImportConfigs: (String?) -> Unit
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSwitchRow(
                title = "Terminal auto wrap",
                detail = "Turn off to scroll horizontally for long terminal lines.",
                checked = autoWrapEnabled,
                onCheckedChange = onAutoWrapChange
            )
            SettingsSwitchRow(
                title = "Privacy mode",
                detail = "Block screenshots and app previews on sensitive screens.",
                checked = privacyModeEnabled,
                onCheckedChange = onPrivacyModeChange
            )
            SettingsSwitchRow(
                title = "Biometric unlock",
                detail = if (biometricAvailable) "Require biometric confirmation before connecting or editing credentials." else "No biometric authenticator is available on this device.",
                checked = biometricUnlockEnabled,
                enabled = biometricAvailable,
                onCheckedChange = onBiometricUnlockChange
            )
            SettingsSwitchRow(
                title = "Strict host-key verification",
                detail = "Default stays automatic trust. Enable this to reject unknown or changed SSH host keys unless they are already trusted.",
                checked = strictHostKeyVerificationEnabled,
                onCheckedChange = onStrictHostKeyVerificationChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download directory",
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
                    Text("Choose")
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Server backup",
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
                        Text("Export", modifier = Modifier.padding(start = 8.dp))
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
                        Text("Import", modifier = Modifier.padding(start = 8.dp))
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
                    text = "About",
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
            title = "Export server backup",
            detail = "Optional: enter a password to encrypt this backup, or leave it blank to export directly.",
            password = exportPassword,
            confirmText = "Export",
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
            title = "Import server backup",
            detail = "Enter the backup password if the file is protected. Leave blank for older or unprotected backups.",
            password = importPassword,
            confirmText = "Import",
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
                    label = { Text("Backup password") },
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
                Text("Cancel")
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
