package com.quickssh.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.quickssh.app.R
import com.quickssh.app.data.PINNED_FOLDER_ID
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshFolder
import com.quickssh.app.data.SshServerGroup
import com.quickssh.app.data.filterSshConfigs
import com.quickssh.app.data.groupedSshServers
import com.quickssh.app.data.serverNodeLabel
import com.quickssh.app.data.workspaceLabel
import com.quickssh.app.utils.DiscoveredSshHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshListScreen(
    configs: List<SshConfig>,
    bottomBar: @Composable () -> Unit,
    onAddClicked: () -> Unit,
    onEditServerClicked: (SshServerGroup) -> Unit,
    onAddWorkspaceClicked: (SshConfig) -> Unit,
    onConnectClicked: (SshConfig) -> Unit,
    onEditClicked: (SshConfig) -> Unit,
    onCopyClicked: (SshConfig) -> Unit,
    onDeleteClicked: (SshConfig) -> Unit,
    onReorderServers: (List<Long>) -> Unit,
    onReorderWorkspaces: (Long, List<Long>) -> Unit,
    onImportDiscoveredHost: (DiscoveredSshHost) -> Unit = {},
    folders: List<SshFolder> = emptyList(),
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (Long, String) -> Unit = { _, _ -> },
    onDeleteFolder: (Long) -> Unit = {},
    onSetWorkspaceFolder: (Long, Long?) -> Unit = { _, _ -> }
) {
    val language = LocalQuickSshLanguage.current
    var searchQuery by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<SshFolder?>(null) }
    var folderToDelete by remember { mutableStateOf<SshFolder?>(null) }
    var workspaceForFolderPicker by remember { mutableStateOf<SshConfig?>(null) }
    val filteredConfigs by remember(configs, searchQuery) { derivedStateOf { filterSshConfigs(configs, searchQuery) } }
    val groups by remember(filteredConfigs) { derivedStateOf { groupedSshServers(filteredConfigs) } }
    val groupByKey = remember(groups) { groups.associateBy { it.key } }
    val orderedGroupKeys = rememberSyncedOrder(groups.map { it.key })
    val reorderEnabled = isEditMode && searchQuery.isBlank()
    val groupSpacingPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val groupReorderState = remember(groupSpacingPx) { VerticalDragReorderState<String>(groupSpacingPx) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    val folderNameById = remember(folders) { folders.associate { it.id to it.name } }
    val workspacesByFolderId = remember(configs) {
        configs.filter { it.folderId != null }.groupBy { it.folderId!! }
    }

    Scaffold(
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = language.text("QuickSSH 主机", "QuickSSH Hosts"),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = language.text("服务器与工作区", "Servers and workspaces"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                FeedbackIconButton(
                    drawableResId = R.drawable.ic_folder,
                    contentDescription = language.text("新建文件夹", "New folder"),
                    onClick = { showCreateFolderDialog = true },
                    enabled = configs.isNotEmpty(),
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Add,
                    contentDescription = language.text("添加服务器", "Add server"),
                    onClick = onAddClicked,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { showDevicesDialog = true },
                    label = {
                        Text(language.text("主机一览", "Hosts"))
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                if (configs.isNotEmpty()) {
                    FilterChip(
                        selected = isEditMode,
                        onClick = { isEditMode = !isEditMode },
                        label = {
                            Text(
                                text = if (isEditMode) language.text("完成", "Done") else language.text("排序", "Reorder")
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Menu,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (configs.isNotEmpty()) {
                    Text(
                        text = language.text("${configs.size} 个配置", "${configs.size} profiles"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isEditMode) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = language.text(
                                "编辑排序模式已开启：拖动右侧手柄可调整卡片或工作区顺序",
                                "Reorder mode active: drag handles on the right to reorder"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (configs.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(language.text("搜索主机、工作区或路径", "Search hosts, workspaces, paths")) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            if (searchQuery.isBlank() && configs.isNotEmpty() && configs.none { it.isLocalSession || it.authType == "LOCAL" }) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = language.text("✨ 新增：本地终端模式", "✨ New: Local Terminal Mode"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = language.text("无需联网，直接调用手机 Termux 或系统 Shell 与 AI 交互", "Run local Termux or system shell & AI without network"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FeedbackButton(
                            onClick = onAddClicked,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(language.text("快速开启", "Try Now"))
                        }
                    }
                }
            }

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = language.text("还没有保存的服务器。", "No saved servers yet."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        FeedbackButton(onClick = onAddClicked) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = language.text("添加服务器", "Add server"),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            } else if (filteredConfigs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = language.text("没有匹配的主机或工作区", "No matching hosts or workspaces"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (searchQuery.isBlank()) {
                        // 1. "置顶" folder card (always pinned to top)
                        val pinnedWorkspaces = workspacesByFolderId[PINNED_FOLDER_ID].orEmpty()
                        item(key = "folder:pinned", contentType = "folder") {
                            val isExpanded = expandedFolders["pinned"] ?: false
                            SshFolderCard(
                                title = language.text("置顶", "Pinned"),
                                workspaces = pinnedWorkspaces,
                                expanded = isExpanded,
                                isPinned = true,
                                onToggleExpanded = { expandedFolders["pinned"] = !isExpanded },
                                onConnectWorkspace = onConnectClicked,
                                onEditWorkspace = onEditClicked,
                                onCopyWorkspace = onCopyClicked,
                                onDeleteWorkspace = onDeleteClicked,
                                onFolderWorkspaceClicked = { workspaceForFolderPicker = it },
                                onRemoveFromFolder = { onSetWorkspaceFolder(it.id, null) }
                            )
                        }

                        // 2. Custom folders
                        items(folders, key = { "folder:${it.id}" }, contentType = { "folder" }) { folder ->
                            val folderWorkspaces = workspacesByFolderId[folder.id].orEmpty()
                            val isExpanded = expandedFolders["folder:${folder.id}"] ?: false
                            SshFolderCard(
                                title = folder.name,
                                workspaces = folderWorkspaces,
                                expanded = isExpanded,
                                isPinned = false,
                                onToggleExpanded = { expandedFolders["folder:${folder.id}"] = !isExpanded },
                                onRenameFolder = { folderToRename = folder },
                                onDeleteFolder = { folderToDelete = folder },
                                onConnectWorkspace = onConnectClicked,
                                onEditWorkspace = onEditClicked,
                                onCopyWorkspace = onCopyClicked,
                                onDeleteWorkspace = onDeleteClicked,
                                onFolderWorkspaceClicked = { workspaceForFolderPicker = it },
                                onRemoveFromFolder = { onSetWorkspaceFolder(it.id, null) }
                            )
                        }
                    }

                    items(orderedGroupKeys, key = { it }, contentType = { "server" }) { groupKey ->
                        val group = groupByKey[groupKey] ?: return@items
                        val isExpanded = expandedGroups[group.key] ?: false
                        val isDragging = reorderEnabled && groupReorderState.isDragging(groupKey)
                        val placementAnimation = if (reorderEnabled) {
                            rememberPlacementAnimation(
                                reorderState = groupReorderState,
                                key = groupKey,
                                isDragging = isDragging
                            )
                        } else {
                            null
                        }
                        SshServerNodeCard(
                            group = group,
                            expanded = isExpanded,
                            isEditMode = isEditMode,
                            folderNameById = folderNameById,
                            modifier = if (reorderEnabled) {
                                Modifier
                                    .onSizeChanged { groupReorderState.onMeasured(groupKey, it.height) }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = groupReorderState.placementOffset(groupKey) +
                                            (placementAnimation?.value ?: 0f) +
                                            if (isDragging) groupReorderState.dragOffsetPx else 0f
                                        alpha = if (isDragging) 0.96f else 1f
                                    }
                            } else {
                                Modifier
                            },
                            reorderHandle = {
                                ReorderHandle(
                                    enabled = reorderEnabled,
                                    contentDescription = "Reorder server",
                                    onDragStart = { groupReorderState.startDrag(groupKey) },
                                    onDrag = { groupReorderState.dragBy(it, orderedGroupKeys) },
                                    onDragEnd = {
                                        finishDragReorder(orderedGroupKeys, groupReorderState) { orderedKeys ->
                                            val orderedServerIds = orderedKeys.mapNotNull { key ->
                                                groupByKey[key]?.workspaces?.firstOrNull()?.serverNodeId
                                            }
                                            onReorderServers(orderedServerIds)
                                        }
                                    }
                                )
                            },
                            reorderEnabled = reorderEnabled,
                            onToggleExpanded = { expandedGroups[group.key] = !isExpanded },
                            onEditServer = { onEditServerClicked(group) },
                            onAddWorkspace = { onAddWorkspaceClicked(group.workspaces.first()) },
                            onConnectWorkspace = onConnectClicked,
                            onEditWorkspace = onEditClicked,
                            onCopyWorkspace = onCopyClicked,
                            onDeleteWorkspace = onDeleteClicked,
                            onReorderWorkspaces = onReorderWorkspaces,
                            onFolderWorkspaceClicked = { workspaceForFolderPicker = it }
                        )
                    }
                }
            }
        }
    }

    if (showDevicesDialog) {
        SshDevicesOverviewDialog(
            configs = configs,
            language = language,
            onDismiss = { showDevicesDialog = false },
            onConnectConfig = { configId ->
                showDevicesDialog = false
                configs.firstOrNull { it.id == configId }?.let { onConnectClicked(it) }
            },
            onImportAndConnect = { device ->
                showDevicesDialog = false
                onImportDiscoveredHost(device)
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            language = language,
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                showCreateFolderDialog = false
                onCreateFolder(name)
            }
        )
    }

    folderToRename?.let { folder ->
        RenameFolderDialog(
            initialName = folder.name,
            language = language,
            onDismiss = { folderToRename = null },
            onConfirm = { newName ->
                val id = folder.id
                folderToRename = null
                onRenameFolder(id, newName)
            }
        )
    }

    folderToDelete?.let { folder ->
        DeleteFolderConfirmDialog(
            folderName = folder.name,
            language = language,
            onDismiss = { folderToDelete = null },
            onConfirm = {
                val id = folder.id
                folderToDelete = null
                onDeleteFolder(id)
            }
        )
    }

    workspaceForFolderPicker?.let { workspace ->
        FolderPickerDialog(
            workspace = workspace,
            folders = folders,
            language = language,
            onDismiss = { workspaceForFolderPicker = null },
            onSelectFolder = { targetFolderId ->
                val workspaceId = workspace.id
                workspaceForFolderPicker = null
                onSetWorkspaceFolder(workspaceId, targetFolderId)
            },
            onNewFolder = {
                workspaceForFolderPicker = null
                showCreateFolderDialog = true
            }
        )
    }
}

@Composable
private fun SshServerNodeCard(
    group: SshServerGroup,
    expanded: Boolean,
    isEditMode: Boolean,
    modifier: Modifier = Modifier,
    reorderEnabled: Boolean,
    reorderHandle: @Composable () -> Unit,
    onToggleExpanded: () -> Unit,
    onEditServer: () -> Unit,
    onAddWorkspace: () -> Unit,
    onConnectWorkspace: (SshConfig) -> Unit,
    onEditWorkspace: (SshConfig) -> Unit,
    onCopyWorkspace: (SshConfig) -> Unit,
    onDeleteWorkspace: (SshConfig) -> Unit,
    onReorderWorkspaces: (Long, List<Long>) -> Unit,
    folderNameById: Map<Long, String> = emptyMap(),
    onFolderWorkspaceClicked: (SshConfig) -> Unit = {}
) {
    val language = LocalQuickSshLanguage.current
    val workspaceSpacingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val workspaceDragState = remember(group.key, workspaceSpacingPx) {
        VerticalDragReorderState<Long>(workspaceSpacingPx)
    }
    val orderedWorkspaceIds = rememberSyncedOrder(group.workspaces.map { it.id })
    val workspaceById = remember(group.workspaces) { group.workspaces.associateBy { it.id } }
    val containerColor = if (expanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleExpanded() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse server" else "Expand server"
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = group.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val isLocal = group.workspaces.firstOrNull()?.let { it.isLocalSession || it.authType == "LOCAL" } == true
                            if (isLocal) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(start = 2.dp)
                                ) {
                                    Text(
                                        text = language.text("本地终端", "Local"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        val subtitle = if (group.displayName != group.hostLabel) {
                            "${group.hostLabel} · ${group.workspaces.size} workspace${if (group.workspaces.size == 1) "" else "s"}"
                        } else {
                            "${group.workspaces.size} workspace${if (group.workspaces.size == 1) "" else "s"}"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                FeedbackIconButton(
                    imageVector = Icons.Default.Edit,
                    contentDescription = language.text("编辑服务器配置", "Edit server configuration"),
                    onClick = onEditServer,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Add,
                    contentDescription = language.text("添加工作区", "Add workspace"),
                    onClick = onAddWorkspace,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                if (isEditMode) {
                    reorderHandle()
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    orderedWorkspaceIds.forEach { workspaceId ->
                        val config = workspaceById[workspaceId] ?: return@forEach
                        key(workspaceId) {
                            val isDragging = reorderEnabled && workspaceDragState.isDragging(workspaceId)
                            val placementAnimation = if (reorderEnabled) {
                                rememberPlacementAnimation(
                                    reorderState = workspaceDragState,
                                    key = workspaceId,
                                    isDragging = isDragging
                                )
                            } else {
                                null
                            }
                            val folderBadge = when (config.folderId) {
                                PINNED_FOLDER_ID -> language.text("置顶", "Pinned")
                                null -> null
                                else -> folderNameById[config.folderId]
                            }
                            WorkspaceRow(
                                config = config,
                                isEditMode = isEditMode,
                                modifier = if (reorderEnabled) {
                                    Modifier
                                        .onSizeChanged { workspaceDragState.onMeasured(workspaceId, it.height) }
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = workspaceDragState.placementOffset(workspaceId) +
                                                (placementAnimation?.value ?: 0f) +
                                                if (isDragging) workspaceDragState.dragOffsetPx else 0f
                                            alpha = if (isDragging) 0.96f else 1f
                                        }
                                } else {
                                    Modifier
                                },
                                reorderHandle = {
                                    ReorderHandle(
                                        enabled = reorderEnabled,
                                        contentDescription = "Reorder workspace",
                                        onDragStart = { workspaceDragState.startDrag(workspaceId) },
                                        onDrag = { workspaceDragState.dragBy(it, orderedWorkspaceIds) },
                                        onDragEnd = {
                                            finishDragReorder(orderedWorkspaceIds, workspaceDragState) { orderedIds ->
                                                onReorderWorkspaces(config.serverNodeId, orderedIds)
                                            }
                                        }
                                    )
                                },
                                onConnect = { onConnectWorkspace(config) },
                                onEdit = { onEditWorkspace(config) },
                                onCopy = { onCopyWorkspace(config) },
                                onDelete = { onDeleteWorkspace(config) },
                                onFolderClicked = { onFolderWorkspaceClicked(config) },
                                folderBadge = folderBadge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    config: SshConfig,
    isEditMode: Boolean,
    modifier: Modifier = Modifier,
    reorderHandle: @Composable () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onFolderClicked: (() -> Unit)? = null,
    onRemoveFromFolder: (() -> Unit)? = null,
    hostLabel: String? = null,
    folderBadge: String? = null
) {
    val language = LocalQuickSshLanguage.current
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null) { onConnect() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if (!hostLabel.isNullOrBlank()) {
                        Text(
                            text = hostLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = config.workspaceLabel(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!folderBadge.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (config.folderId == PINNED_FOLDER_ID) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            ) {
                                Text(
                                    text = folderBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (config.folderId == PINNED_FOLDER_ID) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    if (!config.workDirectory.isNullOrBlank()) {
                        Text(
                            text = config.workDirectory,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (!config.postConnectCommand.isNullOrBlank()) {
                        Text(
                            text = config.postConnectCommand,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                if (isEditMode) {
                    reorderHandle()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onRemoveFromFolder != null) {
                    FeedbackIconButton(
                        imageVector = Icons.Default.Close,
                        contentDescription = language.text("移出文件夹", "Remove from folder"),
                        onClick = onRemoveFromFolder,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                if (onFolderClicked != null) {
                    FeedbackIconButton(
                        drawableResId = R.drawable.ic_folder,
                        contentDescription = language.text("加入/更改文件夹", "Folder"),
                        onClick = onFolderClicked,
                        modifier = Modifier.size(36.dp),
                        tint = if (config.folderId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                FeedbackIconButton(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit workspace",
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                FeedbackIconButton(
                    drawableResId = R.drawable.ic_content_copy,
                    contentDescription = "Copy workspace",
                    onClick = onCopy,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete workspace",
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Connect workspace",
                    onClick = onConnect,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SshFolderCard(
    title: String,
    workspaces: List<SshConfig>,
    expanded: Boolean,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
    onToggleExpanded: () -> Unit,
    onRenameFolder: (() -> Unit)? = null,
    onDeleteFolder: (() -> Unit)? = null,
    onConnectWorkspace: (SshConfig) -> Unit,
    onEditWorkspace: (SshConfig) -> Unit,
    onCopyWorkspace: (SshConfig) -> Unit,
    onDeleteWorkspace: (SshConfig) -> Unit,
    onFolderWorkspaceClicked: (SshConfig) -> Unit,
    onRemoveFromFolder: (SshConfig) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    val containerColor = if (isPinned) {
        if (expanded) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
    } else {
        if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleExpanded() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse folder" else "Expand folder"
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isPinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isPinned) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(start = 2.dp)
                                ) {
                                    Text(
                                        text = language.text("永远置顶", "Pinned"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = language.text(
                                "${workspaces.size} 个工作区",
                                "${workspaces.size} workspace${if (workspaces.size == 1) "" else "s"}"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onRenameFolder != null) {
                    FeedbackIconButton(
                        imageVector = Icons.Default.Edit,
                        contentDescription = language.text("重命名文件夹", "Rename folder"),
                        onClick = onRenameFolder,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                if (onDeleteFolder != null) {
                    FeedbackIconButton(
                        imageVector = Icons.Default.Delete,
                        contentDescription = language.text("删除文件夹", "Delete folder"),
                        onClick = onDeleteFolder,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (workspaces.isEmpty()) {
                        Text(
                            text = if (isPinned) {
                                language.text(
                                    "置顶文件夹暂无工作区。点击任意工作区卡片上的文件夹图标即可将其置顶收藏。",
                                    "Pinned folder is empty. Tap the folder icon on any workspace card to pin it here."
                                )
                            } else {
                                language.text(
                                    "文件夹暂无工作区。点击任意工作区卡片上的文件夹图标即可将其收纳至此。",
                                    "Folder is empty. Tap the folder icon on any workspace card to collect it here."
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    } else {
                        workspaces.forEach { config ->
                            key(config.id) {
                                WorkspaceRow(
                                    config = config,
                                    isEditMode = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    reorderHandle = {},
                                    onConnect = { onConnectWorkspace(config) },
                                    onEdit = { onEditWorkspace(config) },
                                    onCopy = { onCopyWorkspace(config) },
                                    onDelete = { onDeleteWorkspace(config) },
                                    onFolderClicked = { onFolderWorkspaceClicked(config) },
                                    onRemoveFromFolder = { onRemoveFromFolder(config) },
                                    hostLabel = config.serverDisplayName?.takeIf { it.isNotBlank() } ?: config.serverNodeLabel()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(language.text("新建文件夹", "New Folder")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = language.text("文件夹可跨主机收容工作区", "Folders can collect workspaces across hosts"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(language.text("文件夹名称", "Folder Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(language.text("创建", "Create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(language.text("取消", "Cancel"))
            }
        }
    )
}

@Composable
private fun RenameFolderDialog(
    initialName: String,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(language.text("重命名文件夹", "Rename Folder")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(language.text("文件夹名称", "Folder Name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(language.text("保存", "Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(language.text("取消", "Cancel"))
            }
        }
    )
}

@Composable
private fun DeleteFolderConfirmDialog(
    folderName: String,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(language.text("删除文件夹", "Delete Folder")) },
        text = {
            Text(
                text = language.text(
                    "确定要删除文件夹「$folderName」吗？其中的工作区不会被删除，将保留在原主机下。",
                    "Delete folder '$folderName'? Workspaces inside will not be deleted."
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = language.text("删除", "Delete"),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(language.text("取消", "Cancel"))
            }
        }
    )
}

@Composable
private fun FolderPickerDialog(
    workspace: SshConfig,
    folders: List<SshFolder>,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSelectFolder: (Long?) -> Unit,
    onNewFolder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(language.text("收纳工作区", "Organize Workspace"))
                Text(
                    text = workspace.workspaceLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isPinned = workspace.folderId == PINNED_FOLDER_ID
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPinned) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFolder(PINNED_FOLDER_ID) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = language.text("置顶（永远置顶）", "Pinned (Always on top)"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (isPinned) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                folders.forEach { folder ->
                    val isSelected = workspace.folderId == folder.id
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectFolder(folder.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (workspace.folderId != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectFolder(null) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = language.text("移出文件夹", "Remove from folder"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNewFolder() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = language.text("+ 新建文件夹", "+ New folder"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(language.text("取消", "Cancel"))
            }
        }
    )
}

@Composable
private fun ReorderHandle(
    enabled: Boolean,
    contentDescription: String,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val draggableState = rememberDraggableState { delta -> onDrag(delta) }

    Box(
        modifier = modifier
            .size(36.dp)
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                enabled = enabled,
                onDragStarted = { onDragStart() },
                onDragStopped = { onDragEnd() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
        )
    }
}

private class VerticalDragReorderState<Key>(private val spacingPx: Int) {
    var draggingKey by mutableStateOf<Key?>(null)
        private set
    var dragOffsetPx by mutableFloatStateOf(0f)
        private set
    var reflowVersion by mutableIntStateOf(0)
        private set
    private val itemHeights = mutableStateMapOf<Key, Int>()
    private val placementOffsets = mutableStateMapOf<Key, Float>()
    private var geometryDirty = true
    private var cachedOrderIdentity: Any? = null
    private var cachedCenters: List<Float> = emptyList()

    fun onMeasured(key: Key, heightPx: Int) {
        if (heightPx > 0 && itemHeights[key] != heightPx) {
            itemHeights[key] = heightPx
            geometryDirty = true
        }
    }

    fun startDrag(key: Key) {
        draggingKey = key
        dragOffsetPx = 0f
    }

    fun dragBy(deltaY: Float, orderedKeys: MutableList<Key>) {
        if (draggingKey == null) return
        dragOffsetPx += deltaY
        reorderIfNeededInternal(orderedKeys)
    }

    fun isDragging(key: Key): Boolean {
        return draggingKey == key
    }

    private fun targetIndex(orderedKeys: MutableList<Key>): Int? {
        val key = draggingKey ?: return null
        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex < 0) return null

        val centers = itemCenters(orderedKeys)
        val draggedCenter = centers[currentIndex] + dragOffsetPx

        var target = currentIndex
        if (dragOffsetPx > 0f) {
            for (index in currentIndex + 1 until centers.size) {
                if (draggedCenter >= centers[index]) {
                    target = index
                } else {
                    break
                }
            }
        } else if (dragOffsetPx < 0f) {
            for (index in currentIndex - 1 downTo 0) {
                if (draggedCenter <= centers[index]) {
                    target = index
                } else {
                    break
                }
            }
        }
        return target
    }

    fun placementOffset(key: Key): Float {
        return placementOffsets[key] ?: 0f
    }

    fun clearPlacementOffset(key: Key) {
        placementOffsets.remove(key)
    }

    private fun reorderIfNeededInternal(orderedKeys: MutableList<Key>) {
        val key = draggingKey ?: return
        val currentIndex = orderedKeys.indexOf(key)
        val targetIndex = targetIndex(orderedKeys) ?: return
        if (currentIndex < 0 || targetIndex == currentIndex) return

        val centers = itemCenters(orderedKeys)
        val reordered = moveItem(orderedKeys, currentIndex, targetIndex)
        val reorderedCenters = computeItemCenters(reordered)

        placementOffsets.clear()
        reordered.forEachIndexed { index, reorderedKey ->
            if (reorderedKey == key) {
                placementOffsets[reorderedKey] = 0f
            } else {
                val previousIndex = orderedKeys.indexOf(reorderedKey)
                placementOffsets[reorderedKey] = if (previousIndex >= 0) {
                    centers[previousIndex] - reorderedCenters[index]
                } else {
                    0f
                }
            }
        }
        orderedKeys.clear()
        orderedKeys.addAll(reordered)
        cachedOrderIdentity = orderedKeys
        cachedCenters = reorderedCenters
        geometryDirty = false

        // Keep the dragged card under the finger after the surrounding items reflow.
        dragOffsetPx = adjustedDragOffsetAfterReorder(
            previousOffset = dragOffsetPx,
            previousCenters = centers,
            reorderedCenters = reorderedCenters,
            fromIndex = currentIndex,
            targetIndex = targetIndex
        )
        reflowVersion++
    }

    private fun itemCenters(orderedKeys: MutableList<Key>): List<Float> {
        if (geometryDirty || cachedOrderIdentity !== orderedKeys) {
            cachedCenters = computeItemCenters(orderedKeys)
            cachedOrderIdentity = orderedKeys
            geometryDirty = false
        }
        return cachedCenters
    }

    private fun computeItemCenters(orderedKeys: List<Key>): List<Float> {
        val fallbackHeight = itemHeights[draggingKey] ?: itemHeights.values.firstOrNull() ?: 1
        var top = 0f
        return orderedKeys.map { orderedKey ->
            val height = (itemHeights[orderedKey] ?: fallbackHeight).coerceAtLeast(1)
            val center = top + (height / 2f)
            top += height + spacingPx
            center
        }
    }

    fun reset() {
        draggingKey = null
        dragOffsetPx = 0f
    }
}

@Composable
private fun <Key> rememberPlacementAnimation(
    reorderState: VerticalDragReorderState<Key>,
    key: Key,
    isDragging: Boolean
): Animatable<Float, AnimationVector1D> {
    val placementAnimation = remember { Animatable(0f) }
    LaunchedEffect(reorderState.reflowVersion, isDragging) {
        val pendingOffset = reorderState.placementOffset(key)
        if (isDragging || pendingOffset == 0f) {
            placementAnimation.snapTo(0f)
        } else {
            placementAnimation.snapTo(0f)
            placementAnimation.animateTo(
                targetValue = -pendingOffset,
                animationSpec = tween(
                    durationMillis = 180,
                    easing = FastOutSlowInEasing
                )
            )
            reorderState.clearPlacementOffset(key)
            placementAnimation.snapTo(0f)
        }
    }
    return placementAnimation
}

@Composable
private fun <T> rememberSyncedOrder(sourceKeys: List<T>): SnapshotStateList<T> {
    return remember(sourceKeys) {
        mutableStateListOf<T>().apply {
            addAll(sourceKeys)
        }
    }
}

private fun <T> finishDragReorder(
    orderedKeys: SnapshotStateList<T>,
    reorderState: VerticalDragReorderState<T>,
    onOrderChanged: (List<T>) -> Unit
) {
    if (reorderState.draggingKey == null) return reorderState.reset()
    onOrderChanged(orderedKeys.toList())
    reorderState.reset()
}

internal fun <T> moveItem(order: List<T>, fromIndex: Int, targetIndex: Int): List<T> {
    if (fromIndex !in order.indices || targetIndex !in order.indices || fromIndex == targetIndex) {
        return order.toList()
    }
    return order.toMutableList().apply {
        add(targetIndex, removeAt(fromIndex))
    }
}

internal fun adjustedDragOffsetAfterReorder(
    previousOffset: Float,
    previousCenters: List<Float>,
    reorderedCenters: List<Float>,
    fromIndex: Int,
    targetIndex: Int
): Float {
    if (fromIndex !in previousCenters.indices || targetIndex !in reorderedCenters.indices) {
        return previousOffset
    }
    return previousOffset + previousCenters[fromIndex] - reorderedCenters[targetIndex]
}
