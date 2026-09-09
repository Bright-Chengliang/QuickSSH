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

    @Test
    fun backupRoundTripPreservesServerDisplayName() {
        val record = backupRecord().copy(serverDisplayName = "生产跳板机")
        val decoded = SshConfigBackupCodec.decode(SshConfigBackupCodec.encode(listOf(record)))

        assertEquals("生产跳板机", decoded.first().serverDisplayName)
    }

    @Test
    fun backupDecodeHandlesLocalOrZeroPortGracefullyWithoutThrowing() {
        val json = """
            {
              "app": "QuickSSH",
              "formatVersion": 2,
              "servers": [
                {
                  "name": "本机",
                  "host": "/system/bin/sh",
                  "port": 0,
                  "username": "local",
                  "authType": "LOCAL"
                },
                {
                  "name": "Valid Server",
                  "host": "192.168.1.100",
                  "port": 22,
                  "username": "admin",
                  "authType": "PASSWORD"
                }
              ]
            }
        """.trimIndent()

        val decoded = SshConfigBackupCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("本机", decoded[0].name)
        assertEquals(0, decoded[0].port)
        assertEquals("Valid Server", decoded[1].name)
        assertEquals(22, decoded[1].port)
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
