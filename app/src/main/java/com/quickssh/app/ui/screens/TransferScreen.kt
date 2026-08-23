package com.quickssh.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.quickssh.app.R
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.groupedSshServers
import com.quickssh.app.data.transferContextLabel
import com.quickssh.app.data.workspaceLabel
import com.quickssh.app.service.FileTransferHelper
import kotlin.math.roundToInt

data class RemoteDownloadPlanConfirmationUiState(
    val fileCount: Int,
    val directoryCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    configs: List<SshConfig>,
    selectedConfig: SshConfig?,
    selectedLocalUris: List<Uri>,
    uploadRemotePath: String,
    downloadRemotePath: String,
    downloadDirectoryLabel: String,
    statusText: String,
    isTransferring: Boolean,
    progressText: String,
    progressFraction: Float?,
    queuedCount: Int,
    queuePaused: Boolean,
    uploadConflictPolicy: FileTransferHelper.UploadConflictPolicy,
    downloadConflictPolicy: FileTransferHelper.UploadConflictPolicy,
    tasks: List<TransferTaskUiState>,
    remoteEntries: List<FileTransferHelper.RemoteEntry>,
    remoteBrowserStatus: String,
    isBrowsingRemote: Boolean,
    selectedRemotePaths: Set<String>,
    remoteMultiSelectEnabled: Boolean,
    isPreparingRemoteDownloadPlan: Boolean,
    remoteDownloadPlanElapsedSeconds: Long,
    showRemoteDownloadPlanSlowWarning: Boolean,
    remoteDownloadPlanConfirmation: RemoteDownloadPlanConfirmationUiState?,
    bottomBar: @Composable () -> Unit,
    onConfigSelected: (SshConfig) -> Unit,
    onUploadRemotePathChange: (String) -> Unit,
    onDownloadRemotePathChange: (String) -> Unit,
    onChooseDownloadDirectory: () -> Unit,
    onPickLocalFile: () -> Unit,
    onUploadClicked: () -> Unit,
    onDownloadClicked: () -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onClearQueue: () -> Unit,
    onUploadConflictPolicyChange: (FileTransferHelper.UploadConflictPolicy) -> Unit,
    onDownloadConflictPolicyChange: (FileTransferHelper.UploadConflictPolicy) -> Unit,
    onCancelTransfer: () -> Unit,
    onBrowseRemote: () -> Unit,
    onRemoteEntryClicked: (FileTransferHelper.RemoteEntry) -> Unit,
    onRemoteEntrySelectionToggle: (FileTransferHelper.RemoteEntry) -> Unit,
    onRemoteMultiSelectStarted: (FileTransferHelper.RemoteEntry) -> Unit,
    onRemoteSelectionCleared: () -> Unit,
    onDownloadSelectedRemoteEntries: () -> Unit,
    onCancelRemoteDownloadPlan: () -> Unit,
    onKeepPreparingRemoteDownloadPlan: () -> Unit,
    onConfirmRemoteDownloadPlan: () -> Unit,
    onCancelPendingRemoteDownloadPlan: () -> Unit,
    onRemoteParentClicked: () -> Unit,
    onTaskClicked: (TransferTaskUiState) -> Unit,
    onTaskDeleted: (TransferTaskUiState) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    Scaffold(
        topBar = {
            TransferProgressTopBar(
                isTransferring = isTransferring,
                progressText = progressText,
                progressFraction = progressFraction
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
            ServerSelector(configs, selectedConfig, onConfigSelected)

            TransferSection(title = language.text("下载", "Download")) {
                OutlinedTextField(
                    value = downloadRemotePath,
                    onValueChange = onDownloadRemotePathChange,
                    label = { Text(language.text("下载远端路径", "Remote path to download")) },
                    placeholder = { Text(selectedConfig?.workDirectory ?: language.text("默认使用 SSH 登录目录", "Defaults to the SSH login directory")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ResponsiveOutlinedButton(
                        onClick = onBrowseRemote,
                        enabled = selectedConfig != null && !isTransferring && !isBrowsingRemote,
                        modifier = Modifier.weight(1f),
                        text = if (isBrowsingRemote) language.text("读取中", "Loading") else language.text("浏览远端", "Browse remote")
                    )
                    ResponsiveOutlinedButton(
                        onClick = onRemoteParentClicked,
                        enabled = selectedConfig != null && !isTransferring,
                        modifier = Modifier.weight(1f),
                        text = language.text("上级目录", "Parent directory")
                    )
                }
                ResponsiveOutlinedButton(
                    onClick = onChooseDownloadDirectory,
                    enabled = !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = transferDownloadDirectoryActionLabel(downloadDirectoryLabel, language)
                )
                RemoteBrowser(
                    entries = remoteEntries,
                    status = remoteBrowserStatus,
                    selectedRemotePath = downloadRemotePath,
                    selectedRemotePaths = selectedRemotePaths,
                    multiSelectEnabled = remoteMultiSelectEnabled,
                    isPreparingDownloadPlan = isPreparingRemoteDownloadPlan,
                    downloadPlanElapsedSeconds = remoteDownloadPlanElapsedSeconds,
                    onEntryClicked = onRemoteEntryClicked,
                    onEntrySelectionToggle = onRemoteEntrySelectionToggle,
                    onMultiSelectStarted = onRemoteMultiSelectStarted,
                    onSelectionCleared = onRemoteSelectionCleared,
                    onDownloadSelected = onDownloadSelectedRemoteEntries,
                    onCancelDownloadPlan = onCancelRemoteDownloadPlan
                )
                UploadConflictPolicySelector(
                    title = language.text("下载冲突处理", "Download conflict policy"),
                    selectedPolicy = downloadConflictPolicy,
                    enabled = !isTransferring,
                    onPolicySelected = onDownloadConflictPolicyChange
                )
                ResponsiveButton(
                    onClick = onDownloadClicked,
                    enabled = selectedConfig != null && downloadRemotePath.isNotBlank() && !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = language.text("下载", "Download")
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
            )

            TransferSection(title = language.text("上传", "Upload")) {
                OutlinedTextField(
                    value = uploadRemotePath,
                    onValueChange = onUploadRemotePathChange,
                    label = { Text(language.text("上传目标路径", "Remote upload destination")) },
                    placeholder = { Text(selectedConfig?.workDirectory ?: language.text("默认使用 SSH 登录目录", "Defaults to the SSH login directory")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ResponsiveOutlinedButton(
                    onClick = onPickLocalFile,
                    enabled = !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = selectedLocalFilesLabel(selectedLocalUris, language)
                )
                UploadConflictPolicySelector(
                    title = language.text("上传冲突处理", "Upload conflict policy"),
                    selectedPolicy = uploadConflictPolicy,
                    enabled = !isTransferring,
                    onPolicySelected = onUploadConflictPolicyChange
                )
                ResponsiveButton(
                    onClick = onUploadClicked,
                    enabled = selectedConfig != null && selectedLocalUris.isNotEmpty() && !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = language.text("上传", "Upload")
                )
            }

            if (isTransferring || progressText.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (progressFraction != null) {
                        LinearProgressIndicator(progress = progressFraction.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(text = progressText.ifBlank { statusText }, style = MaterialTheme.typography.bodyMedium)
                    if (isTransferring) {
                        FeedbackTextButton(onClick = onCancelTransfer) { Text("\u53d6\u6d88\u4f20\u8f93") }
                    }
                }
            } else {
                Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
            }

            if (queuedCount > 0 || queuePaused) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (queuePaused) language.text("队列已暂停：$queuedCount 个等待", "Queue paused: $queuedCount waiting") else language.text("队列：$queuedCount 个等待", "Queue: $queuedCount waiting"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    FeedbackTextButton(onClick = if (queuePaused) onResumeQueue else onPauseQueue) {
                        Text(if (queuePaused) language.text("继续", "Resume") else language.text("暂停", "Pause"))
                    }
                    FeedbackTextButton(onClick = onClearQueue, enabled = queuedCount > 0) { Text(language.text("取消等待", "Clear queue")) }
                }
            }

            if (tasks.isNotEmpty()) {
                var recentTasksExpanded by remember { mutableStateOf(false) }
                var recentTasksPage by remember { mutableStateOf(0) }
                val pageCount = recentTransferTaskPageCount(tasks.size)
                val currentPage = recentTasksPage.coerceIn(0, pageCount - 1)
                val visibleTasks = visibleRecentTransferTasks(
                    tasks = tasks,
                    expanded = recentTasksExpanded,
                    pageIndex = currentPage
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\u4f20\u8f93\u4efb\u52a1",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (tasks.size > RECENT_TRANSFER_TASK_COLLAPSED_LIMIT) {
                        FeedbackTextButton(
                            onClick = {
                                recentTasksExpanded = !recentTasksExpanded
                                recentTasksPage = 0
                            }
                        ) {
                            Text(if (recentTasksExpanded) language.text("收起", "Collapse") else language.text("展开", "Expand"))
                        }
                    }
                }

                Text(
                    text = recentTransferTaskRangeLabel(
                        totalTaskCount = tasks.size,
                        visibleTaskCount = visibleTasks.size,
                        expanded = recentTasksExpanded,
                        pageIndex = currentPage,
                        language = language
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                visibleTasks.forEach { task ->
                    TransferTaskRow(
                        task = task,
                        onTaskClicked = onTaskClicked,
                        onTaskDeleted = onTaskDeleted
                    )
                }

                if (recentTasksExpanded && tasks.size > RECENT_TRANSFER_TASK_PAGE_SIZE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 ${currentPage + 1} / $pageCount 页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f)
                        )
                        FeedbackTextButton(
                            enabled = currentPage > 0,
                            onClick = { recentTasksPage = (currentPage - 1).coerceAtLeast(0) }
                        ) {
                            Text(language.text("上一页", "Previous"))
                        }
                        FeedbackTextButton(
                            enabled = currentPage < pageCount - 1,
                            onClick = { recentTasksPage = (currentPage + 1).coerceAtMost(pageCount - 1) }
                        ) {
                            Text(language.text("下一页", "Next"))
                        }
                    }
                }
            }
        }
    }

    if (showRemoteDownloadPlanSlowWarning) {
        AlertDialog(
            onDismissRequest = onKeepPreparingRemoteDownloadPlan,
            title = { Text(language.text("下载计划耗时较久", "Download planning is taking a while")) },
            text = {
                Text(
                    language.text(
                        "创建下载计划已等待 ${formatElapsedSeconds(remoteDownloadPlanElapsedSeconds)}，目录可能过大或网络响应较慢。可以取消后减少下载范围，或继续等待。",
                        "Download planning has waited ${formatElapsedSeconds(remoteDownloadPlanElapsedSeconds, language)}. The directory may be large or the network may be slow. Cancel to narrow the scope, or keep waiting."
                    )
                )
            },
            confirmButton = {
                FeedbackTextButton(onClick = onKeepPreparingRemoteDownloadPlan) {
                    Text(language.text("继续等待", "Keep waiting"))
                }
            },
            dismissButton = {
                FeedbackTextButton(onClick = onCancelRemoteDownloadPlan) {
                    Text(language.text("取消计划", "Cancel plan"))
                }
            }
        )
    }

    if (remoteDownloadPlanConfirmation != null) {
        AlertDialog(
            onDismissRequest = onCancelPendingRemoteDownloadPlan,
            title = { Text(language.text("确认大目录下载", "Confirm large directory download")) },
            text = {
                Text(
                    language.text(
                        "已扫描到 ${remoteDownloadPlanConfirmation.fileCount} 个文件、${remoteDownloadPlanConfirmation.directoryCount} 个文件夹。继续后会创建本地目录树并把文件加入下载队列；如果数量不符合预期，可以取消后缩小下载范围。",
                        "Scanned ${remoteDownloadPlanConfirmation.fileCount} files and ${remoteDownloadPlanConfirmation.directoryCount} folders. Continuing will create the local directory tree and queue the files; cancel to narrow the scope if the count is unexpected."
                    )
                )
            },
            confirmButton = {
                FeedbackTextButton(onClick = onConfirmRemoteDownloadPlan) {
                    Text(language.text("继续下载", "Continue download"))
                }
            },
            dismissButton = {
                FeedbackTextButton(onClick = onCancelPendingRemoteDownloadPlan) {
                    Text(language.text("取消", "Cancel"))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferProgressTopBar(
    isTransferring: Boolean,
    progressText: String,
    progressFraction: Float?
) {
    val language = LocalQuickSshLanguage.current
    val mode = transferTopProgressMode(
        isTransferring = isTransferring,
        progressText = progressText,
        progressFraction = progressFraction
    )
    Column {
        QuickSshPageHeader(
            title = language.text("文件传输", "File transfers"),
            subtitle = language.text("SFTP 上传、下载与传输历史", "SFTP uploads, downloads and transfer history")
        )
        when (mode) {
            TransferTopProgressMode.Idle -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                )
            }
            TransferTopProgressMode.Indeterminate -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
            TransferTopProgressMode.Determinate -> {
                LinearProgressIndicator(
                    progress = progressFraction?.coerceIn(0f, 1f) ?: 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
        }
    }
}

@Composable
private fun TransferSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadConflictPolicySelector(
    title: String,
    selectedPolicy: FileTransferHelper.UploadConflictPolicy,
    enabled: Boolean,
    onPolicySelected: (FileTransferHelper.UploadConflictPolicy) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FileTransferHelper.UploadConflictPolicy.values().forEach { policy ->
                FilterChip(
                    selected = selectedPolicy == policy,
                    onClick = { onPolicySelected(policy) },
                    enabled = enabled,
                    label = { Text(transferConflictPolicyLabel(policy, language), maxLines = 1) }
                )
            }
        }
    }
}

internal const val RECENT_TRANSFER_TASK_COLLAPSED_LIMIT = 10
internal const val RECENT_TRANSFER_TASK_PAGE_SIZE = 30
internal const val REMOTE_BROWSER_COLLAPSED_LIMIT = 30
internal const val REMOTE_BROWSER_PAGE_SIZE = 30

internal enum class TransferTopProgressMode {
    Idle,
    Indeterminate,
    Determinate
}

data class TransferTaskUiState(
    val id: Long = 0,
    val fileName: String,
    val direction: String,
    val serverName: String,
    val status: String,
    val localUri: Uri? = null,
    val remotePath: String = "",
    val detail: String = "",
    val serverNodeName: String = serverName.substringBefore(" / ").ifBlank { serverName },
    val workspaceName: String = serverName.substringAfter(" / ", "").ifBlank { serverName }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferTaskRow(
    task: TransferTaskUiState,
    onTaskClicked: (TransferTaskUiState) -> Unit,
    onTaskDeleted: (TransferTaskUiState) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    var showActions by remember { mutableStateOf(false) }
    val actionable = task.id > 0L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (actionable) onTaskClicked(task) },
                onLongClick = { showActions = true }
            )
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = "${task.direction} - ${task.fileName}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${task.serverNodeName} / ${task.workspaceName} - ${task.status}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (task.detail.isNotBlank()) {
            Text(
                text = task.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text(language.text("\u4efb\u52a1\u5c5e\u6027", "Task details")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(language.text("\u6587\u4ef6\uff1a${task.fileName}", "File: ${task.fileName}"))
                    Text(language.text("\u65b9\u5411\uff1a${task.direction}", "Direction: ${task.direction}"))
                    Text(language.text("\u670d\u52a1\u5668\uff1a${task.serverNodeName}", "Server: ${task.serverNodeName}"))
                    Text(language.text("\u5de5\u4f5c\u533a\uff1a${task.workspaceName}", "Workspace: ${task.workspaceName}"))
                    Text(language.text("\u72b6\u6001\uff1a${task.status}", "Status: ${task.status}"))
                    if (task.remotePath.isNotBlank()) Text(language.text("\u8fdc\u7aef\uff1a${task.remotePath}", "Remote: ${task.remotePath}"))
                    task.localUri?.let { Text(language.text("\u672c\u5730\uff1a$it", "Local: $it")) }
                    if (task.detail.isNotBlank()) Text(task.detail)
                }
            },
            confirmButton = {
                FeedbackTextButton(onClick = {
                    showActions = false
                    if (actionable) onTaskClicked(task)
                }) { Text(if (actionable) language.text("\u6253\u5f00", "Open") else language.text("\u5173\u95ed", "Close")) }
            },
            dismissButton = {
                if (actionable) {
                    Row {
                        FeedbackTextButton(onClick = {
                            showActions = false
                            onTaskDeleted(task)
                        }) { Text(language.text("\u5220\u9664\u8bb0\u5f55", "Delete record")) }
                        FeedbackTextButton(onClick = { showActions = false }) { Text(language.text("\u5173\u95ed", "Close")) }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteBrowser(
    entries: List<FileTransferHelper.RemoteEntry>,
    status: String,
    selectedRemotePath: String,
    selectedRemotePaths: Set<String>,
    multiSelectEnabled: Boolean,
    isPreparingDownloadPlan: Boolean,
    downloadPlanElapsedSeconds: Long,
    onEntryClicked: (FileTransferHelper.RemoteEntry) -> Unit,
    onEntrySelectionToggle: (FileTransferHelper.RemoteEntry) -> Unit,
    onMultiSelectStarted: (FileTransferHelper.RemoteEntry) -> Unit,
    onSelectionCleared: () -> Unit,
    onDownloadSelected: () -> Unit,
    onCancelDownloadPlan: () -> Unit
) {
    val language = LocalQuickSshLanguage.current
    if (status.isNotBlank()) {
        Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    val selectedCount = selectedRemotePaths.size
    val selectionMode = multiSelectEnabled || selectedCount > 0
    if (selectionMode) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedCount == 0) language.text("多选模式", "Multi-select mode") else language.text("已选择 $selectedCount 项", "$selectedCount selected"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            FeedbackTextButton(onClick = onDownloadSelected, enabled = selectedCount > 0) {
                Text(language.text("下载选中", "Download selected"))
            }
            FeedbackTextButton(onClick = onSelectionCleared, enabled = selectedCount > 0 || multiSelectEnabled) {
                Text(language.text("清除", "Clear"))
            }
        }
    }
    if (isPreparingDownloadPlan) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                text = language.text("正在创建下载计划，已等待 ${formatElapsedSeconds(downloadPlanElapsedSeconds)}", "Preparing download plan for ${formatElapsedSeconds(downloadPlanElapsedSeconds, language)}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            FeedbackTextButton(onClick = onCancelDownloadPlan) {
                Text(language.text("取消", "Cancel"))
            }
        }
    }
    val remoteEntryListKey = "${entries.size}:${entries.firstOrNull()?.path.orEmpty()}:${entries.lastOrNull()?.path.orEmpty()}"
    var remoteEntriesExpanded by remember(remoteEntryListKey) { mutableStateOf(false) }
    var remoteEntriesPage by remember(remoteEntryListKey) { mutableStateOf(0) }
    val pageCount = remoteBrowserPageCount(entries.size)
    val currentPage = remoteEntriesPage.coerceIn(0, pageCount - 1)
    val visibleEntries = visibleRemoteBrowserEntries(
        entries = entries,
        expanded = remoteEntriesExpanded,
        pageIndex = currentPage
    )

    if (entries.size > REMOTE_BROWSER_COLLAPSED_LIMIT) {
        RemoteBrowserPagingControls(
            totalEntryCount = entries.size,
            visibleEntryCount = visibleEntries.size,
            expanded = remoteEntriesExpanded,
            pageIndex = currentPage,
            pageCount = pageCount,
            onExpandedChange = { expanded ->
                remoteEntriesExpanded = expanded
                remoteEntriesPage = 0
            },
            onPreviousPage = { remoteEntriesPage = (currentPage - 1).coerceAtLeast(0) },
            onNextPage = { remoteEntriesPage = (currentPage + 1).coerceAtMost(pageCount - 1) }
        )
    }

    visibleEntries.forEach { entry ->
        var showEntryMenu by remember(entry.path) { mutableStateOf(false) }
        val selected = remoteEntryIsSelected(
            entryPath = entry.path,
            selectedRemotePath = selectedRemotePath,
            selectedRemotePaths = selectedRemotePaths,
            isDirectory = entry.isDirectory,
            selectionMode = selectionMode
        )
        val rowColor by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            label = "remoteEntrySelectedColor"
        )
        val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
        val supportingColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.outline
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowColor, MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = {
                        if (multiSelectEnabled) onEntrySelectionToggle(entry) else onEntryClicked(entry)
                    },
                    onLongClick = { showEntryMenu = true }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_insert_drive_file),
                contentDescription = null,
                tint = contentColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!entry.isDirectory) {
                    Text(text = formatBytes(entry.size), style = MaterialTheme.typography.bodySmall, color = supportingColor)
                }
            }
        }
        if (showEntryMenu) {
            RemoteEntryMenuDialog(
                entry = entry,
                selected = selectedRemotePaths.any { remoteEntryMatchesSelectedPath(entry.path, it) },
                multiSelectEnabled = multiSelectEnabled,
                onSelectClicked = {
                    showEntryMenu = false
                    onEntrySelectionToggle(entry)
                },
                onMultiSelectClicked = {
                    showEntryMenu = false
                    onMultiSelectStarted(entry)
                },
                onOpenDirectoryClicked = {
                    showEntryMenu = false
                    onEntryClicked(entry)
                },
                onDismiss = { showEntryMenu = false }
            )
        }
    }
}

@Composable
private fun RemoteBrowserPagingControls(
    totalEntryCount: Int,
    visibleEntryCount: Int,
    expanded: Boolean,
    pageIndex: Int,
    pageCount: Int,
    onExpandedChange: (Boolean) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit
) {
    val language = LocalQuickSshLanguage.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = remoteBrowserRangeLabel(
                totalEntryCount = totalEntryCount,
                visibleEntryCount = visibleEntryCount,
                expanded = expanded,
                pageIndex = pageIndex
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        FeedbackTextButton(onClick = { onExpandedChange(!expanded) }) {
            Text(if (expanded) language.text("收起", "Collapse") else language.text("展开", "Expand"))
        }
    }

    if (expanded && totalEntryCount > REMOTE_BROWSER_PAGE_SIZE) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第 ${pageIndex + 1} / $pageCount 页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            FeedbackTextButton(
                enabled = pageIndex > 0,
                onClick = onPreviousPage
            ) {
                Text(language.text("上一页", "Previous"))
            }
            FeedbackTextButton(
                enabled = pageIndex < pageCount - 1,
                onClick = onNextPage
            ) {
                Text(language.text("下一页", "Next"))
            }
        }
    }
}

@Composable
private fun RemoteEntryMenuDialog(
    entry: FileTransferHelper.RemoteEntry,
    selected: Boolean,
    multiSelectEnabled: Boolean,
    onSelectClicked: () -> Unit,
    onMultiSelectClicked: () -> Unit,
    onOpenDirectoryClicked: () -> Unit,
    onDismiss: () -> Unit
) {
    val language = LocalQuickSshLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackTextButton(onClick = onSelectClicked, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected) language.text("取消选择", "Deselect") else language.text("选中此项", "Select"))
                }
                FeedbackTextButton(onClick = onMultiSelectClicked, modifier = Modifier.fillMaxWidth()) {
                    Text(if (multiSelectEnabled) language.text("加入/移出多选", "Toggle multi-select") else language.text("多选", "Multi-select"))
                }
                if (entry.isDirectory) {
                    FeedbackTextButton(onClick = onOpenDirectoryClicked, modifier = Modifier.fillMaxWidth()) {
                        Text(language.text("进入文件夹", "Open folder"))
                    }
                }
            }
        },
        confirmButton = {
            FeedbackTextButton(onClick = onDismiss) { Text(language.text("关闭", "Close")) }
        }
    )
}

