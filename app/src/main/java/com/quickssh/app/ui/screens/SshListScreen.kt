package com.quickssh.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshServerGroup
import com.quickssh.app.data.filterSshConfigs
import com.quickssh.app.data.groupedSshServers
import com.quickssh.app.data.workspaceLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshListScreen(
    configs: List<SshConfig>,
    bottomBar: @Composable () -> Unit,
    onAddClicked: () -> Unit,
    onAddWorkspaceClicked: (SshConfig) -> Unit,
    onConnectClicked: (SshConfig) -> Unit,
    onEditClicked: (SshConfig) -> Unit,
    onCopyClicked: (SshConfig) -> Unit,
    onDeleteClicked: (SshConfig) -> Unit,
    onReorderServers: (List<Long>) -> Unit,
    onReorderWorkspaces: (Long, List<Long>) -> Unit
) {
    val language = LocalQuickSshLanguage.current
    var searchQuery by remember { mutableStateOf("") }
    val filteredConfigs by remember(configs, searchQuery) { derivedStateOf { filterSshConfigs(configs, searchQuery) } }
    val groups by remember(filteredConfigs) { derivedStateOf { groupedSshServers(filteredConfigs) } }
    val groupByKey = remember(groups) { groups.associateBy { it.key } }
    val orderedGroupKeys = rememberSyncedOrder(groups.map { it.key })
    val reorderEnabled = searchQuery.isBlank()
    val groupSpacingPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val groupReorderState = remember(groupSpacingPx) { VerticalDragReorderState<String>(groupSpacingPx) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

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
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = language.text("QuickSSH 主机", "QuickSSH Hosts"),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = language.text("服务器与工作区", "Servers and workspaces"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = language.text("${configs.size} 个配置", "${configs.size} profiles"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
                FeedbackIconButton(
                    imageVector = Icons.Default.Add,
                    contentDescription = language.text("添加服务器", "Add server"),
                    onClick = onAddClicked,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
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
                    items(orderedGroupKeys, key = { it }) { groupKey ->
                        val group = groupByKey[groupKey] ?: return@items
                        val isExpanded = expandedGroups[group.key] ?: true
                        val isDragging = groupReorderState.isDragging(groupKey)
                        val placementAnimation = rememberPlacementAnimation(
                            reorderState = groupReorderState,
                            key = groupKey,
                            isDragging = isDragging
                        )
                        SshServerNodeCard(
                            group = group,
                            expanded = isExpanded,
                            modifier = Modifier
                                .onSizeChanged { groupReorderState.onMeasured(groupKey, it.height) }
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = groupReorderState.placementOffset(groupKey) +
                                        placementAnimation.value +
                                        if (isDragging) groupReorderState.dragOffsetPx else 0f
                                    alpha = if (isDragging) 0.96f else 1f
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
                            onAddWorkspace = { onAddWorkspaceClicked(group.workspaces.first()) },
                            onConnectWorkspace = onConnectClicked,
                            onEditWorkspace = onEditClicked,
                            onCopyWorkspace = onCopyClicked,
                            onDeleteWorkspace = onDeleteClicked,
                            onReorderWorkspaces = onReorderWorkspaces
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SshServerNodeCard(
    group: SshServerGroup,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    reorderEnabled: Boolean,
    reorderHandle: @Composable () -> Unit,
    onToggleExpanded: () -> Unit,
    onAddWorkspace: () -> Unit,
    onConnectWorkspace: (SshConfig) -> Unit,
    onEditWorkspace: (SshConfig) -> Unit,
    onCopyWorkspace: (SshConfig) -> Unit,
    onDeleteWorkspace: (SshConfig) -> Unit,
    onReorderWorkspaces: (Long, List<Long>) -> Unit
) {
    val workspaceSpacingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val workspaceDragState = remember(group.key, workspaceSpacingPx) {
        VerticalDragReorderState<Long>(workspaceSpacingPx)
    }
    val orderedWorkspaceIds = rememberSyncedOrder(group.workspaces.map { it.id })
    val workspaceById = remember(group.workspaces) { group.workspaces.associateBy { it.id } }
    val containerColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        label = "serverNodeColor"
    )
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
                        Text(
                            text = group.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${group.workspaces.size} workspace${if (group.workspaces.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                reorderHandle()
                FeedbackIconButton(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add workspace",
                    onClick = onAddWorkspace,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    orderedWorkspaceIds.forEach { workspaceId ->
                        val config = workspaceById[workspaceId] ?: return@forEach
                        key(workspaceId) {
                            val isDragging = workspaceDragState.isDragging(workspaceId)
                            val placementAnimation = rememberPlacementAnimation(
                                reorderState = workspaceDragState,
                                key = workspaceId,
                                isDragging = isDragging
                            )
                            WorkspaceRow(
                            config = config,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { workspaceDragState.onMeasured(workspaceId, it.height) }
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = workspaceDragState.placementOffset(workspaceId) +
                                        placementAnimation.value +
                                        if (isDragging) workspaceDragState.dragOffsetPx else 0f
                                    alpha = if (isDragging) 0.96f else 1f
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
                            onDelete = { onDeleteWorkspace(config) }
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
    modifier: Modifier = Modifier,
    reorderHandle: @Composable () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(interactionSource = interactionSource, indication = null) { onConnect() }
                ) {
                    Text(
                        text = config.workspaceLabel(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                reorderHandle()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
