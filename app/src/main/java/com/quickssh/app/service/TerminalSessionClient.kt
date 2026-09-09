package com.quickssh.app.service

import com.quickssh.app.utils.TerminalBuffer
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified interface for terminal sessions (both remote SSH and local PTY/shell).
 */
interface TerminalSessionClient {
    val sessionId: String
    val termuxSession: TerminalSession?
        get() = termuxSessionFlow.value
    val termuxSessionFlow: StateFlow<TerminalSession?>
        get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    val terminalOutput: SharedFlow<String>
    val status: StateFlow<SshSessionStatus>
    val statusMessage: StateFlow<String>
    val historyBuffer: TerminalBuffer
    val historyFile: TerminalHistoryFile

    fun connect()
    fun sendLine(line: String)
    fun sendRawInput(data: String)
    fun resizeTerminal(size: TerminalBuffer.Size)
    fun disconnect()
    fun reconnectNow()
    fun reconnectWhenNetworkAvailable()
    fun recentOutputSnapshot(): List<String>
    fun runCodexResumeShortcut(command: String, workDirectory: String?)
}