@Composable
private fun ServerSelector(configs: List<SshConfig>, selectedConfig: SshConfig?, onConfigSelected: (SshConfig) -> Unit) {
    val language = LocalQuickSshLanguage.current
    var expanded by remember { mutableStateOf(false) }
    val groups by remember(configs) { derivedStateOf { groupedSshServers(configs) } }
    Box(modifier = Modifier.fillMaxWidth()) {
        ResponsiveOutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            text = selectedConfig?.transferContextLabel() ?: language.text("\u9009\u62e9\u670d\u52a1\u5668 / \u5de5\u4f5c\u533a", "Select server / workspace"),
            trailingIcon = { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = language.text("\u5c55\u5f00", "Expand")) }
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
                DropdownMenuItem(text = { Text(language.text("\u6682\u65e0\u670d\u52a1\u5668\u914d\u7f6e", "No server profiles")) }, onClick = { expanded = false })
            }
        }
    }
}

@Composable
private fun ResponsiveButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    text: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "transferButtonPressScale")
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
private fun ResponsiveOutlinedButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    text: String,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "transferOutlinedPressScale")
    val containerColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color.Transparent,
        label = "transferOutlinedPressColor"
    )
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = containerColor),
        modifier = modifier.scale(scale)
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        trailingIcon?.invoke()
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

