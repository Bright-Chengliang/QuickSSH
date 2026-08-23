package com.quickssh.app.service

import java.io.IOException
import java.net.BindException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelForegroundServiceTest {
    @Test
    fun tunnelNotificationTextShowsSingleForward() {
        val text = tunnelNotificationText(
            listOf(
                TunnelServiceState(
                    tunnelId = "t1",
                    configId = 1L,
                    serverLabel = "Server / Web",
                    sshHost = "example.com",
                    remoteHost = "127.0.0.1",
                    remotePort = 3000,
                    localHost = "127.0.0.1",
                    localPort = 18080,
                    status = TunnelStatus.RUNNING,
                    statusText = "Tunnel running"
                )
            )
        )

        assertEquals("127.0.0.1:18080 -> 127.0.0.1:3000", text)
    }

    @Test
    fun tunnelNotificationTextCountsActiveTunnelsOnly() {
        val text = tunnelNotificationText(
            listOf(
                sampleTunnel("running", TunnelStatus.RUNNING),
                sampleTunnel("connecting", TunnelStatus.CONNECTING),
                sampleTunnel("failed", TunnelStatus.FAILED)
            )
        )

        assertEquals("Keeping 2 SSH tunnels active", text)
    }

    @Test
    fun friendlyTunnelErrorExplainsBusyLocalPort() {
        val message = friendlyTunnelError(BindException("Address already in use"))

        assertTrue(message.contains("Local port is already in use"))
    }

    @Test
    fun tunnelReconnectDelayUsesShortBoundedBackoff() {
        assertEquals(1_000L, tunnelReconnectDelayMillis(1))
        assertEquals(2_000L, tunnelReconnectDelayMillis(2))
        assertEquals(4_000L, tunnelReconnectDelayMillis(3))
        assertEquals(4_000L, tunnelReconnectDelayMillis(99))
        assertEquals(
            "Reconnecting SSH tunnel (attempt 2/3) in 2s...",
            tunnelReconnectStatusText(2, 2_000L)
        )
    }

    @Test
    fun tunnelReconnectOnlyRetriesTransientFailuresWithinLimit() {
        assertTrue(tunnelShouldRetry(IOException("Connection reset by peer"), nextAttempt = 1))
        assertTrue(tunnelShouldRetry(IOException("Connection timed out"), nextAttempt = 3))
        assertFalse(tunnelShouldRetry(IOException("Connection reset by peer"), nextAttempt = 4))
        assertFalse(tunnelShouldRetry(BindException("Address already in use"), nextAttempt = 1))
        assertFalse(tunnelShouldRetry(IllegalStateException("Authentication failed"), nextAttempt = 1))
        assertFalse(tunnelShouldRetry(IOException("Permission denied"), nextAttempt = 1))
    }

    @Test
    fun localTunnelParametersKeepPhoneEndpointLocalAndServerTargetRemote() {
        val parameters = localTunnelParameters(
            localHost = "127.0.0.1",
            localPort = 41611,
            remoteHost = "127.0.0.1",
            remotePort = 3002
        )

        assertEquals("127.0.0.1", parameters.localHost)
        assertEquals(41611, parameters.localPort)
        assertEquals("127.0.0.1", parameters.remoteHost)
        assertEquals(3002, parameters.remotePort)
    }

    private fun sampleTunnel(id: String, status: TunnelStatus): TunnelServiceState {
        return TunnelServiceState(
            tunnelId = id,
            configId = 1L,
            serverLabel = "Server",
            sshHost = "example.com",
            remoteHost = "127.0.0.1",
            remotePort = 3000,
            localHost = "127.0.0.1",
            localPort = 18080,
            status = status,
            statusText = status.name
        )
    }
}
