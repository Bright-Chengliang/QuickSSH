package com.quickssh.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.quickssh.app.utils.TerminalBuffer

class TerminalInputHelpersTest {
    @Test
    fun shouldConfirmPasteOnlyForMultilineText() {
        assertFalse(shouldConfirmPaste("ls -la"))
        assertTrue(shouldConfirmPaste("cd /tmp\nls"))
    }

    @Test
    fun multilineInputChangeOnlyConfirmsNewMultilinePaste() {
        assertFalse(shouldConfirmMultilineInputChange("git", "git status"))
        assertTrue(shouldConfirmMultilineInputChange("git ", "git add .\ngit status"))
        assertFalse(shouldConfirmMultilineInputChange("git add .\ngit status", "git add .\ngit diff"))
        assertFalse(shouldConfirmMultilineInputChange("git add .\ngit status", "git add ."))
        assertFalse(shouldConfirmMultilineInputChange("ls", "ls\n"))
        assertFalse(shouldConfirmMultilineInputChange("ls", "ls\r\n"))
    }

    @Test
    fun addCommandToHistoryDeduplicatesAndTrims() {
        assertEquals(
            listOf("pwd", "ls"),
            addCommandToHistory(listOf("ls"), " pwd ")
        )
        assertEquals(
            listOf("ls", "pwd"),
            addCommandToHistory(listOf("pwd", "ls"), "ls")
        )
    }

    @Test
    fun historyIndexMovesWithinBounds() {
        val history = listOf("new", "old")
        assertEquals(0, previousHistoryIndex(history, -1))
        assertEquals(1, previousHistoryIndex(history, 0))
        assertEquals(1, previousHistoryIndex(history, 1))
        assertEquals(0, nextHistoryIndex(history, 1))
        assertEquals(-1, nextHistoryIndex(history, 0))
    }

    @Test
    fun terminalDisplayTypographyUsesComfortableDefaults() {
        assertEquals(12, terminalDisplayFontSizeSp(12))
        assertEquals(10, terminalDisplayFontSizeSp(10))
        assertEquals(13, terminalDisplayLineHeightSp(12))
        assertEquals(17, terminalDisplayLineHeightSp(16))
    }

    @Test
    fun terminalNearBottomAllowsSmallStreamingGap() {
        assertTrue(isTerminalNearBottom(totalItems = 100, lastVisibleIndex = 99))
        assertTrue(isTerminalNearBottom(totalItems = 100, lastVisibleIndex = 97))
        assertFalse(isTerminalNearBottom(totalItems = 100, lastVisibleIndex = 80))
    }

    @Test
    fun terminalAutoFollowOnlyTurnsOffForUserScrollAway() {
        assertTrue(
            nextTerminalAutoFollowState(
                currentAutoFollow = true,
                isNearBottom = false,
                isScrollInProgress = false
            )
        )
        assertFalse(
            nextTerminalAutoFollowState(
                currentAutoFollow = true,
                isNearBottom = false,
                isScrollInProgress = true
            )
        )
        assertTrue(
            nextTerminalAutoFollowState(
                currentAutoFollow = false,
                isNearBottom = true,
                isScrollInProgress = true
            )
        )
    }

    @Test
    fun codexStyleStreamingOutputKeepsBottomAnchorWhenUserIsFollowing() {
        var autoFollow = true

        repeat(200) { index ->
            val totalItems = index + 1
            autoFollow = nextTerminalAutoFollowState(
                currentAutoFollow = autoFollow,
                isNearBottom = true,
                isScrollInProgress = false
            )

            assertEquals(totalItems - 1, terminalScrollTargetIndex(totalItems, alternateScreen = false, autoFollowOutput = autoFollow))
        }
    }

    @Test
    fun codexStyleStreamingOutputDoesNotHijackManualHistoryScroll() {
        var autoFollow = nextTerminalAutoFollowState(
            currentAutoFollow = true,
            isNearBottom = false,
            isScrollInProgress = true
        )
        assertFalse(autoFollow)

        repeat(200) { index ->
            val totalItems = 100 + index
            autoFollow = nextTerminalAutoFollowState(
                currentAutoFollow = autoFollow,
                isNearBottom = false,
                isScrollInProgress = false
            )

            assertEquals(null, terminalScrollTargetIndex(totalItems, alternateScreen = false, autoFollowOutput = autoFollow))
        }
    }

    @Test
    fun alternateScreenAlwaysAnchorsToTopScreenBuffer() {
        assertEquals(0, terminalScrollTargetIndex(totalItems = 24, alternateScreen = true, autoFollowOutput = false))
        assertEquals(null, terminalScrollTargetIndex(totalItems = 0, alternateScreen = true, autoFollowOutput = true))
    }

    @Test
    fun terminalDoesNotProgrammaticallyJumpToBottomDuringUserScroll() {
        assertEquals(
            null,
            terminalScrollTargetIndex(
                totalItems = 4000,
                alternateScreen = false,
                autoFollowOutput = true,
                userScrollInProgress = true
            )
        )
    }

    @Test
    fun imeOnlyTerminalResizeDoesNotDispatchPtyResize() {
        val normal = TerminalBuffer.Size(columns = 80, rows = 24, widthPixels = 800, heightPixels = 480)
        val imeShrunk = normal.copy(rows = 10, heightPixels = 220)

        assertTrue(
            shouldDispatchTerminalResize(
                previousSize = null,
                nextSize = normal
            )
        )
        assertFalse(
            shouldDispatchTerminalResize(
                previousSize = normal,
                nextSize = imeShrunk
            )
        )
        assertFalse(
            shouldDispatchTerminalResize(
                previousSize = normal,
                nextSize = normal
            )
        )
    }

