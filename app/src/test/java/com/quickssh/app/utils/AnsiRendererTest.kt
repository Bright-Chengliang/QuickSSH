package com.quickssh.app.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiRendererTest {
    @Test
    fun cleanNonSgrAnsiPreservesSgrColorSequences() {
        val cleaned = AnsiRenderer.cleanNonSgrAnsi("\u001B[31mred\u001B[0m\u001B[2J")

        assertEquals("\u001B[31mred\u001B[0m", cleaned)
    }

    @Test
    fun cleanNonSgrAnsiPreservesColonSeparatedSgrColorSequences() {
        val cleaned = AnsiRenderer.cleanNonSgrAnsi("\u001B[38:5:196mhot\u001B[0m\u001B[2J")

        assertEquals("\u001B[38:5:196mhot\u001B[0m", cleaned)
    }

    @Test
    fun renderAnsiTextAppliesForegroundColor() {
        val rendered = AnsiRenderer.renderAnsiText("\u001B[32mgreen\u001B[0m")

        assertEquals("green", rendered.text)
        assertTrue(rendered.spanStyles.any { range ->
            range.item.color == Color(0xFF10B981) && range.start == 0 && range.end == 5
        })
    }

    @Test
    fun renderAnsiTextAppliesColonSeparatedExtendedColors() {
        val rendered = AnsiRenderer.renderAnsiText("\u001B[38:5:196mhot\u001B[0m \u001B[48:2:12:34:56mbg\u001B[0m")

        assertEquals("hot bg", rendered.text)
        assertTrue(rendered.spanStyles.any { range ->
            range.item.color == Color(255, 0, 0) && range.start == 0 && range.end == 3
        })
        assertTrue(rendered.spanStyles.any { range ->
            range.item.background == Color(12, 34, 56) && range.start == 4 && range.end == 6
        })
    }

    @Test
    fun terminalBufferSnapshotPreservesSgrStyleForRenderer() {
        val snapshot = TerminalBuffer(maxLines = 10, initialColumns = 20, initialRows = 6)
            .append("\u001B[31mred\u001B[0m")

        assertTrue(snapshot.first().contains("\u001B[31m"))
        assertEquals("red", AnsiRenderer.renderAnsiText(snapshot.first()).text)
    }

    @Test
    fun renderAnsiTextAppliesInverseVideo() {
        val rendered = AnsiRenderer.renderAnsiText("\u001B[7mselected\u001B[0m normal")

        assertEquals("selected normal", rendered.text)
        assertTrue(rendered.spanStyles.any { range ->
            range.start == 0 &&
                range.end == 8 &&
                range.item.color == Color(0xFF0F0F12) &&
                range.item.background == Color(0xFFE5E7EB)
        })
        assertTrue(rendered.spanStyles.any { range ->
            range.start == 8 &&
                range.end == 15 &&
                range.item.color == Color(0xFFE5E7EB) &&
                range.item.background == Color.Unspecified
        })
    }

    @Test
    fun terminalBufferSnapshotPreservesInverseVideo() {
        val snapshot = TerminalBuffer(maxLines = 10, initialColumns = 30, initialRows = 6)
            .append("\u001B[7mstatus\u001B[27m body")

        assertTrue(snapshot.first().contains("\u001B[7m"))
        assertEquals("status body", AnsiRenderer.renderAnsiText(snapshot.first()).text)
    }

    @Test
    fun renderAnsiTextAppliesDimAndStrikethrough() {
        val rendered = AnsiRenderer.renderAnsiText("\u001B[2mdim\u001B[22m \u001B[9mdel\u001B[29m")

        assertEquals("dim del", rendered.text)
        assertTrue(rendered.spanStyles.any { range ->
            range.start == 0 &&
                range.end == 3 &&
                range.item.color.alpha in 0.70f..0.74f
        })
        assertTrue(rendered.spanStyles.any { range ->
            range.start == 4 &&
                range.end == 7 &&
                range.item.textDecoration == TextDecoration.LineThrough
        })
    }

    @Test
    fun terminalBufferSnapshotPreservesDimAndStrikethrough() {
        val snapshot = TerminalBuffer(maxLines = 10, initialColumns = 30, initialRows = 6)
            .append("\u001B[2mweak\u001B[22m \u001B[9mdeleted\u001B[29m")

        assertTrue(snapshot.first().contains("\u001B[2m"))
        assertTrue(snapshot.first().contains("\u001B[9m"))
        assertEquals("weak deleted", AnsiRenderer.renderAnsiText(snapshot.first()).text)
    }
}
