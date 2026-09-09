package com.quickssh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickssh.app.service.TerminalHistoryFile
import com.quickssh.app.ui.theme.QuickSshTerminalBackground
import com.quickssh.app.ui.theme.QuickSshTerminalChrome
import com.quickssh.app.ui.theme.QuickSshTerminalText
import com.quickssh.app.ui.theme.QuickSshTerminalAccent
import com.quickssh.app.ui.theme.QuickSshTerminalMuted
import com.quickssh.app.utils.AnsiRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen terminal history viewer.
 * Loads the session history from a disk-backed file in pages,
 * allowing smooth scrolling through the full session output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalHistoryScreen(
    historyFile: TerminalHistoryFile,
    sessionName: String,
    onBackClicked: () -> Unit
) {
    val language = LocalQuickSshLanguage.current
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    var allLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalLineCount by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var currentSearchIndex by remember { mutableStateOf(-1) }

    // Load all lines on first composition
    LaunchedEffect(historyFile) {
        withContext(Dispatchers.IO) {
            totalLineCount = historyFile.lineCount()
            val pages = historyFile.pageCount()
            val lines = mutableListOf<String>()
            for (page in 0 until pages) {
                lines.addAll(historyFile.readPage(page))
            }
            allLines = lines
            isLoading = false
        }
    }

    fun performSearch(query: String) {
        if (query.isBlank()) {
            searchResults = emptyList()
            currentSearchIndex = -1
            return
        }
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                historyFile.search(query)
            }
            searchResults = results
            currentSearchIndex = if (results.isNotEmpty()) 0 else -1
            if (results.isNotEmpty()) {
                lazyListState.scrollToItem(results[0].first.coerceIn(0, allLines.lastIndex.coerceAtLeast(0)))
            }
        }
    }

    fun navigateSearchResult(direction: Int) {
        if (searchResults.isEmpty()) return
        currentSearchIndex = (currentSearchIndex + direction).let { next ->
            when {
                next < 0 -> searchResults.lastIndex
                next > searchResults.lastIndex -> 0
                else -> next
            }
        }
        val targetLine = searchResults[currentSearchIndex].first
        scope.launch {
            lazyListState.scrollToItem(targetLine.coerceIn(0, allLines.lastIndex.coerceAtLeast(0)))
        }
    }

    val terminalTextStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.sp,
            color = QuickSshTerminalText,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        language.text("历史记录: $sessionName", "History: $sessionName"),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (showSearch) QuickSshTerminalAccent else Color.White
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            lazyListState.scrollToItem(0)
                        }
                    }) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Top", tint = Color.White)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            if (allLines.isNotEmpty()) {
                                lazyListState.scrollToItem(allLines.lastIndex)
                            }
                        }
                    }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bottom", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = QuickSshTerminalChrome,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(QuickSshTerminalBackground)
        ) {
            // Search bar
            if (showSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(QuickSshTerminalChrome)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .background(QuickSshTerminalBackground, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = QuickSshTerminalText
                        ),
                        cursorBrush = SolidColor(QuickSshTerminalAccent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { performSearch(searchQuery) })
                    )
                    if (searchResults.isNotEmpty()) {
                        Text(
                            "${currentSearchIndex + 1}/${searchResults.size}",
                            color = QuickSshTerminalMuted,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(
                        onClick = { navigateSearchResult(-1) },
                        enabled = searchResults.isNotEmpty()
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous", tint = QuickSshTerminalText)
                    }
                    IconButton(
                        onClick = { navigateSearchResult(1) },
                        enabled = searchResults.isNotEmpty()
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next", tint = QuickSshTerminalText)
                    }
                }
            }

            // Line count indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QuickSshTerminalChrome.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    language.text("共 $totalLineCount 行", "$totalLineCount lines total"),
                    color = QuickSshTerminalMuted,
                    fontSize = 10.sp
                )
                if (isLoading) {
                    Text(
                        language.text("加载中...", "Loading..."),
                        color = QuickSshTerminalMuted,
                        fontSize = 10.sp
                    )
                }
            }

            // Main content
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = QuickSshTerminalAccent)
                }
            } else if (allLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        language.text("暂无历史记录", "No history yet"),
                        color = QuickSshTerminalMuted
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(allLines, key = { index, _ -> index }) { index, line ->
                        val isSearchHit = searchResults.any { it.first == index }
                        val bgColor = if (isSearchHit) {
                            QuickSshTerminalAccent.copy(alpha = 0.15f)
                        } else {
                            Color.Transparent
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                        ) {
                            if (line.contains('\u001B')) {
                                Text(
                                    text = AnsiRenderer.renderAnsiText(line),
                                    style = terminalTextStyle
                                )
                            } else {
                                Text(
                                    text = line.ifEmpty { " " },
                                    style = terminalTextStyle
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