internal fun uploadConflictPolicyLabel(policy: FileTransferHelper.UploadConflictPolicy): String {
    return transferConflictPolicyLabel(policy)
}

internal fun transferConflictPolicyLabel(policy: FileTransferHelper.UploadConflictPolicy): String {
    return transferConflictPolicyLabel(policy, AppLanguage.ZH)
}

internal fun transferConflictPolicyLabel(policy: FileTransferHelper.UploadConflictPolicy, language: AppLanguage): String {
    return when (policy) {
        FileTransferHelper.UploadConflictPolicy.RENAME -> language.text("自动改名", "Auto-rename")
        FileTransferHelper.UploadConflictPolicy.OVERWRITE -> language.text("覆盖", "Overwrite")
        FileTransferHelper.UploadConflictPolicy.FAIL -> language.text("失败", "Fail")
    }
}

internal fun transferDownloadDirectoryActionLabel(downloadDirectoryLabel: String, language: AppLanguage = AppLanguage.ZH): String {
    val label = downloadDirectoryLabel.trim()
    return if (label.isBlank() || label.startsWith("Not set", ignoreCase = true)) {
        language.text("选择保存目录", "Choose save directory")
    } else {
        language.text("保存到：$label", "Save to: $label")
    }
}

internal fun recentTransferTaskPageCount(totalTaskCount: Int): Int {
    if (totalTaskCount <= 0) return 1
    return ((totalTaskCount - 1) / RECENT_TRANSFER_TASK_PAGE_SIZE) + 1
}

