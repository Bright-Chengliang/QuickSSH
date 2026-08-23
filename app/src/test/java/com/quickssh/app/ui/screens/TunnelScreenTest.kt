package com.quickssh.app.ui.screens

import com.quickssh.app.data.SshTunnelPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelScreenTest {
    @Test
    fun parseTunnelPortAllowsBlankAutoLocalPort() {
        assertEquals(0, parseTunnelPort("", allowAuto = true))
    }

    @Test
    fun parseTunnelPortRejectsBlankRemotePort() {
        assertNull(parseTunnelPort("", allowAuto = false))
    }

    @Test
    fun parseTunnelPortAcceptsValidPortRange() {
        assertEquals(3000, parseTunnelPort("3000", allowAuto = false))
        assertEquals(65535, parseTunnelPort("65535", allowAuto = true))
    }

    @Test
    fun parseTunnelPortRejectsOutOfRangeValues() {
        assertNull(parseTunnelPort("0", allowAuto = false))
        assertNull(parseTunnelPort("65536", allowAuto = true))
    }

    @Test
    fun tunnelPresetLabelShowsRemoteTargetAndAutoLocalPort() {
        val preset = SshTunnelPreset(
            workspaceId = 1L,
            name = "New API",
            remoteHost = "127.0.0.1",
            remotePort = 3002,
            localPort = 0
        )

        assertEquals("New API · 127.0.0.1:3002 -> 自动", preset.presetLabel())
    }
}
