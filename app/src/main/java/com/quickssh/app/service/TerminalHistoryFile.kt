package com.quickssh.app.service

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * Manages a disk-backed terminal history file for a single SSH session.
 *
 * All terminal output (after stripping cursor-control ANSI sequences but preserving
 * color/style codes) is appended to the file incrementally. The file can then be
 * read in paginated chunks for the history viewer UI.
 *
 * Thread safety: All write operations are synchronized on [lock]. Read operations
 * (page loading) are also synchronized to avoid reading partial lines.
 */
class TerminalHistoryFile(
    private val context: Context,
    private val sessionId: String
) {
    companion object {
        private const val DIR_NAME = "terminal_history"
        const val MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024 // 5 MB per session cap
        const val MAX_TOTAL_HISTORY_BYTES = 50L * 1024 * 1024 // 50 MB total limit
        private const val PAGE_SIZE_LINES = 200

        fun historyDirectory(context: Context): File {
            return File(context.cacheDir, DIR_NAME)
        }

        fun cleanOldHistoryFiles(context: Context, keepSessionIds: Set<String>) {
            val dir = historyDirectory(context)
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { file ->
                val name = file.nameWithoutExtension
                if (name !in keepSessionIds) {
                    file.delete()
                }
            }
        }

        /**
         * Enforces maximum total history directory size by deleting oldest log files (FIFO).
         */
        fun pruneHistoryToTotalLimit(context: Context, maxTotalBytes: Long = MAX_TOTAL_HISTORY_BYTES) {
            val dir = historyDirectory(context)
            if (!dir.isDirectory) return
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var currentTotal = files.sumOf { it.length() }
            if (currentTotal <= maxTotalBytes) return

            // Sort oldest first (FIFO)
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                val size = f.length()
                if (f.delete()) {
                    currentTotal -= size
                    if (currentTotal <= maxTotalBytes) break
                }
            }
        }
    }

    private val lock = Any()
    private val file: File
    private var writer: BufferedWriter? = null
    private var totalLines = 0
    private var closed = false

    init {
        val dir = historyDirectory(context)
        dir.mkdirs()
        file = File(dir, "$sessionId.txt")
        if (file.exists()) {
            // Count existing lines for an appended session
            totalLines = file.useLines { it.count() }
        }
    }

    /**
     * Append rendered terminal lines to the history file.
     * Receives the plain-text (or ANSI-colored) lines from the terminal snapshot diff.
     */
    fun appendLines(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            if (file.length() > MAX_FILE_SIZE_BYTES) return
            try {
                val w = writer ?: run {
                    pruneHistoryToTotalLimit(context)
                    BufferedWriter(FileWriter(file, true)).also { writer = it }
                }
                for (line in lines) {
                    w.write(line)
                    w.newLine()
                    totalLines++
                }
                w.flush()
            } catch (e: IOException) {
                // Silently ignore write failures — history is best-effort
            }
        }
    }

    /**
     * Append a single raw chunk of text, splitting into lines.
     */
    fun appendRawText(text: String) {
        if (text.isEmpty()) return
        val stripped = stripCursorControlSequences(text)
        if (stripped.isEmpty()) return
        val lines = stripped.split('\n')
        appendLines(lines)
    }

    /**
     * Get total number of lines currently in the history file.
     */
    fun lineCount(): Int {
        synchronized(lock) {
            return totalLines
        }
    }

    /**
     * Read a page of lines from the history file.
     * @param page 0-based page index (0 = first page from the top)
     * @return list of lines in that page, empty if out of range
     */
    fun readPage(page: Int): List<String> {
        synchronized(lock) {
            if (!file.exists() || totalLines == 0) return emptyList()
            val startLine = page * PAGE_SIZE_LINES
            if (startLine >= totalLines) return emptyList()
            return try {
                file.useLines { sequence ->
                    sequence.drop(startLine).take(PAGE_SIZE_LINES).toList()
                }
            } catch (e: IOException) {
                emptyList()
            }
        }
    }

    /**
     * Read the last N lines from the file (for "jump to bottom" in history viewer).
     */
    fun readLastLines(count: Int): List<String> {
        synchronized(lock) {
            if (!file.exists() || totalLines == 0) return emptyList()
            val skipCount = max(0, totalLines - count)
            return try {
                file.useLines { sequence ->
                    sequence.drop(skipCount).toList()
                }
            } catch (e: IOException) {
                emptyList()
            }
        }
    }

    /**
     * Search for lines containing the query string (case-insensitive).
     * Returns matching line indices and content.
     */
    fun search(query: String, maxResults: Int = 200): List<Pair<Int, String>> {
        if (query.isBlank()) return emptyList()
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val lowerQuery = query.lowercase()
            val results = mutableListOf<Pair<Int, String>>()
            try {
                file.useLines { sequence ->
                    sequence.forEachIndexed { index, line ->
                        if (results.size >= maxResults) return@forEachIndexed
                        if (line.lowercase().contains(lowerQuery)) {
                            results.add(index to line)
                        }
                    }
                }
            } catch (e: IOException) {
                // ignore
            }
            return results
        }
    }

    /**
     * Get the total page count.
     */
    fun pageCount(): Int {
        val lines = lineCount()
        if (lines == 0) return 0
        return (lines + PAGE_SIZE_LINES - 1) / PAGE_SIZE_LINES
    }

    /**
     * Close the writer. Call when session ends.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            try {
                writer?.close()
            } catch (_: IOException) {}
            writer = null
        }
    }

    /**
     * Delete the history file.
     */
    fun delete() {
        close()
        file.delete()
    }

    /**
     * Get the file path for sharing/export.
     */
    fun filePath(): String = file.absolutePath
}

/**
 * Strip cursor-control ANSI escape sequences but keep color/style codes.
 * This removes sequences like cursor movement (CSI n A/B/C/D/H/f/G),
 * erase (CSI n J/K), scroll (CSI n S/T), and other non-visual commands,
 * but preserves SGR color sequences (CSI ... m).
 */
internal fun stripCursorControlSequences(text: String): String {
    if (!text.contains('\u001B')) return text
    // Keep only SGR (color/style) sequences: ESC[...m
    // Remove all other CSI sequences: ESC[...X where X is not 'm'
    return text.replace(Regex("\u001B\\[([0-9;?]*)([A-LN-Zf-z@`])")) { match ->
        val finalChar = match.groupValues[2]
        if (finalChar == "m") match.value else ""
    }
}