internal fun visibleRecentTransferTasks(
    tasks: List<TransferTaskUiState>,
    expanded: Boolean,
    pageIndex: Int
): List<TransferTaskUiState> {
    if (!expanded) return tasks.take(RECENT_TRANSFER_TASK_COLLAPSED_LIMIT)
    val safePage = pageIndex.coerceIn(0, recentTransferTaskPageCount(tasks.size) - 1)
    return tasks
        .drop(safePage * RECENT_TRANSFER_TASK_PAGE_SIZE)
        .take(RECENT_TRANSFER_TASK_PAGE_SIZE)
}

internal fun recentTransferTaskRangeLabel(
    totalTaskCount: Int,
    visibleTaskCount: Int,
    expanded: Boolean,
    pageIndex: Int,
    language: AppLanguage = AppLanguage.ZH
): String {
    if (totalTaskCount <= 0) return language.text("暂无任务", "No tasks")
    if (!expanded) return language.text(
        "显示最近 ${visibleTaskCount.coerceAtMost(totalTaskCount)} / $totalTaskCount 个任务",
        "Showing the latest ${visibleTaskCount.coerceAtMost(totalTaskCount)} / $totalTaskCount tasks"
    )
    val safePage = pageIndex.coerceIn(0, recentTransferTaskPageCount(totalTaskCount) - 1)
    val start = safePage * RECENT_TRANSFER_TASK_PAGE_SIZE + 1
    val end = (start + visibleTaskCount - 1).coerceAtMost(totalTaskCount)
    return language.text(
        "显示第 $start-$end / $totalTaskCount 个任务",
        "Showing $start-$end / $totalTaskCount tasks"
    )
}

