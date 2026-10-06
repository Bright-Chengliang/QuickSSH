package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class AgentBridgeServiceTest {

    @Test
    fun testIsPortAvailableDetectsBoundPort() {
        // Find an open port first
        val ephemeralSocket = ServerSocket(0)
        val port = ephemeralSocket.localPort

        try {
            // Port is currently held, should NOT be available
            assertFalse(AgentBridgeService.isPortAvailable(port))
        } finally {
            ephemeralSocket.close()
        }

        // Port is now released, should be available
        assertTrue(AgentBridgeService.isPortAvailable(port))
    }

    @Test
    fun testGenerateTermuxInstallerScriptContainsCommands() {
        val script = AgentBridgeService.generateTermuxInstallerScript(
            bridgePort = 8024,
            termuxSshPort = 8023
        )

        assertTrue(script.contains("8024"))
        assertTrue(script.contains("8023"))
        assertTrue(script.contains("sshd -p 8023"))
        assertTrue(script.contains("/api/status"))
        assertTrue(script.contains("/api/configs"))
        assertTrue(script.contains("/api/devices"))
        assertTrue(script.contains("/api/terminal/exec"))
        assertTrue(script.contains("Usage: quickssh {status|configs|devices|exec <command>}"))
    }

    @Test
    fun testDefaultStateValues() {
        val defaultState = AgentBridgeState()
        assertEquals(AgentBridgeStatus.STOPPED, defaultState.status)
        assertEquals(8024, defaultState.bridgePort)
        assertEquals(8024, defaultState.port)
        assertEquals(8023, defaultState.termuxSshPort)
    }
}
