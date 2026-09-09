package com.quickssh.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickssh.app.R
import com.quickssh.app.data.SshConfig
import com.quickssh.app.utils.AnsiRenderer
import com.quickssh.app.utils.TerminalBuffer
import com.quickssh.app.ui.theme.QuickSshTerminalAccent
import com.quickssh.app.ui.theme.QuickSshTerminalAccentPressed
import com.quickssh.app.ui.theme.QuickSshTerminalBackground
import com.quickssh.app.ui.theme.QuickSshTerminalChrome
import com.quickssh.app.ui.theme.QuickSshTerminalDanger
import com.quickssh.app.ui.theme.QuickSshTerminalDisabled
import com.quickssh.app.ui.theme.QuickSshTerminalKey
import com.quickssh.app.ui.theme.QuickSshTerminalKeyAccent
import com.quickssh.app.ui.theme.QuickSshTerminalKeyContent
import com.quickssh.app.ui.theme.QuickSshTerminalKeySecondary
import com.quickssh.app.ui.theme.QuickSshTerminalMuted
import com.quickssh.app.ui.theme.QuickSshTerminalSelection
import com.quickssh.app.ui.theme.QuickSshTerminalText
import com.quickssh.app.ui.theme.QuickSshConnected
import com.termux.terminal.TerminalSession
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    config: SshConfig,
    termuxSession: TerminalSession? = null,
    logs: List<String>,
    alternateScreen: Boolean,
    applicationCursorKeys: Boolean,
    bracketedPasteMode: Boolean,
    autoWrapEnabled: Boolean,
    onHomeClicked: () -> Unit,
    onLineSend: (String) -> Unit,
    onCodexResumeShortcut: (String) -> Unit = onLineSend,
    onRawInputSend: (String) -> Unit,
    onTerminalResize: (TerminalBuffer.Size) -> Unit,
    quickUploadStatus: String = "",
    pendingInputInsertion: String? = null,
    onPendingInputInsertionConsumed: () -> Unit = {},
    onQuickUploadClicked: () -> Unit = {},
    onHistoryClicked: () -> Unit = {},
    onDisconnectClicked: () -> Unit
) {
    val effectiveAutoWrap = config.terminalWrapEnabled ?: autoWrapEnabled
    var inputCmd by remember { mutableStateOf("") }
    var showControlKeyboard by remember { mutableStateOf(false) }
    var ctrlModifier by remember { mutableStateOf(false) }
    var altModifier by remember { mutableStateOf(false) }
    var commandHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    var copyMode by remember { mutableStateOf(false) }
    var selectedCopyRows by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var lastReportedTerminalSize by remember { mutableStateOf<TerminalBuffer.Size?>(null) }
    val lazyListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val terminalFontSizeSp = terminalDisplayFontSizeSp(config.terminalFontSizeSp)
    val terminalLineHeightSp = terminalDisplayLineHeightSp(terminalFontSizeSp)
    val terminalFontSize = terminalFontSizeSp.sp
    val terminalLineHeight = terminalLineHeightSp.sp
    val terminalOutputTextStyle = remember(terminalFontSize, terminalLineHeight) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = terminalFontSize,
            lineHeight = terminalLineHeight,
            letterSpacing = 0.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }
    fun submitCommand(command: String) {
        if (termuxSession != null) {
            termuxSession.write(command + "\r")
        } else {
            onLineSend(command)
        }
        commandHistory = addCommandToHistory(commandHistory, command)
        historyIndex = -1
        inputCmd = ""
    }

    fun recordCommand(command: String) {
        commandHistory = addCommandToHistory(commandHistory, command)
        historyIndex = -1
        inputCmd = ""
    }

    fun submitShortcut(shortcut: TerminalShortcutCommand) {
        if (shouldRunCodexResumeShortcutAction(shortcut, config.workDirectory)) {
            onCodexResumeShortcut(shortcut.command)
            recordCommand(shortcut.command)
        } else {
            submitCommand(shortcut.command)
        }
    }

    fun recallHistory(nextIndex: Int) {
        historyIndex = nextIndex
        inputCmd = if (nextIndex >= 0) commandHistory.getOrNull(nextIndex).orEmpty() else ""
    }

    LaunchedEffect(pendingInputInsertion) {
        val insertion = pendingInputInsertion?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (termuxSession != null) {
            termuxSession.write(insertion)
        } else {
            inputCmd = appendTerminalInputReference(inputCmd, insertion)
        }
        onPendingInputInsertionConsumed()
    }

    var autoFollowOutput by remember { mutableStateOf(true) }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = isTerminalNearBottom(
                totalItems = lazyListState.layoutInfo.totalItemsCount,
                lastVisibleIndex = lastVisible
            )
            nextTerminalAutoFollowState(
                currentAutoFollow = autoFollowOutput,
                isNearBottom = nearBottom,
                isScrollInProgress = lazyListState.isScrollInProgress
            )
        }.collect { nextAutoFollow ->
            autoFollowOutput = nextAutoFollow
        }
    }

    val terminalItemCount = logs.size
    val terminalScrollInProgress = lazyListState.isScrollInProgress
    LaunchedEffect(terminalItemCount, alternateScreen, autoFollowOutput, terminalScrollInProgress) {
        val targetIndex = terminalScrollTargetIndex(
            totalItems = terminalItemCount,
            alternateScreen = alternateScreen,
            autoFollowOutput = autoFollowOutput,
            userScrollInProgress = terminalScrollInProgress
        )
        if (targetIndex != null) {
            lazyListState.scrollToItem(targetIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = config.name.ifBlank { if (config.isLocalSession) "Local Terminal" else config.host },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = if (config.isLocalSession) {
                            config.serverDisplayName?.takeIf { it.isNotBlank() } ?: "Local Shell"
                        } else {
                            val hostInfo = if (config.port != 22) "${config.host}:${config.port}" else config.host
                            if (config.username.isNotBlank()) "${config.username}@$hostInfo" else hostInfo
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = QuickSshTerminalMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QuickSshTerminalChrome,
                    titleContentColor = Color.White
                ),
                actions = {
                    FeedbackIconButton(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        onClick = onHomeClicked,
                        tint = Color.White,
                        pressedTint = QuickSshTerminalAccentPressed
                    )
                    FeedbackIconButton(
                        drawableResId = R.drawable.ic_content_copy,
                        contentDescription = "Copy mode",
                        onClick = {
                            copyMode = !copyMode
                            if (!copyMode) selectedCopyRows = emptySet()
                        },
                        tint = if (copyMode) QuickSshTerminalAccentPressed else QuickSshTerminalText,
                        pressedTint = QuickSshTerminalAccent
                    )
                    FeedbackIconButton(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Disconnect",
                        onClick = onDisconnectClicked,
                        tint = QuickSshTerminalDanger,
                        pressedTint = QuickSshTerminalDanger.copy(alpha = 0.72f)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(QuickSshTerminalBackground)
        ) {
            if (copyMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(QuickSshTerminalChrome)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedCopyRows.size} selected",
                        color = QuickSshTerminalText,
                        modifier = Modifier.weight(1f)
                    )
                    FeedbackTextButton(onClick = { selectedCopyRows = logs.indices.toSet() }) { Text("All") }
                    FeedbackTextButton(
                        enabled = selectedCopyRows.isNotEmpty(),
                        onClick = {
                            val text = selectedTerminalText(logs, selectedCopyRows)
                            if (text.isNotBlank()) clipboardManager.setText(AnnotatedString(text))
                            copyMode = false
                            selectedCopyRows = emptySet()
                        }
                    ) { Text("Copy") }
                    FeedbackTextButton(onClick = {
                        copyMode = false
                        selectedCopyRows = emptySet()
                    }) { Text("Done") }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(QuickSshTerminalBackground)
            ) {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val charWidthPx = textMeasurer.measure(
                    text = AnnotatedString("W"),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = terminalFontSize
                    )
                ).size.width.coerceAtLeast(1)
                val widthPx = with(density) { maxWidth.toPx() }.roundToInt().coerceAtLeast(1)
                val heightPx = with(density) { maxHeight.toPx() }.roundToInt().coerceAtLeast(1)
                val lineHeightPx = with(density) { terminalLineHeight.toPx() }.roundToInt().coerceAtLeast(1)
                val terminalViewportWidth = maxWidth
                val columns = (widthPx / charWidthPx)
                    .coerceIn(TerminalBuffer.MIN_COLUMNS, TerminalBuffer.MAX_COLUMNS)
                val rows = (heightPx / lineHeightPx)
                    .coerceIn(TerminalBuffer.MIN_ROWS, TerminalBuffer.MAX_ROWS)
                val horizontalScrollState = rememberScrollState()
                val terminalListModifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (effectiveAutoWrap) {
                            Modifier
                        } else {
                            Modifier.horizontalScroll(horizontalScrollState)
                        }
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)

                if (termuxSession == null) {
                    LaunchedEffect(columns, rows, widthPx, heightPx) {
                        val nextSize = TerminalBuffer.Size(
                            columns = columns,
                            rows = rows,
                            widthPixels = widthPx,
                            heightPixels = heightPx
                        )
                        if (shouldDispatchTerminalResize(
                                previousSize = lastReportedTerminalSize,
                                nextSize = nextSize
                            )
                        ) {
                            lastReportedTerminalSize = nextSize
                            onTerminalResize(nextSize)
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (termuxSession != null) {
                        TermuxTerminalComponent(
                            session = termuxSession,
                            fontSizeSp = terminalFontSizeSp,
                            ctrlKeyActive = ctrlModifier,
                            altKeyActive = altModifier,
                            onCtrlKeyConsumed = { ctrlModifier = false },
                            onAltKeyConsumed = { altModifier = false },
                            onSingleTap = { focusManager.clearFocus() },
                            onTerminalResize = onTerminalResize,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = terminalListModifier
                        ) {
                            itemsIndexed(logs) { index, log ->
                                val styledText = remember(log) { AnsiRenderer.renderAnsiText(log) }
                                val copyText = remember(log) {
                                    AnsiRenderer.cleanNonSgrAnsi(log).stripSgrAnsi()
                                }
                                val rowSelected = index in selectedCopyRows

                                Text(
                                    text = styledText,
                                    style = terminalOutputTextStyle,
                                    softWrap = effectiveAutoWrap,
                                    modifier = (if (effectiveAutoWrap) {
                                        Modifier.fillMaxWidth()
                                    } else {
                                        Modifier.widthIn(min = terminalViewportWidth)
                                    })
                                        .background(if (rowSelected) QuickSshTerminalSelection.copy(alpha = 0.32f) else Color.Transparent)
                                        .combinedClickable(
                                            onClick = {
                                                if (copyMode) {
                                                    selectedCopyRows = if (rowSelected) selectedCopyRows - index else selectedCopyRows + index
                                                }
                                            },
                                            onLongClick = {
                                                if (copyMode) {
                                                    selectedCopyRows = if (rowSelected) selectedCopyRows - index else selectedCopyRows + index
                                                } else if (copyText.isNotBlank()) {
                                                    clipboardManager.setText(AnnotatedString(copyText))
                                                }
                                            }
                                        )
                                        .padding(vertical = 0.dp)
                                )
                            }
                        }

                        if (shouldShowJumpToLatestButton(
                                totalItems = terminalItemCount,
                                alternateScreen = alternateScreen,
                                autoFollowOutput = autoFollowOutput,
                                copyMode = copyMode
                            )
                        ) {
                            SmallFloatingActionButton(
                                onClick = { autoFollowOutput = true },
                                containerColor = QuickSshTerminalAccent,
                                contentColor = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Jump to latest"
                                )
                            }
                        }
                    }
                }
            }

            Column {
                TerminalControlPanel(
                    expanded = showControlKeyboard,
                    ctrlModifier = ctrlModifier,
                    altModifier = altModifier,
                    onExpandedChange = { showControlKeyboard = it },
                    onCtrlModifierChange = { ctrlModifier = it },
                    onAltModifierChange = { altModifier = it },
                    onShortcutCommand = { submitShortcut(it) },
                    onRawInputSend = { data ->
                        if (termuxSession != null) {
                            termuxSession.write(data)
                        } else {
                            onRawInputSend(data)
                        }
                    },
                    workDirectory = config.workDirectory,
                    workspaceShortcuts = config.terminalShortcuts,
                    applicationCursorKeys = applicationCursorKeys,
                    canRecallPreviousCommand = commandHistory.isNotEmpty(),
                    canRecallNextCommand = historyIndex >= 0,
                    onPreviousCommand = {
                        recallHistory(previousHistoryIndex(commandHistory, historyIndex))
                    },
                    onNextCommand = {
                        recallHistory(nextHistoryIndex(commandHistory, historyIndex))
                    },
                    compact = isLandscape
                )

                if (quickUploadStatus.isNotBlank()) {
                    Text(
                        text = quickUploadStatus,
                        color = QuickSshTerminalMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(QuickSshTerminalChrome)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(QuickSshTerminalChrome)
                        .padding(horizontal = 10.dp, vertical = if (isLandscape) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$",
                        color = QuickSshTerminalAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    BasicTextField(
                        value = inputCmd,
                        onValueChange = { value ->
                            if (value.contains('\n') || value.contains('\r')) {
                                if (shouldConfirmMultilineInputChange(inputCmd, value)) {
                                    pendingPaste = value
                                } else {
                                    val sanitized = value.replace("\r", "").replace("\n", "")
                                    submitCommand(if (sanitized.isNotEmpty()) sanitized else inputCmd)
                                }
                            } else {
                                inputCmd = value
                            }
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            letterSpacing = 0.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                submitCommand(inputCmd)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = if (isLandscape) 36.dp else 44.dp, max = 96.dp)
                            .padding(horizontal = 4.dp, vertical = if (isLandscape) 6.dp else 8.dp)
                    )
                    TerminalInputIconButton(
                        drawableResId = R.drawable.ic_file_upload,
                        contentDescription = if (config.isLocalSession) "Select file path" else "Upload file",
                        enabled = true,
                        compact = isLandscape,
                        onClick = onQuickUploadClicked
                    )
                    TerminalInputIconButton(
                        drawableResId = R.drawable.ic_open_in_browser,
                        contentDescription = "History",
                        enabled = true,
                        compact = isLandscape,
                        onClick = onHistoryClicked
                    )
                    TerminalInputIconButton(
                        drawableResId = R.drawable.ic_keyboard_return,
                        contentDescription = "Enter",
                        enabled = true,
                        compact = isLandscape,
                        onClick = {
                            submitCommand(inputCmd)
                        }
                    )
                }
            }
        }
    }
    pendingPaste?.let { paste ->
        AlertDialog(
            onDismissRequest = { pendingPaste = null },
            title = { Text("Confirm paste") },
            text = { Text("Paste ${paste.lineSequence().count()} lines into the terminal?") },
            confirmButton = {
                FeedbackTextButton(onClick = {
                    if (termuxSession != null) {
                        termuxSession.write(paste)
                    } else {
                        onRawInputSend(terminalPastePayload(paste, bracketedPasteMode))
                    }
                    inputCmd = ""
                    historyIndex = -1
                    pendingPaste = null
                }) { Text("Paste") }
            },
            dismissButton = {
                FeedbackTextButton(onClick = { pendingPaste = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TerminalInputIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    TerminalInputIconButtonFrame(
        enabled = enabled,
        compact = compact,
        onClick = onClick
    ) { tint ->
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

@Composable
private fun TerminalInputIconButton(
    drawableResId: Int,
    contentDescription: String,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    TerminalInputIconButtonFrame(
        enabled = enabled,
        compact = compact,
        onClick = onClick
    ) { tint ->
        Icon(
            painter = painterResource(drawableResId),
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

@Composable
private fun TerminalInputIconButtonFrame(
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.92f else 1f, label = "terminalInputIconPressScale")
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> QuickSshTerminalDisabled
            isPressed -> QuickSshTerminalAccentPressed
            else -> QuickSshTerminalText
        },
        label = "terminalInputIconTint"
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(if (compact) 34.dp else 38.dp)
            .scale(scale)
    ) {
        icon(tint)
    }
}

private fun String.stripSgrAnsi(): String {
    return replace(Regex("\u001B\\[[0-9;]*m"), "")
}

internal data class TerminalKey(
    val label: String,
    val sequence: String,
    val accent: Boolean = false
)

internal data class TerminalShortcutCommand(
    val label: String,
    val command: String
)

@Composable
private fun TerminalControlPanel(
    expanded: Boolean,
    ctrlModifier: Boolean,
    altModifier: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCtrlModifierChange: (Boolean) -> Unit,
    onAltModifierChange: (Boolean) -> Unit,
    onShortcutCommand: (TerminalShortcutCommand) -> Unit,
    onRawInputSend: (String) -> Unit,
    workDirectory: String?,
    workspaceShortcuts: String?,
    applicationCursorKeys: Boolean,
    canRecallPreviousCommand: Boolean,
    canRecallNextCommand: Boolean,
    onPreviousCommand: () -> Unit,
    onNextCommand: () -> Unit,
    compact: Boolean
) {
    val shortcuts = remember(workspaceShortcuts, workDirectory) {
        terminalShortcutCommands(workspaceShortcuts, workDirectory)
    }
    val compactTerminalKeys = remember(applicationCursorKeys) { compactKeys(applicationCursorKeys) }
    val navigationTerminalKeys = remember(applicationCursorKeys) { navigationKeys(applicationCursorKeys) }
    val hapticFeedback = LocalHapticFeedback.current
    val terminalKeyHaptic = remember(hapticFeedback) {
        { hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val verticalPadding = if (compact) 3.dp else 6.dp
    val horizontalPadding = if (compact) 6.dp else 8.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(QuickSshTerminalChrome)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalKeyButton(
                label = if (expanded) "Hide keys" else "Show keys",
                onClick = { onExpandedChange(!expanded) },
                containerColor = QuickSshTerminalAccent,
                contentColor = Color.White,
                hapticFeedback = terminalKeyHaptic
            )
            compactTerminalKeys.forEach { key ->
                TerminalKeyButton(
                    label = key.label,
                    onClick = { onRawInputSend(key.sequence) },
                    containerColor = if (key.accent) QuickSshTerminalKeyAccent else QuickSshTerminalKey,
                    contentColor = QuickSshTerminalKeyContent,
                    hapticFeedback = terminalKeyHaptic
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            shortcuts.forEach { shortcut ->
                TerminalKeyButton(
                    label = shortcut.label,
                    onClick = { onShortcutCommand(shortcut) },
                    containerColor = QuickSshTerminalKey,
                    contentColor = QuickSshTerminalMuted
                )
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModifierKeyButton("Ctrl", ctrlModifier, onCtrlModifierChange, terminalKeyHaptic)
                ModifierKeyButton("Alt", altModifier, onAltModifierChange, terminalKeyHaptic)
                TerminalKeyButton(
                    label = "Clear modifiers",
                    onClick = {
                        onCtrlModifierChange(false)
                        onAltModifierChange(false)
                    },
                    containerColor = QuickSshTerminalKeySecondary,
                    contentColor = QuickSshTerminalText,
                    hapticFeedback = terminalKeyHaptic
                )
                TerminalKeyButton(
                    label = "Prev cmd",
                    onClick = onPreviousCommand,
                    containerColor = QuickSshTerminalKeySecondary,
                    contentColor = QuickSshTerminalText,
                    hapticFeedback = terminalKeyHaptic,
                    enabled = canRecallPreviousCommand
                )
                TerminalKeyButton(
                    label = "Next cmd",
                    onClick = onNextCommand,
                    containerColor = QuickSshTerminalKeySecondary,
                    contentColor = QuickSshTerminalText,
                    hapticFeedback = terminalKeyHaptic,
                    enabled = canRecallNextCommand
                )
            }

            listOf(
                navigationTerminalKeys,
                editingKeys,
                functionKeys.take(6),
                functionKeys.drop(6),
                alphaKeys.take(13),
                alphaKeys.drop(13)
            ).forEach { rowKeys ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowKeys.forEach { key ->
                        TerminalKeyButton(
                            label = key.label,
                            onClick = {
                                onRawInputSend(applyModifiers(key.sequence, ctrlModifier, altModifier))
                                onCtrlModifierChange(false)
                                onAltModifierChange(false)
                            },
                            containerColor = if (key.accent) QuickSshTerminalKeyAccent else QuickSshTerminalKey,
                            contentColor = QuickSshTerminalKeyContent,
                            hapticFeedback = terminalKeyHaptic
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModifierKeyButton(
    label: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    hapticFeedback: (() -> Unit)? = null
) {
    TerminalKeyButton(
        label = label,
        onClick = { onSelectedChange(!selected) },
        containerColor = if (selected) QuickSshConnected else QuickSshTerminalKeySecondary,
        contentColor = Color.White,
        hapticFeedback = hapticFeedback
    )
}

@Composable
private fun TerminalKeyButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    hapticFeedback: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(targetValue = if (isPressed) 0.94f else 1f, label = "terminalKeyPressScale")
    val pressedContainerColor by animateColorAsState(
        targetValue = when {
            !enabled -> QuickSshTerminalKey
            isPressed -> containerColor.copy(alpha = 0.78f)
            else -> containerColor
        },
        label = "terminalKeyPressColor"
    )
    Button(
        onClick = {
            hapticFeedback?.invoke()
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = pressedContainerColor,
            contentColor = contentColor,
            disabledContainerColor = QuickSshTerminalKey,
            disabledContentColor = QuickSshTerminalDisabled
        ),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.scale(pressScale).defaultMinSize(minHeight = 1.dp, minWidth = 1.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

internal fun compactKeys(applicationCursorKeys: Boolean) = listOf(
    TerminalKey("Esc", "\u001B", accent = true),
    TerminalKey("Up", terminalCursorKeySequence("up", applicationCursorKeys), accent = true),
    TerminalKey("Down", terminalCursorKeySequence("down", applicationCursorKeys), accent = true),
    TerminalKey("Left", terminalCursorKeySequence("left", applicationCursorKeys), accent = true),
    TerminalKey("Right", terminalCursorKeySequence("right", applicationCursorKeys), accent = true),
    TerminalKey("PgUp", "\u001B[5~", accent = true),
    TerminalKey("PgDn", "\u001B[6~", accent = true),
    TerminalKey("Tab", "\t", accent = true),
    TerminalKey("Ctrl+C", "\u0003", accent = true)
)

private fun navigationKeys(applicationCursorKeys: Boolean) = listOf(
    TerminalKey("Esc", "\u001B", accent = true),
    TerminalKey("Up", terminalCursorKeySequence("up", applicationCursorKeys), accent = true),
    TerminalKey("Down", terminalCursorKeySequence("down", applicationCursorKeys), accent = true),
    TerminalKey("Left", terminalCursorKeySequence("left", applicationCursorKeys), accent = true),
    TerminalKey("Right", terminalCursorKeySequence("right", applicationCursorKeys), accent = true),
    TerminalKey("Home", terminalHomeEndKeySequence("home", applicationCursorKeys)),
    TerminalKey("End", terminalHomeEndKeySequence("end", applicationCursorKeys)),
    TerminalKey("PgUp", "\u001B[5~"),
    TerminalKey("PgDn", "\u001B[6~")
)

private val editingKeys = listOf(
    TerminalKey("Tab", "\t", accent = true),
    TerminalKey("Enter", "\r", accent = true),
    TerminalKey("Backspace", "\u007F", accent = true),
    TerminalKey("Delete", "\u001B[3~"),
    TerminalKey("Insert", "\u001B[2~"),
    TerminalKey("Space", " "),
    TerminalKey("Ctrl+C", "\u0003", accent = true),
    TerminalKey("Ctrl+D", "\u0004", accent = true),
    TerminalKey("Ctrl+Z", "\u001A", accent = true)
)

private val functionKeys = listOf(
    TerminalKey("F1", "\u001BOP"),
    TerminalKey("F2", "\u001BOQ"),
    TerminalKey("F3", "\u001BOR"),
    TerminalKey("F4", "\u001BOS"),
    TerminalKey("F5", "\u001B[15~"),
    TerminalKey("F6", "\u001B[17~"),
    TerminalKey("F7", "\u001B[18~"),
    TerminalKey("F8", "\u001B[19~"),
    TerminalKey("F9", "\u001B[20~"),
    TerminalKey("F10", "\u001B[21~"),
    TerminalKey("F11", "\u001B[23~"),
    TerminalKey("F12", "\u001B[24~")
)

private val alphaKeys = ('A'..'Z').map { char -> TerminalKey(char.toString(), char.lowercase()) }

private const val CODEX_RESUME_SHORTCUT_LABEL = "Codex resume"

private fun defaultCommandShortcuts(workDirectory: String?) = listOf(
    TerminalShortcutCommand("ls -la", "ls -la"),
    TerminalShortcutCommand("uname -a", "uname -a"),
    TerminalShortcutCommand("top", "top"),
    TerminalShortcutCommand("df -h", "df -h"),
    TerminalShortcutCommand("free -m", "free -m"),
    TerminalShortcutCommand("clear", "clear"),
    TerminalShortcutCommand(CODEX_RESUME_SHORTCUT_LABEL, codexResumeShortcutCommand(workDirectory))
)

internal fun shortcutCommands(rawShortcuts: String?): List<String> {
    return terminalShortcutCommands(rawShortcuts).map { it.command }
}

internal fun terminalShortcutCommands(
    rawShortcuts: String?,
    workDirectory: String? = null
): List<TerminalShortcutCommand> {
    val custom = rawShortcuts
        ?.replace("\r\n", "\n")
        ?.replace('\r', '\n')
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.take(8)
        ?.map { TerminalShortcutCommand(it, it) }
        ?.toList()
        .orEmpty()
    return custom.ifEmpty { defaultCommandShortcuts(workDirectory) }
}

internal fun codexResumeShortcutCommand(workDirectory: String?): String {
    val base = "codex resume --last --no-alt-screen"
    val directory = workDirectory?.trim().orEmpty()
    if (directory.isBlank()) return base
    return "$base -C ${shellSafePathReference(directory)}"
}

internal fun shouldRunCodexResumeShortcutAction(
    shortcut: TerminalShortcutCommand,
    workDirectory: String?
): Boolean {
    return shortcut.label == CODEX_RESUME_SHORTCUT_LABEL &&
        shortcut.command == codexResumeShortcutCommand(workDirectory)
}

internal fun selectedTerminalText(logs: List<String>, selectedRows: Set<Int>): String {
    return selectedRows
        .asSequence()
        .filter { it in logs.indices }
        .sorted()
        .map { logs[it].plainTerminalText() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun String.plainTerminalText(): String {
    return AnsiRenderer.cleanNonSgrAnsi(this)
        .stripSgrAnsi()
}

private fun applyModifiers(sequence: String, ctrl: Boolean, alt: Boolean): String {
    var result = sequence
    if (ctrl && sequence.length == 1) {
        val code = sequence[0].uppercaseChar().code
        if (code in 'A'.code..'Z'.code) {
            result = ((code - 'A'.code + 1).toChar()).toString()
        }
    }
    if (alt && result.length == 1) {
        result = "\u001B$result"
    }
    return result
}