internal fun transferTopProgressMode(
    isTransferring: Boolean,
    progressText: String,
    progressFraction: Float?
): TransferTopProgressMode {
    if (!isTransferring && progressText.isBlank()) return TransferTopProgressMode.Idle
    return if (progressFraction == null) {
        TransferTopProgressMode.Indeterminate
    } else {
        TransferTopProgressMode.Determinate
    }
}

internal fun transferTaskProgressDetail(progressText: String, progressFraction: Float?): String {
    if (progressText.isNotBlank()) return "当前进度：$progressText"
    val percent = progressFraction?.coerceIn(0f, 1f)?.let { "${(it * 100f).roundToInt()}%" }
    return percent?.let { "当前进度：$it" } ?: "当前进度：准备中"
}

internal fun transferQueueTaskUiState(
    index: Int,
    totalWaiting: Int,
    queuePaused: Boolean,
    direction: String,
    fileName: String,
    serverName: String,
    serverNodeName: String,
    workspaceName: String,
    localUri: Uri?,
    destinationUri: Uri?,
    remotePath: String
): TransferTaskUiState {
    val position = (index + 1).coerceAtLeast(1)
    val safeTotal = totalWaiting.coerceAtLeast(position)
    val pathDetail = when {
        direction == "Upload" && remotePath.isNotBlank() -> "目标：$remotePath"
        direction == "Download" && remotePath.isNotBlank() -> "远端：$remotePath"
        else -> ""
    }
    val localDetail = when {
        direction == "Upload" && localUri != null -> "本机：$localUri"
        direction == "Download" && destinationUri != null -> "保存到：$destinationUri"
        else -> ""
    }

    return TransferTaskUiState(
        id = -position.toLong(),
        fileName = fileName,
        direction = direction,
        serverName = serverName,
        status = if (queuePaused) "已暂停等待" else "等待传输",
        localUri = localUri ?: destinationUri,
        remotePath = remotePath,
        detail = listOf(
            "等待传输：第 $position / $safeTotal 个",
            pathDetail,
            localDetail
        ).filter { it.isNotBlank() }.joinToString(" | "),
        serverNodeName = serverNodeName,
        workspaceName = workspaceName
    )
}

