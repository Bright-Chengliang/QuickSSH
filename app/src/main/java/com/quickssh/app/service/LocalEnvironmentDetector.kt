package com.quickssh.app.service

import android.content.Context
import java.io.File

data class DetectedEnvironment(
    val shellPath: String,
    val shellName: String,
    val isTermux: Boolean,
    val homeDir: String,
    val initialWorkDir: String,
    val environment: Map<String, String>,
    val args: Array<String> = emptyArray()
)

data class ShellOption(
    val id: String,
    val displayName: String,
    val shellPath: String,
    val isTermux: Boolean,
    val isAvailable: Boolean
)

object LocalEnvironmentDetector {

    const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val TERMUX_LOGIN = "/data/data/com.termux/files/usr/bin/login"
    const val TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_LIB = "/data/data/com.termux/files/usr/lib"
    const val TERMUX_TMP = "/data/data/com.termux/files/usr/tmp"

    const val BUILTIN_SHELL = "builtin"
    const val SYSTEM_SH = "/system/bin/sh"
    const val DEFAULT_TERM = "xterm-256color"

    fun getBuiltinBusyboxFile(context: Context): File {
        val dir = runCatching { context.applicationInfo?.nativeLibraryDir }.getOrNull()
            ?: context.filesDir.absolutePath
        return File(dir, "libbusybox.so")
    }

