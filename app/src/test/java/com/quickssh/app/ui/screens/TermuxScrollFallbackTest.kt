package com.quickssh.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the terminal scroll fallback. Deleting the PgUp/PgDn fallback makes a Windows ConPTY session
 * completely unscrollable, because Termux reports no mouse tracking, no alternate buffer and zero
 * transcript rows there.
 */
class TermuxScrollFallbackTest {
    @Test
    fun conptySessionWithoutTranscriptNeedsTheFallback() {
        assertTrue(
            termuxNativeScrollIsIneffective(
                mouseTrackingActive = false,
                alternateBufferActive = false,
                activeTranscriptRows = 0
            )
        )
    }

    @Test
    fun negativeTranscriptRowsStillNeedTheFallback() {
        assertTrue(
            termuxNativeScrollIsIneffective(
                mouseTrackingActive = false,
                alternateBufferActive = false,
                activeTranscriptRows = -1
            )
        )
    }

    @Test
    fun localPtyWithTranscriptScrollsNatively() {
        assertFalse(
            termuxNativeScrollIsIneffective(
                mouseTrackingActive = false,
                alternateBufferActive = false,
                activeTranscriptRows = 240
            )
        )
    }

    @Test
    fun mouseTrackingSessionScrollsNatively() {
        assertFalse(
            termuxNativeScrollIsIneffective(
                mouseTrackingActive = true,
                alternateBufferActive = false,
                activeTranscriptRows = 0
            )
        )
    }

    @Test
    fun alternateScreenSessionScrollsNatively() {
        assertFalse(
            termuxNativeScrollIsIneffective(
                mouseTrackingActive = false,
                alternateBufferActive = true,
                activeTranscriptRows = 0
            )
        )
    }

    @Test
    fun unknownEmulatorStateNeverSendsPageKeys() {
        assertFalse(termuxNativeScrollIsIneffective(null, null, null))
        assertFalse(termuxNativeScrollIsIneffective(false, null, 0))
        assertFalse(termuxNativeScrollIsIneffective(null, false, 0))
    }
}