internal fun transferTasksWithBatchState(
    historyTasks: List<TransferTaskUiState>,
    activeTask: TransferTaskUiState?,
    queuedTasks: List<TransferTaskUiState>
): List<TransferTaskUiState> {
    val activeId = activeTask?.id?.takeIf { it > 0L }
    var mergedActiveTask: TransferTaskUiState? = null
    val remainingHistory = historyTasks.mapNotNull { task ->
        if (activeId != null && task.id == activeId) {
            mergedActiveTask = task.copy(
                status = activeTask.status.ifBlank { task.status },
                detail = activeTask.detail.ifBlank { task.detail },
                remotePath = task.remotePath.ifBlank { activeTask.remotePath },
                localUri = task.localUri ?: activeTask.localUri
            )
            null
        } else {
            task
        }
    }

    return buildList {
        (mergedActiveTask ?: activeTask)?.let(::add)
        addAll(queuedTasks)
        addAll(remainingHistory)
    }
}

internal fun remoteBrowserPageCount(totalEntryCount: Int): Int {
    if (totalEntryCount <= 0) return 1
    return ((totalEntryCount - 1) / REMOTE_BROWSER_PAGE_SIZE) + 1
}

internal fun visibleRemoteBrowserEntries(
    entries: List<FileTransferHelper.RemoteEntry>,
    expanded: Boolean,
    pageIndex: Int
): List<FileTransferHelper.RemoteEntry> {
    if (!expanded) return entries.take(REMOTE_BROWSER_COLLAPSED_LIMIT)
    val safePage = pageIndex.coerceIn(0, remoteBrowserPageCount(entries.size) - 1)
    return entries
        .drop(safePage * REMOTE_BROWSER_PAGE_SIZE)
        .take(REMOTE_BROWSER_PAGE_SIZE)
}

