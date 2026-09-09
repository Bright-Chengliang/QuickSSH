package com.quickssh.app.service

import android.content.Context
import com.quickssh.app.data.PERSISTENT_SESSION_AUTO
import com.quickssh.app.data.PERSISTENT_SESSION_NONE
import com.quickssh.app.data.PERSISTENT_SESSION_SCREEN
import com.quickssh.app.data.PERSISTENT_SESSION_TMUX
import com.quickssh.app.data.SshConfig
import com.quickssh.app.security.KeystoreManager
import com.quickssh.app.utils.TerminalBuffer
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SshClientHelper(
    val config: SshConfig,
    private val appContext: Context,
    initialTerminalSize: TerminalBuffer.Size = TerminalBuffer.Size(
        TerminalBuffer.DEFAULT_COLUMNS,
        TerminalBuffer.DEFAULT_ROWS
    ),
    override val sessionId: String = "ssh-${config.id}-${System.currentTimeMillis()}"
) : TerminalSessionClient {
    companion object {
        private const val KEEPALIVE_INTERVAL_SECONDS = 20
        private const val SHELL_STARTUP_COMMAND_DELAY_MS = 600L
        private const val PERSISTENT_SESSION_ATTACH_DELAY_MS = 400L
        private const val PERSISTENT_SESSION_DETECT_TIMEOUT_SECONDS = 5L
        private const val WORK_DIRECTORY_COMMAND_DELAY_MS = 250L
        private const val POST_CONNECT_COMMAND_DELAY_MS = 250L
        private const val DIRECT_COMMAND_SETTLE_DELAY_MS = 120L
        private const val DIRECT_COMMAND_NUDGE_DELAY_MS = 80L
        private const val REMOTE_OUTPUT_QUIET_WINDOW_MS = 180L
        private const val REMOTE_OUTPUT_QUIET_MAX_WAIT_MS = 900L
        private const val HISTORY_BUFFER_MAX_LINES = 100_000
        private const val RECENT_OUTPUT_REPLAY_MAX_CHUNKS = 32768
        private const val RECENT_OUTPUT_REPLAY_MAX_CHARS = 16 * 1024 * 1024
        private const val CODEX_PREVIEW_EXEC_TIMEOUT_SECONDS = 45L

        init {
            setupBouncyCastle()
        }

        private fun setupBouncyCastle() {
            try {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var sshClient: SSHClient? = null
    private var sshSession: Session? = null
    private var shell: Session.Shell? = null
    private var outputStream: OutputStream? = null
    private val _termuxSessionState = MutableStateFlow<TerminalSession?>(null)
    override val termuxSessionFlow: StateFlow<TerminalSession?> = _termuxSessionState.asStateFlow()
    override val termuxSession: TerminalSession? get() = _termuxSessionState.value
    private var sshBridgeResult: SshSessionBridgeResult? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var userRequestedDisconnect = false

    @Volatile
    private var reconnectAttempt = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var terminalSize = initialTerminalSize.sanitized()

    @Volatile
    private var lastRemoteOutputAtMillis = 0L

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
    override val historyFile = TerminalHistoryFile(appContext, "session-${config.id}-${System.currentTimeMillis()}")

    private val _status = MutableStateFlow(SshSessionStatus.CONNECTING)
    override val status: StateFlow<SshSessionStatus> = _status.asStateFlow()

    private val _statusMessage = MutableStateFlow("Connecting")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    override fun connect() {
        userRequestedDisconnect = false
        updateStatus(SshSessionStatus.CONNECTING, "Connecting to ${config.host}:${config.port}")
        scope.launch {
            connectOnce()
        }
    }

    private suspend fun connectOnce() {
        try {
            updateStatus(
                if (reconnectAttempt == 0) SshSessionStatus.CONNECTING else SshSessionStatus.RECONNECTING,
                if (reconnectAttempt == 0) "Connecting to ${config.host}:${config.port}" else "Reconnecting to ${config.host}:${config.port}"
            )
            emitLog(if (reconnectAttempt == 0) "[QuickSSH] Connecting..." else "\n[QuickSSH] Reconnecting background SSH session...")
            closeCurrentConnection()

            sshClient = SSHClient().apply {
                addHostKeyVerifier(KnownHostsVerifier(appContext))
                connect(config.host, config.port)
                connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
            }

            emitLog("[Keystore] Decrypting saved ${authTypeLabel(config.authType)} credential...")
            val client = sshClient
            if (client == null || !authenticate(client)) {
                userRequestedDisconnect = true
                closeCurrentConnection()
                return
            }

            if (sshClient?.isAuthenticated == true) {
                val terminalTerm = sanitizedTerminalTerm(config.terminalTerm)
                emitLog("[SSHv2] Authenticated. Starting TTY $terminalTerm ${terminalSize.columns}x${terminalSize.rows}...")
                sshSession = sshClient?.startSession()
                sshSession?.allocatePTY(
                    terminalTerm,
                    terminalSize.columns,
                    terminalSize.rows,
                    terminalSize.widthPixels,
                    terminalSize.heightPixels,
                    emptyMap<PTYMode, Int>()
                )
                shell = sshSession?.startShell()
                outputStream = shell?.outputStream
                isConnected = true
                reconnectAttempt = 0
                updateStatus(SshSessionStatus.CONNECTED, "Connected to ${config.host}:${config.port}")

                val activeShell = shell
                if (activeShell != null) {
                    val termuxManager = TermuxSessionManager(appContext, scope)
                    val bridge = termuxManager.createSshSession(
                        sshInputStream = activeShell.inputStream,
                        sshOutputStream = activeShell.outputStream,
                        initialCols = terminalSize.columns,
                        initialRows = terminalSize.rows,
                        terminalTerm = config.terminalTerm,
                        onDataReceived = {
                            lastRemoteOutputAtMillis = System.currentTimeMillis()
                        },
                        onSessionFinished = {
                            if (!userRequestedDisconnect) {
                                closeCurrentConnection()
                            }
                        }
                    )
                    sshBridgeResult = bridge
                    _termuxSessionState.value = bridge?.session

                    if (bridge == null) {
                        launchReaderRoutine(shell?.inputStream)
                    }
                } else {
                    launchReaderRoutine(shell?.inputStream)
                }
                delay(SHELL_STARTUP_COMMAND_DELAY_MS)

                // Persistent session: attach to tmux/screen if configured (Linux/macOS only)
                val persistentAttached = attachPersistentSession(config)

                if (!config.workDirectory.isNullOrBlank() && !persistentAttached) {
                    // When persistent session is attached, tmux/screen restores the previous
                    // working directory automatically, so skip the auto-cd.
                    emitLog("\n[QuickSSH] Auto cd: ${config.workDirectory}\n")
                    waitForRemoteOutputQuiet()
                    writeCommandDirect(autoCdCommand(config.workDirectory))
                    delay(WORK_DIRECTORY_COMMAND_DELAY_MS)
                }

                val postConnectCommands = postConnectCommands(config.postConnectCommand)
                if (postConnectCommands.isNotEmpty() && !persistentAttached) {
                    // Skip post-connect commands when attaching to an existing persistent
                    // session, as the session state is already established.
                    emitLog("[QuickSSH] Running ${postConnectCommands.size} post-connect command(s)...\n")
                    postConnectCommands.forEachIndexed { index, command ->
                        waitForRemoteOutputQuiet()
                        writeCommandDirect(command)
                        if (index < postConnectCommands.lastIndex) {
                            delay(POST_CONNECT_COMMAND_DELAY_MS)
                        }
                    }
                } else if (!persistentAttached) {
                    emitLog("[QuickSSH] No post-connect command configured.")
                }
            } else {
                updateStatus(SshSessionStatus.FAILED, "Authentication failed")
                emitLog("[QuickSSH Error] Authentication failed: username or credential mismatch.")
                userRequestedDisconnect = true
                closeCurrentConnection()
            }
        } catch (e: net.schmizz.sshj.transport.TransportException) {
            updateStatus(SshSessionStatus.FAILED, "SSH transport failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            emitLog("[QuickSSH Error] SSH transport failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            userRequestedDisconnect = true
            closeCurrentConnection()
        } catch (e: UserAuthException) {
            updateStatus(SshSessionStatus.FAILED, "Authentication failed")
            emitLog("[QuickSSH Error] SSH authentication failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            userRequestedDisconnect = true
            closeCurrentConnection()
        } catch (e: Exception) {
            if (!userRequestedDisconnect) scheduleReconnect(e)
        }
    }

    private suspend fun authenticate(client: SSHClient): Boolean {
        emitLog("[SSHv2] Authenticating as ${config.username} with ${authTypeLabel(config.authType)}...")
        if (config.authType == AUTH_TYPE_PRIVATE_KEY) {
            val encryptedPrivateKey = config.encryptedPrivateKey
            if (encryptedPrivateKey.isNullOrBlank()) {
                updateStatus(SshSessionStatus.FAILED, "No saved private key")
                emitLog("[QuickSSH Error] No saved private key. Edit this server and save the key again.")
                return false
            }
            val rawPrivateKey = KeystoreManager.decrypt(encryptedPrivateKey)
            if (rawPrivateKey.isBlank()) {
                updateStatus(SshSessionStatus.FAILED, "Private key decrypt result is empty")
                emitLog("[QuickSSH Error] Private key decrypt result is empty. Re-enter private key and save.")
                return false
            }
            val keyProvider = client.loadKeys(rawPrivateKey, null, null)
            client.authPublickey(config.username, keyProvider)
            return true
        }

        val encryptedPassword = config.encryptedPassword
        if (encryptedPassword.isNullOrBlank()) {
            updateStatus(SshSessionStatus.FAILED, "No saved password")
            emitLog("[QuickSSH Error] No saved password. Edit this server and save password again.")
            return false
        }
        val rawPassword = KeystoreManager.decrypt(encryptedPassword)
        if (rawPassword.isEmpty()) {
            updateStatus(SshSessionStatus.FAILED, "Password decrypt result is empty")
            emitLog("[QuickSSH Error] Password decrypt result is empty. Re-enter password and save.")
            return false
        }
        client.authPassword(config.username, rawPassword)
        return true
    }

    override fun resizeTerminal(size: TerminalBuffer.Size) {
        val next = size.sanitized()
        if (next == terminalSize) return
        historyBuffer.resize(next)
        terminalSize = next
        _termuxSessionState.value?.updateSize(next.columns, next.rows)
        scope.launch(Dispatchers.IO) {
            try {
                shell?.changeWindowDimensions(
                    next.columns,
                    next.rows,
                    next.widthPixels,
                    next.heightPixels
                )
            } catch (e: Exception) {
                emitLog("\n[QuickSSH Info] Failed to sync terminal size: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Attempt to attach a persistent terminal session (tmux or screen) based on config.
     * Returns true if a persistent session was attached (either existing or new),
     * which means auto-cd and post-connect commands should be skipped.
     *
     * For Windows remote hosts (detected via workDirectory path), always returns false
     * since tmux/screen are not available on Windows.
     */
    private suspend fun attachPersistentSession(config: SshConfig): Boolean {
        val mode = config.persistentSessionMode.trim().lowercase()
        if (mode == PERSISTENT_SESSION_NONE) return false

        // Skip for Windows remote hosts — tmux/screen not available
        if (!config.workDirectory.isNullOrBlank() && isWindowsPath(config.workDirectory)) {
            emitLog("[QuickSSH] Persistent session skipped: Windows remote detected.")
            return false
        }

        val sessionName = persistentSessionName(config.id)

        val resolvedMode = when (mode) {
            PERSISTENT_SESSION_TMUX -> {
                if (detectRemoteCommand("tmux")) PERSISTENT_SESSION_TMUX else {
                    emitLog("[QuickSSH] tmux not found on remote. Falling back to normal shell.")
                    return false
                }
            }
            PERSISTENT_SESSION_SCREEN -> {
                if (detectRemoteCommand("screen")) PERSISTENT_SESSION_SCREEN else {
                    emitLog("[QuickSSH] screen not found on remote. Falling back to normal shell.")
                    return false
                }
            }
            PERSISTENT_SESSION_AUTO -> {
                when {
                    detectRemoteCommand("tmux") -> PERSISTENT_SESSION_TMUX
                    detectRemoteCommand("screen") -> PERSISTENT_SESSION_SCREEN
                    else -> {
                        emitLog("[QuickSSH] Neither tmux nor screen found. Using normal shell.")
                        return false
                    }
                }
            }
            else -> return false
        }

        val command = when (resolvedMode) {
            PERSISTENT_SESSION_TMUX -> persistentTmuxCommand(sessionName, config.workDirectory)
            PERSISTENT_SESSION_SCREEN -> persistentScreenCommand(sessionName, config.workDirectory)
            else -> return false
        }

        emitLog("\n[QuickSSH] Attaching persistent $resolvedMode session: $sessionName")
        waitForRemoteOutputQuiet()
        writeCommandDirect(command)
        delay(PERSISTENT_SESSION_ATTACH_DELAY_MS)
        return true
    }

    /**
     * Detect whether a command is available on the remote host by running `which` or `command -v`.
     */
    private fun detectRemoteCommand(commandName: String): Boolean {
        val client = sshClient ?: return false
        return try {
            client.startSession().use { session ->
                val command = session.exec("command -v $commandName >/dev/null 2>&1 && echo YES || echo NO")
                val output = command.inputStream.bufferedReader().readText().trim()
                runCatching {
                    command.join(PERSISTENT_SESSION_DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    command.close()
                }
                output.contains("YES")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun launchReaderRoutine(inputStream: InputStream?) {
        if (inputStream == null) return
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            val outputDecoder = TerminalOutputDecoder()
            try {
                while (isConnected && !userRequestedDisconnect) {
                    val readSize = inputStream.read(buffer)
                    if (readSize == -1) throw IllegalStateException("remote stream closed")
                    val decoded = outputDecoder.decode(buffer, readSize)
                    if (decoded.isNotEmpty()) {
                        lastRemoteOutputAtMillis = System.currentTimeMillis()
                        emitLog(decoded)
                    }
                }
            } catch (e: Exception) {
                if (userRequestedDisconnect) {
                    emitLog("\n[QuickSSH] Session stream closed.")
                } else {
                    emitLog("\n[QuickSSH Info] Network interrupted, keeping session and reconnecting: ${e.localizedMessage}")
                    scheduleReconnect(e)
                }
            }
        }
    }

    override fun sendLine(line: String) {
        _termuxSessionState.value?.write(terminalLinePayload(line)) ?: sendCommand(terminalLinePayload(line))
    }

    override fun runCodexResumeShortcut(command: String, workDirectory: String?) {
        val resumeCommand = command.trim().ifBlank { "codex resume --last --no-alt-screen" }
        val previewCommand = codexTranscriptPreviewCommand(workDirectory)
        if (previewCommand == null) {
            sendLine(resumeCommand)
            return
        }
        if (!isConnected || sshClient == null) {
            sendLine(resumeCommand)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                emitLog("\n[QuickSSH] Loading Codex recent transcript preview...\n")
                val result = execRemoteCommand(previewCommand)
                val output = listOf(result.stdout, result.stderr)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .normalizeRemoteCommandText()
                if (output.isNotBlank()) {
                    emitLog(output.ensureTerminalBlock())
                }
                if (result.exitStatus != 0) {
                    emitLog("\n[QuickSSH Info] Codex transcript preview exited with status ${result.exitStatus ?: "unknown"}.\n")
                }
            } catch (e: Exception) {
                emitLog("\n[QuickSSH Info] Codex transcript preview unavailable: ${e.localizedMessage ?: e.javaClass.simpleName}\n")
            } finally {
                sendCommand(terminalLinePayload(resumeCommand))
            }
        }
    }

    private suspend fun writeCommandDirect(line: String) {
        outputStream?.write(directCommandPayload(line).toByteArray(Charsets.UTF_8))
        outputStream?.flush()
        delay(DIRECT_COMMAND_NUDGE_DELAY_MS)
        // Some PTY streams do not process the final submitted line until another input packet arrives.
        outputStream?.write(directCommandNudgePayload())
        outputStream?.flush()
        delay(DIRECT_COMMAND_SETTLE_DELAY_MS)
    }

    private suspend fun waitForRemoteOutputQuiet() {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val lastOutputAt = lastRemoteOutputAtMillis
            if (lastOutputAt == 0L || now - lastOutputAt >= REMOTE_OUTPUT_QUIET_WINDOW_MS) return
            if (now - startedAt >= REMOTE_OUTPUT_QUIET_MAX_WAIT_MS) return
            delay(50L)
        }
    }

    private fun sendCommand(command: String) {
        if (!isConnected || outputStream == null) {
            scope.launch { emitLog("\n[QuickSSH Info] Not connected now. Session is retained; send again after reconnect.") }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val isControlChar = command.startsWith("\u001B") ||
                    command == "\r" || command == "\n" || command == "\t" || command == " " ||
                    command == "\u007F" || command == "\u0003" || command.length == 1 && command[0] < ' '
                val commandLine = command.trimEnd('\r', '\n')

                val formatted = when {
                    commandLine == "clear" -> {
                        emitLog("\u001b[H\u001b[2J")
                        "clear\r"
                    }
                    isControlChar -> if (command == "\n") "\r" else command
                    !command.endsWith("\n") && !command.endsWith("\r") -> command + "\n"
                    else -> command
                }

                outputStream?.write(formatted.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) {
                emitLog("\n[QuickSSH Error] Failed to send command, reconnecting: ${e.localizedMessage}")
                scheduleReconnect(e)
            }
        }
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
                emitLog("\n[QuickSSH Error] Failed to send terminal input, reconnecting: ${e.localizedMessage}")
                scheduleReconnect(e)
            }
        }
    }
    override fun reconnectNow() {
        if (userRequestedDisconnect || isConnected) return
        scope.launch(Dispatchers.IO) {
            emitLog("\n[QuickSSH] Manual reconnect requested.")
            reconnectAttempt = 0
            connectOnce()
        }
    }
    override fun reconnectWhenNetworkAvailable() {
        if (!shouldReconnectOnNetworkAvailable(userRequestedDisconnect, isConnected, _status.value)) return
        scope.launch(Dispatchers.IO) {
            emitLog("\n[QuickSSH] Network is available. Reconnect will resume now.")
            connectOnce()
        }
    }

    override fun disconnect() {
        userRequestedDisconnect = true
        closeCurrentConnection()
        updateStatus(SshSessionStatus.DISCONNECTED, "Disconnected")
        scope.launch { emitLog("[QuickSSH] SSH session disconnected.") }
    }

    private suspend fun scheduleReconnect(cause: Exception) {
        closeCurrentConnection()
        if (userRequestedDisconnect) return
        reconnectAttempt++
        updateStatus(SshSessionStatus.RECONNECTING, "Reconnecting after ${cause.localizedMessage ?: cause.javaClass.simpleName}")
        val delayMillis = reconnectDelayMillis(reconnectAttempt)
        emitLog("\n[QuickSSH] Session retained. Reconnect attempt $reconnectAttempt in ${delayMillis / 1000}s.")
        delay(delayMillis)
        if (shouldReconnectAfterDelay(userRequestedDisconnect, isConnected)) connectOnce()
    }

    private fun closeCurrentConnection() {
        isConnected = false
        sshBridgeResult?.closeAction?.invoke()
        sshBridgeResult = null
        _termuxSessionState.value = null
        try {
            shell?.close()
            sshSession?.close()
            sshClient?.disconnect()
        } catch (_: Exception) {}
        shell = null
        sshSession = null
        sshClient = null
        outputStream = null
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

    override fun recentOutputSnapshot(): List<String> {
        return recentOutputReplay.snapshot()
    }

    private fun execRemoteCommand(commandLine: String): RemoteCommandResult {
        val client = sshClient ?: throw IllegalStateException("SSH client is not connected")
        client.startSession().use { session ->
            val command = session.exec(commandLine)
            val stdoutFuture = CompletableFuture.supplyAsync { readDecodedStream(command.inputStream) }
            val stderrFuture = CompletableFuture.supplyAsync { readDecodedStream(command.errorStream) }
            try {
                command.join(CODEX_PREVIEW_EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (command.exitStatus == null) {
                    runCatching { command.close() }
                    return RemoteCommandResult(
                        exitStatus = null,
                        stdout = readRemoteCommandFuture(stdoutFuture),
                        stderr = "Timed out after ${CODEX_PREVIEW_EXEC_TIMEOUT_SECONDS}s"
                    )
                }
                val stdout = readRemoteCommandFuture(stdoutFuture)
                val stderr = readRemoteCommandFuture(stderrFuture)
                return RemoteCommandResult(command.exitStatus, stdout, stderr)
            } finally {
                runCatching { command.close() }
            }
        }
    }

    private fun readDecodedStream(inputStream: InputStream): String {
        val buffer = ByteArray(4096)
        val decoder = TerminalOutputDecoder()
        val text = StringBuilder()
        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            text.append(decoder.decode(buffer, read))
        }
        return text.toString()
    }

    private fun readRemoteCommandFuture(future: CompletableFuture<String>): String {
        return runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("")
    }

    private data class RemoteCommandResult(
        val exitStatus: Int?,
        val stdout: String,
        val stderr: String
    )
}

internal class BoundedTextReplay(
    private val maxChunks: Int,
    private val maxChars: Int
) {
    private val chunks = ArrayDeque<String>()
    private var totalChars = 0

    @Synchronized
    fun append(text: String) {
        if (text.isEmpty()) return
        chunks.addLast(text)
        totalChars += text.length
        trim()
    }

    @Synchronized
    fun snapshot(): List<String> {
        return chunks.toList()
    }

    private fun trim() {
        val chunkLimit = maxChunks.coerceAtLeast(1)
        val charLimit = maxChars.coerceAtLeast(1)
        while (chunks.size > chunkLimit || totalChars > charLimit) {
            val removed = chunks.removeFirstOrNull() ?: break
            totalChars -= removed.length
        }
    }
}

internal fun terminalLinePayload(line: String): String = line + "\r"

internal fun directCommandText(line: String): String = line.trimEnd('\r', '\n')

internal fun directCommandEnterPayload(): String = "\r\n"

internal fun directCommandPayload(line: String): String = directCommandText(line) + directCommandEnterPayload()

internal fun directCommandNudgePayload(): ByteArray = byteArrayOf(0x20, 0x08)

internal fun postConnectCommands(commands: String?): List<String> {
    if (commands.isNullOrBlank()) return emptyList()
    return commands
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
}

internal fun shellSingleQuoted(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

internal fun shellPathLiteral(value: String): String {
    val trimmed = value.trim()
    if (trimmed == "~") return "~"
    if (trimmed.startsWith("~/")) {
        val rest = trimmed.removePrefix("~/")
        return if (rest.isBlank()) "~" else "~/${shellSingleQuoted(rest)}"
    }
    if (trimmed.startsWith("~")) {
        val slashIndex = trimmed.indexOf('/')
        if (slashIndex < 0) return trimmed
        val prefix = trimmed.substring(0, slashIndex)
        val rest = trimmed.substring(slashIndex + 1)
        return if (rest.isBlank()) prefix else "$prefix/${shellSingleQuoted(rest)}"
    }
    return shellSingleQuoted(trimmed)
}

internal fun autoCdCommand(workDirectory: String): String {
    val trimmed = workDirectory.trim()
    return if (isWindowsPath(trimmed)) {
        windowsAutoCdCommand(trimmed)
    } else {
        "cd ${shellPathLiteral(trimmed)}"
    }
}

internal fun isWindowsPath(value: String): Boolean {
    val trimmed = value.trim()
    return Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed) || trimmed.startsWith("\\\\")
}

internal fun windowsAutoCdCommand(value: String): String {
    val trimmed = value.trim()
    return "pushd ${cmdPathLiteral(trimmed)}"
}

internal fun cmdPathLiteral(value: String): String {
    return "\"${value.trim().replace("\"", "")}\""
}

internal fun codexTranscriptPreviewCommand(workDirectory: String?): String? {
    val directory = workDirectory?.trim().orEmpty()
    if (directory.isBlank() || !isWindowsPath(directory)) return null
    return windowsCodexTranscriptPreviewCommand(directory)
}

internal fun windowsCodexTranscriptPreviewCommand(workDirectory: String): String {
    val cwd = powerShellSingleQuoted(
        workDirectory.trim().replace('\\', '/').trimEnd('/').lowercase()
    )
    val script = listOf(
        "${'$'}ErrorActionPreference='Continue'",
        "${'$'}nl=[Environment]::NewLine",
        "${'$'}d=$cwd",
        "${'$'}base=${'$'}env:USERPROFILE+'\\.codex\\sessions'",
        "${'$'}root=${'$'}base+'\\'+(Get-Date -f yyyy)+'\\'+(Get-Date -f MM)",
        "if(!(Test-Path -LiteralPath ${'$'}root)){${'$'}root=${'$'}base}",
        "${'$'}fs=@(gci -LiteralPath ${'$'}root -Recurse -File -Filter *.jsonl -ErrorAction SilentlyContinue|sort LastWriteTime -Descending|select -First 240)",
        "if(${'$'}fs.Count -eq 0 -and ${'$'}root -ne ${'$'}base){${'$'}root=${'$'}base;${'$'}fs=@(gci -LiteralPath ${'$'}root -Recurse -File -Filter *.jsonl -ErrorAction SilentlyContinue|sort LastWriteTime -Descending|select -First 240)}",
        "${'$'}q=([char]34+'cwd'+[char]34+':'+[char]34)+${'$'}d",
        "${'$'}f=${'$'}null",
        "foreach(${'$'}x in ${'$'}fs){try{${'$'}h=(gc -LiteralPath ${'$'}x.FullName -First 1 -ErrorAction Stop).ToLowerInvariant() -replace '\\\\\\\\','/' -replace '\\\\','/';if(${'$'}h.Contains(${'$'}q)){${'$'}f=${'$'}x;break}}catch{}}",
        "if(!${'$'}f){${'$'}f=${'$'}fs|select -First 1}",
        "function ju(${'$'}s){if(${'$'}null -eq ${'$'}s){return ''};try{return [regex]::Unescape([string]${'$'}s)}catch{return [string]${'$'}s}}",
        "function qclip(${'$'}t){${'$'}t=([string]${'$'}t).Trim();if(${'$'}t.Length -le 6000){return ${'$'}t};return ${'$'}t.Substring(0,3000)+${'$'}nl+'...[message middle truncated for mobile scrollback]...'+${'$'}nl+${'$'}t.Substring(${'$'}t.Length-2600)}",
        "if(${'$'}f){'';'[QuickSSH] Codex recent transcript preview: '+${'$'}f.Name;'------------------------------------------------------------';${'$'}items=New-Object 'System.Collections.Generic.List[string]';${'$'}used=0;${'$'}max=1200000;${'$'}limit=1200;${'$'}trimmed=${'$'}false;${'$'}pat=@('\"payload\":{\"type\":\"user_message\"','\"payload\":{\"type\":\"agent_message\"','\"payload\":{\"type\":\"message\"');${'$'}hits=@(Select-String -LiteralPath ${'$'}f.FullName -SimpleMatch -Pattern ${'$'}pat -ErrorAction SilentlyContinue|select -Last 1200);foreach(${'$'}hit in ${'$'}hits){${'$'}line=${'$'}hit.Line;${'$'}s=${'$'}null;${'$'}t=${'$'}null;if(${'$'}line.Contains('\"payload\":{\"type\":\"user_message\"')){${'$'}s='You';${'$'}m=[regex]::Match(${'$'}line,'\"message\":\"((?:\\\\.|[^\"\\\\])*)\"');if(${'$'}m.Success){${'$'}t=ju ${'$'}m.Groups[1].Value}}elseif(${'$'}line.Contains('\"payload\":{\"type\":\"agent_message\"')){${'$'}s='Codex';${'$'}m=[regex]::Match(${'$'}line,'\"message\":\"((?:\\\\.|[^\"\\\\])*)\"');if(${'$'}m.Success){${'$'}t=ju ${'$'}m.Groups[1].Value}}elseif(${'$'}line.Contains('\"payload\":{\"type\":\"message\"') -and (${'$'}line.Contains('\"role\":\"user\"') -or ${'$'}line.Contains('\"role\":\"assistant\"'))){${'$'}s=if(${'$'}line.Contains('\"role\":\"user\"')){'You'}else{'Codex'};${'$'}parts=@();foreach(${'$'}m in [regex]::Matches(${'$'}line,'\"text\":\"((?:\\\\.|[^\"\\\\])*)\"')){${'$'}parts+=ju ${'$'}m.Groups[1].Value};${'$'}t=${'$'}parts -join ${'$'}nl};if(!${'$'}s -or [string]::IsNullOrWhiteSpace(${'$'}t)){continue};${'$'}t=qclip ${'$'}t;${'$'}block='['+${'$'}s+']'+${'$'}nl+${'$'}t+${'$'}nl+'------------------------------------------------------------';[void]${'$'}items.Add(${'$'}block);${'$'}used+=${'$'}block.Length;while(${'$'}items.Count -gt ${'$'}limit -or ${'$'}used -gt ${'$'}max){${'$'}used-=${'$'}items[0].Length;${'$'}items.RemoveAt(0);${'$'}trimmed=${'$'}true}};if(${'$'}items.Count -eq 0){'[QuickSSH] No readable user/assistant transcript messages found in recent session.'}else{'[QuickSSH] Transcript preview prepared '+${'$'}items.Count+' readable messages for mobile scrollback.';if(${'$'}trimmed){'[QuickSSH] Transcript preview trimmed older messages to keep startup responsive.'};foreach(${'$'}block in ${'$'}items){${'$'}block}};'------------------------------------------------------------';'[QuickSSH] End of transcript preview. Starting Codex resume...'}else{'[QuickSSH] No Codex session transcript found.'}"
    ).joinToString(";")
    return powerShellCommandLine(script)
}

internal fun powerShellCommandLine(script: String): String {
    return "powershell -NoProfile -ExecutionPolicy Bypass -Command \"${script.replace("\"", "`\"")}\""
}

private fun powerShellSingleQuoted(value: String): String {
    return "'" + value.replace("'", "''") + "'"
}

private fun String.normalizeRemoteCommandText(): String {
    return replace("\r\n", "\n").replace('\r', '\n')
}

private fun String.ensureTerminalBlock(): String {
    val prefix = if (startsWith("\n")) "" else "\n"
    val suffix = if (endsWith("\n")) "" else "\n"
    return prefix + this + suffix
}

internal class TerminalOutputDecoder {
    private var pending = ByteArray(0)
    private var legacyDecoder: CharsetDecoder? = null

    fun decode(bytes: ByteArray, length: Int): String {
        if (length <= 0) return ""
        val chunk = bytes.copyOf(length)
        val legacy = legacyDecoder
        if (legacy != null) {
            return decodeLegacy(chunk, legacy)
        }

        val input = pending + chunk
        val scan = utf8DecodablePrefixLength(input)
        if (scan.invalid) {
            val utf8Prefix = if (scan.prefixLength > 0) {
                String(input, 0, scan.prefixLength, Charsets.UTF_8)
            } else {
                ""
            }
            val decoder = Charset.forName("GB18030")
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            legacyDecoder = decoder
            pending = ByteArray(0)
            return utf8Prefix + decodeLegacy(input.copyOfRange(scan.prefixLength, input.size), decoder)
        }

        pending = input.copyOfRange(scan.prefixLength, input.size)
        if (scan.prefixLength == 0) return ""
        return String(input, 0, scan.prefixLength, Charsets.UTF_8)
    }

    private data class Utf8Scan(val prefixLength: Int, val invalid: Boolean)

    private fun utf8DecodablePrefixLength(bytes: ByteArray): Utf8Scan {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 <= 0x7F -> i += 1
                b0 in 0xC2..0xDF -> {
                    if (i + 1 >= bytes.size) return Utf8Scan(i, invalid = false)
                    if (!isUtf8Continuation(bytes[i + 1])) return Utf8Scan(i, invalid = true)
                    i += 2
                }
                b0 == 0xE0 -> {
                    if (i + 2 >= bytes.size) return Utf8Scan(i, invalid = false)
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b1 !in 0xA0..0xBF || !isUtf8Continuation(bytes[i + 2])) return Utf8Scan(i, invalid = true)
                    i += 3
                }
                b0 in 0xE1..0xEC || b0 in 0xEE..0xEF -> {
                    if (i + 2 >= bytes.size) return Utf8Scan(i, invalid = false)
                    if (!isUtf8Continuation(bytes[i + 1]) || !isUtf8Continuation(bytes[i + 2])) return Utf8Scan(i, invalid = true)
                    i += 3
                }
                b0 == 0xED -> {
                    if (i + 2 >= bytes.size) return Utf8Scan(i, invalid = false)
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b1 !in 0x80..0x9F || !isUtf8Continuation(bytes[i + 2])) return Utf8Scan(i, invalid = true)
                    i += 3
                }
                b0 == 0xF0 -> {
                    if (i + 3 >= bytes.size) return Utf8Scan(i, invalid = false)
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b1 !in 0x90..0xBF || !isUtf8Continuation(bytes[i + 2]) || !isUtf8Continuation(bytes[i + 3])) {
                        return Utf8Scan(i, invalid = true)
                    }
                    i += 4
                }
                b0 in 0xF1..0xF3 -> {
                    if (i + 3 >= bytes.size) return Utf8Scan(i, invalid = false)
                    if (!isUtf8Continuation(bytes[i + 1]) || !isUtf8Continuation(bytes[i + 2]) || !isUtf8Continuation(bytes[i + 3])) {
                        return Utf8Scan(i, invalid = true)
                    }
                    i += 4
                }
                b0 == 0xF4 -> {
                    if (i + 3 >= bytes.size) return Utf8Scan(i, invalid = false)
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b1 !in 0x80..0x8F || !isUtf8Continuation(bytes[i + 2]) || !isUtf8Continuation(bytes[i + 3])) {
                        return Utf8Scan(i, invalid = true)
                    }
                    i += 4
                }
                else -> return Utf8Scan(i, invalid = true)
            }
        }
        return Utf8Scan(bytes.size, invalid = false)
    }

    private fun isUtf8Continuation(byte: Byte): Boolean {
        return (byte.toInt() and 0xC0) == 0x80
    }

    private fun decodeLegacy(bytes: ByteArray, decoder: CharsetDecoder): String {
        if (bytes.isEmpty()) return ""
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate((input.remaining() * 2).coerceAtLeast(32))
        val decoded = StringBuilder()
        while (true) {
            val result = decoder.decode(input, output, false)
            output.flip()
            decoded.append(output)
            output.clear()
            when {
                result.isOverflow -> continue
                result.isUnderflow -> break
                result.isError -> result.throwException()
            }
        }
        pending = input.remainingBytes()
        return decoded.toString()
    }

    private fun ByteBuffer.remainingBytes(): ByteArray {
        val remaining = ByteArray(remaining())
        get(remaining)
        return remaining
    }
}

