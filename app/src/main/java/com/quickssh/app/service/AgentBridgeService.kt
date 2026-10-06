package com.quickssh.app.service

import android.content.Context
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.utils.SshHostDiscoveryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

enum class AgentBridgeStatus {
    STOPPED,
    STARTING,
    RUNNING,
    PORT_CONFLICT,
    PORT_OCCUPIED,
    ERROR
}

data class AgentBridgeState(
    val status: AgentBridgeStatus = AgentBridgeStatus.STOPPED,
    val bridgePort: Int = AgentBridgeService.DEFAULT_BRIDGE_PORT,
    val termuxSshPort: Int = AgentBridgeService.DEFAULT_TERMUX_SSH_PORT,
    val errorMessage: String? = null
) {
    val port: Int get() = bridgePort
}

/**
 * Lightweight loopback-only (127.0.0.1) Agent Bridge HTTP service.
 * Enables AI Agents in Termux and QuickSSH to seamlessly interact,
 * execute commands, and inspect configurations across both environments.
 */
object AgentBridgeService {
    const val DEFAULT_BRIDGE_PORT = 8024
    const val DEFAULT_TERMUX_SSH_PORT = 8023
    const val PREFS_KEY_BRIDGE_ENABLED = "agent_bridge_enabled"
    const val PREFS_KEY_BRIDGE_PORT = "agent_bridge_port"
    const val PREFS_KEY_TERMUX_SSH_PORT = "termux_ssh_port"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _state = MutableStateFlow(AgentBridgeState())
    val state: StateFlow<AgentBridgeState> = _state.asStateFlow()
    val bridgeState: StateFlow<AgentBridgeState> get() = state

    fun isPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun start(context: Context, bridgePort: Int = DEFAULT_BRIDGE_PORT, termuxSshPort: Int = DEFAULT_TERMUX_SSH_PORT): Boolean {
        stop()

        if (bridgePort !in 1024..65535) {
            _state.value = AgentBridgeState(
                status = AgentBridgeStatus.ERROR,
                bridgePort = bridgePort,
                termuxSshPort = termuxSshPort,
                errorMessage = "端口号无效（范围须在 1024-65535）"
            )
            return false
        }

        if (!isPortAvailable(bridgePort)) {
            _state.value = AgentBridgeState(
                status = AgentBridgeStatus.PORT_OCCUPIED,
                bridgePort = bridgePort,
                termuxSshPort = termuxSshPort,
                errorMessage = "本地网关端口 $bridgePort 已被占用，请自定义更换为其他端口"
            )
            return false
        }

        _state.value = AgentBridgeState(
            status = AgentBridgeStatus.STARTING,
            bridgePort = bridgePort,
            termuxSshPort = termuxSshPort
        )

        return try {
            val server = ServerSocket(bridgePort, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            val appContext = context.applicationContext

            serverJob = scope.launch {
                _state.value = AgentBridgeState(
                    status = AgentBridgeStatus.RUNNING,
                    bridgePort = bridgePort,
                    termuxSshPort = termuxSshPort
                )

                while (isActive && !server.isClosed) {
                    try {
                        val client = server.accept()
                        launch { handleClient(appContext, client, bridgePort, termuxSshPort) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            _state.value = AgentBridgeState(
                status = AgentBridgeStatus.ERROR,
                bridgePort = bridgePort,
                termuxSshPort = termuxSshPort,
                errorMessage = "启动网关失败: ${e.localizedMessage}"
            )
            false
        }
    }

    @Synchronized
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        _state.value = _state.value.copy(status = AgentBridgeStatus.STOPPED, errorMessage = null)
    }

    private suspend fun handleClient(
        context: Context,
        socket: Socket,
        bridgePort: Int,
        termuxSshPort: Int
    ) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return@use
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                // Read headers
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // Read body if any
                val body = if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val count = reader.read(charArray, readTotal, contentLength - readTotal)
                        if (count < 0) break
                        readTotal += count
                    }
                    String(charArray, 0, readTotal)
                } else ""

                val response = handleRoute(context, method, path, body, bridgePort, termuxSshPort)
                val writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("HTTP/1.1 ${response.statusCode} ${response.statusText}\r\n")
                writer.write("Content-Type: ${response.contentType}\r\n")
                writer.write("Content-Length: ${response.body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(response.body)
                writer.flush()
            } catch (_: Exception) {}
        }
    }

    private data class HttpResponse(
        val statusCode: Int = 200,
        val statusText: String = "OK",
        val contentType: String = "application/json; charset=utf-8",
        val body: String
    )

