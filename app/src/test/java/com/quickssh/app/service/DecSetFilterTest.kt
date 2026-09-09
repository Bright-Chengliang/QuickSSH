package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class DecSetFilterTest {

    @Test
    fun testRewriteStandaloneDecSet1003() {
        val input = "\u001B[?1003h".toByteArray(StandardCharsets.UTF_8)
        DecSetFilter.rewrite(input)
        val result = String(input, StandardCharsets.UTF_8)
        assertEquals("\u001B[?1002h", result)
    }

    @Test
    fun testRewriteStandaloneDecReset1003() {
        val input = "\u001B[?1003l".toByteArray(StandardCharsets.UTF_8)
        DecSetFilter.rewrite(input)
        val result = String(input, StandardCharsets.UTF_8)
        assertEquals("\u001B[?1002l", result)
    }

    @Test
    fun testRewriteOpenCodeCompositeSequence() {
        val input = "\u001B[?1000h\u001B[?1002h\u001B[?1003h\u001B[?1006h".toByteArray(StandardCharsets.UTF_8)
        DecSetFilter.rewrite(input)
        val result = String(input, StandardCharsets.UTF_8)
        assertEquals("\u001B[?1000h\u001B[?1002h\u001B[?1002h\u001B[?1006h", result)
    }

    @Test
    fun testRewriteSemicolonDelimitedDecSet1003() {
        val input = "\u001B[?1000;1002;1003;1006h".toByteArray(StandardCharsets.UTF_8)
        DecSetFilter.rewrite(input)
        val result = String(input, StandardCharsets.UTF_8)
        assertEquals("\u001B[?1000;1002;1002;1006h", result)
    }

    @Test
    fun testDoesNotAlterUnrelatedText() {
        val original = "There are 1003 items in the list: code 1003."
        val input = original.toByteArray(StandardCharsets.UTF_8)
        DecSetFilter.rewrite(input)
        val result = String(input, StandardCharsets.UTF_8)
        assertEquals(original, result)
    }

    @Test
    fun testDoesNotAlterOtherDecSetNumbers() {
        val original = "\u001B[?25h\u001B[?1049h\u001B[?2004h"
        val input = original.toByteArray(StandardCharsets.UTF_8)
        DecSetFilter.rewrite(input)
        val result = String(input, StandardCharsets.UTF_8)
        assertEquals(original, result)
    }
}
