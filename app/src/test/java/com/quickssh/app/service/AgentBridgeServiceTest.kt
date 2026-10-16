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
    fun testGenerateTermuxInstallerScriptInstallsTokenCliAndSkill() {
        val cli = AgentToolkit.cliScript(cliTemplate(), AgentToolkit.TERMUX_SHELL, "\$HOME/.config/quickssh/bridge.env", 8024)
        val script = AgentBridgeService.generateTermuxInstallerScript(
            bridgePort = 8024,
            termuxSshPort = 8023,
            token = "t0ken",
            cliScript = cli
        )

        assertTrue(script.contains("sshd -p 8023"))
        assertTrue(script.contains("export QUICKSSH_BRIDGE_URL=http://127.0.0.1:8024\nexport QUICKSSH_BRIDGE_TOKEN=t0ken\nQUICKSSH_ENV_EOF"))
        assertTrue(script.contains("umask 077"))
        assertTrue(script.contains("#!/data/data/com.termux/files/usr/bin/sh\n"))
        assertTrue(script.contains("QUICKSSH_ENV_FILE=\"\${QUICKSSH_ENV_FILE:-\$HOME/.config/quickssh/bridge.env}\""))
        assertTrue(script.contains("\$HOME/.agents/skills/quickssh-workspace/SKILL.md"))
        // Heredoc delimiters must not occur inside the embedded CLI, or the script would be cut short.
        assertEquals(1, Regex("^QUICKSSH_CLI_EOF$", RegexOption.MULTILINE).findAll(script).count())
        assertFalse(cli.contains("@QUICKSSH_"))
        assertTrue(script.contains("printf '%s' 't0ken' > \"\$HOME/.config/quickssh/bridge.token\""))
        assertTrue(script.contains("\"url\": \"http://127.0.0.1:8024/mcp\""))
        assertTrue(script.contains("OC_CONF=\"\$HOME/.config/opencode/opencode.json\""))
        assertTrue(script.contains("{file:/data/data/com.termux/files/home/.config/quickssh/bridge.token}"))
        // One heredoc per opencode config layout (2.x and 1.x); the installer picks one at run time.
        assertEquals(2, Regex("^QUICKSSH_OPENCODE_EOF$", RegexOption.MULTILINE).findAll(script).count())
    }

    @Test
    fun testOpencodeConfigRegistersMcpWithTokenFile() {
        val tokenFile = "/data/user/0/com.quickssh.app/files/.config/quickssh/bridge.token"
        fun server(config: String, vararg path: String): org.json.JSONObject =
            path.fold(org.json.JSONObject(config)) { json, key -> json.getJSONObject(key) }

        val current = server(AgentToolkit.opencodeConfigContent(9000, tokenFile), "mcp", "servers", "quickssh")
        assertEquals("remote", current.getString("type"))
        assertEquals("http://127.0.0.1:9000/mcp", current.getString("url"))
        assertFalse(current.getBoolean("oauth"))
        assertEquals("Bearer {file:$tokenFile}", current.getJSONObject("headers").getString("Authorization"))
        val legacy = server(AgentToolkit.opencodeConfigContent(9000, tokenFile, legacyFormat = true), "mcp", "quickssh")
        assertEquals("http://127.0.0.1:9000/mcp", legacy.getString("url"))

        assertTrue(AgentToolkit.isGeneratedOpencodeConfig(AgentToolkit.opencodeConfigContent(8024, "/other/token")))
        assertTrue(AgentToolkit.isGeneratedOpencodeConfig(AgentToolkit.opencodeConfigContent(8030, tokenFile, legacyFormat = true)))
        assertFalse(AgentToolkit.isGeneratedOpencodeConfig("""{"model":"anthropic/claude"}"""))
    }

    @Test
    fun testMcpOriginCheckAllowsOnlyLoopback() {
        assertTrue(AgentBridgeService.isLoopbackOrigin("http://127.0.0.1:3000"))
        assertTrue(AgentBridgeService.isLoopbackOrigin("http://localhost"))
        assertFalse(AgentBridgeService.isLoopbackOrigin("https://evil.example"))
        assertFalse(AgentBridgeService.isLoopbackOrigin("null"))
    }

    @Test
    fun testCliTemplateCoversWorkspaceApi() {
        val template = cliTemplate()
        listOf("/api/v2/tree", "/api/v2/export", "/api/v2/apply", "/api/v2/skill", "--post-file", "Authorization: Bearer")
            .forEach { assertTrue(it, template.contains(it)) }
    }

    @Test
    fun testAuthorizationAcceptsBearerHeaderAndQueryToken() {
        val token = AgentBridgeService.newToken()
        assertEquals(48, token.length)
        fun request(path: String, headers: Map<String, String> = emptyMap()) =
            AgentBridgeService.BridgeRequest("GET", path, "", headers)

        assertTrue(AgentBridgeService.isAuthorized(request("/api/v2/tree", mapOf("authorization" to "Bearer $token")), token))
        assertTrue(AgentBridgeService.isAuthorized(request("/api/v2/tree", mapOf("x-quickssh-token" to token)), token))
        assertTrue(AgentBridgeService.isAuthorized(request("/api/v2/tree?token=$token"), token))
        assertFalse(AgentBridgeService.isAuthorized(request("/api/v2/tree"), token))
        assertFalse(AgentBridgeService.isAuthorized(request("/api/v2/tree", mapOf("authorization" to "Bearer wrong")), token))
        assertFalse(AgentBridgeService.isAuthorized(request("/api/v2/tree", mapOf("authorization" to "Bearer ")), ""))
    }

    @Test
    fun testProtectedRouteWithoutTokenIsRejectedWithoutCors() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val port = ServerSocket(0).use { it.localPort }
        try {
            assertTrue(AgentBridgeService.start(context, bridgePort = port, termuxSshPort = 8023))
            java.net.Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /api/configs/export HTTP/1.1\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                val response = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response, response.startsWith("HTTP/1.1 401"))
                assertFalse(response.contains("Access-Control-Allow-Origin"))
            }
        } finally {
            AgentBridgeService.stop()
        }
    }

    private fun cliTemplate(): String = java.io.File("src/main/assets/agent/quickssh").readText()

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
    fun testReadRequestParsesHeadersCaseInsensitively() {
        val raw = "GET /api/v2/tree HTTP/1.1\r\nAuthorization: Bearer abc\r\nX-QuickSSH-Token: def\r\n\r\n"
        val request = AgentBridgeService.readRequest(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))!!

        assertEquals("Bearer abc", request.headers["authorization"])
        assertEquals("def", request.headers["x-quickssh-token"])
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
