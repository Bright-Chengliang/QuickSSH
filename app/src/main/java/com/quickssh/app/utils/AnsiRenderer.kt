package com.quickssh.app.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * ANSI text renderer.
 *
 * Converts ANSI SGR color and style sequences into a Compose AnnotatedString,
 * and strips unrelated OSC, DCS, and control sequences before rendering.
 */
object AnsiRenderer {
    private val defaultTerminalForeground = Color(0xFFE5E7EB)
    private val defaultTerminalBackground = Color(0xFF0F0F12)

    // ANSI foreground and background color lookup table.
    private val ansiColors = mapOf(
        // Standard foreground colors (30-37)
        30 to Color(0xFF1F2937),
        31 to Color(0xFFEF4444),
        32 to Color(0xFF10B981),
        33 to Color(0xFFF59E0B),
        34 to Color(0xFF3B82F6),
        35 to Color(0xFFA855F7),
        36 to Color(0xFF06B6D4),
        37 to Color(0xFFE5E7EB),

        // Bright foreground colors (90-97)
        90 to Color(0xFF6B7280),
        91 to Color(0xFFF87171),
        92 to Color(0xFF34D399),
        93 to Color(0xFFFBBF24),
        94 to Color(0xFF60A5FA),
        95 to Color(0xFFC084FC),
        96 to Color(0xFF22D3EE),
        97 to Color(0xFFF3F4F6),

        // Standard background colors (40-47)
        40 to Color(0xFF1F2937),
        41 to Color(0xFFEF4444),
        42 to Color(0xFF10B981),
        43 to Color(0xFFF59E0B),
        44 to Color(0xFF3B82F6),
        45 to Color(0xFFA855F7),
        46 to Color(0xFF06B6D4),
        47 to Color(0xFFE5E7EB),

        // Bright background colors (100-107)
        100 to Color(0xFF6B7280),
        101 to Color(0xFFF87171),
        102 to Color(0xFF34D399),
        103 to Color(0xFFFBBF24),
        104 to Color(0xFF60A5FA),
        105 to Color(0xFFC084FC),
        106 to Color(0xFF22D3EE),
        107 to Color(0xFFF3F4F6)
    )

    /**
     * Removes non-SGR ANSI sequences while preserving SGR style markers.
     * Strips unsupported ANSI control sequences while keeping SGR codes intact.
     */
    fun cleanNonSgrAnsi(text: String): String {
        // Temporarily preserve SGR sequences with placeholders.
        val sgrPattern = Regex("\u001B\\[([0-9;:]*)m")
        val sgrSequences = mutableListOf<String>()
        val textWithPlaceholders = sgrPattern.replace(text) { matchResult ->
            val placeholder = "__SGR_${sgrSequences.size}__"
            sgrSequences.add(matchResult.value)
            placeholder
        }

        // Strip unrelated control sequences from the remaining text.
        var cleaned = textWithPlaceholders

        // Remove CSI commands except SGR.
        cleaned = cleaned.replace(Regex("\u001B\\[[<=>?!]?[0-9;]*[ !\"#$%&'()*+,\\-./:]*[A-Za-km-z@\\[\\\\\\]^_`\\{\\|\\}~]"), "")

        // Remove OSC sequences (ESC ] ... BEL/ST).
        cleaned = cleaned.replace(Regex("\u001B\\]([^\u0007\u001B]*?)(\u0007|\u001B\\\\)"), "")

        // Remove DCS sequences (ESC P ... ST).
        cleaned = cleaned.replace(Regex("\u001BP[^\u001B]*\u001B\\\\"), "")

        // Remove single-character ESC sequences.
        cleaned = cleaned.replace(Regex("\u001B[><=\\(\\)NOMED78HcZB]"), "")

        // Remove leftover control characters.
        cleaned = cleaned.replace(Regex("[\u0000-\u0008\u000B-\u001F\u007F]"), "")

        // Append any remaining text.
        cleaned = cleaned.replace(Regex("\r(?!\n)"), "")

        // Restore preserved SGR sequences.
        val placeholderPattern = Regex("__SGR_(\\d+)__")
        cleaned = placeholderPattern.replace(cleaned) { matchResult ->
            val index = matchResult.groupValues[1].toInt()
            sgrSequences[index]
        }

        return cleaned
    }

