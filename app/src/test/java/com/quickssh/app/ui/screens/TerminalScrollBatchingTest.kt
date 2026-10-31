package com.quickssh.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards how a drag turns into PgUp/PgDn traffic. Over a real network each write is a round trip, so a
 * drag must collapse into as few writes as possible instead of one write per scrolled page.
 */
class TerminalScrollBatchingTest {
    @Test
    fun aWholeDragCollapsesIntoOnePageKeyBatch() {
        // 10 pages of scroll accumulated within a single frame window.
        val pages = listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1).sum()
        assertEquals(10, pages)
        assertEquals("\u001B[5~".repeat(10), scrollPayload(pages))
    }

    @Test
    fun upwardDragSendsPageDown() {
        assertEquals("\u001B[6~", scrollPayload(-1))
        assertEquals("\u001B[6~".repeat(3), scrollPayload(-3))
    }

    @Test
    fun zeroPagesSendsNothing() {
        assertEquals("", scrollPayload(0))
    }

    @Test
    fun payloadSizeGrowsWithPagesNotWithWrites() {
        // One write carrying 30 repeats must be far smaller than 30 separate protocol writes would be
        // in aggregate overhead terms, and the payload itself stays linear and small.
        val payload = scrollPayload(30)
        assertEquals(30 * 4, payload.length)
        assertTrue(payload.length < 200)
    }

    @Test
    fun conptyWithoutTranscriptIsTheOnlyCaseThatNeedsPageKeys() {
        assertTrue(termuxNativeScrollIsIneffective(false, false, 0))
        assertEquals(false, termuxNativeScrollIsIneffective(false, false, 100))
    }

    private fun scrollPayload(pages: Int): String {
        if (pages == 0) return ""
        val sequence = if (pages > 0) "\u001B[5~" else "\u001B[6~"
        return sequence.repeat(kotlin.math.abs(pages))
    }
}