    fun isBuiltinBusyboxAvailable(context: Context): Boolean {
        // Android 10+ (API 29+) W^X security strictly denies executing binaries from app_data_file
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return false
        }
        return runCatching {
            val file = getBuiltinBusyboxFile(context)
            if (!file.exists() || !file.canExecute()) return false

            val proc = ProcessBuilder(file.absolutePath, "true").start()
            val finished = proc.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            finished && proc.exitValue() == 0
        }.getOrDefault(false)
    }

    private val BUSYBOX_APPLETS = listOf(
        "busybox", "[", "[[", "ash", "awk", "base64", "basename", "bc", "bunzip2", "bzip2",
        "cal", "cat", "chmod", "chown", "clear", "cmp", "comm", "cp", "cut",
        "date", "dc", "dd", "df", "diff", "dirname", "dos2unix", "du", "echo",
        "egrep", "env", "expand", "expr", "factor", "false", "fgrep", "find",
        "flock", "fold", "free", "fsync", "ftpget", "ftpput", "getopt", "grep",
        "gunzip", "gzip", "head", "hexdump", "hexedit", "hostname", "id", "ifconfig",
        "install", "ip", "kill", "killall", "less", "link", "ln", "logname",
        "ls", "lsof", "lzcat", "lzma", "lzop", "md5sum", "mkdir", "mktemp",
        "more", "mv", "nc", "netstat", "nice", "nl", "nohup", "nproc", "nslookup",
        "od", "paste", "patch", "pgrep", "pidof", "ping", "ping6", "pkill",
        "printenv", "printf", "ps", "pstree", "pwd", "pwdx", "readlink", "realpath",
        "renice", "reset", "resize", "rev", "rm", "rmdir", "route", "sed", "seq",
        "sh", "sha1sum", "sha256sum", "sha512sum", "shuf", "sleep", "sort", "split",
        "stat", "strings", "stty", "sum", "sync", "tac", "tail", "tar", "tee",
        "telnet", "test", "time", "timeout", "top", "touch", "tr", "traceroute",
        "true", "truncate", "tty", "uname", "unexpand", "uniq", "unix2dos", "unlink",
        "unlzma", "unxz", "unzip", "uptime", "usleep", "vi", "watch", "wc",
        "wget", "which", "whoami", "xargs", "xxd", "xz", "xzcat", "yes", "zcat"
    )

    fun setupBuiltinEnvironment(context: Context): File {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
        val busybox = getBuiltinBusyboxFile(context)
        if (busybox.exists() && busybox.canExecute()) {
            for (applet in BUSYBOX_APPLETS) {
                val link = File(binDir, applet)
                try {
                    // Always delete before creating so stale symlinks from previous app installs are replaced
                    link.delete()
                    android.system.Os.symlink(busybox.absolutePath, link.absolutePath)
                } catch (_: Throwable) {}
            }
        }

        val nativeLibDir = runCatching { context.applicationInfo?.nativeLibraryDir }.getOrNull() ?: ""
        val profileFile = File(context.filesDir, ".profile")
        val pathValue = if (nativeLibDir.isNotBlank()) {
            "${binDir.absolutePath}:$nativeLibDir:/system/bin:/system/xbin"
        } else {
            "${binDir.absolutePath}:/system/bin:/system/xbin"
        }
        try {
            profileFile.writeText(
                """
                export PATH="$pathValue"
                export PS1='\[\033[01;32m\]quickssh\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                export TERM=xterm-256color
                export COLORTERM=truecolor
                export HOME="${context.filesDir.absolutePath}"
                alias ls='ls --color=auto'
                alias ll='ls -la --color=auto'
                alias grep='grep --color=auto'
                """.trimIndent() + "\n"
            )
        } catch (_: Exception) {}

        return binDir
    }

    fun isTermuxInstalled(): Boolean {
        val bashFile = File(TERMUX_BASH)
        val shFile = File(TERMUX_SH)
        return (bashFile.exists() && bashFile.canExecute()) || (shFile.exists() && shFile.canExecute())
    }

    fun getAvailableShells(context: Context? = null): List<ShellOption> {
        val list = mutableListOf<ShellOption>()

        val systemSh = File(SYSTEM_SH)
        list.add(
            ShellOption(
                id = "system_sh",
                displayName = "Android System Shell ($SYSTEM_SH)",
                shellPath = SYSTEM_SH,
                isTermux = false,
                isAvailable = systemSh.exists()
            )
        )

        val termuxBash = File(TERMUX_BASH)
        if (termuxBash.exists()) {
            list.add(
                ShellOption(
                    id = "termux_bash",
                    displayName = "Termux Bash (${termuxBash.absolutePath})",
                    shellPath = TERMUX_BASH,
                    isTermux = true,
                    isAvailable = termuxBash.canExecute()
                )
            )
        }

        val termuxSh = File(TERMUX_SH)
        if (termuxSh.exists()) {
            list.add(
                ShellOption(
                    id = "termux_sh",
                    displayName = "Termux Sh (${termuxSh.absolutePath})",
                    shellPath = TERMUX_SH,
                    isTermux = true,
                    isAvailable = termuxSh.canExecute()
                )
            )
        }

        if (context != null && isBuiltinBusyboxAvailable(context)) {
            val bb = getBuiltinBusyboxFile(context)
            list.add(
                ShellOption(
                    id = "builtin_busybox",
                    displayName = "Built-in Linux Shell (BusyBox)",
                    shellPath = bb.absolutePath,
                    isTermux = true,
                    isAvailable = true
                )
            )
        }

        return list
    }

    /**
     * Detects the optimal local shell environment.
     * Priority 1: Termux environment (if requested or installed)
     * Priority 2: Built-in Busybox (if explicitly requested and available)
     * Priority 3: Android system shell (/system/bin/sh) (guaranteed to work across all Android versions)
     */
    fun detect(
        context: Context,
        preferredShellPath: String? = null,
        preferredWorkDir: String? = null,
        term: String = DEFAULT_TERM
    ): DetectedEnvironment {
        val builtinBusybox = getBuiltinBusyboxFile(context)
        val isExplicitSystemSh = preferredShellPath == SYSTEM_SH
        val useBuiltin = !isExplicitSystemSh &&
                (preferredShellPath == BUILTIN_SHELL ||
                 preferredShellPath == builtinBusybox.absolutePath ||
                 preferredShellPath?.endsWith("/files/bin/sh") == true)

        if (useBuiltin && isBuiltinBusyboxAvailable(context)) {
            val binDir = setupBuiltinEnvironment(context)
            val busyboxSh = File(binDir, "sh")
            val shellExecutable = if (busyboxSh.exists()) busyboxSh.absolutePath else builtinBusybox.absolutePath
            val homeDir = context.filesDir.absolutePath
            val tmpDir = context.cacheDir.absolutePath
            val nativeLibDir = runCatching { context.applicationInfo?.nativeLibraryDir }.getOrNull() ?: ""

            val envMap = mutableMapOf<String, String>()
            envMap["HOME"] = homeDir
            envMap["TMPDIR"] = tmpDir
            val pathValue = if (nativeLibDir.isNotBlank()) {
                "${binDir.absolutePath}:$nativeLibDir:/system/bin:/system/xbin"
            } else {
                "${binDir.absolutePath}:/system/bin:/system/xbin"
            }
            envMap["PATH"] = pathValue
            envMap["TERM"] = term.ifBlank { DEFAULT_TERM }
            envMap["COLORTERM"] = "truecolor"
            envMap["LANG"] = "en_US.UTF-8"
            envMap["ENV"] = "$homeDir/.profile"
            envMap["PS1"] = "\u001B[1;32mquickssh\u001B[0m:\u001B[1;34m\\w\u001B[0m\\$ "

            val resolvedWorkDir = resolveWorkingDir(preferredWorkDir, homeDir, context)

            return DetectedEnvironment(
                shellPath = shellExecutable,
                shellName = "Built-in Linux Shell (BusyBox)",
                isTermux = true,
                homeDir = homeDir,
                initialWorkDir = resolvedWorkDir,
                environment = envMap,
                args = arrayOf("-l")
            )
        }

        val termuxBash = File(TERMUX_BASH)
        val termuxSh = File(TERMUX_SH)
        val termuxHome = File(TERMUX_HOME)

        val useTermux = when {
            !preferredShellPath.isNullOrBlank() -> preferredShellPath.startsWith(TERMUX_PREFIX)
            termuxBash.exists() && termuxBash.canExecute() -> true
            termuxSh.exists() && termuxSh.canExecute() -> true
            else -> false
        }

        if (useTermux) {
            val resolvedShell = when {
                !preferredShellPath.isNullOrBlank() && File(preferredShellPath).exists() -> preferredShellPath
                termuxBash.exists() && termuxBash.canExecute() -> TERMUX_BASH
                termuxSh.exists() && termuxSh.canExecute() -> TERMUX_SH
                else -> SYSTEM_SH
            }

            val homeDir = if (termuxHome.exists() && termuxHome.canRead()) {
                TERMUX_HOME
            } else {
                context.filesDir.absolutePath
            }

            val tmpDir = File(TERMUX_TMP).let {
                if (it.exists() && it.canWrite()) TERMUX_TMP else context.cacheDir.absolutePath
            }

            val envMap = mutableMapOf<String, String>()
            envMap["PREFIX"] = TERMUX_PREFIX
            envMap["PATH"] = "$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:/system/bin:/system/xbin"
            envMap["LD_LIBRARY_PATH"] = TERMUX_LIB
            envMap["HOME"] = homeDir
            envMap["TMPDIR"] = tmpDir
            envMap["TERM"] = term.ifBlank { DEFAULT_TERM }
            envMap["COLORTERM"] = "truecolor"
            envMap["LANG"] = "en_US.UTF-8"

            val resolvedWorkDir = resolveWorkingDir(preferredWorkDir, homeDir, context)

            return DetectedEnvironment(
                shellPath = resolvedShell,
                shellName = if (resolvedShell.endsWith("bash")) "Termux Bash" else "Termux Shell",
                isTermux = true,
                homeDir = homeDir,
                initialWorkDir = resolvedWorkDir,
                environment = envMap
            )
        } else {
            val isCustomValid = !preferredShellPath.isNullOrBlank() &&
                    preferredShellPath != BUILTIN_SHELL &&
                    !preferredShellPath.contains("/files/bin/") &&
                    !preferredShellPath.contains("busybox") &&
                    File(preferredShellPath).exists()

            val resolvedShell = if (isCustomValid) preferredShellPath!! else SYSTEM_SH

            val homeDir = context.filesDir.absolutePath
            val tmpDir = context.cacheDir.absolutePath
            val binDir = File(context.filesDir, "bin")

            val envMap = mutableMapOf<String, String>()
            envMap["HOME"] = homeDir
            envMap["TMPDIR"] = tmpDir
            val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
            envMap["PATH"] = if (binDir.exists()) "${binDir.absolutePath}:$systemPath" else systemPath
            envMap["TERM"] = term.ifBlank { DEFAULT_TERM }
            envMap["COLORTERM"] = "truecolor"
            envMap["LANG"] = "en_US.UTF-8"
            envMap["PS1"] = "\u001B[1;32mquickssh\u001B[0m:\u001B[1;34m\\w\u001B[0m\\$ "

            val resolvedWorkDir = resolveWorkingDir(preferredWorkDir, homeDir, context)

            return DetectedEnvironment(
                shellPath = resolvedShell,
                shellName = if (resolvedShell == SYSTEM_SH) "Android System Shell" else "Custom Shell",
                isTermux = false,
                homeDir = homeDir,
                initialWorkDir = resolvedWorkDir,
                environment = envMap,
                args = emptyArray()
            )
        }
    }

    private fun resolveWorkingDir(preferred: String?, defaultHome: String, context: Context): String {
        if (!preferred.isNullOrBlank()) {
            val file = File(preferred)
            if (file.exists() && file.isDirectory) {
                return file.absolutePath
            }
        }
        val defaultHomeFile = File(defaultHome)
        if (defaultHomeFile.exists() && defaultHomeFile.isDirectory) {
            return defaultHomeFile.absolutePath
        }
        return context.filesDir.absolutePath
    }
}
