package com.quickssh.app.service

import android.content.Context
import com.quickssh.app.data.SshConfig
import com.quickssh.app.utils.TerminalBuffer
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class LocalPtyHelper(
    override val sessionId: String,
    val config: SshConfig,
    private val appContext: Context,
    initialTerminalSize: TerminalBuffer.Size = TerminalBuffer.Size(
        TerminalBuffer.DEFAULT_COLUMNS,
        TerminalBuffer.DEFAULT_ROWS
    )
) : TerminalSessionClient {

    companion object {
        private const val HISTORY_BUFFER_MAX_LINES = 100_000
        private const val RECENT_OUTPUT_REPLAY_MAX_CHUNKS = 32768
        private const val RECENT_OUTPUT_REPLAY_MAX_CHARS = 16 * 1024 * 1024
        private const val SHELL_STARTUP_DELAY_MS = 300L
        private const val POST_CONNECT_COMMAND_DELAY_MS = 250L
    }

    private var process: Process? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readerJob: Job? = null
    private var processMonitorJob: Job? = null
    private val _termuxSessionState = MutableStateFlow<TerminalSession?>(null)
    override val termuxSessionFlow: StateFlow<TerminalSession?> = _termuxSessionState.asStateFlow()
    override val termuxSession: TerminalSession? get() = _termuxSessionState.value

    @Volatile
    private var isConnected = false

    @Volatile
    private var userRequestedDisconnect = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var terminalSize = initialTerminalSize.sanitized()

    private val _terminalOutput = MutableSharedFlow<String>(
        replay = 64,
        extraBufferCapacity = 128
    )
    override val terminalOutput: SharedFlow<String> = _terminalOutput

    private val recentOutputReplay = BoundedTextReplay(
        maxChunks = RECENT_OUTPUT_REPLAY_MAX_CHUNKS,
        maxChars = RECENT_OUTPUT_REPLAY_MAX_CHARS
    )

    override val historyBuffer = TerminalBuffer(
        maxLines = HISTORY_BUFFER_MAX_LINES,
        initialColumns = initialTerminalSize.columns,
        initialRows = initialTerminalSize.rows
    )

    override val historyFile = TerminalHistoryFile(appContext, sessionId)

    private val _status = MutableStateFlow(SshSessionStatus.CONNECTING)
    override val status: StateFlow<SshSessionStatus> = _status.asStateFlow()

    private val _statusMessage = MutableStateFlow("Starting local terminal...")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    override fun connect() {
        userRequestedDisconnect = false
        updateStatus(SshSessionStatus.CONNECTING, "Starting local terminal...")
        scope.launch {
            startLocalSession()
        }
    }

    private suspend fun startLocalSession() {
        try {
            cleanOrphanedPtyHandles()

            val detected = LocalEnvironmentDetector.detect(
                context = appContext,
                preferredShellPath = config.host.takeIf { it.isNotBlank() && it != "localhost" && it != "127.0.0.1" },
                preferredWorkDir = config.workDirectory,
                term = config.terminalTerm
            )

            val termuxManager = TermuxSessionManager(appContext, scope)

            var activeEnv = detected
            val launchTime = System.currentTimeMillis()
            var fallbackTriggered = false

            suspend fun tryStartNativeSession(targetEnv: DetectedEnvironment): TerminalSession? {
                emitLog("[QuickSSH Local] Initializing ${targetEnv.shellName} (${targetEnv.shellPath})...\n")
                emitLog("[QuickSSH Local] Working dir: ${targetEnv.initialWorkDir}\n")
                val isSystemSh = targetEnv.shellPath == LocalEnvironmentDetector.SYSTEM_SH || targetEnv.shellPath.endsWith("/sh")
                val sessionArgs = when {
                    targetEnv.args.isNotEmpty() -> targetEnv.args
                    isSystemSh -> emptyArray() // /system/bin/sh exits immediately on -l
                    targetEnv.shellPath.endsWith("bash") -> arrayOf("-l")
                    else -> arrayOf("-l")
                }
                return termuxManager.createLocalSession(
                    shellPath = targetEnv.shellPath,
                    cwd = targetEnv.initialWorkDir,
                    args = sessionArgs,
                    env = targetEnv.environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                    initialCols = terminalSize.columns,
                    initialRows = terminalSize.rows,
                    onSessionFinished = { exitCode ->
                        val duration = System.currentTimeMillis() - launchTime
                        scope.launch {
                            if (!fallbackTriggered && (exitCode == 127 || duration < 1500) && targetEnv.shellPath != LocalEnvironmentDetector.SYSTEM_SH) {
                                fallbackTriggered = true
                                emitLog("\r\n[QuickSSH Local] ${targetEnv.shellName} exited ($exitCode). Automatically falling back to Android System Shell (${LocalEnvironmentDetector.SYSTEM_SH})...\r\n")
                                val fallbackEnv = LocalEnvironmentDetector.detect(
                                    context = appContext,
                                    preferredShellPath = LocalEnvironmentDetector.SYSTEM_SH,
                                    preferredWorkDir = config.workDirectory,
                                    term = config.terminalTerm
                                )
                                val fallbackSession = tryStartNativeSession(fallbackEnv)
                                if (fallbackSession != null) {
                                    _termuxSessionState.value = fallbackSession
                                    isConnected = true
                                    updateStatus(SshSessionStatus.CONNECTED, "Local terminal active: ${fallbackEnv.shellName}")
                                    emitLog("[QuickSSH Local] Shell active: ${fallbackEnv.shellName}\n")
                                    val postConnectCommands = postConnectCommands(config.postConnectCommand)
                                    if (postConnectCommands.isNotEmpty()) {
                                        postConnectCommands.forEach { command ->
                                            fallbackSession.write(command + "\n")
                                        }
                                    }
                                    return@launch
                                }
                            }
                            emitLog("\r\n[QuickSSH Local] Shell process terminated (exit code: $exitCode).\r\n")
                            updateStatus(SshSessionStatus.DISCONNECTED, "Local terminal exited ($exitCode)")
                            _termuxSessionState.value = null
                            isConnected = false
                        }
                    }
                )
            }

            var nativeSession = tryStartNativeSession(activeEnv)

            if (nativeSession == null && activeEnv.shellPath != LocalEnvironmentDetector.SYSTEM_SH) {
                emitLog("[QuickSSH Local] Warning: ${activeEnv.shellName} failed to start. Falling back to Android System Shell...\n")
                activeEnv = LocalEnvironmentDetector.detect(
                    context = appContext,
                    preferredShellPath = LocalEnvironmentDetector.SYSTEM_SH,
                    preferredWorkDir = config.workDirectory,
                    term = config.terminalTerm
                )
                nativeSession = tryStartNativeSession(activeEnv)
            }

            if (nativeSession != null) {
                _termuxSessionState.value = nativeSession
                isConnected = true
                updateStatus(SshSessionStatus.CONNECTED, "Local terminal active: ${activeEnv.shellName}")
                emitLog("[QuickSSH Local] Shell active: ${activeEnv.shellName}\n")
                val postConnectCommands = postConnectCommands(config.postConnectCommand)
                if (postConnectCommands.isNotEmpty()) {
                    emitLog("[QuickSSH Local] Running ${postConnectCommands.size} post-connect command(s)...\n")
                    postConnectCommands.forEachIndexed { _, command ->
                        nativeSession.write(command + "\n")
                    }
                }
                return
            }

            val cmdList = mutableListOf(detected.shellPath)
            if (detected.shellPath.endsWith("bash") || detected.shellPath.endsWith("sh")) {
                cmdList.add("-i")
            }

            val pb = ProcessBuilder(cmdList)
            pb.directory(File(detected.initialWorkDir))
            pb.redirectErrorStream(true)

            val pbEnv = pb.environment()
            pbEnv.putAll(detected.environment)
            pbEnv["LINES"] = terminalSize.rows.toString()
            pbEnv["COLUMNS"] = terminalSize.columns.toString()

            val proc = pb.start()
            process = proc
            outputStream = proc.outputStream
            inputStream = proc.inputStream
            isConnected = true

            updateStatus(SshSessionStatus.CONNECTED, "Local terminal active: ${detected.shellName}")

            launchReaderRoutine(proc.inputStream)
            launchProcessMonitor(proc)

            delay(SHELL_STARTUP_DELAY_MS)

            // Initial window size sync via stty if available
            val sttyCmd = "stty rows ${terminalSize.rows} cols ${terminalSize.columns} 2>/dev/null\n"
            outputStream?.write(sttyCmd.toByteArray(Charsets.UTF_8))
            outputStream?.flush()

            val postConnectCommands = postConnectCommands(config.postConnectCommand)
            if (postConnectCommands.isNotEmpty()) {
                emitLog("[QuickSSH Local] Running ${postConnectCommands.size} post-connect command(s)...\n")
                postConnectCommands.forEachIndexed { index, command ->
                    writeCommandDirect(command)
                    if (index < postConnectCommands.lastIndex) {
                        delay(POST_CONNECT_COMMAND_DELAY_MS)
                    }
                }
            }
        } catch (e: Exception) {
            updateStatus(SshSessionStatus.FAILED, "Failed to start local shell: ${e.localizedMessage ?: e.javaClass.simpleName}")
            emitLog("\n[QuickSSH Local Error] Cannot launch local shell: ${e.localizedMessage ?: e.javaClass.simpleName}\n")
            cleanOrphanedPtyHandles()
        }
    }

    private fun launchReaderRoutine(stream: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val decoder = TerminalOutputDecoder()
            val buffer = ByteArray(16384)
            try {
                while (isConnected) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        val text = decoder.decode(buffer, read)
                        if (text.isNotEmpty()) {
                            emitLog(text)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (!userRequestedDisconnect && isConnected) {
                    emitLog("\n[QuickSSH Local] Local shell process terminated.\n")
                }
            }
        }
    }

    private fun launchProcessMonitor(proc: Process) {
        processMonitorJob?.cancel()
        processMonitorJob = scope.launch(Dispatchers.IO) {
            try {
                val exitCode = proc.waitFor()
                if (!userRequestedDisconnect) {
                    updateStatus(SshSessionStatus.DISCONNECTED, "Local shell exited ($exitCode)")
                    emitLog("\n[QuickSSH Local] Shell exited with status $exitCode.\n")
                }
            } catch (_: Exception) {
            } finally {
                cleanOrphanedPtyHandles()
            }
        }
    }

    override fun sendLine(line: String) {
        _termuxSessionState.value?.write(terminalLinePayload(line)) ?: sendCommand(terminalLinePayload(line))
    }

    override fun sendRawInput(data: String) {
        if (data.isEmpty()) return
        _termuxSessionState.value?.let {
            it.write(data)
            return
        }
        if (!isConnected || outputStream == null) return
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.write(data.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) {
                emitLog("\n[QuickSSH Local Error] Failed to write input: ${e.localizedMessage}")
            }
        }
    }

    override fun resizeTerminal(size: TerminalBuffer.Size) {
        val next = size.sanitized()
        if (next == terminalSize) return
        historyBuffer.resize(next)
        terminalSize = next
        _termuxSessionState.value?.updateSize(next.columns, next.rows)
        scope.launch(Dispatchers.IO) {
            try {
                val sttyCmd = "stty rows ${next.rows} cols ${next.columns} 2>/dev/null\n"
                outputStream?.write(sttyCmd.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun disconnect() {
        userRequestedDisconnect = true
        _termuxSessionState.value?.finishIfRunning()
        _termuxSessionState.value = null
        cleanOrphanedPtyHandles()
        updateStatus(SshSessionStatus.DISCONNECTED, "Disconnected")
        scope.launch { emitLog("[QuickSSH Local] Session closed.\n") }
    }

    override fun reconnectNow() {
        userRequestedDisconnect = false
        cleanOrphanedPtyHandles()
        connect()
    }

    override fun reconnectWhenNetworkAvailable() {
        // Local terminal does not depend on remote network availability
    }

    override fun recentOutputSnapshot(): List<String> {
        return recentOutputReplay.snapshot()
    }

    override fun runCodexResumeShortcut(command: String, workDirectory: String?) {
        val resumeCommand = command.trim().ifBlank { "codex resume --last --no-alt-screen" }
        sendLine(resumeCommand)
    }

    /**
     * Cleans up child processes and closes file streams to avoid handle leaks,
     * following Section 4 requirements.
     */
    fun cleanOrphanedPtyHandles() {
        isConnected = false
        readerJob?.cancel()
        processMonitorJob?.cancel()
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            process?.destroy()
        } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        process = null

        // Trigger history storage pruning
        TerminalHistoryFile.pruneHistoryToTotalLimit(appContext)
    }

    private suspend fun writeCommandDirect(line: String) {
        outputStream?.write(directCommandPayload(line).toByteArray(Charsets.UTF_8))
        outputStream?.flush()
        delay(80L)
    }

    private fun sendCommand(command: String) {
        if (!isConnected || outputStream == null) {
            scope.launch { emitLog("\n[QuickSSH Local] Session is closed.") }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val isControlChar = command.startsWith("\u001B") ||
                    command == "\r" || command == "\n" || command == "\t" || command == " " ||
                    command == "\u007F" || command == "\u0003" || (command.length == 1 && command[0] < ' ')
                val commandLine = command.trimEnd('\r', '\n')

                val formatted = when {
                    commandLine == "clear" -> {
                        emitLog("\u001b[H\u001b[2J")
                        "clear\r\n"
                    }
                    isControlChar -> if (command == "\n") "\r\n" else command
                    !command.endsWith("\n") && !command.endsWith("\r") -> command + "\n"
                    else -> command
                }

                outputStream?.write(formatted.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) {
                emitLog("\n[QuickSSH Local Error] Failed to send command: ${e.localizedMessage}")
            }
        }
    }

    private fun updateStatus(status: SshSessionStatus, message: String) {
        _status.value = status
        _statusMessage.value = message
    }

    private suspend fun emitLog(text: String) {
        recentOutputReplay.append(text)
        historyBuffer.appendRaw(text)
        historyFile.appendLines(historyBuffer.drainHistoryLines())
        _terminalOutput.emit(text)
    }
}