    @Test
    fun terminalResizeStillDispatchesWhenWidthChanges() {
        val previous = TerminalBuffer.Size(columns = 80, rows = 24, widthPixels = 800, heightPixels = 480)
        val rotated = TerminalBuffer.Size(columns = 120, rows = 14, widthPixels = 1200, heightPixels = 300)

        assertTrue(
            shouldDispatchTerminalResize(
                previousSize = previous,
                nextSize = rotated
            )
        )
    }

    @Test
    fun terminalResizeIgnoresKeyboardCloseHeightOnlyChange() {
        val keyboardRotated = TerminalBuffer.Size(columns = 120, rows = 14, widthPixels = 1200, heightPixels = 300)
        val fullRotated = TerminalBuffer.Size(columns = 120, rows = 32, widthPixels = 1200, heightPixels = 680)

        assertFalse(
            shouldDispatchTerminalResize(
                previousSize = keyboardRotated,
                nextSize = fullRotated
            )
        )
    }

    @Test
    fun jumpToLatestButtonOnlyAppearsForManualHistoryScroll() {
        assertTrue(
            shouldShowJumpToLatestButton(
                totalItems = 100,
                alternateScreen = false,
                autoFollowOutput = false,
                copyMode = false
            )
        )
        assertFalse(
            shouldShowJumpToLatestButton(
                totalItems = 100,
                alternateScreen = false,
                autoFollowOutput = true,
                copyMode = false
            )
        )
        assertFalse(
            shouldShowJumpToLatestButton(
                totalItems = 24,
                alternateScreen = true,
                autoFollowOutput = false,
                copyMode = false
            )
        )
        assertFalse(
            shouldShowJumpToLatestButton(
                totalItems = 100,
                alternateScreen = false,
                autoFollowOutput = false,
                copyMode = true
            )
        )
    }

    @Test
    fun cursorKeysSwitchToApplicationModeSequences() {
        assertEquals("\u001B[A", terminalCursorKeySequence("up", applicationCursorKeys = false))
        assertEquals("\u001B[B", terminalCursorKeySequence("down", applicationCursorKeys = false))
        assertEquals("\u001BOA", terminalCursorKeySequence("up", applicationCursorKeys = true))
        assertEquals("\u001BOB", terminalCursorKeySequence("down", applicationCursorKeys = true))
        assertEquals("\u001BOD", terminalCursorKeySequence("left", applicationCursorKeys = true))
        assertEquals("\u001BOC", terminalCursorKeySequence("right", applicationCursorKeys = true))
    }

    @Test
    fun homeEndKeysSwitchToApplicationModeSequences() {
        assertEquals("\u001B[H", terminalHomeEndKeySequence("home", applicationCursorKeys = false))
        assertEquals("\u001B[F", terminalHomeEndKeySequence("end", applicationCursorKeys = false))
        assertEquals("\u001BOH", terminalHomeEndKeySequence("home", applicationCursorKeys = true))
        assertEquals("\u001BOF", terminalHomeEndKeySequence("end", applicationCursorKeys = true))
    }

    @Test
    fun appendTerminalInputReferenceKeepsExistingCommandReadable() {
        assertEquals("'file.txt'", appendTerminalInputReference("", "'file.txt'"))
        assertEquals("cat 'file.txt'", appendTerminalInputReference("cat", "'file.txt'"))
        assertEquals("cat 'file.txt'", appendTerminalInputReference("cat ", "'file.txt'"))
    }

    @Test
    fun shellSafePathReferenceQuotesPosixAndWindowsPaths() {
        assertEquals("'/srv/app/a file.txt'", shellSafePathReference("/srv/app/a file.txt"))
        assertEquals("'bob'\"'\"'s.txt'", shellSafePathReference("bob's.txt"))
        assertEquals("\"C:\\Users\\example\\a file.txt\"", shellSafePathReference("C:\\Users\\example\\a file.txt"))
    }

    @Test
    fun selectedTerminalTextStripsSgrColorSequences() {
        assertEquals(
            "red\nplain",
            selectedTerminalText(listOf("\u001B[31mred\u001B[0m", "plain"), setOf(0, 1))
        )
    }

    @Test
    fun terminalPastePayloadWrapsOnlyWhenBracketedPasteIsEnabled() {
        assertEquals("a\nb", terminalPastePayload("a\nb", bracketedPasteMode = false))
        assertEquals("\u001B[200~a\nb\u001B[201~", terminalPastePayload("a\nb", bracketedPasteMode = true))
    }

    @Test
    fun compactKeysIncludesPageUpAndPageDownForQuickNavigation() {
        val keys = compactKeys(applicationCursorKeys = false)
        val labels = keys.map { it.label }
        assertTrue("compactKeys should include PgUp", labels.contains("PgUp"))
        assertTrue("compactKeys should include PgDn", labels.contains("PgDn"))
        assertEquals("\u001B[5~", keys.first { it.label == "PgUp" }.sequence)
        assertEquals("\u001B[6~", keys.first { it.label == "PgDn" }.sequence)
    }
}
