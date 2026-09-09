package com.quickssh.app.service

/**
 * Rewrites DECSET/DECRST 1003 (any-event mouse tracking) sequences to 1002
 * (button-event mouse tracking) in-place.
 *
 * Termux's TerminalEmulator only implements DECSET 1000 and 1002, dropping 1003 as unmapped.
 * Modern TUI applications (e.g. OpenCode, OpenTUI, Bubble Tea) request 1003. By translating 1003 to
 * 1002 in the incoming SSH byte stream, Termux activates its internal mouse tracking state
 * (isMouseTrackingActive() == true), enabling touch scroll gestures to be converted into
 * mouse wheel events.
 */
object DecSetFilter {
    fun rewrite(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        val end = offset + length
        var i = offset
        while (i < end - 5) {
            // Check for ESC [ ? (0x1B, 0x5B, 0x3F)
            if (buffer[i] == 0x1B.toByte() && buffer[i + 1] == '['.code.toByte() && buffer[i + 2] == '?'.code.toByte()) {
                var j = i + 3
                while (j < end) {
                    val b = buffer[j]
                    // Reached end of DECSET/DECRST command ('h' = set, 'l' = reset)
                    if (b == 'h'.code.toByte() || b == 'l'.code.toByte()) {
                        i = j
                        break
                    }
                    // Non-parameter character terminates escape sequence
                    if (b !in '0'.code.toByte()..'9'.code.toByte() && b != ';'.code.toByte()) {
                        i = j
                        break
                    }
                    // Check for "1003"
                    if (j + 4 <= end &&
                        buffer[j] == '1'.code.toByte() &&
                        buffer[j + 1] == '0'.code.toByte() &&
                        buffer[j + 2] == '0'.code.toByte() &&
                        buffer[j + 3] == '3'.code.toByte()
                    ) {
                        val prev = buffer[j - 1]
                        val next = if (j + 4 < end) buffer[j + 4] else 0.toByte()
                        if ((prev == '?'.code.toByte() || prev == ';'.code.toByte()) &&
                            (next == ';'.code.toByte() || next == 'h'.code.toByte() || next == 'l'.code.toByte())
                        ) {
                            buffer[j + 3] = '2'.code.toByte()
                        }
                        j += 4
                    } else {
                        j++
                    }
                }
            }
            i++
        }
    }
}