    private suspend fun handleRoute(
        context: Context,
        method: String,
        path: String,
        body: String,
        bridgePort: Int,
        termuxSshPort: Int
    ): HttpResponse {
        val cleanPath = path.substringBefore("?")
        return when {
            cleanPath == "/api/status" -> {
                val json = JSONObject().apply {
                    put("app", "QuickSSH")
                    put("status", "running")
                    put("bridgePort", bridgePort)
                    put("termuxSshPort", termuxSshPort)
                    put("timestamp", System.currentTimeMillis())
                }
                HttpResponse(body = json.toString())
            }

            cleanPath == "/api/configs" -> {
                val db = AppDatabase.getDatabase(context)
                val configs = db.sshConfigDao().getAllConfigs()
                val array = JSONArray()
                configs.forEach { config ->
                    array.put(
                        JSONObject().apply {
                            put("id", config.id)
                            put("name", config.name)
                            put("host", config.host)
                            put("port", config.port)
                            put("username", config.username)
                            put("authType", config.authType)
                            put("isLocal", config.isLocalSession)
                            put("preConnectTunnelPresetId", config.preConnectTunnelPresetId ?: JSONObject.NULL)
                        }
                    )
                }
                HttpResponse(body = array.toString())
            }

            cleanPath == "/api/devices" -> {
                val db = AppDatabase.getDatabase(context)
                val configs = db.sshConfigDao().getAllConfigs()
                val devices = SshHostDiscoveryHelper.aggregateHosts(configs)
                val json = SshHostDiscoveryHelper.toJson(devices)
                HttpResponse(body = json)
            }

            cleanPath == "/api/terminal/exec" -> {
                val cmd = if (body.startsWith("{")) {
                    runCatching { JSONObject(body).optString("command", "") }.getOrDefault("")
                } else {
                    body.trim()
                }

                if (cmd.isBlank()) {
                    HttpResponse(statusCode = 400, statusText = "Bad Request", body = """{"error":"Missing command"}""")
                } else {
                    val result = executeLocalCommand(cmd)
                    HttpResponse(body = result.toString())
                }
            }

            cleanPath == "/install.sh" -> {
                val script = generateTermuxInstallerScript(bridgePort, termuxSshPort)
                HttpResponse(
                    contentType = "text/x-shellscript; charset=utf-8",
                    body = script
                )
            }

            else -> HttpResponse(
                statusCode = 404,
                statusText = "Not Found",
                body = """{"error":"Endpoint not found: $cleanPath"}"""
            )
        }
    }

    private fun executeLocalCommand(command: String): JSONObject {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val exitCode = process.waitFor()
            JSONObject().apply {
                put("command", command)
                put("exitCode", exitCode)
                put("output", output)
                put("success", exitCode == 0)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("command", command)
                put("exitCode", -1)
                put("output", e.localizedMessage ?: "Execution failed")
                put("success", false)
            }
        }
    }

    fun generateTermuxInstallerScript(bridgePort: Int, termuxSshPort: Int): String {
        return """
            #!/data/data/com.termux/files/usr/bin/bash
            # QuickSSH <-> Termux Agent Bridge Auto-Configurator
            set -e
            echo "[QuickSSH] Initializing Agent Bridge Integration..."

            # 1. Ensure OpenSSH and utilities are installed in Termux
            if ! command -v sshd >/dev/null 2>&1; then
                echo "[QuickSSH] Installing openssh in Termux..."
                pkg install -y openssh curl jq
            fi

            # 2. Start Termux sshd on the configured port
            pkill -f "sshd -p $termuxSshPort" 2>/dev/null || true
            sshd -p $termuxSshPort
            echo "[QuickSSH] Termux OpenSSH daemon running on 127.0.0.1:$termuxSshPort"

            # 3. Install the 'quickssh' CLI command into Termux PATH
            mkdir -p "${'$'}PREFIX/bin"
            cat << 'EOF' > "${'$'}PREFIX/bin/quickssh"
            #!/data/data/com.termux/files/usr/bin/bash
            # QuickSSH CLI helper for Termux Agents
            BRIDGE="http://127.0.0.1:$bridgePort"
            case "${'$'}1" in
                status)
                    curl -s "${'$'}BRIDGE/api/status" | jq . || curl -s "${'$'}BRIDGE/api/status"
                    ;;
                configs)
                    curl -s "${'$'}BRIDGE/api/configs" | jq . || curl -s "${'$'}BRIDGE/api/configs"
                    ;;
                devices)
                    curl -s "${'$'}BRIDGE/api/devices" | jq . || curl -s "${'$'}BRIDGE/api/devices"
                    ;;
                exec)
                    shift
                    curl -s -X POST -d "${'$'}*" "${'$'}BRIDGE/api/terminal/exec" | jq -r '.output // .'
                    ;;
                *)
                    echo "QuickSSH Agent Bridge CLI"
                    echo "Usage: quickssh {status|configs|devices|exec <command>}"
                    ;;
            esac
            EOF
            chmod +x "${'$'}PREFIX/bin/quickssh"
            echo "[QuickSSH] Successfully configured 'quickssh' CLI in Termux!"
            echo "[QuickSSH] Both environments are now interconnected."
        """.trimIndent()
    }
}