    /** Renders ANSI text into an AnnotatedString. */
    fun renderAnsiText(text: String): AnnotatedString {
        // Normalize input by removing non-SGR ANSI sequences first.
        val cleanedText = cleanNonSgrAnsi(text)

        val builder = AnnotatedString.Builder()

        // Append any remaining text.
        var currentColor: Color? = null
        var currentBgColor: Color? = null
        var isBold = false
        var isDim = false
        var isItalic = false
        var isUnderline = false
        var isStrikethrough = false
        var isInverse = false

        // Match CSI SGR sequences like ESC [ 31m.
        val csiPattern = Regex("\u001B\\[([0-9;:]*)m")

        var lastIndex = 0

        csiPattern.findAll(cleanedText).forEach { match ->
            // Append plain text using the current style.
            val plainText = cleanedText.substring(lastIndex, match.range.first)
            if (plainText.isNotEmpty()) {
                val spanStyle = buildSpanStyle(
                    color = currentColor,
                    backgroundColor = currentBgColor,
                    isBold = isBold,
                    isDim = isDim,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    isStrikethrough = isStrikethrough,
                    isInverse = isInverse
                )
                builder.pushStyle(spanStyle)
                builder.append(plainText)
                builder.pop()
            }

            // Apply SGR parameters.
            val params = match.groupValues[1]
            if (params.isEmpty() || params == "0") {
                // Reset all active styles.
                currentColor = null
                currentBgColor = null
                isBold = false
                isDim = false
                isItalic = false
                isUnderline = false
                isStrikethrough = false
                isInverse = false
            } else {
                val codes = params.split(';', ':').mapNotNull { it.toIntOrNull() }
                var index = 0
                while (index < codes.size) {
                    when (val code = codes[index]) {
                        0 -> {
                            // Full reset.
                            currentColor = null
                            currentBgColor = null
                            isBold = false
                            isDim = false
                            isItalic = false
                            isUnderline = false
                            isStrikethrough = false
                            isInverse = false
                        }
                        1 -> isBold = true
                        2 -> isDim = true
                        3 -> isItalic = true
                        4 -> isUnderline = true
                        7 -> isInverse = true
                        9 -> isStrikethrough = true
                        22 -> {
                            isBold = false
                            isDim = false
                        }
                        23 -> isItalic = false
                        24 -> isUnderline = false
                        27 -> isInverse = false
                        29 -> isStrikethrough = false
                        in 30..37, in 90..97 -> currentColor = ansiColors[code]
                        39 -> currentColor = null
                        in 40..47, in 100..107 -> currentBgColor = ansiColors[code]
                        49 -> currentBgColor = null
                        38 -> {
                            val parsed = parseExtendedColor(codes, index)
                            if (parsed != null) {
                                currentColor = parsed.first
                                index = parsed.second
                            }
                        }
                        48 -> {
                            val parsed = parseExtendedColor(codes, index)
                            if (parsed != null) {
                                currentBgColor = parsed.first
                                index = parsed.second
                            }
                        }
                    }
                    index++
                }
            }

            lastIndex = match.range.last + 1
        }

        // Append any remaining text.
        val remainingText = cleanedText.substring(lastIndex)
        if (remainingText.isNotEmpty()) {
            val spanStyle = buildSpanStyle(
                color = currentColor,
                backgroundColor = currentBgColor,
                isBold = isBold,
                isDim = isDim,
                isItalic = isItalic,
                isUnderline = isUnderline,
                isStrikethrough = isStrikethrough,
                isInverse = isInverse
            )
            builder.pushStyle(spanStyle)
            builder.append(remainingText)
            builder.pop()
        }

        return builder.toAnnotatedString()
    }

    private fun parseExtendedColor(codes: List<Int>, start: Int): Pair<Color, Int>? {
        if (start + 2 >= codes.size) return null
        return when (codes[start + 1]) {
            5 -> xterm256Color(codes[start + 2]) to (start + 2)
            2 -> {
                if (start + 4 >= codes.size) return null
                Color(
                    red = codes[start + 2].coerceIn(0, 255),
                    green = codes[start + 3].coerceIn(0, 255),
                    blue = codes[start + 4].coerceIn(0, 255)
                ) to (start + 4)
            }
            else -> null
        }
    }

    private fun xterm256Color(index: Int): Color {
        val safeIndex = index.coerceIn(0, 255)
        val base = listOf(
            Color(0xFF1F2937), Color(0xFFEF4444), Color(0xFF10B981), Color(0xFFF59E0B),
            Color(0xFF3B82F6), Color(0xFFA855F7), Color(0xFF06B6D4), Color(0xFFE5E7EB),
            Color(0xFF6B7280), Color(0xFFF87171), Color(0xFF34D399), Color(0xFFFBBF24),
            Color(0xFF60A5FA), Color(0xFFC084FC), Color(0xFF22D3EE), Color(0xFFF3F4F6)
        )
        if (safeIndex < 16) return base[safeIndex]
        if (safeIndex in 16..231) {
            val value = safeIndex - 16
            val red = value / 36
            val green = (value % 36) / 6
            val blue = value % 6
            fun component(part: Int): Int = if (part == 0) 0 else 55 + part * 40
            return Color(component(red), component(green), component(blue))
        }
        val gray = 8 + (safeIndex - 232) * 10
        return Color(gray, gray, gray)
    }

    private fun buildSpanStyle(
        color: Color?,
        backgroundColor: Color?,
        isBold: Boolean,
        isDim: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean,
        isStrikethrough: Boolean,
        isInverse: Boolean
    ): SpanStyle {
        val baseColor = if (isInverse) {
            backgroundColor ?: defaultTerminalBackground
        } else {
            color ?: defaultTerminalForeground
        }
        val effectiveColor = if (isDim) baseColor.copy(alpha = 0.72f) else baseColor
        val effectiveBackground = if (isInverse) {
            color ?: defaultTerminalForeground
        } else {
            backgroundColor ?: Color.Unspecified
        }
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { isUnderline },
            TextDecoration.LineThrough.takeIf { isStrikethrough }
        )
        return SpanStyle(
            color = effectiveColor,
            background = effectiveBackground,
            fontWeight = if (isBold) FontWeight.Bold else null,
            fontStyle = if (isItalic) FontStyle.Italic else null,
            textDecoration = when (decorations.size) {
                0 -> null
                1 -> decorations.first()
                else -> TextDecoration.combine(decorations)
            }
        )
    }
}