internal fun remoteBrowserRangeLabel(
    totalEntryCount: Int,
    visibleEntryCount: Int,
    expanded: Boolean,
    pageIndex: Int
): String {
    if (totalEntryCount <= 0) return "暂无远端条目"
    if (!expanded) return "显示前 ${visibleEntryCount.coerceAtMost(totalEntryCount)} / $totalEntryCount 项"
    val safePage = pageIndex.coerceIn(0, remoteBrowserPageCount(totalEntryCount) - 1)
    val start = safePage * REMOTE_BROWSER_PAGE_SIZE + 1
    val end = (start + visibleEntryCount - 1).coerceAtMost(totalEntryCount)
    return "显示第 $start-$end / $totalEntryCount 项"
}

internal fun selectedLocalFilesLabel(uris: List<Uri>): String {
    val singleLabel = uris.firstOrNull()?.lastPathSegment?.substringAfterLast('/')
    return selectedLocalFilesLabel(uris.size, singleLabel)
}

internal fun selectedLocalFilesLabel(uris: List<Uri>, language: AppLanguage): String {
    val singleLabel = uris.firstOrNull()?.lastPathSegment?.substringAfterLast('/')
    return selectedLocalFilesLabel(uris.size, singleLabel, language)
}

internal fun selectedLocalFilesLabel(fileCount: Int, singleFileLabel: String?, language: AppLanguage = AppLanguage.ZH): String {
    return when (fileCount) {
        0 -> language.text("\u9009\u62e9\u672c\u673a\u6587\u4ef6", "Choose local file")
        1 -> singleFileLabel?.takeIf { it.isNotBlank() } ?: language.text("\u5df2\u9009\u62e9 1 \u4e2a\u6587\u4ef6", "1 file selected")
        else -> language.text("\u5df2\u9009\u62e9 $fileCount \u4e2a\u6587\u4ef6", "$fileCount files selected")
    }
}

