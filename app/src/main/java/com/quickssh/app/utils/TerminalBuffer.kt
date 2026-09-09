package com.quickssh.app.utils

import kotlin.math.max

/**
 * Lightweight VT-style terminal screen buffer for interactive SSH output.
 *
 * The buffer keeps a primary scrollback plus an alternate screen so full-screen
 * TUI programs can repaint in-place instead of being appended as log lines.
 */
class TerminalBuffer(
    private val maxLines: Int = 1000,
    initialColumns: Int = DEFAULT_COLUMNS,
    initialRows: Int = DEFAULT_ROWS
) {
    data class Size(
        val columns: Int,
        val rows: Int,
        val widthPixels: Int = 0,
        val heightPixels: Int = 0
    ) {
        fun sanitized(): Size = copy(
            columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
            rows = rows.coerceIn(MIN_ROWS, MAX_ROWS),
            widthPixels = widthPixels.coerceAtLeast(0),
            heightPixels = heightPixels.coerceAtLeast(0)
        )
    }

    private data class Style(
        val foreground: List<Int>? = null,
        val background: List<Int>? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val inverse: Boolean = false
    ) {
        fun isDefault(): Boolean = this == DEFAULT

        fun toAnsi(): String {
            if (isDefault()) return "\u001B[0m"
            val codes = mutableListOf<Int>()
            if (bold) codes.add(1)
            if (dim) codes.add(2)
            if (italic) codes.add(3)
            if (underline) codes.add(4)
            if (inverse) codes.add(7)
            if (strikethrough) codes.add(9)
            foreground?.let { codes.addAll(it) }
            background?.let { codes.addAll(it) }
            return "\u001B[${codes.joinToString(";")}m"
        }

        companion object {
            val DEFAULT = Style()
        }
    }

    private data class Cell(
        var text: String = " ",
        var style: Style = Style.DEFAULT,
        var continuation: Boolean = false
    )

    private data class ScreenState(
        val lines: MutableList<MutableList<Cell>> = mutableListOf(mutableListOf()),
        var cursorRow: Int = 0,
        var cursorColumn: Int = 0,
        var savedCursorRow: Int = 0,
        var savedCursorColumn: Int = 0,
        var scrollTop: Int = 0,
        var scrollBottom: Int = DEFAULT_ROWS - 1,
        var marginLeft: Int = 0,
        var marginRight: Int = DEFAULT_COLUMNS - 1
    )

    private data class RectangleBounds(
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int
    )

    private val primaryScreen = ScreenState()
    private val alternateScreenState = ScreenState()
    private var screen = primaryScreen

    private var terminalSize = Size(initialColumns, initialRows).sanitized()
    private var tabStops = defaultTabStops()
    private var currentStyle = Style.DEFAULT
    private var savedStyle = Style.DEFAULT
    private var pendingEscape = ""
    private val pendingResponses = mutableListOf<String>()
    private val pendingHistoryLines = ArrayDeque<String>()
    private val historyLine = StringBuilder()
    private var historySuppressed = false
    private var historyChunkUnsafe = false
    private var lastPrintable = " "
    private var g0UsesDecSpecialGraphics = false
    private var g1UsesDecSpecialGraphics = false
    private var g2UsesDecSpecialGraphics = false
    private var g3UsesDecSpecialGraphics = false
    private var usingG1Charset = false
    private var singleShiftCharset: Int? = null
    private var autoWrap = true
    private var insertMode = false
    private var originMode = false
    private var leftRightMarginMode = false
    var applicationCursorKeys: Boolean = false
        private set
    var applicationKeypadMode: Boolean = false
        private set
    var isSynchronizedOutputMode: Boolean = false
        private set
    var isInAlternateScreen: Boolean = false
        private set
    private var cursorVisible = true
    private var cursorBlinking = false
    private var cursorStyle = DEFAULT_CURSOR_STYLE
    var bracketedPasteMode: Boolean = false
        private set
    private var focusEventMode = false
    private val mouseModes = mutableSetOf<Int>()

    init {
        resetScrollRegion(primaryScreen)
        resetScrollRegion(alternateScreenState)
        ensureAlternateScreenShape()
    }

    fun clear() {
        clearScreen(primaryScreen)
        clearScreen(alternateScreenState)
        screen = primaryScreen
        isInAlternateScreen = false
        currentStyle = Style.DEFAULT
        savedStyle = Style.DEFAULT
        pendingEscape = ""
        pendingResponses.clear()
        lastPrintable = " "
        g0UsesDecSpecialGraphics = false
        g1UsesDecSpecialGraphics = false
        g2UsesDecSpecialGraphics = false
        g3UsesDecSpecialGraphics = false
        usingG1Charset = false
        singleShiftCharset = null
        autoWrap = true
        insertMode = false
        originMode = false
        leftRightMarginMode = false
        applicationCursorKeys = false
        applicationKeypadMode = false
        isSynchronizedOutputMode = false
        cursorVisible = true
        cursorBlinking = false
        cursorStyle = DEFAULT_CURSOR_STYLE
        bracketedPasteMode = false
        focusEventMode = false
        mouseModes.clear()
        tabStops = defaultTabStops()
        pendingHistoryLines.clear()
        historyLine.setLength(0)
        historySuppressed = false
        historyChunkUnsafe = false
    }

    fun resize(size: Size): List<String> {
        terminalSize = size.sanitized()
        tabStops.removeAll { it >= terminalSize.columns }
        resetScrollRegion(primaryScreen)
        resetScrollRegion(alternateScreenState)
        resetHorizontalMargins(primaryScreen)
        resetHorizontalMargins(alternateScreenState)
        ensureAlternateScreenShape()
        screen.cursorRow = if (isInAlternateScreen) {
            screen.cursorRow.coerceIn(0, terminalSize.rows - 1)
        } else {
            screen.cursorRow.coerceIn(0, screen.lines.lastIndex.coerceAtLeast(0))
        }
        screen.cursorColumn = screen.cursorColumn.coerceIn(horizontalBounds())
        return snapshot()
    }

    fun append(raw: String): List<String> {
        appendRaw(raw)
        return snapshot()
    }

    fun appendRaw(raw: String) {
        historyChunkUnsafe = pendingEscape.isNotEmpty() || containsHistoryUnsafeControl(raw)
        if (!historyChunkUnsafe) historySuppressed = false

        var text = pendingEscape + raw
        pendingEscape = ""

        val incompleteEscapeStart = findIncompleteEscapeStart(text)
        if (incompleteEscapeStart >= 0) {
            pendingEscape = text.substring(incompleteEscapeStart)
            text = text.substring(0, incompleteEscapeStart)
        }

        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            when (codePoint) {
                ESC -> {
                    val consumed = consumeEscape(text, i)
                    val sequence = text.substring(i, (i + consumed).coerceAtMost(text.length))
                    if (sequence.matches(Regex("\\u001B\\[[0-9;:]*m"))) {
                        recordHistoryText(sequence)
                    }
                    i += consumed
                }
                IND_C1 -> {
                    moveToNextLine()
                    i += charCount
                }
                NEL_C1 -> {
                    moveToNextLine()
                    screen.cursorColumn = 0
                    i += charCount
                }
                HTS_C1 -> {
                    setTabStop()
                    i += charCount
                }
                RI_C1 -> {
                    reverseIndex()
                    i += charCount
                }
                CSI_C1 -> {
                    val consumed = consumeCsi(text, i, c1 = true)
                    val sequence = text.substring(i, (i + consumed).coerceAtMost(text.length))
                    if (sequence.matches(Regex("\\u009B[0-9;:]*m"))) {
                        recordHistoryText(sequence)
                    }
                    i += consumed
                }
                OSC_C1 -> i += consumeOsc(text, i, prefixLength = 1)
                DCS_C1 -> i += consumeDcs(text, i, prefixLength = 1)
                SOS_C1, PM_C1, APC_C1 -> i += consumeControlString(text, i, prefixLength = 1)
                SHIFT_OUT -> {
                    usingG1Charset = true
                    i += charCount
                }
                SHIFT_IN -> {
                    usingG1Charset = false
                    i += charCount
                }
                '['.code -> {
                    val consumed = consumeOrphanPrivateCsi(text, i)
                    if (consumed > 0) {
                        i += consumed
                    } else {
                        putCodePoint(codePoint)
                        recordHistoryText("[")
                        i += charCount
                    }
                }
                '\r'.code -> {
                    screen.cursorColumn = 0
                    i += charCount
                }
                '\n'.code -> {
                    finishHistoryLine()
                    moveToNextLine()
                    i += charCount
                }
                '\b'.code -> {
                    if (screen.cursorColumn > 0) screen.cursorColumn--
                    i += charCount
                }
                '\t'.code -> {
                    moveToNextTabStop()
                    i += charCount
                }
                0, in 1..8, in 11..31, 127, in C1_CONTROL_CODE_RANGE -> i += charCount
                else -> {
                    putCodePoint(codePoint)
                    recordHistoryText(mapPrintableCodePoint(codePoint))
                    i += charCount
                }
            }
        }
    }

    fun drainResponses(): List<String> {
        if (pendingResponses.isEmpty()) return emptyList()
        val responses = pendingResponses.toList()
        pendingResponses.clear()
        return responses
    }

    /** Returns complete transcript lines produced since the previous drain. */
    fun drainHistoryLines(): List<String> {
        if (pendingHistoryLines.isEmpty()) return emptyList()
        val lines = pendingHistoryLines.toList()
        pendingHistoryLines.clear()
        return lines
    }

    private fun recordHistoryText(text: String) {
        if (text.isEmpty() || isInAlternateScreen || historySuppressed || historyChunkUnsafe) return
        historyLine.append(text)
    }

    private fun finishHistoryLine() {
        if (!isInAlternateScreen && !historySuppressed && !historyChunkUnsafe) {
            pendingHistoryLines.addLast(historyLine.toString())
        }
        historyLine.setLength(0)
        if (historyChunkUnsafe) historySuppressed = true
    }

    private fun markHistoryUnsafe() {
        historyChunkUnsafe = true
        historyLine.setLength(0)
    }

    private fun containsHistoryUnsafeControl(text: String): Boolean {
        if (text.any { it.code in C1_CONTROL_CODE_RANGE }) return true
        return Regex("\\u001B(?!\\[[0-9;:]*m)").containsMatchIn(text)
    }

    private fun putCodePoint(codePoint: Int) {
        val mapped = mapPrintableCodePoint(codePoint)
        val width = mapped.displayWidth()
        if (width <= 0) {
            appendZeroWidthText(mapped)
        } else {
            putText(mapped, width)
        }
    }

    private fun appendZeroWidthText(text: String) {
        if (text.isEmpty()) return
        ensureCursorLine()

        var row = screen.cursorRow
        var column = screen.cursorColumn - 1
        while (row >= 0) {
            val line = screen.lines.getOrNull(row)
            if (line != null) {
                val startColumn = if (row == screen.cursorRow) column else line.lastIndex
                for (index in startColumn downTo 0) {
                    val cell = line.getOrNull(index) ?: continue
                    if (!cell.continuation && cell.text.isNotBlank()) {
                        cell.text += text
                        return
                    }
                }
            }
            row--
            column = screen.lines.getOrNull(row)?.lastIndex ?: -1
        }
    }

    private fun putText(text: String, width: Int) {
        if (width <= 0) return
        val horizontalBounds = horizontalBounds()
        if (screen.cursorColumn < horizontalBounds.first) {
            screen.cursorColumn = horizontalBounds.first
        }
        if (screen.cursorColumn > horizontalBounds.last) {
            if (autoWrap) wrapLine() else screen.cursorColumn = horizontalBounds.last
        }
        if (width > 1 && screen.cursorColumn == horizontalBounds.last) {
            if (autoWrap) wrapLine() else return
        }

        ensureCursorLine()
        val line = screen.lines[screen.cursorRow]
        ensureLineSize(line, screen.cursorColumn)
        applyInsertMode(line, width)
        if (line[screen.cursorColumn].continuation && screen.cursorColumn > 0) {
            line[screen.cursorColumn - 1] = blankCell()
        }
        line[screen.cursorColumn] = Cell(text, currentStyle, continuation = false)

        if (width > 1) {
            ensureLineSize(line, screen.cursorColumn + 1)
            line[screen.cursorColumn + 1] = Cell("", currentStyle, continuation = true)
        } else if (screen.cursorColumn + 1 < line.size && line[screen.cursorColumn + 1].continuation) {
            line[screen.cursorColumn + 1] = blankCell()
        }

        lastPrintable = text
        singleShiftCharset = null
        screen.cursorColumn = (screen.cursorColumn + width).coerceAtMost(horizontalBounds.last + 1)
    }

    private fun wrapLine() {
        screen.cursorColumn = horizontalBounds().first
        moveToNextLine()
    }

    private fun moveToNextLine() {
        if (isInAlternateScreen && screen.cursorRow >= screen.scrollBottom) {
            scrollUpInRegion(screen.scrollTop, screen.scrollBottom)
            screen.cursorRow = screen.scrollBottom
        } else {
            screen.cursorRow++
            ensureCursorLine()
            if (isInAlternateScreen && screen.cursorRow >= terminalSize.rows) {
                scrollUpInRegion(0, terminalSize.rows - 1)
                screen.cursorRow = terminalSize.rows - 1
            }
        }
        screen.cursorColumn = horizontalBounds().first
        trimPrimaryScrollback()
    }

    private fun ensureCursorLine() {
        screen.cursorRow = screen.cursorRow.coerceAtLeast(0)
        while (screen.cursorRow >= screen.lines.size) {
            screen.lines.add(mutableListOf())
        }
        if (isInAlternateScreen) ensureAlternateScreenShape()
    }

    private fun ensureLineSize(line: MutableList<Cell>, lastIndex: Int) {
        while (line.size <= lastIndex) line.add(blankCell())
    }

    private fun applyInsertMode(line: MutableList<Cell>, width: Int) {
        if (!insertMode) return
        ensureLineSize(line, terminalSize.columns - 1)
        repeat(width.coerceAtLeast(1)) {
            line.add(screen.cursorColumn.coerceAtMost(line.size), blankCell())
        }
        while (line.size > terminalSize.columns) line.removeAt(line.lastIndex)
    }

    private fun blankCell(): Cell = Cell(" ", Style.DEFAULT, continuation = false)

    fun snapshot(): List<String> {
        trimPrimaryScrollback()
        if (isInAlternateScreen) ensureAlternateScreenShape()

        val result = screen.lines.mapIndexed { index, cells ->
            val preserveWidth = isInAlternateScreen && index < terminalSize.rows
            cellsToAnsiString(cells, preserveWidth)
        }.toMutableList()

        if (isInAlternateScreen) {
            while (result.size < terminalSize.rows) result.add("")
            while (result.size > terminalSize.rows) result.removeAt(result.lastIndex)
            return result
        }

        while (result.size > 1 && stripAnsiForEmptyCheck(result.last()).isEmpty()) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    private fun cellsToAnsiString(cells: List<Cell>, preserveWidth: Boolean): String {
        val targetLastIndex = if (preserveWidth) {
            terminalSize.columns - 1
        } else {
            cells.indexOfLast { !it.continuation && it.text != " " }
        }
        if (targetLastIndex < 0) return ""

        val builder = StringBuilder()
        var activeStyle = Style.DEFAULT
        for (index in 0..targetLastIndex) {
            val cell = cells.getOrNull(index) ?: blankCell()
            if (cell.style != activeStyle) {
                builder.append(cell.style.toAnsi())
                activeStyle = cell.style
            }
            if (!cell.continuation) builder.append(cell.text)
        }
        if (!activeStyle.isDefault()) builder.append(Style.DEFAULT.toAnsi())
        return builder.toString()
    }

    private fun stripAnsiForEmptyCheck(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*m"), "")
    }

    private fun trimPrimaryScrollback() {
        while (primaryScreen.lines.size > maxLines) {
            primaryScreen.lines.removeAt(0)
            primaryScreen.cursorRow = (primaryScreen.cursorRow - 1).coerceAtLeast(0)
            primaryScreen.savedCursorRow = (primaryScreen.savedCursorRow - 1).coerceAtLeast(0)
        }
        if (primaryScreen.lines.isEmpty()) primaryScreen.lines.add(mutableListOf())
        primaryScreen.cursorRow = primaryScreen.cursorRow.coerceIn(0, primaryScreen.lines.lastIndex)
        primaryScreen.savedCursorRow = primaryScreen.savedCursorRow.coerceIn(0, primaryScreen.lines.lastIndex)
    }

    private fun consumeEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) return 1
        return when (text[start + 1]) {
            '[' -> consumeCsi(text, start, c1 = false)
            ']' -> consumeOsc(text, start, prefixLength = 2)
            'P' -> consumeDcs(text, start, prefixLength = 2)
            'X', '^', '_' -> consumeControlString(text, start, prefixLength = 2)
            'N' -> {
                singleShiftCharset = 2
                2
            }
            'O' -> {
                singleShiftCharset = 3
                2
            }
            '(', ')', '*', '+' -> consumeCharsetDesignation(text, start)
            '%' -> if (start + 2 < text.length) 3 else 2
            '#' -> consumeEscHash(text, start)
            'H' -> {
                setTabStop()
                2
            }
            '7' -> {
                saveCursor()
                2
            }
            '8' -> {
                restoreCursor()
                2
            }
            'M' -> {
                reverseIndex()
                2
            }
            'D' -> {
                moveToNextLine()
                2
            }
            'E' -> {
                moveToNextLine()
                screen.cursorColumn = 0
                2
            }
            'c' -> {
                clear()
                2
            }
            'Z' -> {
                pendingResponses.add(PRIMARY_DEVICE_ATTRIBUTES)
                2
            }
            '=' -> {
                applicationKeypadMode = true
                2
            }
            '>' -> {
                applicationKeypadMode = false
                2
            }
            else -> 2
        }
    }

    private fun consumeCsi(text: String, start: Int, c1: Boolean): Int {
        var index = start + if (c1) 1 else 2
        while (index < text.length) {
            val final = text[index]
            if (final in '@'..'~') {
                val sequence = text.substring(start, index + 1)
                handleCsi(sequence, final)
                return index - start + 1
            }
            index++
        }
        return text.length - start
    }

    private fun consumeOrphanPrivateCsi(text: String, start: Int): Int {
        if (start + 1 >= text.length || text[start + 1] != '?') return 0
        var index = start + 2
        while (index < text.length) {
            val final = text[index]
            if (final in '@'..'~') {
                handleCsi("\u001B" + text.substring(start, index + 1), final)
                return index - start + 1
            }
            val valid = final.isDigit() || final == ';' || final in '<'..'?'
            if (!valid) return 0
            index++
        }
        return 0
    }

    private fun handleCsi(sequence: String, final: Char) {
        if (handleDecRectangleControlSequence(sequence, final)) return
        if (sequence.isKeyboardProtocolSequence(final)) return
        when (final) {
            '@' -> insertBlankCharacters(sequence.extractFirstNumber(defaultValue = 1))
            'A' -> moveCursorRows(-sequence.extractFirstNumber(defaultValue = 1))
            'B', 'e' -> moveCursorRows(sequence.extractFirstNumber(defaultValue = 1))
            'C', 'a' -> moveCursorColumns(sequence.extractFirstNumber(defaultValue = 1))
            'D' -> moveCursorColumns(-sequence.extractFirstNumber(defaultValue = 1))
            'E' -> {
                moveCursorRows(sequence.extractFirstNumber(defaultValue = 1))
                screen.cursorColumn = 0
            }
            'F' -> {
                moveCursorRows(-sequence.extractFirstNumber(defaultValue = 1))
                screen.cursorColumn = 0
            }
            'G', '`' -> screen.cursorColumn = (sequence.extractFirstNumber(defaultValue = 1) - 1)
                .coerceIn(0, terminalSize.columns - 1)
            'H', 'f' -> moveCursorTo(sequence)
            'I' -> moveToNextTabStop(sequence.extractFirstNumber(defaultValue = 1))
            'J' -> handleEraseScreen(sequence)
            'K' -> handleEraseLine(sequence)
            'L' -> insertLines(sequence.extractFirstNumber(defaultValue = 1))
            'M' -> deleteLines(sequence.extractFirstNumber(defaultValue = 1))
            'P' -> deleteCharacters(sequence.extractFirstNumber(defaultValue = 1))
            'S' -> repeat(sequence.extractFirstNumber(defaultValue = 1)) { scrollUpInRegion(screen.scrollTop, screen.scrollBottom) }
            'T' -> repeat(sequence.extractFirstNumber(defaultValue = 1)) { scrollDownInRegion(screen.scrollTop, screen.scrollBottom) }
            'X' -> eraseCharacters(sequence.extractFirstNumber(defaultValue = 1))
            'b' -> repeat(sequence.extractFirstNumber(defaultValue = 1)) { putText(lastPrintable, lastPrintable.displayWidth()) }
            'd' -> {
                val requestedRow = (sequence.extractFirstNumber(defaultValue = 1) - 1)
                    .coerceIn(0, terminalSize.rows - 1)
                screen.cursorRow = screenRowForTerminalRow(requestedRow)
                ensureCursorLine()
            }
            'g' -> clearTabStops(sequence)
            'c' -> handleDeviceAttributes(sequence)
            'h', 'l' -> handleMode(sequence, final)
            'm' -> {
                if (sequence.isPlainSgrSequence()) {
                    currentStyle = currentStyle.applySgr(sequence.extractNumbers(defaultZeroWhenEmpty = true))
                }
            }
            'n' -> handleDeviceStatusReport(sequence)
            'p' -> handleSoftResetOrModeStatusReport(sequence)
            'q' -> handleCursorStyle(sequence)
            'r' -> setScrollRegion(sequence)
            's' -> {
                if (leftRightMarginMode && sequence.extractNumbers(defaultZeroWhenEmpty = false).size >= 2) {
                    setLeftRightMargins(sequence)
                } else {
                    saveCursor()
                }
            }
            't' -> handleWindowManipulation(sequence)
            'u' -> {
                if (sequence.isPlainCursorRestoreSequence()) restoreCursor()
            }
            'Z' -> moveToPreviousTabStop(sequence.extractFirstNumber(defaultValue = 1))
        }
    }

    private fun consumeCharsetDesignation(text: String, start: Int): Int {
        if (start + 2 >= text.length) return 2
        val useDecSpecialGraphics = text[start + 2] == '0'
        when (text[start + 1]) {
            '(' -> g0UsesDecSpecialGraphics = useDecSpecialGraphics
            ')' -> g1UsesDecSpecialGraphics = useDecSpecialGraphics
            '*' -> g2UsesDecSpecialGraphics = useDecSpecialGraphics
            '+' -> g3UsesDecSpecialGraphics = useDecSpecialGraphics
        }
        return 3
    }

    private fun consumeEscHash(text: String, start: Int): Int {
        if (start + 2 >= text.length) return 2
        if (text[start + 2] == '8') {
            fillScreenWithAlignmentPattern()
        }
        return 3
    }

    private fun handleDeviceAttributes(sequence: String) {
        pendingResponses.add(
            if (sequence.contains('>')) SECONDARY_DEVICE_ATTRIBUTES else PRIMARY_DEVICE_ATTRIBUTES
        )
    }

    private fun handleDeviceStatusReport(sequence: String) {
        val nums = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        when (nums.firstOrNull() ?: 0) {
            5 -> pendingResponses.add("\u001B[0n")
            6 -> {
                val row = reportedCursorRow()
                val column = screen.cursorColumn + 1
                val privatePrefix = if (sequence.contains('?')) "?" else ""
                pendingResponses.add("\u001B[${privatePrefix}${row};${column}R")
            }
            15 -> pendingResponses.add("\u001B[?13n")
            25 -> pendingResponses.add("\u001B[?20n")
            26 -> pendingResponses.add("\u001B[?27;1n")
        }
    }

    private fun handleWindowManipulation(sequence: String) {
        when (sequence.extractNumbers(defaultZeroWhenEmpty = false).firstOrNull()) {
            11 -> pendingResponses.add("\u001B[1t")
            13 -> pendingResponses.add("\u001B[3;0;0t")
            14 -> pendingResponses.add("\u001B[4;${terminalSize.heightPixels};${terminalSize.widthPixels}t")
            16 -> {
                val cellWidth = (terminalSize.widthPixels / terminalSize.columns).coerceAtLeast(1)
                val cellHeight = (terminalSize.heightPixels / terminalSize.rows).coerceAtLeast(1)
                pendingResponses.add("\u001B[6;${cellHeight};${cellWidth}t")
            }
            18, 19 -> pendingResponses.add("\u001B[8;${terminalSize.rows};${terminalSize.columns}t")
        }
    }

    private fun handleCursorStyle(sequence: String) {
        val content = sequence.csiContentBeforeFinal('q')
        if (!content.endsWith(" ")) return
        cursorStyle = sequence.extractFirstNumber(defaultValue = DEFAULT_CURSOR_STYLE)
            .coerceIn(DEFAULT_CURSOR_STYLE, MAX_CURSOR_STYLE)
    }

    private fun handleDecRectangleControlSequence(sequence: String, final: Char): Boolean {
        if ('$' !in sequence) return false
        when (final) {
            'x' -> fillRectangle(sequence)
            'z' -> eraseRectangle(sequence)
            'r', 't', 'v', 'w', '{', '}' -> Unit
            else -> return false
        }
        return true
    }

    private fun fillRectangle(sequence: String) {
        val numbers = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        val fillCodePoint = numbers.firstOrNull() ?: return
        val bounds = rectangleBounds(numbers, coordinateStartIndex = 1) ?: return
        val fillText = printableSingleWidthText(fillCodePoint)
        updateRectangle(bounds) { Cell(fillText, currentStyle) }
    }

    private fun eraseRectangle(sequence: String) {
        val bounds = rectangleBounds(sequence.extractNumbers(defaultZeroWhenEmpty = false), coordinateStartIndex = 0)
            ?: return
        updateRectangle(bounds) { blankCell() }
    }

    private fun rectangleBounds(numbers: List<Int>, coordinateStartIndex: Int): RectangleBounds? {
        val requestedTop = (numbers.getOrNull(coordinateStartIndex) ?: 1) - 1
        val requestedLeft = (numbers.getOrNull(coordinateStartIndex + 1) ?: 1) - 1
        val requestedBottom = (numbers.getOrNull(coordinateStartIndex + 2) ?: terminalSize.rows) - 1
        val requestedRight = (numbers.getOrNull(coordinateStartIndex + 3) ?: terminalSize.columns) - 1
        if (requestedTop > requestedBottom || requestedLeft > requestedRight) return null
        val top = requestedTop.coerceIn(0, terminalSize.rows - 1)
        val left = requestedLeft.coerceIn(0, terminalSize.columns - 1)
        val bottom = requestedBottom.coerceIn(top, terminalSize.rows - 1)
        val right = requestedRight.coerceIn(left, terminalSize.columns - 1)
        if (top > bottom || left > right) return null
        val base = terminalViewportTopRow()
        return RectangleBounds(
            top = base + top,
            left = left,
            bottom = base + bottom,
            right = right
        )
    }

    private fun updateRectangle(bounds: RectangleBounds, cellFactory: () -> Cell) {
        ensureLineExists(bounds.bottom)
        for (row in bounds.top..bounds.bottom) {
            val line = screen.lines[row]
            ensureLineSize(line, bounds.right)
            if (bounds.left > 0 && line.getOrNull(bounds.left)?.continuation == true) {
                line[bounds.left - 1] = blankCell()
            }
            for (column in bounds.left..bounds.right) {
                line[column] = cellFactory()
            }
            if (line.getOrNull(bounds.right + 1)?.continuation == true) {
                line[bounds.right + 1] = blankCell()
            }
        }
        if (isInAlternateScreen) ensureAlternateScreenShape()
    }

    private fun printableSingleWidthText(codePoint: Int): String {
        return if (codePoint > 0 && codePoint.displayWidth() == 1) {
            mapPrintableCodePoint(codePoint)
        } else {
            " "
        }
    }

    private fun fillScreenWithAlignmentPattern() {
        val startRow = if (isInAlternateScreen) 0 else visiblePrimaryTopRow()
        while (screen.lines.size < startRow + terminalSize.rows) {
            screen.lines.add(mutableListOf())
        }
        for (row in startRow until startRow + terminalSize.rows) {
            val line = screen.lines[row]
            ensureLineSize(line, terminalSize.columns - 1)
            for (column in 0 until terminalSize.columns) {
                line[column] = Cell("E", currentStyle)
            }
        }
        screen.cursorRow = startRow
        screen.cursorColumn = 0
    }

    private fun moveCursorTo(sequence: String) {
        val nums = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        val requestedRow = ((nums.getOrNull(0) ?: 1) - 1).coerceIn(0, terminalSize.rows - 1)
        screen.cursorRow = screenRowForTerminalRow(requestedRow)
        val requestedColumn = ((nums.getOrNull(1) ?: 1) - 1).coerceIn(0, terminalSize.columns - 1)
        val horizontalBounds = horizontalBounds()
        screen.cursorColumn = if (leftRightMarginMode) {
            (horizontalBounds.first + requestedColumn).coerceIn(horizontalBounds)
        } else {
            requestedColumn.coerceIn(horizontalBounds)
        }
        ensureCursorLine()
    }

    private fun moveCursorRows(delta: Int) {
        val bounds = cursorRowBounds()
        screen.cursorRow = (screen.cursorRow + delta).coerceIn(bounds.first, bounds.last)
        ensureCursorLine()
    }

    private fun moveCursorColumns(delta: Int) {
        screen.cursorColumn = (screen.cursorColumn + delta).coerceIn(horizontalBounds())
    }

    private fun setTabStop() {
        tabStops.add(screen.cursorColumn.coerceIn(horizontalBounds()))
    }

    private fun clearTabStops(sequence: String) {
        when (sequence.extractFirstNumber(defaultValue = 0)) {
            0 -> tabStops.remove(screen.cursorColumn)
            3 -> tabStops.clear()
        }
    }

    private fun moveToNextTabStop(count: Int = 1) {
        repeat(count.coerceAtLeast(1)) {
            screen.cursorColumn = nextTabStopAfter(screen.cursorColumn)
        }
    }

    private fun moveToPreviousTabStop(count: Int = 1) {
        repeat(count.coerceAtLeast(1)) {
            screen.cursorColumn = previousTabStopBefore(screen.cursorColumn)
        }
    }

    private fun nextTabStopAfter(column: Int): Int {
        val horizontalBounds = horizontalBounds()
        return tabStops
            .filter { it > column }
            .minOrNull()
            ?.coerceIn(horizontalBounds)
            ?: horizontalBounds.last
    }

    private fun previousTabStopBefore(column: Int): Int {
        val horizontalBounds = horizontalBounds()
        return tabStops
            .filter { it < column }
            .maxOrNull()
            ?.coerceIn(horizontalBounds)
            ?: horizontalBounds.first
    }

    private fun saveCursor() {
        screen.savedCursorRow = screen.cursorRow
        screen.savedCursorColumn = screen.cursorColumn
        savedStyle = currentStyle
    }

    private fun restoreCursor() {
        val bounds = cursorRowBounds()
        screen.cursorRow = screen.savedCursorRow.coerceIn(bounds.first, bounds.last)
        screen.cursorColumn = screen.savedCursorColumn.coerceIn(horizontalBounds())
        currentStyle = savedStyle
        ensureCursorLine()
    }

    private fun handleMode(sequence: String, final: Char) {
        if (sequence.contains('?')) {
            handlePrivateMode(sequence, final)
        } else {
            handleStandardMode(sequence, final)
        }
    }

    private fun handleStandardMode(sequence: String, final: Char) {
        val enable = final == 'h'
        val modes = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        modes.forEach { mode ->
            when (mode) {
                4 -> insertMode = enable
            }
        }
    }

    private fun handlePrivateMode(sequence: String, final: Char) {
        val enable = final == 'h'
        val modes = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        modes.forEach { mode ->
            when (mode) {
                1 -> applicationCursorKeys = enable
                6 -> {
                    originMode = enable
                    screen.cursorRow = screenRowForTerminalRow(0)
                    screen.cursorColumn = 0
                    ensureCursorLine()
                }
                7 -> autoWrap = enable
                12 -> cursorBlinking = enable
                25 -> cursorVisible = enable
                66 -> applicationKeypadMode = enable
                69 -> {
                    leftRightMarginMode = enable
                    if (!enable) {
                        resetHorizontalMargins(primaryScreen)
                        resetHorizontalMargins(alternateScreenState)
                    }
                    screen.cursorColumn = screen.cursorColumn.coerceIn(horizontalBounds())
                }
                47 -> if (enable) enterAlternateScreen(clear = true) else leaveAlternateScreen()
                1047 -> if (enable) enterAlternateScreen(clear = true) else leaveAlternateScreen()
                1048 -> if (enable) saveCursor() else restoreCursor()
                1049 -> if (enable) {
                    saveCursor()
                    enterAlternateScreen(clear = true)
                } else {
                    leaveAlternateScreen()
                        restoreCursor()
                    }
                1000, 1002, 1003, 1005, 1006, 1015 -> {
                    if (enable) mouseModes.add(mode) else mouseModes.remove(mode)
                }
                1004 -> focusEventMode = enable
                2004 -> bracketedPasteMode = enable
                2026 -> isSynchronizedOutputMode = enable
            }
        }
    }

    private fun handleModeStatusReport(sequence: String) {
        if (!sequence.contains('$')) return
        val private = sequence.contains('?')
        val mode = sequence.extractNumbers(defaultZeroWhenEmpty = false).firstOrNull() ?: return
        val state = if (private) privateModeReportState(mode) else standardModeReportState(mode)
        val prefix = if (private) "?" else ""
        pendingResponses.add("\u001B[${prefix}${mode};${state}\$y")
    }

    private fun standardModeReportState(mode: Int): Int {
        val enabled = when (mode) {
            4 -> insertMode
            else -> return MODE_REPORT_NOT_RECOGNIZED
        }
        return modeReportState(enabled)
    }

    private fun privateModeReportState(mode: Int): Int {
        val enabled = when (mode) {
            1 -> applicationCursorKeys
            6 -> originMode
            7 -> autoWrap
            12 -> cursorBlinking
            25 -> cursorVisible
            66 -> applicationKeypadMode
            69 -> leftRightMarginMode
            47, 1047, 1049 -> isInAlternateScreen
            1000, 1002, 1003, 1005, 1006, 1015 -> mode in mouseModes
            1004 -> focusEventMode
            2004 -> bracketedPasteMode
            2026 -> isSynchronizedOutputMode
            else -> return MODE_REPORT_NOT_RECOGNIZED
        }
        return modeReportState(enabled)
    }

    private fun modeReportState(enabled: Boolean): Int {
        return if (enabled) MODE_REPORT_SET else MODE_REPORT_RESET
    }

    private fun handleSoftResetOrModeStatusReport(sequence: String) {
        if (sequence.contains('!')) {
            softReset()
        } else {
            handleModeStatusReport(sequence)
        }
    }

    private fun softReset() {
        currentStyle = Style.DEFAULT
        savedStyle = Style.DEFAULT
        insertMode = false
        originMode = false
        leftRightMarginMode = false
        autoWrap = true
        applicationCursorKeys = false
        applicationKeypadMode = false
        isSynchronizedOutputMode = false
        cursorVisible = true
        cursorBlinking = false
        cursorStyle = DEFAULT_CURSOR_STYLE
        bracketedPasteMode = false
        focusEventMode = false
        mouseModes.clear()
        g0UsesDecSpecialGraphics = false
        g1UsesDecSpecialGraphics = false
        g2UsesDecSpecialGraphics = false
        g3UsesDecSpecialGraphics = false
        usingG1Charset = false
        singleShiftCharset = null
        resetScrollRegion(screen)
        resetHorizontalMargins(screen)
        screen.cursorRow = screenRowForTerminalRow(0)
        screen.cursorColumn = horizontalBounds().first
        ensureCursorLine()
        if (isInAlternateScreen) ensureAlternateScreenShape()
    }

    private fun enterAlternateScreen(clear: Boolean) {
        markHistoryUnsafe()
        historySuppressed = true
        if (!isInAlternateScreen) {
            screen = alternateScreenState
            isInAlternateScreen = true
        }
        if (clear) clearScreen(alternateScreenState)
        ensureAlternateScreenShape()
    }

    private fun leaveAlternateScreen() {
        if (!isInAlternateScreen) return
        markHistoryUnsafe()
        historySuppressed = true
        screen = primaryScreen
        isInAlternateScreen = false
        trimPrimaryScrollback()
    }

    private fun handleEraseScreen(sequence: String) {
        val mode = sequence.extractFirstNumber(defaultValue = 0)
        if (!isInAlternateScreen) {
            handlePrimaryEraseScreen(mode)
            return
        }

        when (mode) {
            2, 3 -> clearScreen(screen)
            0 -> {
                ensureCursorLine()
                eraseLineFromCursor(screen.lines[screen.cursorRow])
                for (row in (screen.cursorRow + 1)..screen.lines.lastIndex) {
                    screen.lines[row].clear()
                }
                if (isInAlternateScreen) ensureAlternateScreenShape()
            }
            1 -> {
                for (row in 0 until screen.cursorRow.coerceAtMost(screen.lines.size)) {
                    screen.lines[row].clear()
                }
                if (screen.cursorRow in screen.lines.indices) eraseLineToCursor(screen.lines[screen.cursorRow])
                if (isInAlternateScreen) ensureAlternateScreenShape()
            }
        }
    }

    private fun handlePrimaryEraseScreen(mode: Int) {
        ensureCursorLine()
        val viewportTop = visiblePrimaryTopRow()
        when (mode) {
            0 -> {
                eraseLineFromCursor(screen.lines[screen.cursorRow])
                for (row in (screen.cursorRow + 1)..screen.lines.lastIndex) {
                    screen.lines[row].clear()
                }
            }
            1 -> {
                for (row in viewportTop until screen.cursorRow.coerceAtMost(screen.lines.size)) {
                    screen.lines[row].clear()
                }
                if (screen.cursorRow in screen.lines.indices) eraseLineToCursor(screen.lines[screen.cursorRow])
            }
            2 -> {
                for (row in viewportTop..screen.lines.lastIndex) {
                    screen.lines[row].clear()
                }
            }
            3 -> Unit
        }
    }

    private fun handleEraseLine(sequence: String) {
        ensureCursorLine()
        val line = screen.lines[screen.cursorRow]
        when (sequence.extractFirstNumber(defaultValue = 0)) {
            0 -> eraseLineFromCursor(line)
            1 -> eraseLineToCursor(line)
            2 -> line.clear()
        }
        if (isInAlternateScreen) ensureLineSize(line, terminalSize.columns - 1)
    }

    private fun eraseLineFromCursor(line: MutableList<Cell>) {
        val bounds = horizontalBounds()
        val start = screen.cursorColumn.coerceIn(bounds)
        if (isInAlternateScreen || leftRightMarginMode) {
            ensureLineSize(line, bounds.last)
            for (index in start..bounds.last) line[index] = blankCell()
        } else {
            while (line.size > start) line.removeAt(line.lastIndex)
        }
    }

    private fun eraseLineToCursor(line: MutableList<Cell>) {
        val bounds = horizontalBounds()
        val end = screen.cursorColumn.coerceIn(bounds)
        ensureLineSize(line, end)
        for (index in bounds.first..end) line[index] = blankCell()
    }

    private fun eraseCharacters(count: Int) {
        ensureCursorLine()
        val line = screen.lines[screen.cursorRow]
        val bounds = horizontalBounds()
        val start = screen.cursorColumn.coerceIn(bounds)
        val end = (start + count.coerceAtLeast(0)).coerceAtMost(bounds.last + 1)
        ensureLineSize(line, bounds.last)
        for (index in start until end) line[index] = blankCell()
    }

    private fun insertBlankCharacters(count: Int) {
        ensureCursorLine()
        val line = screen.lines[screen.cursorRow]
        val bounds = horizontalBounds()
        val start = screen.cursorColumn.coerceIn(bounds)
        ensureLineSize(line, bounds.last)
        repeat(count.coerceAtLeast(0)) {
            for (index in bounds.last downTo (start + 1)) {
                line[index] = line[index - 1].copy()
            }
            line[start] = blankCell()
        }
    }

    private fun deleteCharacters(count: Int) {
        ensureCursorLine()
        val line = screen.lines[screen.cursorRow]
        val bounds = horizontalBounds()
        val start = screen.cursorColumn.coerceIn(bounds)
        val amount = count.coerceAtLeast(0).coerceAtMost(bounds.last - start + 1)
        ensureLineSize(line, bounds.last)
        for (index in start..(bounds.last - amount)) {
            line[index] = line[index + amount].copy()
        }
        for (index in (bounds.last - amount + 1)..bounds.last) {
            if (index in line.indices) line[index] = blankCell()
        }
    }

    private fun insertLines(count: Int) {
        val region = absoluteScrollRegion()
        val top = if (screen.cursorRow in region) screen.cursorRow else region.first
        repeat(count.coerceAtLeast(0)) {
            ensureLineExists(region.last)
            screen.lines.add(top, mutableListOf())
            val bottom = region.last.coerceAtMost(screen.lines.lastIndex)
            screen.lines.removeAt(bottom)
        }
        if (isInAlternateScreen) ensureAlternateScreenShape()
    }

    private fun deleteLines(count: Int) {
        val region = absoluteScrollRegion()
        val top = if (screen.cursorRow in region) screen.cursorRow else region.first
        repeat(count.coerceAtLeast(0)) {
            ensureLineExists(region.last)
            if (top < screen.lines.size) screen.lines.removeAt(top)
            val bottom = region.last.coerceAtMost(screen.lines.size)
            screen.lines.add(bottom, mutableListOf())
        }
        if (isInAlternateScreen) ensureAlternateScreenShape()
    }

    private fun setLeftRightMargins(sequence: String) {
        val nums = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        val left = ((nums.getOrNull(0) ?: 1) - 1).coerceIn(0, terminalSize.columns - 1)
        val right = ((nums.getOrNull(1) ?: terminalSize.columns) - 1).coerceIn(0, terminalSize.columns - 1)
        if (left >= right) return
        screen.marginLeft = left
        screen.marginRight = right
        screen.cursorRow = screenRowForTerminalRow(0)
        screen.cursorColumn = left
        ensureCursorLine()
    }

    private fun setScrollRegion(sequence: String) {
        val nums = sequence.extractNumbers(defaultZeroWhenEmpty = false)
        val top = ((nums.getOrNull(0) ?: 1) - 1).coerceIn(0, terminalSize.rows - 1)
        val bottom = ((nums.getOrNull(1) ?: terminalSize.rows) - 1).coerceIn(top, terminalSize.rows - 1)
        screen.scrollTop = top
        screen.scrollBottom = bottom
        screen.cursorRow = screenRowForTerminalRow(0)
        screen.cursorColumn = horizontalBounds().first
        ensureCursorLine()
    }

    private fun reverseIndex() {
        if (isInAlternateScreen && screen.cursorRow == screen.scrollTop) {
            scrollDownInRegion(screen.scrollTop, screen.scrollBottom)
        } else {
            screen.cursorRow = (screen.cursorRow - 1).coerceAtLeast(terminalViewportTopRow())
        }
    }

    private fun scrollUpInRegion(top: Int, bottom: Int) {
        ensureAlternateScreenShape()
        val base = terminalViewportTopRow()
        val safeTop = base + top.coerceIn(0, terminalSize.rows - 1)
        val safeBottom = base + bottom.coerceIn(top.coerceIn(0, terminalSize.rows - 1), terminalSize.rows - 1)
        ensureLineExists(safeBottom)
        if (isInAlternateScreen) {
            screen.lines.removeAt(safeTop)
            screen.lines.add(safeBottom, mutableListOf())
            ensureAlternateScreenShape()
        } else {
            if (safeTop < screen.lines.size) screen.lines.removeAt(safeTop)
            screen.lines.add(safeBottom.coerceAtMost(screen.lines.size), mutableListOf())
            screen.cursorRow = (screen.cursorRow - 1).coerceAtLeast(terminalViewportTopRow())
        }
    }

    private fun scrollDownInRegion(top: Int, bottom: Int) {
        ensureAlternateScreenShape()
        val base = terminalViewportTopRow()
        val safeTop = base + top.coerceIn(0, terminalSize.rows - 1)
        val safeBottom = base + bottom.coerceIn(top.coerceIn(0, terminalSize.rows - 1), terminalSize.rows - 1)
        ensureLineExists(safeBottom)
        screen.lines.add(safeTop, mutableListOf())
        screen.lines.removeAt(safeBottom + 1)
        ensureAlternateScreenShape()
    }

    private fun clearScreen(target: ScreenState) {
        target.lines.clear()
        target.lines.add(mutableListOf())
        target.cursorRow = 0
        target.cursorColumn = 0
        target.savedCursorRow = 0
        target.savedCursorColumn = 0
        resetScrollRegion(target)
        resetHorizontalMargins(target)
        if (target === alternateScreenState) ensureAlternateScreenShape()
    }

    private fun resetScrollRegion(target: ScreenState) {
        target.scrollTop = 0
        target.scrollBottom = terminalSize.rows - 1
    }

    private fun resetHorizontalMargins(target: ScreenState) {
        target.marginLeft = 0
        target.marginRight = terminalSize.columns - 1
    }

    private fun horizontalBounds(): IntRange {
        val left = if (leftRightMarginMode) screen.marginLeft else 0
        val right = if (leftRightMarginMode) screen.marginRight else terminalSize.columns - 1
        return left.coerceIn(0, terminalSize.columns - 1)..right.coerceIn(left, terminalSize.columns - 1)
    }

    private fun maxAddressableRow(): Int {
        return if (isInAlternateScreen) {
            terminalSize.rows - 1
        } else {
            max(screen.lines.lastIndex, terminalSize.rows - 1)
        }
    }

    private fun visiblePrimaryTopRow(): Int {
        return (primaryScreen.lines.size - terminalSize.rows).coerceAtLeast(0)
    }

    private fun terminalViewportTopRow(): Int {
        return if (isInAlternateScreen) 0 else visiblePrimaryTopRow()
    }

    private fun absoluteScrollRegion(): IntRange {
        val base = terminalViewportTopRow()
        return (base + screen.scrollTop)..(base + screen.scrollBottom)
    }

    private fun ensureLineExists(row: Int) {
        while (row >= screen.lines.size) {
            screen.lines.add(mutableListOf())
        }
    }

    private fun screenRowForTerminalRow(requestedRow: Int): Int {
        val rowInViewport = if (originMode) {
            (screen.scrollTop + requestedRow).coerceIn(screen.scrollTop, screen.scrollBottom)
        } else {
            requestedRow.coerceIn(0, terminalSize.rows - 1)
        }
        return if (isInAlternateScreen) rowInViewport else visiblePrimaryTopRow() + rowInViewport
    }

    private fun cursorRowBounds(): IntRange {
        if (originMode) {
            val base = terminalViewportTopRow()
            return (base + screen.scrollTop)..(base + screen.scrollBottom)
        }
        return terminalViewportTopRow()..maxAddressableRow()
    }

    private fun reportedCursorRow(): Int {
        val base = when {
            originMode && isInAlternateScreen -> screen.scrollTop
            originMode -> visiblePrimaryTopRow() + screen.scrollTop
            isInAlternateScreen -> 0
            else -> visiblePrimaryTopRow()
        }
        return (screen.cursorRow - base).coerceAtLeast(0) + 1
    }

    private fun ensureAlternateScreenShape() {
        while (alternateScreenState.lines.size < terminalSize.rows) {
            alternateScreenState.lines.add(mutableListOf())
        }
        while (alternateScreenState.lines.size > terminalSize.rows) {
            alternateScreenState.lines.removeAt(alternateScreenState.lines.lastIndex)
        }
        alternateScreenState.lines.forEach { line ->
            while (line.size > terminalSize.columns) line.removeAt(line.lastIndex)
        }
        alternateScreenState.cursorRow = alternateScreenState.cursorRow.coerceIn(0, terminalSize.rows - 1)
        alternateScreenState.cursorColumn = alternateScreenState.cursorColumn.coerceIn(0, terminalSize.columns - 1)
    }

    private fun consumeOsc(text: String, start: Int, prefixLength: Int): Int {
        var index = start + prefixLength
        while (index < text.length) {
            if (text[index] == ST_C1.toChar()) return index - start + 1
            if (text[index] == '\u0007') return index - start + 1
            if (text[index] == '\u001B' && index + 1 < text.length && text[index + 1] == '\\') {
                return index - start + 2
            }
            index++
        }
        return text.length - start
    }

    private fun consumeControlString(text: String, start: Int, prefixLength: Int): Int {
        var index = start + prefixLength
        while (index < text.length) {
            if (text[index] == ST_C1.toChar()) return index - start + 1
            if (text[index] == '\u001B' && index + 1 < text.length && text[index + 1] == '\\') {
                return index - start + 2
            }
            index++
        }
        return text.length - start
    }

    private fun consumeDcs(text: String, start: Int, prefixLength: Int): Int {
        var index = start + prefixLength
        while (index < text.length) {
            if (text[index] == ST_C1.toChar()) {
                handleDcsPayload(text.substring(start + prefixLength, index))
                return index - start + 1
            }
            if (text[index] == '\u001B' && index + 1 < text.length && text[index + 1] == '\\') {
                handleDcsPayload(text.substring(start + prefixLength, index))
                return index - start + 2
            }
            index++
        }
        return text.length - start
    }

    private fun handleDcsPayload(payload: String) {
        if (!payload.startsWith("\$q")) return
        val request = payload.removePrefix("\$q")
        val response = decrqssResponse(request)
        pendingResponses.add(response)
    }

    private fun decrqssResponse(request: String): String {
        val status = when (request) {
            "m" -> "${currentStyle.toSgrContent()}m"
            "r" -> "${screen.scrollTop + 1};${screen.scrollBottom + 1}r"
            "s" -> "${screen.marginLeft + 1};${screen.marginRight + 1}s"
            " q" -> "$cursorStyle q"
            else -> null
        }
        val valid = if (status == null) "0" else "1"
        return DCS + valid + "\$r" + status.orEmpty() + ST
    }

    private fun findIncompleteEscapeStart(text: String): Int {
        val esc = maxOf(
            text.lastIndexOf('\u001B'),
            text.lastIndexOf('\u009B'),
            text.lastIndexOf('\u009D'),
            text.lastIndexOf('\u0090'),
            text.lastIndexOf('\u0098'),
            text.lastIndexOf('\u009E'),
            text.lastIndexOf('\u009F')
        )
        if (esc < 0) return -1
        if (esc == text.lastIndex) return esc
        if (text[esc] == '\u009B') {
            return if (text.substring(esc + 1).none { it in '@'..'~' }) esc else -1
        }
        if (text[esc] == '\u009D') {
            val tail = text.substring(esc + 1)
            val hasC1St = tail.contains(ST_C1.toChar())
            val hasBell = tail.contains('\u0007')
            val hasSt = tail.contains("\u001B\\")
            return if (!hasBell && !hasSt && !hasC1St) esc else -1
        }
        if (text[esc] in C1_STRING_CONTROLS) {
            val tail = text.substring(esc + 1)
            val hasSt = tail.contains("\u001B\\") || tail.contains(ST_C1.toChar())
            return if (!hasSt) esc else -1
        }
        return when (text[esc + 1]) {
            '[' -> if (text.substring(esc + 2).none { it in '@'..'~' }) esc else -1
            ']' -> {
                val tail = text.substring(esc + 2)
                val hasC1St = tail.contains(ST_C1.toChar())
                val hasBell = tail.contains('\u0007')
                val hasSt = tail.contains("\u001B\\")
                if (!hasBell && !hasSt && !hasC1St) esc else -1
            }
            'P', 'X', '^', '_' -> {
                val tail = text.substring(esc + 2)
                val hasSt = tail.contains("\u001B\\") || tail.contains(ST_C1.toChar())
                if (!hasSt) esc else -1
            }
            else -> -1
        }
    }

    private fun Style.applySgr(codes: List<Int>): Style {
        var next = this
        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> next = Style.DEFAULT
                1 -> next = next.copy(bold = true)
                2 -> next = next.copy(dim = true)
                3 -> next = next.copy(italic = true)
                4 -> next = next.copy(underline = true)
                7 -> next = next.copy(inverse = true)
                9 -> next = next.copy(strikethrough = true)
                22 -> next = next.copy(bold = false, dim = false)
                23 -> next = next.copy(italic = false)
                24 -> next = next.copy(underline = false)
                27 -> next = next.copy(inverse = false)
                29 -> next = next.copy(strikethrough = false)
                30, 31, 32, 33, 34, 35, 36, 37, in 90..97 -> next = next.copy(foreground = listOf(code))
                39 -> next = next.copy(foreground = null)
                40, 41, 42, 43, 44, 45, 46, 47, in 100..107 -> next = next.copy(background = listOf(code))
                49 -> next = next.copy(background = null)
                38 -> {
                    val parsed = parseExtendedColor(codes, index)
                    if (parsed != null) {
                        next = next.copy(foreground = parsed.first)
                        index = parsed.second
                    }
                }
                48 -> {
                    val parsed = parseExtendedColor(codes, index)
                    if (parsed != null) {
                        next = next.copy(background = parsed.first)
                        index = parsed.second
                    }
                }
            }
            index++
        }
        return next
    }

    private fun Style.toSgrContent(): String {
        val ansi = toAnsi()
        return ansi
            .removePrefix("\u001B[")
            .removeSuffix("m")
            .ifBlank { "0" }
    }

    private fun parseExtendedColor(codes: List<Int>, start: Int): Pair<List<Int>, Int>? {
        if (start + 2 >= codes.size) return null
        return when (codes[start + 1]) {
            5 -> listOf(codes[start], 5, codes[start + 2]) to (start + 2)
            2 -> if (start + 4 < codes.size) {
                listOf(codes[start], 2, codes[start + 2], codes[start + 3], codes[start + 4]) to (start + 4)
            } else {
                null
            }
            else -> null
        }
    }

    private fun String.extractFirstNumber(defaultValue: Int): Int {
        return extractNumbers(defaultZeroWhenEmpty = false).firstOrNull() ?: defaultValue
    }

    private fun String.isPlainSgrSequence(): Boolean {
        val content = removePrefix("\u001B[").removePrefix("\u009B").removeSuffix("m")
        return content.all { it.isDigit() || it == ';' || it == ':' }
    }

    private fun String.isPlainCursorRestoreSequence(): Boolean {
        val content = csiContentBeforeFinal('u')
        return content.isBlank()
    }

    private fun String.isKeyboardProtocolSequence(final: Char): Boolean {
        if (final != 'u') return false
        val content = csiContentBeforeFinal(final)
        return content.contains('>') || content.contains('=') || content.contains('?')
    }

    private fun String.isDecRectangleControlSequence(final: Char): Boolean {
        if ('$' !in this) return false
        return final in setOf('r', 't', 'v', 'w', 'x', 'z', '{', '}')
    }

    private fun String.csiContentBeforeFinal(final: Char): String {
        return removePrefix("\u001B[")
            .removePrefix("\u009B")
            .removeSuffix(final.toString())
    }

    private fun String.extractNumbers(defaultZeroWhenEmpty: Boolean): List<Int> {
        val matches = Regex("\\d+").findAll(this).mapNotNull { it.value.toIntOrNull() }.toList()
        return if (matches.isEmpty() && defaultZeroWhenEmpty) listOf(0) else matches
    }

    private fun String.displayWidth(): Int {
        if (isEmpty()) return 0
        val codePoint = codePointAt(0)
        return codePoint.displayWidth()
    }

    private fun mapPrintableCodePoint(codePoint: Int): String {
        val usesDecSpecialGraphics = when (singleShiftCharset) {
            2 -> g2UsesDecSpecialGraphics
            3 -> g3UsesDecSpecialGraphics
            else -> if (usingG1Charset) {
                g1UsesDecSpecialGraphics
            } else {
                g0UsesDecSpecialGraphics
            }
        }
        if (!usesDecSpecialGraphics) return String(Character.toChars(codePoint))
        return decSpecialGraphics[codePoint.toChar()] ?: String(Character.toChars(codePoint))
    }

    private fun Int.displayWidth(): Int {
        if (this == 0) return 0
        if (this in 0x0300..0x036F ||
            this in 0x1AB0..0x1AFF ||
            this in 0x1DC0..0x1DFF ||
            this in 0x200B..0x200F ||
            this in 0x202A..0x202E ||
            this in 0x2060..0x206F ||
            this in 0x20D0..0x20FF ||
            this in 0xFE00..0xFE0F ||
            this in 0xFE20..0xFE2F ||
            this in 0xE0100..0xE01EF
        ) {
            return 0
        }
        if (this >= 0x1100 && (
                this <= 0x115F ||
                    this == 0x2329 ||
                    this == 0x232A ||
                    this in 0x2E80..0xA4CF ||
                    this in 0xAC00..0xD7A3 ||
                    this in 0xF900..0xFAFF ||
                    this in 0xFE10..0xFE19 ||
                    this in 0xFE30..0xFE6F ||
                    this in 0xFF00..0xFF60 ||
                    this in 0xFFE0..0xFFE6 ||
                    this in 0x1F300..0x1FAFF
                )
        ) {
            return 2
        }
        return 1
    }

    companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 240
        const val MIN_ROWS = 6
        const val MAX_ROWS = 120

        private const val TAB_WIDTH = 8
        private const val ESC = 0x1B
        private const val IND_C1 = 0x84
        private const val NEL_C1 = 0x85
        private const val HTS_C1 = 0x88
        private const val RI_C1 = 0x8D
        private const val CSI_C1 = 0x9B
        private const val ST_C1 = 0x9C
        private const val DCS_C1 = 0x90
        private const val SOS_C1 = 0x98
        private const val OSC_C1 = 0x9D
        private const val PM_C1 = 0x9E
        private const val APC_C1 = 0x9F
        private const val SHIFT_OUT = 0x0E
        private const val SHIFT_IN = 0x0F
        private const val MODE_REPORT_NOT_RECOGNIZED = 0
        private const val MODE_REPORT_SET = 1
        private const val MODE_REPORT_RESET = 2
        private const val DEFAULT_CURSOR_STYLE = 0
        private const val MAX_CURSOR_STYLE = 6
        private const val DCS = "\u001BP"
        private const val ST = "\u001B\\"
        private const val PRIMARY_DEVICE_ATTRIBUTES = "\u001B[?62;1;2;6;9;15;18;21;22c"
        private const val SECONDARY_DEVICE_ATTRIBUTES = "\u001B[>0;136;0c"
        private val C1_CONTROL_CODE_RANGE = 0x80..0x9F
        private val C1_STRING_CONTROLS = setOf(DCS_C1.toChar(), SOS_C1.toChar(), PM_C1.toChar(), APC_C1.toChar())

        private fun defaultTabStops(): MutableSet<Int> {
            return (TAB_WIDTH until MAX_COLUMNS step TAB_WIDTH).toMutableSet()
        }

        private val decSpecialGraphics = mapOf(
            '`' to "◆",
            'a' to "▒",
            'b' to "\t",
            'c' to "\u000C",
            'd' to "\r",
            'e' to "\n",
            'f' to "°",
            'g' to "±",
            'h' to "␤",
            'i' to "\u000B",
            'j' to "┘",
            'k' to "┐",
            'l' to "┌",
            'm' to "└",
            'n' to "┼",
            'o' to "⎺",
            'p' to "⎻",
            'q' to "─",
            'r' to "⎼",
            's' to "⎽",
            't' to "├",
            'u' to "┤",
            'v' to "┴",
            'w' to "┬",
            'x' to "│",
            'y' to "≤",
            'z' to "≥",
            '{' to "π",
            '|' to "≠",
            '}' to "£",
            '~' to "·"
        )
    }
}







