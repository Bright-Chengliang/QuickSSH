package com.quickssh.app.service

import android.content.Context
import android.os.Handler
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages creation and I/O bridging for Termux TerminalSession instances,
 * supporting both local PTY processes and remote SSH sessions.
 */
class TermuxSessionManager(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TermuxSessionManager"
        private const val DEFAULT_TRANSCRIPT_ROWS = 10000
    }

    /**
     * Creates a local terminal session running directly in a native pseudo-terminal (PTY).
     * Returns null if native Termux PTY is unavailable (e.g. in JVM unit tests).
     */
    suspend fun createLocalSession(
        shellPath: String,
        cwd: String,
        args: Array<String> = emptyArray(),
        env: Array<String> = emptyArray(),
        initialCols: Int = 80,
        initialRows: Int = 24,
        onTitleChanged: ((String) -> Unit)? = null,
        onSessionFinished: ((Int) -> Unit)? = null
    ): TerminalSession? {
        return try {
            val client = object : AbstractTerminalSessionClient() {
                override fun onTitleChanged(changedSession: TerminalSession) {
                    onTitleChanged?.invoke(changedSession.title ?: "")
                }

                override fun onSessionFinished(finishedSession: TerminalSession) {
                    onSessionFinished?.invoke(finishedSession.exitStatus)
                }
            }

            // TerminalSession must be created on a thread with a Looper (Main thread)
            withContext(Dispatchers.Main) {
                val session = TerminalSession(
                    shellPath,
                    cwd,
                    args,
                    env,
                    DEFAULT_TRANSCRIPT_ROWS,
                    client
                )
                session.initializeEmulator(initialCols.coerceAtLeast(4), initialRows.coerceAtLeast(4))
                session
            }
        } catch (e: Throwable) {
            Log.w(TAG, "createLocalSession failed or unavailable: ${e.message}", e)
            null
        }
    }

    /**
     * Creates an SSH bridged Termux TerminalSession.
     * Connects remote SSH InputStream and OutputStream directly to Termux's
     * TerminalEmulator ByteQueues in memory without external subprocesses or loops.
     * Works reliably on all Android versions and devices.
     */
    suspend fun createSshSession(
        sshInputStream: InputStream,
        sshOutputStream: OutputStream,
        initialCols: Int = 80,
        initialRows: Int = 24,
        terminalTerm: String = "xterm-256color",
        onTitleChanged: ((String) -> Unit)? = null,
        onDataReceived: (() -> Unit)? = null,
        onSessionFinished: (() -> Unit)? = null
    ): SshSessionBridgeResult? {
        return try {
            val client = object : AbstractTerminalSessionClient() {
                override fun onTitleChanged(changedSession: TerminalSession) {
                    onTitleChanged?.invoke(changedSession.title ?: "")
                }

                override fun onSessionFinished(finishedSession: TerminalSession) {
                    Log.d(TAG, "SSH terminal session finished: exit=${finishedSession.exitStatus}")
                    onSessionFinished?.invoke()
                }
            }

            val cols = initialCols.coerceAtLeast(4)
            val rows = initialRows.coerceAtLeast(4)

            // TerminalSession must be created on Main thread (Looper requirement)
            val session = withContext(Dispatchers.Main) {
                val s = TerminalSession(
                    null,
                    appContext.filesDir.absolutePath,
                    emptyArray(),
                    arrayOf("TERM=$terminalTerm", "HOME=${appContext.filesDir.absolutePath}"),
                    DEFAULT_TRANSCRIPT_ROWS,
                    client
                )

                // Instantiate official Termux TerminalEmulator directly
                val emulator = TerminalEmulator(
                    s,
                    cols,
                    rows,
                    DEFAULT_TRANSCRIPT_ROWS,
                    client
                )

                // Inject emulator into TerminalSession via reflection
                val emulatorField = TerminalSession::class.java.getDeclaredField("mEmulator")
                emulatorField.isAccessible = true
                emulatorField.set(s, emulator)

                // Set mShellPid to positive (1000) so s.write() is accepted
                val pidField = TerminalSession::class.java.getDeclaredField("mShellPid")
                pidField.isAccessible = true
                pidField.set(s, 1000)

                // Set mTerminalFileDescriptor to -1 (in-memory session, no subprocess pty)
                val fdField = TerminalSession::class.java.getDeclaredField("mTerminalFileDescriptor")
                fdField.isAccessible = true
                fdField.set(s, -1)

                s
            }

            val processToTerminalQueueRaw = TerminalSession::class.java
                .getDeclaredField("mProcessToTerminalIOQueue")
                .apply { isAccessible = true }
                .get(session)!!
            val processToTerminalQueue = ByteQueueWrapper(processToTerminalQueueRaw)

            val terminalToProcessQueueRaw = TerminalSession::class.java
                .getDeclaredField("mTerminalToProcessIOQueue")
                .apply { isAccessible = true }
                .get(session)!!
            val terminalToProcessQueue = ByteQueueWrapper(terminalToProcessQueueRaw)

            val mainThreadHandler = TerminalSession::class.java
                .getDeclaredField("mMainThreadHandler")
                .apply { isAccessible = true }
                .get(session) as Handler

            // Background reader: SSH network input -> Termux ByteQueue -> TerminalEmulator
            val inJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                try {
                    while (isActive) {
                        val count = sshInputStream.read(buffer)
                        if (count <= 0) break
                        onDataReceived?.invoke()
                        val rawStr = String(buffer, 0, count)
                        if (rawStr.contains("100") || rawStr.contains("\u001B[")) {
                            Log.d("QuickSSH_TUI", "SSH IN (len=$count): ${rawStr.replace("\u001B", "\\e")}")
                        }
                        DecSetFilter.rewrite(buffer, 0, count)
                        processToTerminalQueue.write(buffer, 0, count)
                        // MSG_NEW_INPUT (1) informs MainThreadHandler to append to emulator & notify view
                        mainThreadHandler.sendEmptyMessage(1)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "SSH bridge input reader ended: ${e.message}")
                } finally {
                    withContext(Dispatchers.Main) {
                        try {
                            val pidField = TerminalSession::class.java.getDeclaredField("mShellPid")
                            pidField.isAccessible = true
                            pidField.set(session, -1)
                        } catch (_: Exception) {}
                        session.finishIfRunning()
                        onSessionFinished?.invoke()
                    }
                }
            }

            // Background writer: Termux keystrokes ByteQueue -> SSH network output
            val outJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                try {
                    while (isActive) {
                        val count = terminalToProcessQueue.read(buffer, true)
                        if (count <= 0) break
                        val outStr = String(buffer, 0, count)
                        Log.d("QuickSSH_TUI", "SSH OUT (len=$count): ${outStr.replace("\u001B", "\\e")}")
                        sshOutputStream.write(buffer, 0, count)
                        sshOutputStream.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "SSH bridge output writer ended: ${e.message}")
                }
            }

            SshSessionBridgeResult(
                session = session,
                closeAction = {
                    inJob.cancel()
                    outJob.cancel()
                    try {
                        val pidField = TerminalSession::class.java.getDeclaredField("mShellPid")
                        pidField.isAccessible = true
                        pidField.set(session, -1)
                    } catch (_: Exception) {}
                    terminalToProcessQueue.close()
                    processToTerminalQueue.close()
                    session.finishIfRunning()
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "createSshSession failed: ${e.message}", e)
            null
        }
    }
}

/**
 * Reflection wrapper for Termux package-private ByteQueue class.
 */
class ByteQueueWrapper(private val queueInstance: Any) {
    private val writeMethod = queueInstance.javaClass.getMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val readMethod = queueInstance.javaClass.getMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    private val closeMethod = queueInstance.javaClass.getMethod("close").apply { isAccessible = true }

    fun write(buffer: ByteArray, offset: Int, count: Int): Boolean {
        return writeMethod.invoke(queueInstance, buffer, offset, count) as Boolean
    }

    fun read(buffer: ByteArray, block: Boolean): Int {
        return readMethod.invoke(queueInstance, buffer, block) as Int
    }

    fun close() {
        closeMethod.invoke(queueInstance)
    }
}

data class SshSessionBridgeResult(
    val session: TerminalSession,
    val closeAction: () -> Unit
)

/**
 * Default implementation of Termux's TerminalSessionClient.
 */
abstract class AbstractTerminalSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Exception", e)
    }
}
