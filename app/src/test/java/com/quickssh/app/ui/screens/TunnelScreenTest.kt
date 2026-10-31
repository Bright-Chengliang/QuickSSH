package com.quickssh.app.ui.screens

import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.TunnelHostGroup
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

    @Test
    fun tunnelHostLabelShowsAddressWhenTheHostHasNoRemark() {
        val group = TunnelHostGroup(
            key = "10.0.0.5",
            hostAddress = "10.0.0.5",
            label = "10.0.0.5",
            workspaces = listOf(tunnelConfig())
        )

        val label = tunnelHostLabel(group, AppLanguage.ZH)

        assertEquals("10.0.0.5 · root@10.0.0.5:22 · 1 个工作区", label)
    }

    @Test
    fun tunnelHostLabelShowsTheRemarkWithTheAddress() {
        val group = TunnelHostGroup(
            key = "10.0.0.5",
            hostAddress = "10.0.0.5",
            label = "家里 NAS",
            workspaces = listOf(tunnelConfig()),
            remark = "家里 NAS"
        )

        assertEquals("家里 NAS · 10.0.0.5", tunnelHostLabel(group, AppLanguage.ZH))
    }

    @Test
    fun tunnelHostDetailCountsWorkspaces() {
        val group = TunnelHostGroup(
            key = "10.0.0.5",
            hostAddress = "10.0.0.5",
            label = "10.0.0.5",
            workspaces = listOf(tunnelConfig(id = 1), tunnelConfig(id = 2))
        )

        assertEquals("root@10.0.0.5:22 · 2 workspaces", tunnelHostDetail(group, AppLanguage.EN))
    }

    private fun tunnelConfig(id: Long = 1): SshConfig = SshConfig(
        id = id,
        name = "Deploy",
        host = "10.0.0.5",
        port = 22,
        username = "root",
        authType = "PASSWORD"
    )
}
