package com.quickssh.app.ui.screens

import com.quickssh.app.utils.TerminalBuffer

internal fun shouldConfirmPaste(text: String): Boolean {
    return text.count { it == '\n' || it == '\r' } > 0
}

internal fun shouldConfirmMultilineInputChange(previousInput: String, nextInput: String): Boolean {
    if (!shouldConfirmPaste(nextInput)) return false
    if (shouldConfirmPaste(previousInput)) return false
    return nextInput.length > previousInput.length
}

internal fun addCommandToHistory(history: List<String>, command: String, maxSize: Int = 30): List<String> {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return history
    return (listOf(trimmed) + history.filterNot { it == trimmed }).take(maxSize)
}

internal fun previousHistoryIndex(history: List<String>, currentIndex: Int): Int {
    if (history.isEmpty()) return -1
    return when {
        currentIndex < 0 -> 0
        currentIndex < history.lastIndex -> currentIndex + 1
        else -> currentIndex
    }
}

internal fun nextHistoryIndex(history: List<String>, currentIndex: Int): Int {
    if (history.isEmpty() || currentIndex <= 0) return -1
    return currentIndex - 1
}

internal fun terminalDisplayFontSizeSp(savedFontSizeSp: Int): Int {
    return savedFontSizeSp.coerceIn(10, 20)
}

internal fun terminalDisplayLineHeightSp(fontSizeSp: Int): Int {
    return (fontSizeSp + 1).coerceIn(12, 22)
}

internal fun isTerminalNearBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    threshold: Int = 2
): Boolean {
    if (totalItems <= 0) return true
    if (lastVisibleIndex < 0) return false
    return lastVisibleIndex >= totalItems - 1 - threshold
}

internal fun nextTerminalAutoFollowState(
    currentAutoFollow: Boolean,
    isNearBottom: Boolean,
    isScrollInProgress: Boolean
): Boolean {
    return when {
        isNearBottom -> true
        isScrollInProgress -> false
        else -> currentAutoFollow
    }
}

internal fun terminalScrollTargetIndex(
    totalItems: Int,
    alternateScreen: Boolean,
    autoFollowOutput: Boolean,
    userScrollInProgress: Boolean = false
): Int? {
    if (totalItems <= 0) return null
    if (alternateScreen) return 0
    if (userScrollInProgress) return null
    if (!autoFollowOutput) return null
    return totalItems - 1
}

internal fun shouldDispatchTerminalResize(
    previousSize: TerminalBuffer.Size?,
    nextSize: TerminalBuffer.Size
): Boolean {
    val previous = previousSize ?: return true
    val widthChanged = previous.columns != nextSize.columns || previous.widthPixels != nextSize.widthPixels
    return widthChanged
}

internal fun shouldShowJumpToLatestButton(
    totalItems: Int,
    alternateScreen: Boolean,
    autoFollowOutput: Boolean,
    copyMode: Boolean
): Boolean {
    return totalItems > 0 && !alternateScreen && !autoFollowOutput && !copyMode
}

internal fun terminalPastePayload(text: String, bracketedPasteMode: Boolean): String {
    return if (bracketedPasteMode) {
        "\u001B[200~$text\u001B[201~"
    } else {
        text
    }
}

internal fun appendTerminalInputReference(currentInput: String, reference: String): String {
    val trimmedReference = reference.trim()
    if (trimmedReference.isBlank()) return currentInput
    if (currentInput.isBlank()) return trimmedReference
    return if (currentInput.last().isWhitespace()) {
        currentInput + trimmedReference
    } else {
        "$currentInput $trimmedReference"
    }
}

internal fun terminalCursorKeySequence(direction: String, applicationCursorKeys: Boolean): String {
    val final = when (direction.lowercase()) {
        "up" -> 'A'
        "down" -> 'B'
        "right" -> 'C'
        "left" -> 'D'
        else -> return direction
    }
    return if (applicationCursorKeys) "\u001BO$final" else "\u001B[$final"
}

internal fun terminalHomeEndKeySequence(key: String, applicationCursorKeys: Boolean): String {
    val final = when (key.lowercase()) {
        "home" -> 'H'
        "end" -> 'F'
        else -> return key
    }
    return if (applicationCursorKeys) "\u001BO$final" else "\u001B[$final"
}

internal fun shellSafePathReference(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return "''"
    return if (isWindowsPathReference(trimmed)) {
        "\"" + trimmed.replace("\"", "") + "\""
    } else {
        "'" + trimmed.replace("'", "'\"'\"'") + "'"
    }
}

private fun isWindowsPathReference(path: String): Boolean {
    return Regex("^[A-Za-z]:[\\\\/].*").matches(path) || path.startsWith("\\\\")
}
