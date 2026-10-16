package com.termux.terminal

/**
 * Exposes the Termux terminal-emulator's package-private [JNI] PTY primitives to QuickSSH, so the
 * embedded SSH server can give remote shells a real pseudo-terminal without a [TerminalSession].
 */
object QuickSshPty {
    /**
     * Forks [cmd] on a new PTY. [args] is the full argv including argv[0]; [env] entries are KEY=VALUE.
     * Returns the PTY master fd; the child pid is stored in [processId][0].
     */
    fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int
    ): Int = JNI.createSubprocess(cmd, cwd, args, env, processId, rows, columns)

    fun setPtyWindowSize(fd: Int, rows: Int, columns: Int) = JNI.setPtyWindowSize(fd, rows, columns)

    /** Blocks until [pid] exits; returns its exit code, or -signal when it was killed. */
    fun waitFor(pid: Int): Int = JNI.waitFor(pid)
}