internal fun shouldReconnectOnNetworkAvailable(userRequestedDisconnect: Boolean, isConnected: Boolean, status: SshSessionStatus): Boolean {
    return !userRequestedDisconnect && !isConnected && status == SshSessionStatus.RECONNECTING
}

internal fun shouldReconnectAfterDelay(userRequestedDisconnect: Boolean, isConnected: Boolean): Boolean {
    return !userRequestedDisconnect && !isConnected
}

internal fun reconnectDelayMillis(attempt: Int): Long {
    val safeAttempt = attempt.coerceAtLeast(1)
    return (safeAttempt * 2_000L).coerceAtMost(30_000L)
}

internal fun sanitizedTerminalTerm(term: String?): String {
    val trimmed = term?.trim().orEmpty()
    if (trimmed.isBlank()) return "xterm-256color"
    return trimmed.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32).ifBlank { "xterm-256color" }
}

/**
 * Generate a stable, filesystem-safe session name for persistent terminal multiplexer sessions.
 * Uses workspace config id to ensure each workspace gets its own session.
 */
internal fun persistentSessionName(configId: Long): String {
    return "quickssh-$configId"
}

/**
 * Build the tmux command that creates a new session or attaches to an existing one.
 * `-A` means: attach to session if it exists, otherwise create a new one.
 * If workDirectory is provided and is a Linux path, it's passed as the default-path for new sessions.
 */
internal fun persistentTmuxCommand(sessionName: String, workDirectory: String?): String {
    val safeName = shellSingleQuoted(sessionName)
    val cdPart = if (!workDirectory.isNullOrBlank() && !isWindowsPath(workDirectory)) {
        " -c ${shellPathLiteral(workDirectory)}"
    } else {
        ""
    }
    // tmux new-session -A -s <name> [-c <dir>]
    // -A: attach if session exists, create if not
    // -s: session name
    // -c: starting directory (only for new session creation)
    return "tmux new-session -A -s $safeName$cdPart"
}

/**
 * Build the screen command that creates a new session or reattaches to an existing one.
 * `-dRR` means: reattach if possible, otherwise create; detach other clients if needed.
 */
internal fun persistentScreenCommand(sessionName: String, workDirectory: String?): String {
    val safeName = shellSingleQuoted(sessionName)
    // For screen, we need to cd first if there's a work directory, then start/attach screen.
    // screen -dRR <name>: detach and reattach, creating if needed
    return if (!workDirectory.isNullOrBlank() && !isWindowsPath(workDirectory)) {
        "cd ${shellPathLiteral(workDirectory)} && screen -dRR $safeName"
    } else {
        "screen -dRR $safeName"
    }
}
