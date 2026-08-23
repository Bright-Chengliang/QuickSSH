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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

            TransferSection(title = "下载") {
                OutlinedTextField(
                    value = downloadRemotePath,
                    onValueChange = onDownloadRemotePathChange,
                    label = { Text("下载远端路径") },
                    placeholder = { Text(selectedConfig?.workDirectory ?: "默认使用 SSH 登录目录") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ResponsiveOutlinedButton(
                        onClick = onBrowseRemote,
                        enabled = selectedConfig != null && !isTransferring && !isBrowsingRemote,
                        modifier = Modifier.weight(1f),
                        text = if (isBrowsingRemote) "读取中" else "浏览远端"
                    )
                    ResponsiveOutlinedButton(
                        onClick = onRemoteParentClicked,
                        enabled = selectedConfig != null && !isTransferring,
                        modifier = Modifier.weight(1f),
                        text = "上级目录"
                    )
                }
                ResponsiveOutlinedButton(
                    onClick = onChooseDownloadDirectory,
                    enabled = !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = transferDownloadDirectoryActionLabel(downloadDirectoryLabel)
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
                    title = "下载冲突处理",
                    selectedPolicy = downloadConflictPolicy,
                    enabled = !isTransferring,
                    onPolicySelected = onDownloadConflictPolicyChange
                )
                ResponsiveButton(
                    onClick = onDownloadClicked,
                    enabled = selectedConfig != null && downloadRemotePath.isNotBlank() && !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = "下载"
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
            )

            TransferSection(title = "上传") {
                OutlinedTextField(
                    value = uploadRemotePath,
                    onValueChange = onUploadRemotePathChange,
                    label = { Text("上传目标路径") },
                    placeholder = { Text(selectedConfig?.workDirectory ?: "默认使用 SSH 登录目录") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ResponsiveOutlinedButton(
                    onClick = onPickLocalFile,
                    enabled = !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = selectedLocalFilesLabel(selectedLocalUris)
                )
                UploadConflictPolicySelector(
                    title = "上传冲突处理",
                    selectedPolicy = uploadConflictPolicy,
                    enabled = !isTransferring,
                    onPolicySelected = onUploadConflictPolicyChange
                )
                ResponsiveButton(
                    onClick = onUploadClicked,
                    enabled = selectedConfig != null && selectedLocalUris.isNotEmpty() && !isTransferring,
                    modifier = Modifier.fillMaxWidth(),
                    text = "上传"
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
                        text = if (queuePaused) "队列已暂停：$queuedCount 个等待" else "队列：$queuedCount 个等待",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    FeedbackTextButton(onClick = if (queuePaused) onResumeQueue else onPauseQueue) {
                        Text(if (queuePaused) "继续" else "暂停")
                    }
                    FeedbackTextButton(onClick = onClearQueue, enabled = queuedCount > 0) { Text("取消等待") }
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
                            Text(if (recentTasksExpanded) "收起" else "展开")
                        }
                    }
                }

                Text(
                    text = recentTransferTaskRangeLabel(
                        totalTaskCount = tasks.size,
                        visibleTaskCount = visibleTasks.size,
                        expanded = recentTasksExpanded,
                        pageIndex = currentPage
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
                            Text("上一页")
                        }
                        FeedbackTextButton(
                            enabled = currentPage < pageCount - 1,
                            onClick = { recentTasksPage = (currentPage + 1).coerceAtMost(pageCount - 1) }
                        ) {
                            Text("下一页")
                        }
                    }
                }
            }
        }
    }

    if (showRemoteDownloadPlanSlowWarning) {
        AlertDialog(
            onDismissRequest = onKeepPreparingRemoteDownloadPlan,
            title = { Text("下载计划耗时较久") },
            text = {
                Text(
                    "创建下载计划已等待 ${formatElapsedSeconds(remoteDownloadPlanElapsedSeconds)}，目录可能过大或网络响应较慢。可以取消后减少下载范围，或继续等待。"
                )
            },
            confirmButton = {
                FeedbackTextButton(onClick = onKeepPreparingRemoteDownloadPlan) {
                    Text("继续等待")
                }
            },
            dismissButton = {
                FeedbackTextButton(onClick = onCancelRemoteDownloadPlan) {
                    Text("取消计划")
                }
            }
        )
    }

    if (remoteDownloadPlanConfirmation != null) {
        AlertDialog(
            onDismissRequest = onCancelPendingRemoteDownloadPlan,
            title = { Text("确认大目录下载") },
            text = {
                Text(
                    "已扫描到 ${remoteDownloadPlanConfirmation.fileCount} 个文件、${remoteDownloadPlanConfirmation.directoryCount} 个文件夹。继续后会创建本地目录树并把文件加入下载队列；如果数量不符合预期，可以取消后缩小下载范围。"
                )
            },
            confirmButton = {
                FeedbackTextButton(onClick = onConfirmRemoteDownloadPlan) {
                    Text("继续下载")
                }
            },
            dismissButton = {
                FeedbackTextButton(onClick = onCancelPendingRemoteDownloadPlan) {
                    Text("取消")
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
    val mode = transferTopProgressMode(
        isTransferring = isTransferring,
        progressText = progressText,
        progressFraction = progressFraction
    )
    Column {
        TopAppBar(
            title = { Text("\u6587\u4ef6\u4f20\u8f93") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
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
                    label = { Text(transferConflictPolicyLabel(policy), maxLines = 1) }
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
            title = { Text("\u4efb\u52a1\u5c5e\u6027") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\u6587\u4ef6\uff1a${task.fileName}")
                    Text("\u65b9\u5411\uff1a${task.direction}")
                    Text("\u670d\u52a1\u5668\uff1a${task.serverNodeName}")
                    Text("\u5de5\u4f5c\u533a\uff1a${task.workspaceName}")
                    Text("\u72b6\u6001\uff1a${task.status}")
                    if (task.remotePath.isNotBlank()) Text("\u8fdc\u7aef\uff1a${task.remotePath}")
                    task.localUri?.let { Text("\u672c\u5730\uff1a$it") }
                    if (task.detail.isNotBlank()) Text(task.detail)
                }
            },
            confirmButton = {
                FeedbackTextButton(onClick = {
                    showActions = false
                    if (actionable) onTaskClicked(task)
                }) { Text(if (actionable) "\u6253\u5f00" else "\u5173\u95ed") }
            },
            dismissButton = {
                if (actionable) {
                    Row {
                        FeedbackTextButton(onClick = {
                            showActions = false
                            onTaskDeleted(task)
                        }) { Text("\u5220\u9664\u8bb0\u5f55") }
                        FeedbackTextButton(onClick = { showActions = false }) { Text("\u5173\u95ed") }
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
                text = if (selectedCount == 0) "多选模式" else "已选择 $selectedCount 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            FeedbackTextButton(onClick = onDownloadSelected, enabled = selectedCount > 0) {
                Text("下载选中")
            }
            FeedbackTextButton(onClick = onSelectionCleared, enabled = selectedCount > 0 || multiSelectEnabled) {
                Text("清除")
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
                text = "正在创建下载计划，已等待 ${formatElapsedSeconds(downloadPlanElapsedSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f)
            )
            FeedbackTextButton(onClick = onCancelDownloadPlan) {
                Text("取消")
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
            Text(if (expanded) "收起" else "展开")
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
                Text("上一页")
            }
            FeedbackTextButton(
                enabled = pageIndex < pageCount - 1,
                onClick = onNextPage
            ) {
                Text("下一页")
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackTextButton(onClick = onSelectClicked, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected) "取消选择" else "选中此项")
                }
                FeedbackTextButton(onClick = onMultiSelectClicked, modifier = Modifier.fillMaxWidth()) {
                    Text(if (multiSelectEnabled) "加入/移出多选" else "多选")
                }
                if (entry.isDirectory) {
                    FeedbackTextButton(onClick = onOpenDirectoryClicked, modifier = Modifier.fillMaxWidth()) {
                        Text("进入文件夹")
                    }
                }
            }
        },
        confirmButton = {
            FeedbackTextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ServerSelector(configs: List<SshConfig>, selectedConfig: SshConfig?, onConfigSelected: (SshConfig) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val groups by remember(configs) { derivedStateOf { groupedSshServers(configs) } }
    Box(modifier = Modifier.fillMaxWidth()) {
        ResponsiveOutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            text = selectedConfig?.transferContextLabel() ?: "\u9009\u62e9\u670d\u52a1\u5668 / \u5de5\u4f5c\u533a",
            trailingIcon = { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "\u5c55\u5f00") }
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
                DropdownMenuItem(text = { Text("\u6682\u65e0\u670d\u52a1\u5668\u914d\u7f6e") }, onClick = { expanded = false })
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
    return when (policy) {
        FileTransferHelper.UploadConflictPolicy.RENAME -> "自动改名"
        FileTransferHelper.UploadConflictPolicy.OVERWRITE -> "覆盖"
        FileTransferHelper.UploadConflictPolicy.FAIL -> "失败"
    }
}

internal fun transferDownloadDirectoryActionLabel(downloadDirectoryLabel: String): String {
    val label = downloadDirectoryLabel.trim()
    return if (label.isBlank() || label.startsWith("Not set", ignoreCase = true)) {
        "选择保存目录"
    } else {
        "保存到：$label"
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
    pageIndex: Int
): String {
    if (totalTaskCount <= 0) return "暂无任务"
    if (!expanded) return "显示最近 ${visibleTaskCount.coerceAtMost(totalTaskCount)} / $totalTaskCount 个任务"
    val safePage = pageIndex.coerceIn(0, recentTransferTaskPageCount(totalTaskCount) - 1)
    val start = safePage * RECENT_TRANSFER_TASK_PAGE_SIZE + 1
    val end = (start + visibleTaskCount - 1).coerceAtMost(totalTaskCount)
    return "显示第 $start-$end / $totalTaskCount 个任务"
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

internal fun selectedLocalFilesLabel(fileCount: Int, singleFileLabel: String?): String {
    return when (fileCount) {
        0 -> "\u9009\u62e9\u672c\u673a\u6587\u4ef6"
        1 -> singleFileLabel?.takeIf { it.isNotBlank() } ?: "\u5df2\u9009\u62e9 1 \u4e2a\u6587\u4ef6"
        else -> "\u5df2\u9009\u62e9 $fileCount \u4e2a\u6587\u4ef6"
    }
}

internal fun formatElapsedSeconds(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val minutes = safeSeconds / 60L
    val remainder = safeSeconds % 60L
    return if (minutes == 0L) {
        "${remainder}秒"
    } else {
        "${minutes}分${remainder}秒"
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
