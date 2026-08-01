package com.quickssh.app.data

import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import org.junit.Assert.assertEquals
import org.junit.Test

class SshConfigBackupCodecTest {
    @Test
    fun backupRoundTripPreservesTunnelPresets() {
        val record = backupRecord()

        val decoded = SshConfigBackupCodec.decode(SshConfigBackupCodec.encode(listOf(record)))

        assertEquals(1, decoded.size)
        assertEquals("Demo", decoded.first().name)
        assertEquals(
            SshTunnelPresetBackupRecord(
                name = "New API",
                note = "local admin console",
                remoteHost = "127.0.0.1",
                remotePort = 3002,
                localPort = 0
            ),
            decoded.first().tunnelPresets.single()
        )
    }

    @Test
    fun encryptedBackupRoundTripPreservesTunnelPresets() {
        val decoded = SshConfigBackupCodec.decode(
            json = SshConfigBackupCodec.encode(listOf(backupRecord()), password = "backup-pass"),
            password = "backup-pass"
        )

        assertEquals("New API", decoded.single().tunnelPresets.single().name)
        assertEquals("local admin console", decoded.single().tunnelPresets.single().note)
    }

    @Test
    fun backupDecodeTreatsMissingTunnelPresetsAsEmptyForOldFiles() {
        val json = """
            {
              "app": "QuickSSH",
              "formatVersion": 1,
              "servers": [
                {
                  "name": "Debug",
                  "host": "example.com",
                  "port": 22,
                  "username": "root",
                  "authType": "PASSWORD",
                  "password": "secret",
                  "terminalFontSizeSp": 12,
                  "terminalTerm": "xterm-256color"
                }
              ]
            }
        """.trimIndent()

        val decoded = SshConfigBackupCodec.decode(json)

        assertEquals(emptyList<SshTunnelPresetBackupRecord>(), decoded.single().tunnelPresets)
    }

    private fun backupRecord(): SshConfigBackupRecord {
        return SshConfigBackupRecord(
            name = "Demo",
            host = "example.com",
            port = 22,
            username = "user",
            authType = AUTH_TYPE_PASSWORD,
            password = "secret",
            privateKey = null,
            workDirectory = "/home/user/QuickSSH",
            postConnectCommand = "echo ready",
            terminalFontSizeSp = 13,
            terminalWrapEnabled = null,
            terminalTerm = "xterm-256color",
            terminalShortcuts = null,
            tunnelPresets = listOf(
                SshTunnelPresetBackupRecord(
                    name = "New API",
                    note = "local admin console",
                    remoteHost = "127.0.0.1",
                    remotePort = 3002,
                    localPort = 0
                )
            )
        )
    }
}
