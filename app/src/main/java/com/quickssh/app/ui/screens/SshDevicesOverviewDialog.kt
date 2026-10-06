package com.quickssh.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickssh.app.data.SshConfig
import com.quickssh.app.utils.DiscoveredSshHost
import com.quickssh.app.utils.SshHostDiscoveryHelper
import com.quickssh.app.utils.SshHostSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshDevicesOverviewDialog(
    configs: List<SshConfig>,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConnectConfig: (Long) -> Unit,
    onImportAndConnect: (DiscoveredSshHost) -> Unit
) {
    val context = LocalContext.current
    val allDevices = remember(configs) {
        SshHostDiscoveryHelper.aggregateHosts(configs)
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedSourceFilter by remember { mutableStateOf<SshHostSource?>(null) }

    val filteredDevices = remember(allDevices, searchQuery, selectedSourceFilter) {
        allDevices.filter { device ->
            val matchSource = selectedSourceFilter == null || device.source == selectedSourceFilter
            val matchQuery = if (searchQuery.isBlank()) {
                true
            } else {
                device.hostname.contains(searchQuery, ignoreCase = true) ||
                        device.alias.contains(searchQuery, ignoreCase = true) ||
                        (device.username?.contains(searchQuery, ignoreCase = true) == true) ||
                        device.port.toString().contains(searchQuery)
            }
            matchSource && matchQuery
        }
    }

    fun copyToClipboard(label: String, text: String, toastMsg: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = language.text("可连接设备与 Hostname", "Reachable Devices & Hostnames"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = language.text(
                            "共发现 ${allDevices.size} 个 SSH 目标 (QuickSSH / Termux)",
                            "Discovered ${allDevices.size} SSH targets (QuickSSH / Termux)"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(language.text("按 Hostname、别名或端口检索...", "Search by hostname, alias, port...")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedSourceFilter == null,
                        onClick = { selectedSourceFilter = null },
                        label = { Text(language.text("全部", "All")) }
                    )
                    FilterChip(
                        selected = selectedSourceFilter == SshHostSource.QUICKSSH,
                        onClick = { selectedSourceFilter = if (selectedSourceFilter == SshHostSource.QUICKSSH) null else SshHostSource.QUICKSSH },
                        label = { Text("QuickSSH") }
                    )
                    FilterChip(
                        selected = selectedSourceFilter == SshHostSource.TERMUX_CONFIG || selectedSourceFilter == SshHostSource.TERMUX_KNOWN_HOSTS,
                        onClick = {
                            selectedSourceFilter = if (selectedSourceFilter == SshHostSource.TERMUX_CONFIG) null else SshHostSource.TERMUX_CONFIG
                        },
                        label = { Text("Termux") }
                    )
                }

                if (filteredDevices.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = language.text("未匹配到任何设备或 Hostname", "No matching devices or hostnames found"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDevices, key = { it.id }) { device ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = device.alias,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = when (device.source) {
                                                SshHostSource.QUICKSSH -> MaterialTheme.colorScheme.primaryContainer
                                                SshHostSource.TERMUX_CONFIG -> MaterialTheme.colorScheme.tertiaryContainer
                                                SshHostSource.TERMUX_KNOWN_HOSTS -> MaterialTheme.colorScheme.secondaryContainer
                                            }
                                        ) {
                                            Text(
                                                text = device.source.displayName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (device.source) {
                                                    SshHostSource.QUICKSSH -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    SshHostSource.TERMUX_CONFIG -> MaterialTheme.colorScheme.onTertiaryContainer
                                                    SshHostSource.TERMUX_KNOWN_HOSTS -> MaterialTheme.colorScheme.onSecondaryContainer
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${device.hostname}:${device.port}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (!device.username.isNullOrBlank()) {
                                            Text(
                                                text = "(${device.username})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                copyToClipboard(
                                                    "hostname",
                                                    device.hostname,
                                                    language.text("已复制 Hostname: ${device.hostname}", "Copied Hostname: ${device.hostname}")
                                                )
                                            },
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text(language.text("复制 Host", "Copy Host"), style = MaterialTheme.typography.labelSmall)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        OutlinedButton(
                                            onClick = {
                                                copyToClipboard(
                                                    "ssh_cmd",
                                                    device.sshCommand,
                                                    language.text("已复制 SSH 命令", "Copied SSH Command")
                                                )
                                            },
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text(language.text("复制命令", "Copy Cmd"), style = MaterialTheme.typography.labelSmall)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        Button(
                                            onClick = {
                                                if (device.quickSshConfigId != null) {
                                                    onConnectConfig(device.quickSshConfigId)
                                                } else {
                                                    onImportAndConnect(device)
                                                }
                                            },
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (device.quickSshConfigId != null) {
                                                    language.text("连接", "Connect")
                                                } else {
                                                    language.text("导入直连", "Import & Go")
                                                },
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(language.text("关闭", "Close"))
            }
        }
    )
}