internal fun formatElapsedSeconds(seconds: Long, language: AppLanguage = AppLanguage.ZH): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val minutes = safeSeconds / 60L
    val remainder = safeSeconds % 60L
    return if (minutes == 0L) {
        language.text("${remainder}秒", "${remainder}s")
    } else {
        language.text("${minutes}分${remainder}秒", "${minutes}m ${remainder}s")
    }
}

internal fun remoteEntryMatchesSelectedPath(entryPath: String, selectedRemotePath: String): Boolean {
    val entry = normalizedRemoteSelectionPath(entryPath) ?: return false
    val selected = normalizedRemoteSelectionPath(selectedRemotePath) ?: return false
    return entry == selected
}

internal fun remoteEntryIsSelected(
    entryPath: String,
    selectedRemotePath: String,
    selectedRemotePaths: Set<String>,
    isDirectory: Boolean = false,
    selectionMode: Boolean = selectedRemotePaths.isNotEmpty()
): Boolean {
    if (selectedRemotePaths.any { selected -> remoteEntryMatchesSelectedPath(entryPath, selected) }) {
        return true
    }
    if (selectionMode || isDirectory) return false
    return remoteEntryMatchesSelectedPath(entryPath, selectedRemotePath)
}

private fun normalizedRemoteSelectionPath(path: String): String? {
    val normalized = path.trim().replace('\\', '/')
    if (normalized.isBlank()) return null
    val withoutTrailingSlash = normalized.trimEnd('/')
    return withoutTrailingSlash.ifBlank { normalized }
}
