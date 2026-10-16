package com.quickssh.app.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.QuickSshPty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.time.Duration
import java.util.concurrent.Executors

enum class EmbeddedSshStatus {
    STOPPED,
    STARTING,
    RUNNING,
    PORT_OCCUPIED,
    ERROR
}

data class EmbeddedSshState(
    val status: EmbeddedSshStatus = EmbeddedSshStatus.STOPPED,
    val port: Int = EmbeddedSshServer.DEFAULT_PORT,
    /** Host key fingerprint (SHA256:...) clients see on first connect. */
    val hostKeyFingerprint: String? = null,
    val errorMessage: String? = null
)

/**
 * Built-in SSH server (Apache MINA SSHD) that lets other machines log in to QuickSSH's own local
 * environment - the BusyBox / system shell with the `quickssh` CLI, opencode launcher and agent
 * skill - without Termux. Interactive shells get a real PTY through Termux's JNI; exec requests
 * (`ssh phone quickssh tree`) run with plain pipes so stdin, stdout, stderr and the exit code pass
 * through untouched. Port forwarding is limited to `-L` towards loopback, so a remote agent can
 * reach the Agent Bridge and its `/mcp` endpoint but cannot use the phone as a network proxy.
 *
 * Like [AgentBridgeService] it lives in the app process and stops with the activity. Any user name
 * is accepted; authentication is a key from `~/.ssh/authorized_keys` or the generated password.
 */
object EmbeddedSshServer {
    const val DEFAULT_PORT = 8025
    const val PREFS_KEY_ENABLED = "embedded_ssh_enabled"
    const val PREFS_KEY_PORT = "embedded_ssh_port"
    const val PREFS_KEY_PASSWORD = "embedded_ssh_password"
    const val PREFS_KEY_PASSWORD_AUTH = "embedded_ssh_password_auth"

    /** authorized_keys relative to the app home that SSH sessions start in. */
    const val AUTHORIZED_KEYS_HOME_PATH = ".ssh/authorized_keys"

    /** Suggested login name for display; the server accepts any user name. */
    const val LOGIN_USER = "quickssh"

