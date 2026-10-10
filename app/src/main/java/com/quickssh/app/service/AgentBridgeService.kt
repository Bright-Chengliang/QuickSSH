package com.quickssh.app.service

import android.content.Context
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.data.PERSISTENT_SESSION_NONE
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshConfigBackupCodec
import com.quickssh.app.data.SshConfigBackupRecord
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.SshTunnelPresetBackupRecord
import com.quickssh.app.data.tunnelPresetDefaultName
import com.quickssh.app.security.KeystoreManager
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
import java.net.URLDecoder
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
 * Enables AI Agents, CLI scripts, and remote terminals to seamlessly
 * view, export, import, create, and manage QuickSSH connection configs.
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
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val rawPath = parts.getOrNull(1) ?: "/"

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

                val response = handleRoute(context, method, rawPath, body, bridgePort, termuxSshPort)
                val writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("HTTP/1.1 ${response.statusCode} ${response.statusText}\r\n")
                writer.write("Content-Type: ${response.contentType}\r\n")
                writer.write("Access-Control-Allow-Origin: *\r\n")
                writer.write("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
                writer.write("Access-Control-Allow-Headers: *\r\n")
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
        rawPath: String,
        body: String,
        bridgePort: Int,
        termuxSshPort: Int
    ): HttpResponse {
        if (method == "OPTIONS") {
            return HttpResponse(body = "")
        }

        val cleanPath = rawPath.substringBefore("?")
        val queryString = rawPath.substringAfter("?", "")
        val queryParams = parseQueryString(queryString)

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

            cleanPath == "/api/configs" && method == "GET" -> {
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
                            put("workDirectory", config.workDirectory ?: "")
                            put("terminalTerm", config.terminalTerm)
                            put("persistentSessionMode", config.persistentSessionMode)
                            put("preConnectTunnelPresetId", config.preConnectTunnelPresetId ?: JSONObject.NULL)
                        }
                    )
                }
                HttpResponse(body = array.toString())
            }

            cleanPath == "/api/configs/export" && method == "GET" -> {
                val json = exportAllConfigsJson(context)
                HttpResponse(body = json)
            }

            (cleanPath == "/api/configs/import" && method == "POST") || (cleanPath == "/api/configs" && method == "PUT") -> {
                val importResult = importConfigsFromJson(context, body)
                HttpResponse(
                    statusCode = if (importResult.optBoolean("success", false)) 200 else 400,
                    body = importResult.toString()
                )
            }

            (cleanPath == "/api/configs" || cleanPath == "/api/configs/add") && method == "POST" -> {
                val addResult = addOrUpdateSingleConfig(context, body)
                HttpResponse(
                    statusCode = if (addResult.optBoolean("success", false)) 200 else 400,
                    body = addResult.toString()
                )
            }

            (cleanPath == "/api/configs/delete" && method == "POST") || (cleanPath.startsWith("/api/configs") && method == "DELETE") -> {
                val idStr = queryParams["id"] ?: runCatching { JSONObject(body).optString("id", "") }.getOrDefault("")
                val id = idStr.toLongOrNull()
                if (id == null || id <= 0) {
                    HttpResponse(statusCode = 400, statusText = "Bad Request", body = """{"error":"Missing or invalid id"}""")
                } else {
                    val deleteResult = deleteConfigById(context, id)
                    HttpResponse(body = deleteResult.toString())
                }
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

    private suspend fun exportAllConfigsJson(context: Context): String = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.sshConfigDao()
        val tunnelPresetsByWorkspace = dao.getAllTunnelPresets().groupBy { it.workspaceId }
        val configs = dao.getAllConfigs().filterNot { it.isLocalSession || it.authType == AUTH_TYPE_LOCAL }

        val records = configs.map { config ->
            val preConnectPresetName = config.preConnectTunnelPresetId?.let { id ->
                tunnelPresetsByWorkspace[config.id]?.firstOrNull { it.id == id }?.name
            }
            SshConfigBackupRecord(
                name = config.name,
                host = config.host,
                port = config.port,
                username = config.username,
                authType = config.authType,
                password = decryptSecret(config.encryptedPassword),
                privateKey = decryptSecret(config.encryptedPrivateKey),
                workDirectory = config.workDirectory,
                postConnectCommand = config.postConnectCommand,
                terminalFontSizeSp = config.terminalFontSizeSp,
                terminalWrapEnabled = config.terminalWrapEnabled,
                terminalTerm = config.terminalTerm,
                terminalShortcuts = config.terminalShortcuts,
                persistentSessionMode = config.persistentSessionMode,
                preConnectTunnelPresetName = preConnectPresetName,
                serverDisplayName = config.serverDisplayName,
                tunnelPresets = tunnelPresetsByWorkspace[config.id].orEmpty().map { preset ->
                    SshTunnelPresetBackupRecord(
                        name = preset.name,
                        note = preset.note,
                        remoteHost = preset.remoteHost,
                        remotePort = preset.remotePort,
                        localPort = preset.localPort
                    )
                }
            )
        }
        SshConfigBackupCodec.encode(records, password = null)
    }

    private suspend fun importConfigsFromJson(context: Context, jsonText: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            val trimmed = jsonText.trim()
            val finalJson = when {
                trimmed.startsWith("{") && !trimmed.contains("\"servers\"") && trimmed.contains("\"host\"") -> {
                    // Single server JSON object
                    """{"app":"QuickSSH","formatVersion":2,"servers":[$trimmed]}"""
                }
                trimmed.startsWith("[") -> {
                    // Array of server JSON objects
                    """{"app":"QuickSSH","formatVersion":2,"servers":$trimmed}"""
                }
                else -> trimmed
            }

            val records = SshConfigBackupCodec.decode(finalJson, password = null)
            val db = AppDatabase.getDatabase(context)
            val dao = db.sshConfigDao()
            val existingByKey = dao.getAllConfigs()
                .associateBy { SshConfigBackupCodec.mergeKey(it) }
                .toMutableMap()

            var importedCount = 0
            records.forEach { record ->
                val key = SshConfigBackupCodec.mergeKey(record)
                val existing = existingByKey[key]
                val configToSave = recordToSshConfig(record, System.currentTimeMillis()).let {
                    if (existing != null) it.copy(id = existing.id) else it
                }

                val savedId = if (existing == null) {
                    dao.insertConfig(configToSave)
                } else {
                    dao.updateConfig(configToSave)
                    configToSave.id
                }

                if (record.tunnelPresets.isNotEmpty()) {
                    record.tunnelPresets.forEach { presetRecord ->
                        val remoteHost = presetRecord.remoteHost.trim().ifBlank { "127.0.0.1" }
                        val remotePort = presetRecord.remotePort.coerceIn(1, 65535)
                        val localPort = presetRecord.localPort.coerceIn(0, 65535)
                        val presetName = presetRecord.name.trim().ifBlank { tunnelPresetDefaultName(remoteHost, remotePort) }
                        val existingPreset = dao.findTunnelPreset(savedId, presetName, remoteHost, remotePort, localPort)
                        val preset = SshTunnelPreset(
                            id = existingPreset?.id ?: 0L,
                            workspaceId = savedId,
                            name = presetName,
                            note = presetRecord.note?.trim(),
                            remoteHost = remoteHost,
                            remotePort = remotePort,
                            localPort = localPort,
                            updateTime = System.currentTimeMillis()
                        )
                        if (existingPreset == null) {
                            dao.insertTunnelPreset(preset)
                        } else {
                            dao.updateTunnelPreset(preset)
                        }
                    }
                }
                importedCount++
            }

            JSONObject().apply {
                put("success", true)
                put("importedCount", importedCount)
                put("message", "Successfully imported $importedCount server configuration(s)")
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.localizedMessage ?: "Failed to import configurations")
            }
        }
    }

    private suspend fun addOrUpdateSingleConfig(context: Context, jsonText: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonText.trim())
            val host = root.optString("host", "").trim()
            if (host.isBlank()) {
                return@withContext JSONObject().apply {
                    put("success", false)
                    put("error", "Host is required")
                }
            }

            val username = root.optString("username", "root").trim()
            val port = root.optInt("port", 22).coerceIn(1, 65535)
            val name = root.optString("name", "$username@$host:$port").trim()
            val authType = root.optString("authType", "PASSWORD").uppercase()
            val password = root.optString("password", "").takeIf { it.isNotEmpty() }
            val privateKey = root.optString("privateKey", "").takeIf { it.isNotBlank() }
            val workDirectory = root.optString("workDirectory", "").takeIf { it.isNotBlank() }
            val postConnectCommand = root.optString("postConnectCommand", "").takeIf { it.isNotBlank() }
            val id = root.optLong("id", 0L)

            val db = AppDatabase.getDatabase(context)
            val dao = db.sshConfigDao()
            val config = SshConfig(
                id = id,
                name = name,
                host = host,
                port = port,
                username = username,
                authType = authType,
                encryptedPassword = password?.let(KeystoreManager::encrypt),
                encryptedPrivateKey = privateKey?.let(KeystoreManager::encrypt),
                workDirectory = workDirectory,
                postConnectCommand = postConnectCommand,
                updateTime = System.currentTimeMillis()
            )

            val savedId = if (id > 0L && dao.getConfigById(id) != null) {
                dao.updateConfig(config)
                id
            } else {
                dao.insertConfig(config)
            }

            JSONObject().apply {
                put("success", true)
                put("id", savedId)
                put("name", name)
                put("message", "Configuration saved successfully")
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.localizedMessage ?: "Failed to save configuration")
            }
        }
    }

    private suspend fun deleteConfigById(context: Context, id: Long): JSONObject = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.sshConfigDao()
            val config = dao.getConfigById(id)
            if (config == null) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Config with id $id not found")
                }
            } else {
                dao.deleteConfig(config)
                JSONObject().apply {
                    put("success", true)
                    put("id", id)
                    put("message", "Config '${config.name}' deleted successfully")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.localizedMessage ?: "Failed to delete config")
            }
        }
    }

    private fun recordToSshConfig(record: SshConfigBackupRecord, updateTime: Long): SshConfig {
        val normalizedAuth = if (record.authType.equals("PRIVATE_KEY", ignoreCase = true)) "PRIVATE_KEY" else "PASSWORD"
        return SshConfig(
            name = record.name,
            host = record.host,
            port = record.port,
            username = record.username,
            authType = normalizedAuth,
            encryptedPassword = record.password?.takeIf { it.isNotEmpty() }?.let(KeystoreManager::encrypt),
            encryptedPrivateKey = record.privateKey?.takeIf { it.isNotBlank() }?.let(KeystoreManager::encrypt),
            workDirectory = record.workDirectory,
            postConnectCommand = record.postConnectCommand,
            terminalFontSizeSp = record.terminalFontSizeSp,
            terminalWrapEnabled = record.terminalWrapEnabled,
            terminalTerm = record.terminalTerm.ifBlank { "xterm-256color" },
            terminalShortcuts = record.terminalShortcuts,
            persistentSessionMode = record.persistentSessionMode.ifBlank { PERSISTENT_SESSION_NONE },
            serverDisplayName = record.serverDisplayName,
            updateTime = updateTime
        )
    }

    private fun decryptSecret(encrypted: String?): String? {
        val value = encrypted?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { KeystoreManager.decrypt(value).takeIf { it.isNotEmpty() } }.getOrNull()
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            val key = parts.getOrNull(0)?.trim()?.let { URLDecoder.decode(it, "UTF-8") } ?: return@mapNotNull null
            val value = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
            key to value
        }.toMap()
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
        val d = "$"
        return """
            #!/data/data/com.termux/files/usr/bin/bash
            # QuickSSH <-> Termux Agent Bridge & Config Manager CLI
            set -e
            echo "[QuickSSH] Initializing Agent Bridge Integration & CLI..."

            # 1. Ensure curl and jq are installed in Termux
            if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
                echo "[QuickSSH] Installing dependencies (curl, jq, openssh)..."
                pkg install -y curl jq openssh
            fi

            # 2. Start Termux sshd if configured
            if [ "$termuxSshPort" -gt 0 ]; then
                pkill -f "sshd -p $termuxSshPort" 2>/dev/null || true
                sshd -p $termuxSshPort 2>/dev/null || true
                echo "[QuickSSH] Termux OpenSSH daemon running on 127.0.0.1:$termuxSshPort"
            fi

            # 3. Install the 'quickssh' CLI command into Termux PATH
            mkdir -p "${d}PREFIX/bin"
            cat << 'EOF' > "${d}PREFIX/bin/quickssh"
            #!/data/data/com.termux/files/usr/bin/bash
            # QuickSSH CLI - Control and configure QuickSSH directly from CLI / SSH
            BRIDGE="http://127.0.0.1:$bridgePort"

            show_help() {
                echo "QuickSSH Terminal & Config Manager CLI"
                echo "Usage: quickssh {status|configs|devices|exec <command>}"
                echo ""
                echo "Usage:"
                echo "  quickssh list                   List all SSH configurations"
                echo "  quickssh export [file.json]     Export full configuration JSON to stdout or file"
                echo "  quickssh import <file.json|->   Import configuration JSON from file or stdin"
                echo "  quickssh add [options]          Add or update a server connection"
                echo "  quickssh delete <id>            Delete a server configuration by ID"
                echo "  quickssh status                 Check bridge service status"
                echo "  quickssh exec <command>         Execute command inside QuickSSH sandbox"
                echo ""
                echo "Add Options:"
                echo "  --name <name>        Connection display name"
                echo "  --host <ip/domain>   Server host or IP address"
                echo "  --port <port>        SSH port (default: 22)"
                echo "  --user <username>    SSH username (default: root)"
                echo "  --password <pwd>     SSH password"
                echo "  --key <private_key>  SSH private key"
                echo "  --workdir <dir>      Initial working directory"
                echo ""
                echo "Remote / One-liner Examples (from your PC):"
                echo "  ssh phone 'quickssh export' > backup.json"
                echo "  cat backup.json | ssh phone 'quickssh import -'"
                echo "  ssh phone 'quickssh add --name DevServer --host 192.168.1.100 --user admin --password secret'"
            }

            case "${d}1" in
                status)
                    curl -s "${d}BRIDGE/api/status" | jq . 2>/dev/null || curl -s "${d}BRIDGE/api/status"
                    ;;
                list|configs)
                    DATA=${d}(curl -s "${d}BRIDGE/api/configs")
                    if command -v jq >/dev/null 2>&1; then
                        echo "${d}DATA" | jq -r '["ID", "NAME", "HOST:PORT", "USER", "AUTH"], ["--", "----", "---------", "----", "----"], (.[] | [.id, .name, "\(.host):\(.port)", .username, .authType]) | @tsv' | column -t -s "${d}\t" 2>/dev/null || echo "${d}DATA" | jq .
                    else
                        echo "${d}DATA"
                    fi
                    ;;
                export)
                    OUT_FILE="${d}2"
                    if [ -n "${d}OUT_FILE" ]; then
                        curl -s "${d}BRIDGE/api/configs/export" > "${d}OUT_FILE"
                        echo "[OK] Exported configurations to ${d}OUT_FILE"
                    else
                        curl -s "${d}BRIDGE/api/configs/export"
                    fi
                    ;;
                import)
                    IN_FILE="${d}2"
                    if [ -z "${d}IN_FILE" ]; then
                        echo "Error: Please specify JSON file to import, or '-' for stdin."
                        exit 1
                    fi
                    if [ "${d}IN_FILE" = "-" ]; then
                        JSON_PAYLOAD=${d}(cat)
                    elif [ -f "${d}IN_FILE" ]; then
                        JSON_PAYLOAD=${d}(cat "${d}IN_FILE")
                    else
                        echo "Error: File not found: ${d}IN_FILE"
                        exit 1
                    fi
                    curl -s -X POST -H "Content-Type: application/json" -d "${d}JSON_PAYLOAD" "${d}BRIDGE/api/configs/import" | jq . 2>/dev/null || curl -s -X POST -H "Content-Type: application/json" -d "${d}JSON_PAYLOAD" "${d}BRIDGE/api/configs/import"
                    ;;
                add)
                    shift
                    NAME=""
                    HOST=""
                    PORT=22
                    USER="root"
                    PASS=""
                    KEY=""
                    WORKDIR=""
                    while [ ${d}# -gt 0 ]; do
                        case "${d}1" in
                            --name) NAME="${d}2"; shift 2 ;;
                            --host) HOST="${d}2"; shift 2 ;;
                            --port) PORT="${d}2"; shift 2 ;;
                            --user) USER="${d}2"; shift 2 ;;
                            --password) PASS="${d}2"; shift 2 ;;
                            --key) KEY="${d}2"; shift 2 ;;
                            --workdir) WORKDIR="${d}2"; shift 2 ;;
                            *) shift ;;
                        esac
                    done
                    if [ -z "${d}HOST" ]; then
                        echo "Error: --host is required"
                        exit 1
                    fi
                    [ -z "${d}NAME" ] && NAME="${d}USER@${d}HOST:${d}PORT"
                    AUTH="PASSWORD"
                    [ -n "${d}KEY" ] && AUTH="PRIVATE_KEY"

                    PAYLOAD=${d}(jq -n \
                        --arg n "${d}NAME" \
                        --arg h "${d}HOST" \
                        --argjson p "${d}PORT" \
                        --arg u "${d}USER" \
                        --arg a "${d}AUTH" \
                        --arg pwd "${d}PASS" \
                        --arg k "${d}KEY" \
                        --arg w "${d}WORKDIR" \
                        '{name: ${d}n, host: ${d}h, port: ${d}p, username: ${d}u, authType: ${d}a, password: (if ${d}pwd=="" then null else ${d}pwd end), privateKey: (if ${d}k=="" then null else ${d}k end), workDirectory: (if ${d}w=="" then null else ${d}w end)}')

                    curl -s -X POST -H "Content-Type: application/json" -d "${d}PAYLOAD" "${d}BRIDGE/api/configs/add" | jq . 2>/dev/null || curl -s -X POST -H "Content-Type: application/json" -d "${d}PAYLOAD" "${d}BRIDGE/api/configs/add"
                    ;;
                delete|rm)
                    ID="${d}2"
                    if [ -z "${d}ID" ]; then
                        echo "Error: Please specify ID to delete (e.g. quickssh delete 1)"
                        exit 1
                    fi
                    curl -s -X POST "${d}BRIDGE/api/configs/delete?id=${d}ID" | jq . 2>/dev/null || curl -s -X POST "${d}BRIDGE/api/configs/delete?id=${d}ID"
                    ;;
                devices)
                    curl -s "${d}BRIDGE/api/devices" | jq . 2>/dev/null || curl -s "${d}BRIDGE/api/devices"
                    ;;
                exec)
                    shift
                    curl -s -X POST -d "${d}*" "${d}BRIDGE/api/terminal/exec" | jq -r '.output // .' 2>/dev/null || curl -s -X POST -d "${d}*" "${d}BRIDGE/api/terminal/exec"
                    ;;
                help|--help|-h)
                    show_help
                    ;;
                *)
                    show_help
                    ;;
            esac
EOF
            chmod +x "${d}PREFIX/bin/quickssh"
            echo "[QuickSSH] Successfully configured 'quickssh' CLI in Termux!"
            echo "[QuickSSH] You can now run 'quickssh list', 'quickssh export', 'quickssh import', 'quickssh add' directly."
        """.trimIndent()
    }
}

