package com.quickssh.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {
    @Test
    fun alternateScreenRepaintsInPlaceAndRestoresPrimaryScreen() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 12, initialRows = 6)

        var snapshot = buffer.append("prompt> ")
        assertEquals("prompt>", plain(snapshot.first()))

        snapshot = buffer.append("\u001B[?1049h\u001B[2J\u001B[Hfirst")
        assertTrue(buffer.isInAlternateScreen)
        assertEquals(6, snapshot.size)
        assertTrue(plain(snapshot[0]).startsWith("first"))

        snapshot = buffer.append("\u001B[Hsecond")
        assertTrue(plain(snapshot[0]).startsWith("second"))
        assertFalse(plain(snapshot.joinToString("\n")).contains("firstsecond"))

        snapshot = buffer.append("\u001B[?1049l")
        assertFalse(buffer.isInAlternateScreen)
        assertEquals("prompt>", plain(snapshot.first()))
    }

    @Test
    fun cursorAddressingWritesAtRequestedCell() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 10, initialRows = 3)

        val snapshot = buffer.append("\u001B[2;5HHi")

        assertEquals("", plain(snapshot[0]))
        assertEquals("    Hi", plain(snapshot[1]))
    }

    @Test
    fun alternateScreenKeepsFixedRowCountAfterClear() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 8, initialRows = 6)

        val snapshot = buffer.append("\u001B[?1049h\u001B[2J\u001B[3;6HX")

        assertEquals(6, snapshot.size)
        assertEquals(TerminalBuffer.MIN_COLUMNS, plain(snapshot[2]).length)
        assertTrue(plain(snapshot[2]).startsWith("     X"))
    }

    @Test
    fun decSpecialGraphicsMapsLineDrawingCharacters() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B(0lqk\u001B(B normal")

        assertEquals("┌─┐ normal", plain(snapshot.first()))
    }

    @Test
    fun singleShiftG2AndG3CharsetsRenderOneLineDrawingCharacter() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B*0A\u001BNlmn \u001B+0\u001BOqrs")

        assertEquals("A┌mn ─rs", plain(snapshot.first()))
    }

    @Test
    fun softResetClearsExtendedCharsetsAndSingleShiftState() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B*0\u001BN\u001B[!p")
        val snapshot = buffer.append("l")

        assertEquals("l", plain(snapshot.first()))
    }

    @Test
    fun deviceQueriesQueueTerminalResponses() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 40, initialRows = 10)

        buffer.append("abc\u001B[6n\u001B[c\u001B[>c")

        assertEquals(
            listOf("\u001B[1;4R", "\u001B[?62;1;2;6;9;15;18;21;22c", "\u001B[>0;136;0c"),
            buffer.drainResponses()
        )
        assertTrue(buffer.drainResponses().isEmpty())
    }

    @Test
    fun windowSizeQueryUsesCurrentTerminalSize() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 40, initialRows = 10)

        buffer.resize(TerminalBuffer.Size(columns = 80, rows = 24, widthPixels = 800, heightPixels = 480))
        buffer.append("\u001B[18t\u001B[14t\u001B[16t")

        assertEquals(
            listOf("\u001B[8;24;80t", "\u001B[4;480;800t", "\u001B[6;20;10t"),
            buffer.drainResponses()
        )
    }

    @Test
    fun privateAutowrapModeCanBeDisabled() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B[?7l12345678901234567890X")

        assertEquals("1234567890123456789X", plain(snapshot.first()))
        assertEquals(1, snapshot.size)
    }

    @Test
    fun insertModeShiftsCharactersUntilDisabled() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("abcde\u001B[1;3H\u001B[4hX\u001B[4lY")

        assertEquals("abXYde", plain(snapshot.first()))
    }

    @Test
    fun originModeAddressesRowsInsideScrollRegion() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "\u001B[?1049h\u001B[2J" +
                "\u001B[1;1HHEAD" +
                "\u001B[6;1HFOOT" +
                "\u001B[2;5r\u001B[?6h" +
                "\u001B[1;1HINNER" +
                "\u001B[4;1HBOTTOM" +
                "\u001B[20A\u001B[1GUP" +
                "\u001B[?6l\u001B[1;1HROOT"
        )

        assertTrue(plain(snapshot[0]).startsWith("ROOT"))
        assertTrue(plain(snapshot[1]).startsWith("UPNER"))
        assertTrue(plain(snapshot[4]).startsWith("BOTTOM"))
        assertTrue(plain(snapshot[5]).startsWith("FOOT"))
    }

    @Test
    fun originModeCursorReportUsesRegionRelativeRow() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.append("\u001B[?1049h\u001B[2;5r\u001B[?6h\u001B[3;4H\u001B[6n")

        assertEquals(listOf("\u001B[3;4R"), buffer.drainResponses())
    }

    @Test
    fun leftRightMarginsConstrainWrapInAlternateScreen() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B[?1049h\u001B[2J\u001B[?69h\u001B[3;8sabcdefg")

        assertEquals("  abcdef            ", plain(snapshot[0]))
        assertEquals("  g                 ", plain(snapshot[1]))
    }

    @Test
    fun leftRightMarginsConstrainCursorAddressingAndMovement() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B[?1049h\u001B[2J\u001B[?69h\u001B[3;8s\u001B[1;1HX\u001B[20CY")

        assertEquals("  X    Y            ", plain(snapshot[0]))
    }

    @Test
    fun leftRightMarginModeCanBeQueriedByTuiApps() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B[?69\$p\u001B[?69h\u001B[?69\$p")

        assertEquals(
            listOf("\u001B[?69;2\$y", "\u001B[?69;1\$y"),
            buffer.drainResponses()
        )
    }

    @Test
    fun tabMovesCursorWithoutErasingExistingText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("abcdefghi\u001B[1;1H\tX")

        assertEquals("abcdefghX", plain(snapshot.first()))
    }

    @Test
    fun cursorTabForwardAndBackwardUseTabStops() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("abcdefghi\u001B[1;1H\u001B[IZ\u001B[2ZY")

        assertEquals("YbcdefghZ", plain(snapshot.first()))
    }

    @Test
    fun customTabStopsCanBeSetAndCleared() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "abcde" +
                "\u001B[1;3H\u001BH" +
                "\u001B[1;1H\tX" +
                "\u001B[1;3H\u001B[0g" +
                "\u001B[1;1H\tY"
        )

        assertEquals("abXde   Y", plain(snapshot.first()))
    }

    @Test
    fun fullscreenPrivateModesDoNotLeakControlText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B[?2004h\u001B[?25lready\u001B[?25h\u001B[?2004l")

        assertEquals("ready", plain(snapshot.first()))
    }

    @Test
    fun tuiMouseFocusAndCursorModesDoNotLeakControlText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "\u001B[?1h" +
                "\u001B[?12l" +
                "\u001B[?25h" +
                "\u001B[?1000;1002;1003;1004;1006;1015h" +
                "ready" +
                "\u001B[?1000;1002;1003;1004;1006;1015l"
        )

        assertEquals("ready", plain(snapshot.first()))
    }

    @Test
    fun applicationCursorKeyModeIsTrackedForFullscreenApps() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B[?1h")
        assertTrue(buffer.applicationCursorKeys)

        buffer.appendRaw("\u001B[?1l")
        assertFalse(buffer.applicationCursorKeys)

        buffer.appendRaw("\u001B[?1h")
        buffer.clear()
        assertFalse(buffer.applicationCursorKeys)
    }

    @Test
    fun applicationKeypadModeIsTrackedForFullscreenApps() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B=")
        assertTrue(buffer.applicationKeypadMode)

        buffer.appendRaw("\u001B>")
        assertFalse(buffer.applicationKeypadMode)

        buffer.appendRaw("\u001B[?66h\u001B[?66\$p\u001B[?66l\u001B[?66\$p")

        assertEquals(
            listOf("\u001B[?66;1\$y", "\u001B[?66;2\$y"),
            buffer.drainResponses()
        )
    }

    @Test
    fun kittyKeyboardProtocolCsiUDoesNotRestoreCursor() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "\u001B[2;5HS\u001B[s" +
                "\u001B[4;1Hcurrent" +
                "\u001B[>1uX"
        ).map(::plain)

        assertEquals("    S", snapshot[1])
        assertEquals("currentX", snapshot[3])
    }

    @Test
    fun decRectangleAttributeControlsDoNotChangeScrollRegion() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "\u001B[?1049h\u001B[2J" +
                "\u001B[1;1HHEAD" +
                "\u001B[2;1HA" +
                "\u001B[3;1HB" +
                "\u001B[4;1HC" +
                "\u001B[5;1HD" +
                "\u001B[6;1HFOOT" +
                "\u001B[1;1;6;20\$r" +
                "\u001B[5;1H\n"
        ).map(::plain)

        assertEquals("HEAD                ", snapshot[0])
        assertTrue(snapshot[1].startsWith("A"))
        assertTrue(snapshot[2].startsWith("B"))
        assertTrue(snapshot[3].startsWith("C"))
        assertTrue(snapshot[4].startsWith("D"))
        assertEquals("FOOT                ", snapshot[5])
    }

    @Test
    fun decFillAndEraseRectanglePaintActiveScreenWithoutMovingCursor() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "\u001B[?1049h\u001B[2J" +
                "\u001B[6;10H@" +
                "\u001B[88;2;3;4;8\$x" +
                "\u001B[3;5;3;6\$z" +
                "\u001B[6n"
        ).map(::plain)

        assertEquals("  XXXXXX            ", snapshot[1])
        assertEquals("  XX  XX            ", snapshot[2])
        assertEquals("  XXXXXX            ", snapshot[3])
        assertEquals("         @          ", snapshot[5])
        assertEquals(listOf("\u001B[6;11R"), buffer.drainResponses())
    }

    @Test
    fun primaryEraseBeforeCursorDoesNotBlankScrollbackHistory() {
        val buffer = TerminalBuffer(maxLines = 40, initialColumns = 24, initialRows = 5)

        repeat(10) { index ->
            buffer.appendRaw("codex-history-$index\n")
        }
        buffer.appendRaw("\u001B[3;1H\u001B[1J")
        val snapshot = buffer.snapshot().map(::plain)

        assertTrue(snapshot.contains("codex-history-0"))
        assertTrue(snapshot.contains("codex-history-4"))
        assertFalse(snapshot.take(5).any { it.isBlank() })
    }

    @Test
    fun primaryEraseScrollbackSequenceDoesNotDropCodexHistory() {
        val buffer = TerminalBuffer(maxLines = 40, initialColumns = 24, initialRows = 5)

        repeat(10) { index ->
            buffer.appendRaw("codex-history-$index\n")
        }
        buffer.appendRaw("\u001B[3Jvisible-after-clear")
        val snapshot = buffer.snapshot().map(::plain)

        assertTrue(snapshot.contains("codex-history-0"))
        assertTrue(snapshot.contains("codex-history-4"))
        assertTrue(snapshot.any { it.contains("visible-after-clear") })
    }

    @Test
    fun primaryFullScreenRepaintsReplaceViewportWithoutFakeScrollbackFrames() {
        val buffer = TerminalBuffer(maxLines = 80, initialColumns = 24, initialRows = 5)

        repeat(8) { index ->
            buffer.appendRaw("codex-history-$index\n")
        }
        buffer.appendRaw("\u001B[H\u001B[2Jcodex-frame-1\nloading-a")
        buffer.appendRaw("\u001B[H\u001B[2Jcodex-frame-2\nloading-b")
        val snapshot = buffer.snapshot().map(::plain)

        assertTrue(snapshot.contains("codex-history-0"))
        assertFalse(snapshot.contains("codex-frame-1"))
        assertFalse(snapshot.contains("loading-a"))
        assertTrue(snapshot.contains("codex-frame-2"))
        assertTrue(snapshot.contains("loading-b"))
    }

    @Test
    fun primarySynchronizedCursorRepaintsDoNotCreateScrollbackCopies() {
        val buffer = TerminalBuffer(maxLines = 80, initialColumns = 24, initialRows = 5)

        buffer.appendRaw("banner\n")
        buffer.appendRaw("codex-frame-1\n")
        buffer.appendRaw("loading-a\n")
        buffer.appendRaw("status-a\n")
        buffer.appendRaw("bottom-a")
        buffer.appendRaw("\u001B[?2026h\u001B[3A\u001B[2Kcodex-frame-2\n\u001B[2Kloading-b\n\u001B[2Kstatus-b\u001B[?2026l")
        val snapshot = buffer.snapshot().map(::plain)

        assertEquals(5, snapshot.size)
        assertTrue(snapshot.contains("banner"))
        assertFalse(snapshot.contains("codex-frame-1"))
        assertFalse(snapshot.contains("loading-a"))
        assertFalse(snapshot.contains("status-a"))
        assertTrue(snapshot.any { it.contains("codex-frame-2") })
        assertTrue(snapshot.any { it.contains("loading-b") })
        assertTrue(snapshot.any { it.contains("status-b") })
    }

    @Test
    fun primarySynchronizedRepaintDoesNotForkTransientViewportFrames() {
        val buffer = TerminalBuffer(maxLines = 80, initialColumns = 24, initialRows = 5)

        buffer.appendRaw("header\nrow-a\nrow-b\nrow-c\nbottom")
        buffer.appendRaw(
            "\u001B[?2026h" +
                "\u001B[3A\r\u001B[2Krow-a2" +
                "\u001B[1B\r\u001B[2Krow-b2" +
                "\u001B[1B\r\u001B[2Krow-c2" +
                "\u001B[?2026l"
        )
        val snapshot = buffer.snapshot().map(::plain)

        assertEquals(5, snapshot.size)
        assertEquals(1, snapshot.count { it == "header" })
        assertEquals(1, snapshot.count { it == "bottom" })
        assertEquals(0, snapshot.count { it == "row-a" })
        assertEquals(0, snapshot.count { it == "row-b" })
        assertEquals(0, snapshot.count { it == "row-c" })
        assertTrue(snapshot.any { it.startsWith("row-a2") })
        assertTrue(snapshot.any { it.startsWith("row-b2") })
        assertTrue(snapshot.any { it.startsWith("row-c2") })
    }

    @Test
    fun primarySynchronizedFullRepaintReplacesViewportWithoutDuplicatingRows() {
        val buffer = TerminalBuffer(maxLines = 80, initialColumns = 24, initialRows = 5)

        buffer.appendRaw("line-a\nline-b\nline-c\nline-d\nline-e")
        buffer.appendRaw(
            "\u001B[?2026h" +
                "\u001B[5A\r\u001B[2Kline-c\n" +
                "\u001B[2Kline-d\n" +
                "\u001B[2Kline-e\n" +
                "\u001B[2Kline-f\n" +
                "\u001B[2Kline-g" +
                "\u001B[?2026l"
        )
        val snapshot = buffer.snapshot().map(::plain)

        assertEquals(5, snapshot.size)
        assertFalse(snapshot.contains("line-a"))
        assertFalse(snapshot.contains("line-b"))
        assertEquals(1, snapshot.count { it == "line-c" })
        assertEquals(1, snapshot.count { it == "line-d" })
        assertEquals(1, snapshot.count { it == "line-e" })
        assertTrue(snapshot.contains("line-f"))
        assertTrue(snapshot.contains("line-g"))
    }

    @Test
    fun repeatedSynchronizedRepaintsDoNotAppendFakeHistoryFrames() {
        val buffer = TerminalBuffer(maxLines = 80, initialColumns = 80, initialRows = 6)

        buffer.appendRaw("real-0\nreal-1\nreal-2\nold-warning\nold-detail\nprompt")
        repeat(3) { index ->
            buffer.appendRaw(
                "\u001B[?2026h" +
                    "\u001B[3;1H\r\u001B[2KConversation interrupted\n" +
                    "\u001B[2KSkipped loading 1 skill(s)\n" +
                    "\u001B[2KResume paused goal? $index" +
                    "\u001B[?2026l"
            )
        }
        val snapshot = buffer.snapshot().map(::plain)

        assertEquals(6, snapshot.size)
        assertEquals(1, snapshot.count { it == "Conversation interrupted" })
        assertEquals(1, snapshot.count { it == "Skipped loading 1 skill(s)" })
        assertEquals(1, snapshot.count { it.startsWith("Resume paused goal?") })
        assertTrue(snapshot.contains("Resume paused goal? 2"))
    }

    @Test
    fun repeatedPrimaryFullRefreshesDoNotAppendMostlyDuplicateHistoryFrames() {
        val buffer = TerminalBuffer(maxLines = 80, initialColumns = 80, initialRows = 6)

        buffer.appendRaw("real-0\nreal-1\nreal-2\nold-warning\nold-detail\nprompt")
        repeat(4) { index ->
            buffer.appendRaw(
                "\u001B[H\u001B[2J" +
                    "Conversation interrupted\n" +
                    "Skipped loading 1 skill(s)\n" +
                    "Resume paused goal? $index\n" +
                    "Press enter to continue"
            )
        }
        val snapshot = buffer.snapshot().map(::plain)

        assertEquals(1, snapshot.count { it == "Conversation interrupted" })
        assertEquals(1, snapshot.count { it == "Skipped loading 1 skill(s)" })
        assertEquals(1, snapshot.count { it.startsWith("Resume paused goal?") })
        assertTrue(snapshot.contains("Resume paused goal? 3"))
        assertTrue(snapshot.contains("Press enter to continue"))
    }

    @Test
    fun primaryCursorMovementCannotOverwriteOldScrollbackHistory() {
        val buffer = TerminalBuffer(maxLines = 40, initialColumns = 24, initialRows = 5)

        repeat(10) { index ->
            buffer.appendRaw("codex-history-$index\n")
        }
        buffer.appendRaw("\u001B[100A\u001B[2KVISIBLE")
        val snapshot = buffer.snapshot().map(::plain)

        assertTrue(snapshot.contains("codex-history-0"))
        assertTrue(snapshot.contains("codex-history-4"))
        assertFalse(snapshot.first().contains("VISIBLE"))
    }

    @Test
    fun primarySavedCursorRestoreCannotOverwriteOldScrollbackHistory() {
        val buffer = TerminalBuffer(maxLines = 40, initialColumns = 24, initialRows = 5)

        buffer.appendRaw("saved-row\u001B7")
        repeat(10) { index ->
            buffer.appendRaw("\ncodex-history-$index")
        }
        buffer.appendRaw("\u001B8VISIBLE")
        val snapshot = buffer.snapshot().map(::plain)

        assertTrue(snapshot.contains("saved-row"))
        assertFalse(snapshot.first().contains("VISIBLE"))
    }

    @Test
    fun primaryReverseIndexCannotMoveIntoOldScrollbackHistory() {
        val buffer = TerminalBuffer(maxLines = 40, initialColumns = 24, initialRows = 5)

        repeat(10) { index ->
            buffer.appendRaw("codex-history-$index\n")
        }
        buffer.appendRaw("\u001BM\u001B[2KVISIBLE")
        val snapshot = buffer.snapshot().map(::plain)

        assertTrue(snapshot.contains("codex-history-0"))
        assertFalse(snapshot.first().contains("VISIBLE"))
    }

    @Test
    fun synchronizedOutputModeIsTrackedAndDoesNotLeakControlText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B[?2026hpartial")
        assertTrue(buffer.isSynchronizedOutputMode)
        assertEquals("partial", plain(buffer.snapshot().first()))

        buffer.appendRaw("-done\u001B[?2026l")
        assertFalse(buffer.isSynchronizedOutputMode)
        assertEquals("partial-done", plain(buffer.snapshot().first()))

        buffer.appendRaw("\u001B[?2026h")
        buffer.clear()
        assertFalse(buffer.isSynchronizedOutputMode)
    }

    @Test
    fun bracketedPasteModeIsTrackedForInputWrapping() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B[?2004h")
        assertTrue(buffer.bracketedPasteMode)

        buffer.appendRaw("\u001B[?2004l")
        assertFalse(buffer.bracketedPasteMode)

        buffer.appendRaw("\u001B[?2004h")
        buffer.clear()
        assertFalse(buffer.bracketedPasteMode)
    }

    @Test
    fun modeStatusQueriesReportStandardAndPrivateTerminalModes() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw(
            "\u001B[4\$p" +
                "\u001B[4h\u001B[4\$p" +
                "\u001B[?2004\$p" +
                "\u001B[?2004h\u001B[?2004\$p" +
                "\u001B[?2026h\u001B[?2026\$p" +
                "\u001B[?25l\u001B[?25\$p" +
                "\u001B[?1000;1004;1006h\u001B[?1000\$p\u001B[?1004\$p\u001B[?1006\$p" +
                "\u001B[?9999\$p"
        )

        assertEquals(
            listOf(
                "\u001B[4;2\$y",
                "\u001B[4;1\$y",
                "\u001B[?2004;2\$y",
                "\u001B[?2004;1\$y",
                "\u001B[?2026;1\$y",
                "\u001B[?25;2\$y",
                "\u001B[?1000;1\$y",
                "\u001B[?1004;1\$y",
                "\u001B[?1006;1\$y",
                "\u001B[?9999;0\$y"
            ),
            buffer.drainResponses()
        )

        buffer.clear()
        buffer.appendRaw("\u001B[?2004\$p\u001B[?2026\$p\u001B[?1000\$p\u001B[?25\$p")

        assertEquals(
            listOf(
                "\u001B[?2004;2\$y",
                "\u001B[?2026;2\$y",
                "\u001B[?1000;2\$y",
                "\u001B[?25;1\$y"
            ),
            buffer.drainResponses()
        )
    }

    @Test
    fun softResetClearsTuiModesWithoutClearingScreenText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw(
            "\u001B[?1049h\u001B[2J" +
                "\u001B[2;4r\u001B[?6h" +
                "\u001B[?1h\u001B[?2004h\u001B[?1000h\u001B[?1004h\u001B[?2026h" +
                "\u001B[4h\u001B[31m" +
                "\u001B[3;1HKEEP" +
                "\u001B[!p" +
                "X" +
                "\u001B[6n" +
                "\u001B[4\$p\u001B[?1\$p\u001B[?2004\$p\u001B[?1000\$p\u001B[?1004\$p\u001B[?2026\$p"
        )

        val snapshot = buffer.snapshot()
        assertTrue(buffer.isInAlternateScreen)
        assertFalse(buffer.applicationCursorKeys)
        assertFalse(buffer.isSynchronizedOutputMode)
        assertEquals("X                   ", plain(snapshot[0]))
        assertTrue(plain(snapshot.joinToString("\n")).contains("KEEP"))
        assertFalse(snapshot[0].contains("\u001B[31m"))
        assertEquals(
            listOf(
                "\u001B[1;2R",
                "\u001B[4;2\$y",
                "\u001B[?1;2\$y",
                "\u001B[?2004;2\$y",
                "\u001B[?1000;2\$y",
                "\u001B[?1004;2\$y",
                "\u001B[?2026;2\$y"
            ),
            buffer.drainResponses()
        )
    }

    @Test
    fun splitOscAndSosStringsDoNotLeakControlPayloads() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 40, initialRows = 6)

        buffer.appendRaw("before\u001B]0;QuickSSH title")
        assertEquals("before", plain(buffer.snapshot().first()))

        buffer.appendRaw("\u0007middle\u001BXhidden payload")
        assertEquals("beforemiddle", plain(buffer.snapshot().first()))

        buffer.appendRaw("\u001B\\after")
        assertEquals("beforemiddleafter", plain(buffer.snapshot().first()))
    }

    @Test
    fun c1ControlStringsDoNotLeakControlPayloads() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 60, initialRows = 6)

        buffer.appendRaw("a\u009D8;;https://example.invalid\u0007link\u009D8;;\u0007")
        buffer.appendRaw("b\u009Fkitty-private-payload\u001B\\c")
        buffer.appendRaw("\u0098sos-private-payload\u001B\\d")

        assertEquals("alinkbcd", plain(buffer.snapshot().first()))
    }

    @Test
    fun decrqssReportsKnownTerminalStatusStrings() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw(
            "\u001B[31;44m" +
                "\u001B[2;5r" +
                "\u001B[?69h\u001B[3;8s" +
                "\u001B[5 q" +
                "\u001BP\$qm\u001B\\" +
                "\u001BP\$qr\u001B\\" +
                "\u001BP\$qs\u001B\\" +
                "\u001BP\$q q\u001B\\" +
                "\u001BP\$qunknown\u001B\\"
        )

        assertEquals(
            listOf(
                "\u001BP1\$r31;44m\u001B\\",
                "\u001BP1\$r2;5r\u001B\\",
                "\u001BP1\$r3;8s\u001B\\",
                "\u001BP1\$r5 q\u001B\\",
                "\u001BP0\$r\u001B\\"
            ),
            buffer.drainResponses()
        )
    }

    @Test
    fun softResetClearsCursorStyleForFullscreenApps() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u001B[6 q\u001BP\$q q\u001B\\")
        assertEquals(listOf("\u001BP1\$r6 q\u001B\\"), buffer.drainResponses())

        buffer.appendRaw("\u001B[!p\u001BP\$q q\u001B\\")

        assertEquals(listOf("\u001BP1\$r0 q\u001B\\"), buffer.drainResponses())
    }

    @Test
    fun c1DcsTerminatorCanEndDecrqssQuery() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        buffer.appendRaw("\u0090\$qm\u009C")

        assertEquals(listOf("\u001BP1\$r0m\u001B\\"), buffer.drainResponses())
    }

    @Test
    fun c1ControlStringTerminatorEndsOscAndPrivateStrings() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 60, initialRows = 6)

        buffer.appendRaw("a\u009D0;title\u009Cb")
        buffer.appendRaw("c\u0090ignored-dcs-payload\u009Cd")
        buffer.appendRaw("e\u009Fignored-apc-payload\u009Cf")

        assertEquals("abcdef", plain(buffer.snapshot().first()))
    }

    @Test
    fun splitC1OscWithC1TerminatorDoesNotHideFollowingText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 60, initialRows = 6)

        buffer.appendRaw("before\u009D0;half-title")
        assertEquals("before", plain(buffer.snapshot().first()))

        buffer.appendRaw("\u009Cafter")

        assertEquals("beforeafter", plain(buffer.snapshot().first()))
    }

    @Test
    fun c1LineControlsActLikeTheirEscEquivalents() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "row1\u0085row2" +
                "\u008Dtop" +
                "\u001B[3;1Habc\u0088" +
                "\u001B[3;1H\tX"
        ).map(::plain)

        assertEquals("row1top", snapshot[0])
        assertEquals("row2", snapshot[1])
        assertEquals("abcX", snapshot[2])
    }

    @Test
    fun unknownC1ControlsDoNotRenderAsText() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("a\u0080\u0081\u0092b")

        assertEquals("ab", plain(snapshot.first()))
    }

    @Test
    fun nonSgrCsiMSequencesDoNotContaminateTextStyle() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u001B[>4;1mplain")

        assertEquals("plain", snapshot.first())
    }

    @Test
    fun alternateScreenScrollRegionPreservesHeaderAndFooter() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append(
            "\u001B[?1049h\u001B[2J" +
                "\u001B[1;1HHEAD" +
                "\u001B[2;1HA" +
                "\u001B[3;1HB" +
                "\u001B[4;1HC" +
                "\u001B[5;1HD" +
                "\u001B[6;1HFOOT" +
                "\u001B[2;5r" +
                "\u001B[5;1H\n"
        )

        assertEquals("HEAD                ", plain(snapshot[0]))
        assertTrue(plain(snapshot[1]).startsWith("B"))
        assertTrue(plain(snapshot[2]).startsWith("C"))
        assertTrue(plain(snapshot[3]).startsWith("D"))
        assertEquals("                    ", plain(snapshot[4]))
        assertEquals("FOOT                ", plain(snapshot[5]))
    }

    @Test
    fun cjkCharactersAreRenderedWithoutInsertedSpaces() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("中文测试")

        assertEquals("中文测试", plain(snapshot.first()))
    }

    @Test
    fun combiningCharactersAttachToPreviousCellWithoutAdvancingCursor() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("e\u0301X\u001B[1;3HZ")

        assertEquals("e\u0301XZ", plain(snapshot.first()))
    }

    @Test
    fun variationSelectorsDoNotConsumeTerminalColumns() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 6)

        val snapshot = buffer.append("\u2665\uFE0EX\u001B[1;3HZ")

        assertEquals("\u2665\uFE0EXZ", plain(snapshot.first()))
    }

    @Test
    fun historyDrainRecordsOnlyCompletePlainLines() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 40, initialRows = 6)

        buffer.appendRaw("A\nB")
        assertEquals(listOf("A"), buffer.drainHistoryLines())

        buffer.appendRaw("\nC\n")
        assertEquals(listOf("B", "C"), buffer.drainHistoryLines())
        assertTrue(buffer.drainHistoryLines().isEmpty())
    }

    @Test
    fun historyDrainKeepsSgrTextButSkipsCursorRepaint() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 40, initialRows = 6)

        buffer.appendRaw("\u001B[32mready\u001B[0m\n")
        assertEquals(listOf("\u001B[32mready\u001B[0m"), buffer.drainHistoryLines())

        buffer.appendRaw("\u001B[H\u001B[2Jframe-1")
        buffer.appendRaw("\u001B[H\u001B[2Jframe-2\n")
        assertTrue(buffer.drainHistoryLines().isEmpty())

        buffer.appendRaw("next\n")
        assertEquals(listOf("next"), buffer.drainHistoryLines())
    }

    @Test
    fun historyDrainDoesNotRecordAlternateScreenRepaints() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 20, initialRows = 4)

        buffer.appendRaw("before\n")
        buffer.drainHistoryLines()
        buffer.appendRaw("\u001B[?1049h\u001B[2J\u001B[Hscreen-a")
        buffer.appendRaw("\u001B[Hscreen-b")
        buffer.appendRaw("\u001B[?1049l")

        assertTrue(buffer.drainHistoryLines().isEmpty())
        buffer.appendRaw("after\n")
        assertEquals(listOf("after"), buffer.drainHistoryLines())
    }

    @Test
    fun historyDrainResumesAfterAnsiControlSequenceSplitAcrossChunks() {
        val buffer = TerminalBuffer(maxLines = 20, initialColumns = 40, initialRows = 6)

        buffer.appendRaw("before\u001B[")
        assertTrue(buffer.drainHistoryLines().isEmpty())

        buffer.appendRaw("2J")
        assertTrue(buffer.drainHistoryLines().isEmpty())

        buffer.appendRaw("after\n")
        assertEquals(listOf("after"), buffer.drainHistoryLines())
    }

    @Test
    fun codexStyleHeavyStreamingStaysBoundedAndKeepsLatestOutput() {
        val buffer = TerminalBuffer(maxLines = 1000, initialColumns = 80, initialRows = 24)

        repeat(5000) { index ->
            buffer.appendRaw("\u001B[32mstep-$index\u001B[0m 正在生成输出...\n")
        }
        val snapshot = buffer.snapshot()

        assertTrue(snapshot.size <= 1000)
        assertFalse(plain(snapshot.joinToString("\n")).contains("step-0"))
        assertTrue(plain(snapshot.last()).contains("step-4999"))
    }

    private fun plain(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*m"), "")
    }
}



