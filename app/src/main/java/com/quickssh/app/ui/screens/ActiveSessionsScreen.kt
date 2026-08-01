package com.quickssh.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickssh.app.service.SshSessionInfo
import com.quickssh.app.service.SshSessionStatus
import com.quickssh.app.service.displayText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionsScreen(
    sessions: List<SshSessionInfo>,
    bottomBar: @Composable () -> Unit,
    onBackClicked: () -> Unit,
    onOpenSession: (SshSessionInfo) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onReconnectSession: (String) -> Unit,
    onDisconnectSession: (String) -> Unit
) {
    var editingSession by remember { mutableStateOf<SshSessionInfo?>(null) }
    var editingName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions (${sessions.size})") },
                navigationIcon = {
                    FeedbackTextButton(onClick = onBackClicked) { Text("Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No background SSH sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    ActiveSessionCard(
                        session = session,
                        onOpen = { onOpenSession(session) },
                        onRename = {
                            editingSession = session
                            editingName = session.name
                        },
                        onReconnect = { onReconnectSession(session.sessionId) },
                        onDisconnect = { onDisconnectSession(session.sessionId) }
                    )
                }
            }
        }
    }

    editingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = { editingName = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                FeedbackTextButton(
                    onClick = {
                        onRenameSession(session.sessionId, editingName)
                        editingSession = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                FeedbackTextButton(onClick = { editingSession = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ActiveSessionCard(
    session: SshSessionInfo,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${session.status.displayText()} - ${session.username}@${session.host}:${session.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (session.statusMessage.isNotBlank()) {
                    Text(
                        text = session.statusMessage,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (!session.workDirectory.isNullOrBlank()) {
                    Text(
                        text = session.workDirectory,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FeedbackIconButton(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Open terminal",
                    onClick = onOpen,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename session",
                    onClick = onRename,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reconnect session",
                    onClick = onReconnect,
                    enabled = session.status == SshSessionStatus.RECONNECTING || session.status == SshSessionStatus.FAILED,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (session.status == SshSessionStatus.RECONNECTING || session.status == SshSessionStatus.FAILED) 1f else 0.38f
                    )
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Disconnect session",
                    onClick = onDisconnect,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
