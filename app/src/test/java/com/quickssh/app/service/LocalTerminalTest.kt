package com.quickssh.app.service

import android.content.Context
import android.net.Uri
import com.quickssh.app.data.SshConfig
import com.quickssh.app.utils.TerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTerminalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun localEnvironmentDetectorBuildsExpectedEnvironment() {
        val dummyContextDir = tempFolder.newFolder("filesDir")
        val dummyCacheDir = tempFolder.newFolder("cacheDir")

        // In JVM test environment, Termux does not exist so it falls back to system shell
        val detected = LocalEnvironmentDetector.detect(
            context = object : android.content.ContextWrapper(null) {
                override fun getFilesDir(): File = dummyContextDir
                override fun getCacheDir(): File = dummyCacheDir
            },
            preferredShellPath = null,
            preferredWorkDir = null,
            term = "xterm-256color"
        )

        assertNotNull(detected)
        assertEquals(LocalEnvironmentDetector.SYSTEM_SH, detected.shellPath)
        assertFalse(detected.isTermux)
        assertEquals("xterm-256color", detected.environment["TERM"])
        assertEquals("truecolor", detected.environment["COLORTERM"])
        assertEquals("en_US.UTF-8", detected.environment["LANG"])
        assertEquals(dummyContextDir.absolutePath, detected.homeDir)
    }

    @Test
    fun localEnvironmentDetectorRespectsCustomShellAndWorkDir() {
        val workDir = tempFolder.newFolder("my_project")
        val dummyContextDir = tempFolder.newFolder("filesDir2")
        val dummyCacheDir = tempFolder.newFolder("cacheDir2")

        val detected = LocalEnvironmentDetector.detect(
            context = object : android.content.ContextWrapper(null) {
                override fun getFilesDir(): File = dummyContextDir
                override fun getCacheDir(): File = dummyCacheDir
            },
            preferredShellPath = "/system/bin/sh",
            preferredWorkDir = workDir.absolutePath,
            term = "vt100"
        )

        assertEquals("/system/bin/sh", detected.shellPath)
        assertEquals(workDir.absolutePath, detected.initialWorkDir)
        assertEquals("vt100", detected.environment["TERM"])
    }

    @Test
    fun terminalHistoryFileFifoPruningEnforcesLimit() {
        val historyDir = tempFolder.newFolder("terminal_history")
        val f1 = File(historyDir, "oldest.txt").apply {
            writeBytes(ByteArray(1000))
            setLastModified(1000L)
        }
        val f2 = File(historyDir, "middle.txt").apply {
            writeBytes(ByteArray(1000))
            setLastModified(2000L)
        }
        val f3 = File(historyDir, "newest.txt").apply {
            writeBytes(ByteArray(1000))
            setLastModified(3000L)
        }

        // Total is 3000 bytes. If limit is 1500 bytes, oldest files must be deleted (FIFO)
        TerminalHistoryFile.pruneHistoryToTotalLimit(
            context = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): File = historyDir.parentFile!!
            },
            maxTotalBytes = 1500L
        )

        assertFalse("Oldest file should be pruned", f1.exists())
        assertFalse("Middle file should be pruned to get under 1500", f2.exists())
        assertTrue("Newest file should be kept", f3.exists())
    }

    @Test
    fun terminalHistoryFileConstantsMatchProtectionSpec() {
        // Spec Section 4: Single session cap 5MB, total limit 50MB
        assertEquals(5L * 1024 * 1024, TerminalHistoryFile.MAX_FILE_SIZE_BYTES)
        assertEquals(50L * 1024 * 1024, TerminalHistoryFile.MAX_TOTAL_HISTORY_BYTES)
    }

    @Test
    fun localPtyHelperImplementsTerminalSessionClient() {
        val config = SshConfig(
            id = 101,
            name = "Local Test",
            host = "/system/bin/sh",
            port = 0,
            username = "local",
            authType = AUTH_TYPE_LOCAL,
            isLocalSession = true
        )
        val dummyCache = tempFolder.newFolder("cache")
        val context = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = dummyCache
            override fun getFilesDir(): File = dummyCache
        }

        val helper: TerminalSessionClient = LocalPtyHelper(
            sessionId = "session-test-local-101",
            config = config,
            appContext = context
        )

        assertEquals("session-test-local-101", helper.sessionId)
        assertNotNull(helper.terminalOutput)
        assertNotNull(helper.status)
        assertNotNull(helper.historyBuffer)
        assertNotNull(helper.historyFile)

        // Resizing and cleanup
        helper.resizeTerminal(TerminalBuffer.Size(80, 24))
        helper.disconnect()
        assertEquals(SshSessionStatus.DISCONNECTED, helper.status.value)
    }
}
