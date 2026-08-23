package com.quickssh.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshConfigBackupCodecTest {
    @Test
    fun encodesAndDecodesServerBackupRecords() {
        val record = SshConfigBackupRecord(
            name = "Deploy",
            host = "example.com",
            port = 2222,
            username = "ubuntu",
            authType = "PASSWORD",
            password = "secret",
            privateKey = null,
            workDirectory = "/srv/app",
            postConnectCommand = "git status",
            terminalFontSizeSp = 14,
            terminalWrapEnabled = false,
            terminalTerm = "xterm-256color",
            terminalShortcuts = "ls -la"
        )

        val json = SshConfigBackupCodec.encode(listOf(record))
        val decoded = SshConfigBackupCodec.decode(json)

        assertEquals(listOf(record), decoded)
        assertTrue(json.contains("\"app\": \"QuickSSH\""))
    }

    @Test
    fun encodesAndDecodesPasswordProtectedBackups() {
        val record = backupRecord()

        val json = SshConfigBackupCodec.encode(listOf(record), password = "backup-pass")
        val decoded = SshConfigBackupCodec.decode(json, password = "backup-pass")

        assertEquals(listOf(record), decoded)
        assertTrue(json.contains("\"encrypted\": true"))
        assertTrue(!json.contains("\"password\": \"secret\""))
    }

    @Test
    fun encryptedBackupRejectsWrongPassword() {
        val json = SshConfigBackupCodec.encode(listOf(backupRecord()), password = "backup-pass")

        try {
            SshConfigBackupCodec.decode(json, password = "wrong-pass")
            fail("Wrong password should fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("incorrect"))
        }
    }

    @Test
    fun mergeKeyMatchesEquivalentConfigAndBackupRecord() {
        val record = SshConfigBackupRecord(
            name = "Deploy",
            host = "Example.com",
            port = 22,
            username = "root",
            authType = "PASSWORD",
            password = "secret",
            privateKey = null,
            workDirectory = "/srv/app",
            postConnectCommand = null,
            terminalFontSizeSp = 12,
            terminalWrapEnabled = null,
            terminalTerm = "xterm-256color",
            terminalShortcuts = null
        )
        val config = SshConfig(
            name = "Deploy",
            host = "example.com",
            port = 22,
            username = "root",
            authType = "PASSWORD",
            encryptedPassword = "encrypted",
            workDirectory = "/srv/app"
        )

        assertEquals(SshConfigBackupCodec.mergeKey(record), SshConfigBackupCodec.mergeKey(config))
    }

    private fun backupRecord(): SshConfigBackupRecord {
        return SshConfigBackupRecord(
            name = "Deploy",
            host = "example.com",
            port = 2222,
            username = "ubuntu",
            authType = "PASSWORD",
            password = "secret",
            privateKey = null,
            workDirectory = "/srv/app",
            postConnectCommand = "git status",
            terminalFontSizeSp = 14,
            terminalWrapEnabled = false,
            terminalTerm = "xterm-256color",
            terminalShortcuts = "ls -la"
        )
    }
}