    private const val HOST_KEY_FILE = "ssh_host_ec_key"
    private const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    // One thread so start/stop requests from activity recreation apply in call order.
    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())
    private var server: SshServer? = null

    private val _state = MutableStateFlow(EmbeddedSshState())
    val state: StateFlow<EmbeddedSshState> = _state.asStateFlow()

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences(KnownHostsVerifier.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = settings(context).getBoolean(PREFS_KEY_ENABLED, false)

    fun configuredPort(context: Context): Int = settings(context).getInt(PREFS_KEY_PORT, DEFAULT_PORT)

    fun isPasswordAuthEnabled(context: Context): Boolean = settings(context).getBoolean(PREFS_KEY_PASSWORD_AUTH, true)

    /** Login password; generated on first use. */
    @Synchronized
    fun password(context: Context): String =
        settings(context).getString(PREFS_KEY_PASSWORD, null)?.takeIf { it.length >= 12 }
            ?: regeneratePassword(context)

    @Synchronized
    fun regeneratePassword(context: Context): String {
        val password = newPassword()
        settings(context).edit().putString(PREFS_KEY_PASSWORD, password).commit()
        return password
    }

    internal fun newPassword(length: Int = 20): String {
        val random = SecureRandom()
        return String(CharArray(length) { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] })
    }

    fun authorizedKeysFile(context: Context): File = File(context.filesDir, AUTHORIZED_KEYS_HOME_PATH)

    fun readAuthorizedKeys(context: Context): String =
        authorizedKeysFile(context).takeIf { it.isFile }?.readText() ?: ""

    /** Validates and stores authorized_keys; returns the number of keys. Throws [IllegalArgumentException] naming a bad line. */
    fun saveAuthorizedKeys(context: Context, text: String): Int {
        val keys = parseAuthorizedKeys(text)
        val file = authorizedKeysFile(context)
        file.parentFile?.mkdirs()
        file.writeText(text.trim().let { if (it.isEmpty()) "" else it + "\n" })
        file.setReadable(false, false)
        file.setReadable(true, true)
        return keys.size
    }

    /** Parses OpenSSH authorized_keys text, skipping blanks and comments; every other line must be a usable key. */
    internal fun parseAuthorizedKeys(text: String): List<PublicKey> {
        ensureSecurityProviders()
        return text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            try {
                AuthorizedKeyEntry.parseAuthorizedKeyEntry(line)
                    ?.resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING)
                    ?: throw IllegalArgumentException("empty entry")
            } catch (e: Exception) {
                throw IllegalArgumentException("authorized_keys line ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    internal fun isLoopbackHost(host: String?): Boolean {
        val value = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
        return value == "localhost" || value == "::1" || value == "0:0:0:0:0:0:0:1" ||
            Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""").matches(value)
    }

    /** Non-loopback IPv4 addresses for the "ssh -p <port> user@<ip>" hint. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())

    fun start(context: Context, port: Int) {
        val appContext = context.applicationContext
        scope.launch { startNow(appContext, port) }
    }

    fun stop() {
        scope.launch { stopNow() }
    }

    private fun startNow(context: Context, port: Int) {
        val current = _state.value
        if (server?.isStarted == true && current.status == EmbeddedSshStatus.RUNNING && current.port == port) return
        stopNow()
        if (port !in 1024..65535) {
            _state.value = EmbeddedSshState(EmbeddedSshStatus.ERROR, port, errorMessage = "端口号无效（范围须在 1024-65535）")
            return
        }
        _state.value = EmbeddedSshState(EmbeddedSshStatus.STARTING, port)
        try {
            ensureSecurityProviders()
            OsUtils.setAndroid(true)
            PathUtils.setUserHomeFolderResolver { context.filesDir.toPath() }

            val shell = resolveShell(context)
            val hostKeys = SimpleGeneratorHostKeyProvider(File(context.noBackupFilesDir, HOST_KEY_FILE).toPath()).apply {
                algorithm = KeyUtils.EC_ALGORITHM
                keySize = 256
            }
            val sshd = SshServer.setUpDefaultServer().apply {
                this.port = port
                keyPairProvider = hostKeys
                passwordAuthenticator = PasswordAuthenticator { _, presented, _ ->
                    isPasswordAuthEnabled(context) && MessageDigest.isEqual(
                        presented.orEmpty().toByteArray(StandardCharsets.UTF_8),
                        password(context).toByteArray(StandardCharsets.UTF_8)
                    )
                }
                publickeyAuthenticator = PublickeyAuthenticator { _, key, _ ->
                    val allowed = runCatching { parseAuthorizedKeys(readAuthorizedKeys(context)) }.getOrDefault(emptyList())
                    allowed.any { KeyUtils.compareKeys(it, key) }
                }
                shellFactory = ShellFactory { PtyShellCommand(shell) }
                commandFactory = CommandFactory { _, command -> ExecCommand(shell, command) }
                forwardingFilter = LoopbackOnlyForwardingFilter
            }
            // Interactive shells may sit idle for a long time; the client's keepalives cover dead peers.
            CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Duration.ZERO)
            sshd.start()
            server = sshd
            val fingerprint = runCatching {
                hostKeys.loadKeys(null).firstOrNull()?.public?.let { KeyUtils.getFingerPrint(it) }
            }.getOrNull()
            _state.value = EmbeddedSshState(EmbeddedSshStatus.RUNNING, port, fingerprint)
        } catch (e: BindException) {
            stopNow()
            _state.value = EmbeddedSshState(
                EmbeddedSshStatus.PORT_OCCUPIED, port,
                errorMessage = "SSH 端口 $port 已被占用，请更换端口"
            )
        } catch (e: Throwable) {
            stopNow()
            _state.value = EmbeddedSshState(
                EmbeddedSshStatus.ERROR, port,
                errorMessage = "启动 SSH 服务失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun stopNow() {
        server?.let { runCatching { it.stop(true) } }
        server = null
        _state.value = _state.value.copy(status = EmbeddedSshStatus.STOPPED, errorMessage = null)
    }

    /** Same provider setup as [SshClientHelper]: the platform "BC" lacks the algorithms SSH needs. */
    private fun ensureSecurityProviders() {
        if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
            runCatching {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    internal class ShellSpec(val path: String, val home: String, val env: Map<String, String>)

    /** The built-in BusyBox environment when available, else the Android system shell; both get the agent toolkit on PATH. */
    private fun resolveShell(context: Context): ShellSpec {
        val builtin = LocalEnvironmentDetector.isBuiltinBusyboxAvailable(context)
        if (!builtin) LocalEnvironmentDetector.setupBuiltinEnvironment(context)
        val detected = LocalEnvironmentDetector.detect(
            context,
            preferredShellPath = if (builtin) LocalEnvironmentDetector.BUILTIN_SHELL else LocalEnvironmentDetector.SYSTEM_SH
        )
        return ShellSpec(detected.shellPath, detected.homeDir, detected.environment)
    }

    private fun sessionEnvironment(shell: ShellSpec, environment: Environment): Map<String, String> =
        shell.env.toMutableMap().apply {
            environment.env[Environment.ENV_TERM]?.takeIf { it.isNotBlank() }?.let { put("TERM", it) }
            environment.env[Environment.ENV_USER]?.takeIf { it.isNotBlank() }?.let {
                put("USER", it)
                put("LOGNAME", it)
            }
            put("SHELL", shell.path)
            // HOME is under /data/user/0, a symlink to /data/data; without PWD the shell prints the
            // physical path and its prompt never abbreviates to ~.
            put("PWD", shell.home)
        }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
                to.flush()
            }
        } catch (_: Exception) {
            // EIO on the PTY master once the shell exits, or the channel closing.
        }
    }

    private fun daemon(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply {
            isDaemon = true
            start()
        }

    /** Exit status as SSH expects it: signals map to 128 + signal number like a POSIX shell. */
    internal fun sshExitStatus(code: Int): Int = if (code < 0) 128 - code else code

    private abstract class StreamCommand : Command {
        protected lateinit var input: InputStream
        protected lateinit var output: OutputStream
        protected lateinit var error: OutputStream
        protected var onExit: ExitCallback? = null

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            output = out
        }

        override fun setErrorStream(err: OutputStream) {
            error = err
        }

        override fun setExitCallback(callback: ExitCallback) {
            onExit = callback
        }
    }

    /** Interactive login shell on a real PTY, resized on window-change. */
    private class PtyShellCommand(private val shell: ShellSpec) : StreamCommand() {
        @Volatile private var pid = 0
        private var master: ParcelFileDescriptor? = null

        override fun start(channel: ChannelSession, env: Environment) {
            fun size(key: String, fallback: Int) = env.env[key]?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
            val vars = sessionEnvironment(shell, env).map { "${it.key}=${it.value}" }.toTypedArray()
            val processId = IntArray(1)
            // argv[0] "-sh" makes ash/mksh a login shell, so it reads ~/.profile (PATH, prompt, HOME).
            val fd = QuickSshPty.createSubprocess(
                shell.path, shell.home, arrayOf("-sh"), vars, processId,
                size(Environment.ENV_LINES, 24), size(Environment.ENV_COLUMNS, 80)
            )
            pid = processId[0]
            val pty = ParcelFileDescriptor.adoptFd(fd)
            master = pty
            env.addSignalListener(SignalListener { _: Channel, _: Signal ->
                runCatching { QuickSshPty.setPtyWindowSize(fd, size(Environment.ENV_LINES, 24), size(Environment.ENV_COLUMNS, 80)) }
            }, Signal.WINCH)

            val reader = daemon("quickssh-sshd-pty-out") { pump(FileInputStream(pty.fileDescriptor), output) }
            daemon("quickssh-sshd-pty-in") { pump(input, FileOutputStream(pty.fileDescriptor)) }
            daemon("quickssh-sshd-pty-wait") {
                val code = QuickSshPty.waitFor(pid)
                pid = 0
                reader.join(1000)
                runCatching { pty.close() }
                onExit?.onExit(sshExitStatus(code))
            }
        }

        override fun destroy(channel: ChannelSession) {
            val running = pid
            if (running > 0) runCatching { Os.kill(running, OsConstants.SIGHUP) }
            runCatching { master?.close() }
        }
    }

    /** `ssh phone <command>`: runs through the shell with pipes, keeping stdout/stderr apart. */
    private class ExecCommand(private val shell: ShellSpec, private val command: String) : StreamCommand() {
        private var process: Process? = null

        override fun start(channel: ChannelSession, env: Environment) {
            // Non-interactive shells skip ~/.profile, so load the quickssh/opencode wrappers explicitly
            // (Android 10+ forbids exec of those scripts; see LocalEnvironmentDetector.builtinCommandsScript).
            val commands = File(shell.home, LocalEnvironmentDetector.COMMANDS_HOME_PATH).absolutePath
            val script = "[ -f '$commands' ] && . '$commands'\n$command"
            val builder = ProcessBuilder(shell.path, "-c", script).directory(File(shell.home))
            builder.environment().clear()
            builder.environment().putAll(sessionEnvironment(shell, env))
            val proc = builder.start()
            process = proc
            daemon("quickssh-sshd-exec-in") {
                pump(input, proc.outputStream)
                runCatching { proc.outputStream.close() }
            }
            val out = daemon("quickssh-sshd-exec-out") { pump(proc.inputStream, output) }
            val err = daemon("quickssh-sshd-exec-err") { pump(proc.errorStream, error) }
            daemon("quickssh-sshd-exec-wait") {
                val code = proc.waitFor()
                out.join(2000)
                err.join(2000)
                onExit?.onExit(sshExitStatus(code))
            }
        }

        override fun destroy(channel: ChannelSession) {
            process?.destroy()
        }
    }

    /** Only `ssh -L` towards loopback (the Agent Bridge, local dev servers); no remote listeners, agent or X11. */
    private object LoopbackOnlyForwardingFilter : ForwardingFilter {
        override fun canForwardAgent(session: Session, requestType: String): Boolean = false
        override fun canForwardX11(session: Session, requestType: String): Boolean = false
        override fun canListen(address: SshdSocketAddress, session: Session): Boolean = false
        override fun canConnect(type: TcpForwardingFilter.Type, address: SshdSocketAddress, session: Session): Boolean =
            type == TcpForwardingFilter.Type.Direct && isLoopbackHost(address.hostName)
    }
}
