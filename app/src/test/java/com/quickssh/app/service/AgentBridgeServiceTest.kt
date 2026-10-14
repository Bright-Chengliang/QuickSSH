package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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

    @Test
    fun testRestartAfterServingRequestRebindsSamePort() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val port = ServerSocket(0).use { it.localPort }
        try {
            assertTrue(AgentBridgeService.start(context, bridgePort = port, termuxSshPort = 8023))
            // Serve one request so the server-side connection lingers in TIME_WAIT after close.
            java.net.Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /api/status HTTP/1.1\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                val response = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 200"))
            }
            AgentBridgeService.stop()

            // Activity recreation restarts the bridge on the same port right away.
            assertTrue(AgentBridgeService.start(context, bridgePort = port, termuxSshPort = 8023))
            assertEquals(port, AgentBridgeService.bridgeState.value.bridgePort)
            assertTrue(AgentBridgeService.start(context, bridgePort = port, termuxSshPort = 8023))
        } finally {
            AgentBridgeService.stop()
        }
    }

    @Test
    fun testStartReportsPortOccupiedByAnotherListener() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1")).use { other ->
            try {
                assertFalse(AgentBridgeService.start(context, bridgePort = other.localPort, termuxSshPort = 8023))
                assertEquals(AgentBridgeStatus.PORT_OCCUPIED, AgentBridgeService.bridgeState.value.status)
            } finally {
                AgentBridgeService.stop()
            }
        }
    }

    @Test
    fun testReadRequestUsesByteContentLengthForUtf8Body() {
        val body = """{"name":"模型训练","host":"gpu.example.net"}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val raw = "POST /api/configs/add HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${bodyBytes.size}\r\n\r\n"
            .toByteArray(Charsets.UTF_8) + bodyBytes + "NEXT".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(raw)

        val request = AgentBridgeService.readRequest(input)

        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/configs/add", request.rawPath)
        assertEquals(body, request.body)
        // Exactly Content-Length bytes are consumed; nothing past the body is read.
        assertEquals('N'.code, input.read())
    }

    @Test
    fun testReadRequestWithoutBody() {
        val input = ByteArrayInputStream("GET /api/status?x=1 HTTP/1.1\r\n\r\n".toByteArray(Charsets.UTF_8))

        val request = AgentBridgeService.readRequest(input)

        assertEquals("GET", request?.method)
        assertEquals("/api/status?x=1", request?.rawPath)
        assertEquals("", request?.body)
    }
}
