package com.quickssh.app.service

import android.content.Context
import androidx.room.withTransaction
import com.quickssh.app.BuildConfig
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.data.InMemoryWorkspaceStore
import com.quickssh.app.data.PERSISTENT_SESSION_NONE
import com.quickssh.app.data.RoomWorkspaceStore
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshConfigBackupCodec
import com.quickssh.app.data.SshConfigBackupRecord
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.SshTunnelPresetBackupRecord
import com.quickssh.app.data.snapshot
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
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

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
 * Enables AI Agents, CLI scripts, and remote terminals to view and manage QuickSSH
 * configuration: the structured `/api/v2` workspace API ([WorkspaceConfigApi]) plus the
 * legacy flat `/api/configs` endpoints. Every route except `/api/status` requires the bridge
 * token, because the bridge can export stored credentials and run commands in the app sandbox,
 * and any app or web page on the device can reach 127.0.0.1.
 */
object AgentBridgeService {
    const val DEFAULT_BRIDGE_PORT = 8024
    const val DEFAULT_TERMUX_SSH_PORT = 8023
    const val PREFS_KEY_BRIDGE_ENABLED = "agent_bridge_enabled"
    const val PREFS_KEY_BRIDGE_PORT = "agent_bridge_port"
    const val PREFS_KEY_TERMUX_SSH_PORT = "termux_ssh_port"
    const val PREFS_KEY_BRIDGE_TOKEN = "agent_bridge_token"
    private const val MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences(KnownHostsVerifier.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    /** Bridge port saved in Settings (falls back to the default when never configured). */
    fun configuredBridgePort(context: Context): Int =
        settings(context).getInt(PREFS_KEY_BRIDGE_PORT, DEFAULT_BRIDGE_PORT)

    fun isBridgeEnabled(context: Context): Boolean =
        settings(context).getBoolean(PREFS_KEY_BRIDGE_ENABLED, false)

    /** Bearer token clients must present; generated on first use. */
    @Synchronized
    fun bridgeToken(context: Context): String =
        settings(context).getString(PREFS_KEY_BRIDGE_TOKEN, null)?.takeIf { it.length >= 32 }
            ?: regenerateBridgeToken(context)

    /** Replaces the token (revoking every installed client) and refreshes the in-app CLI env file. */
    @Synchronized
    fun regenerateBridgeToken(context: Context): String {
        val token = newToken()
        settings(context).edit().putString(PREFS_KEY_BRIDGE_TOKEN, token).commit()
        runCatching { AgentToolkit.writeLocalBridgeEnv(context, configuredBridgePort(context), token) }
        return token
    }

    internal fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Accepts `Authorization: Bearer <t>`, `X-QuickSSH-Token: <t>` or `?token=<t>`, compared in constant time. */
    internal fun isAuthorized(request: BridgeRequest, expectedToken: String): Boolean {
        if (expectedToken.isEmpty()) return false
        val presented = request.headers["authorization"]?.trim()
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
            ?: request.headers["x-quickssh-token"]?.trim()
            ?: parseQueryString(request.rawPath.substringAfter("?", ""))["token"]
            ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8)
        )
    }

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
        val current = _state.value
        if (serverSocket?.isClosed == false && current.status == AgentBridgeStatus.RUNNING &&
            current.bridgePort == bridgePort && current.termuxSshPort == termuxSshPort
        ) {
            return true
        }
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

        _state.value = AgentBridgeState(
            status = AgentBridgeStatus.STARTING,
            bridgePort = bridgePort,
            termuxSshPort = termuxSshPort
        )

        return try {
            // Bind directly instead of probing with isPortAvailable(): after a restart (e.g. Activity
            // recreation calls stop() then start()) the connections this bridge just served linger in
            // TIME_WAIT, which fails a reuseAddress=false probe even though the port is free. SO_REUSEADDR
            // still refuses a port another process is actively listening on.
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), bridgePort), 10)
                }
            } catch (_: BindException) {
                _state.value = AgentBridgeState(
                    status = AgentBridgeStatus.PORT_OCCUPIED,
                    bridgePort = bridgePort,
                    termuxSshPort = termuxSshPort,
                    errorMessage = "本地网关端口 $bridgePort 已被占用，请自定义更换为其他端口"
                )
                return false
            }
            serverSocket = server
            val appContext = context.applicationContext
            // Keep the in-app CLI pointed at the port actually served.
            runCatching { AgentToolkit.writeLocalBridgeEnv(appContext, bridgePort, bridgeToken(appContext)) }

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
                val request = readRequest(BufferedInputStream(s.getInputStream())) ?: return@use
                val response = handleRoute(context, request, bridgePort, termuxSshPort)
                val writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("HTTP/1.1 ${response.statusCode} ${response.statusText}\r\n")
                // No CORS headers on purpose: browser pages must not read bridge responses.
                writer.write("Content-Type: ${response.contentType}\r\n")
                // JSON ends with a newline so CLI output does not run into the shell prompt.
                val payload = if (response.contentType.startsWith("application/json") && response.body.isNotEmpty() &&
                    !response.body.endsWith("\n")
                ) {
                    response.body + "\n"
                } else {
                    response.body
                }
                writer.write("Content-Length: ${payload.toByteArray(StandardCharsets.UTF_8).size}\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(payload)
                writer.flush()
            } catch (_: Exception) {}
        }
    }

    internal data class BridgeRequest(
        val method: String,
        val rawPath: String,
        val body: String,
        /** Header names are lower-cased. */
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * Parses one HTTP/1.x request from raw bytes. Content-Length counts bytes, so the body must be
     * read as bytes and decoded afterwards; reading it as chars over-reads any multi-byte UTF-8 body
     * and blocks until the client gives up.
     */
    internal fun readRequest(input: InputStream): BridgeRequest? {
        val requestLine = readHeaderLine(input) ?: return null
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0)?.uppercase() ?: "GET"
        val rawPath = parts.getOrNull(1) ?: "/"

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readHeaderLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_REQUEST_BODY_BYTES)

        val bytes = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val count = input.read(bytes, readTotal, contentLength - readTotal)
            if (count < 0) break
            readTotal += count
        }
        return BridgeRequest(method, rawPath, String(bytes, 0, readTotal, StandardCharsets.UTF_8), headers)
    }

    private fun readHeaderLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (line.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            line.write(b)
        }
        return line.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
    }

    internal data class HttpResponse(
        val statusCode: Int = 200,
        val statusText: String = "OK",
        val contentType: String = "application/json; charset=utf-8",
        val body: String
    )

    internal suspend fun handleRoute(
        context: Context,
        request: BridgeRequest,
        bridgePort: Int,
        termuxSshPort: Int
    ): HttpResponse {
        val method = request.method
        val body = request.body
        val cleanPath = request.rawPath.substringBefore("?")
        val queryParams = parseQueryString(request.rawPath.substringAfter("?", ""))

        if (cleanPath == "/api/status") {
            val json = JSONObject().apply {
                put("app", "QuickSSH")
                put("status", "running")
                put("apiVersion", 2)
                put("authRequired", true)
                put("mcp", "/mcp")
                put("bridgePort", bridgePort)
                put("termuxSshPort", termuxSshPort)
                put("timestamp", System.currentTimeMillis())
            }
            return HttpResponse(body = json.toString())
        }

        val token = runCatching { bridgeToken(context) }.getOrDefault("")
        if (!isAuthorized(request, token)) {
            return HttpResponse(
                statusCode = 401,
                statusText = "Unauthorized",
                body = JSONObject()
                    .put("ok", false)
                    .put("error", "Unauthorized: send the bridge token as 'Authorization: Bearer <token>' (QuickSSH Settings > Termux & Agent gateway)")
                    .toString()
            )
        }

        return when {
            cleanPath == "/mcp" -> handleMcp(context, request)

            cleanPath.startsWith("/api/v2/") ->
                handleWorkspaceApi(context, method, cleanPath.removePrefix("/api/v2/"), queryParams, body)

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

            cleanPath == "/api/devices" -> HttpResponse(body = devicesJson(context))

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
                val cli = AgentToolkit.cliScript(
                    template = AgentToolkit.cliTemplate(context),
                    shebang = AgentToolkit.TERMUX_SHELL,
                    // Absolute, not $HOME: agents often run the CLI inside proot-distro, where HOME is /root.
                    envFile = "${LocalEnvironmentDetector.TERMUX_HOME}/${AgentToolkit.ENV_HOME_PATH}",
                    bridgePort = bridgePort
                )
                val script = generateTermuxInstallerScript(bridgePort, termuxSshPort, token, cli)
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

    /**
     * `/api/v2` workspace API. Responses are always HTTP 200 with `{"ok":true,"data":...}` or
     * `{"ok":false,"error":...}` so wget-based clients (which drop non-2xx bodies) still see errors.
     * Writes run in one Room transaction; `apply?dryRun=1` runs on an in-memory copy instead.
     */
    private suspend fun handleWorkspaceApi(
        context: Context,
        method: String,
        path: String,
        queryParams: Map<String, String>,
        body: String
    ): HttpResponse = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        fun api(store: com.quickssh.app.data.WorkspaceStore) =
            WorkspaceConfigApi(store, KeystoreManager::encrypt, KeystoreManager::decrypt)

        fun flag(name: String) = queryParams[name]?.lowercase() in setOf("1", "true", "yes")

        try {
            if (path == "skill" && method == "GET") {
                return@withContext HttpResponse(
                    contentType = "text/markdown; charset=utf-8",
                    body = AgentToolkit.skillMarkdown(context)
                )
            }
            val data: JSONObject = when {
                path == "tree" && method == "GET" -> api(RoomWorkspaceStore(db.sshConfigDao())).tree()
                path == "export" && method == "GET" -> api(RoomWorkspaceStore(db.sshConfigDao())).exportDocument()
                path == "apply" && method == "POST" -> {
                    val document = applyDocument(parseJsonObject(body))
                    val prune = flag("prune")
                    if (flag("dryRun")) {
                        val copy = InMemoryWorkspaceStore(RoomWorkspaceStore(db.sshConfigDao()).snapshot())
                        api(copy).apply(document, prune).put("dryRun", true)
                    } else {
                        db.withTransaction { api(RoomWorkspaceStore(db.sshConfigDao())).apply(document, prune) }
                            .put("dryRun", false)
                    }
                }
                method == "POST" && path.count { it == '/' } == 1 -> {
                    val request = parseJsonObject(body)
                    db.withTransaction { api(RoomWorkspaceStore(db.sshConfigDao())).call(path, request) }
                }
                else -> throw WorkspaceApiException(
                    "Unknown endpoint $method /api/v2/$path. GET tree|export|skill, POST apply or <resource>/<action>"
                )
            }
            HttpResponse(body = JSONObject().put("ok", true).put("data", data).toString(2))
        } catch (e: WorkspaceApiException) {
            HttpResponse(body = JSONObject().put("ok", false).put("error", e.message).toString())
        } catch (e: Exception) {
            HttpResponse(
                body = JSONObject().put("ok", false)
                    .put("error", "${e.javaClass.simpleName}: ${e.localizedMessage ?: "request failed"}").toString()
            )
        }
    }

    /**
     * MCP Streamable HTTP endpoint ([AgentMcpHandler]). Stateless and JSON-only, so GET (the optional
     * server-to-client SSE stream) is refused with 405. Browser origins other than loopback are refused
     * as DNS-rebinding protection, on top of the bearer token checked by [handleRoute].
     */
    private suspend fun handleMcp(context: Context, request: BridgeRequest): HttpResponse {
        val origin = request.headers["origin"]
        if (origin != null && !isLoopbackOrigin(origin)) {
            return HttpResponse(statusCode = 403, statusText = "Forbidden", body = """{"error":"Origin not allowed"}""")
        }
        if (request.method != "POST") {
            return HttpResponse(statusCode = 405, statusText = "Method Not Allowed", body = """{"error":"POST JSON-RPC messages to /mcp"}""")
        }
        val handler = AgentMcpHandler(
            serverVersion = BuildConfig.VERSION_NAME,
            workspaceApi = { method, path, query, body -> handleWorkspaceApi(context, method, path, query, body).body },
            devices = { devicesJson(context) }
        )
        val reply = handler.handle(request.body)
            ?: return HttpResponse(statusCode = 202, statusText = "Accepted", body = "")
        return HttpResponse(body = reply)
    }

    internal fun isLoopbackOrigin(origin: String): Boolean {
        val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }

    private suspend fun devicesJson(context: Context): String = withContext(Dispatchers.IO) {
        val configs = AppDatabase.getDatabase(context).sshConfigDao().getAllConfigs()
        SshHostDiscoveryHelper.toJson(SshHostDiscoveryHelper.aggregateHosts(configs))
    }

    /** `quickssh export file` saves the whole {"ok":true,"data":{...}} response, so apply accepts that envelope too. */
    internal fun applyDocument(json: JSONObject): JSONObject =
        json.optJSONObject("data")?.takeIf { json.has("ok") } ?: json

    private fun parseJsonObject(body: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return JSONObject()
        return try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw WorkspaceApiException("Request body is not a JSON object: ${e.message}")
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

            val id = root.optLong("id", 0L)
            val db = AppDatabase.getDatabase(context)
            val dao = db.sshConfigDao()
            // Updating by id keeps every field the request omits (credentials, terminal settings, tunnel link).
            val existing = if (id > 0L) dao.getConfigById(id) else null

            val username = root.optString("username", existing?.username ?: "root").trim()
            val port = root.optInt("port", existing?.port ?: 22).coerceIn(1, 65535)
            val name = root.optString("name", existing?.name ?: "$username@$host:$port").trim()
            val authType = root.optString("authType", existing?.authType ?: "PASSWORD").uppercase()
            val password = root.optString("password", "").takeIf { it.isNotEmpty() }
            val privateKey = root.optString("privateKey", "").takeIf { it.isNotBlank() }
            val workDirectory = root.optString("workDirectory", "").takeIf { it.isNotBlank() } ?: existing?.workDirectory
            val postConnectCommand = root.optString("postConnectCommand", "").takeIf { it.isNotBlank() }
                ?: existing?.postConnectCommand

            val config = (existing ?: SshConfig(name = name, host = host, username = username, authType = authType)).copy(
                id = id,
                name = name,
                host = host,
                port = port,
                username = username,
                authType = authType,
                encryptedPassword = password?.let(KeystoreManager::encrypt) ?: existing?.encryptedPassword,
                encryptedPrivateKey = privateKey?.let(KeystoreManager::encrypt) ?: existing?.encryptedPrivateKey,
                workDirectory = workDirectory,
                postConnectCommand = postConnectCommand,
                updateTime = System.currentTimeMillis()
            )

            val savedId = if (existing != null) {
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

    /**
     * Termux setup script served at `/install.sh`: starts Termux sshd, stores the bridge token in
     * `~/.config/quickssh/bridge.env` (owner-only), installs the `quickssh` CLI ([cliScript]) and the
     * `quickssh-workspace` agent skill under `~/.agents/skills`, and registers the `/mcp` server for opencode
     * (merged into a user's own config). When Termux's `opencode` is a proot-distro wrapper, the skill and
     * MCP server are also registered under that distro's `/root`, which is the HOME opencode actually reads.
     */
    fun generateTermuxInstallerScript(bridgePort: Int, termuxSshPort: Int, token: String, cliScript: String): String {
        val d = "$"
        val skillDir = "${d}HOME/${AgentToolkit.SKILL_HOME_PATH.substringBeforeLast('/')}"
        val tokenFile = "${LocalEnvironmentDetector.TERMUX_HOME}/${AgentToolkit.TOKEN_HOME_PATH}"
        return listOf(
            """
            #!/data/data/com.termux/files/usr/bin/bash
            # QuickSSH <-> Termux Agent Bridge setup: quickssh CLI, bridge token and agent skill
            set -e
            echo "[QuickSSH] Setting up Agent Bridge integration..."

            if ! command -v curl >/dev/null 2>&1 || ! command -v sshd >/dev/null 2>&1; then
                echo "[QuickSSH] Installing dependencies (curl, openssh)..."
                pkg install -y curl openssh
            fi

            if [ "$termuxSshPort" -gt 0 ]; then
                pkill -f "sshd -p $termuxSshPort" 2>/dev/null || true
                sshd -p $termuxSshPort 2>/dev/null || true
                echo "[QuickSSH] Termux OpenSSH daemon listening on port $termuxSshPort"
            fi

            mkdir -p "${d}HOME/.config/quickssh" "${d}PREFIX/bin"
            ( umask 077; cat > "${d}HOME/${AgentToolkit.ENV_HOME_PATH}" << 'QUICKSSH_ENV_EOF'
            """.trimIndent(),
            AgentToolkit.envFileContent(bridgePort, token).trimEnd(),
            "QUICKSSH_ENV_EOF",
            "printf '%s' '$token' > \"${d}HOME/${AgentToolkit.TOKEN_HOME_PATH}\"",
            ")",
            "cat > \"${d}PREFIX/bin/quickssh\" << 'QUICKSSH_CLI_EOF'",
            cliScript.trimEnd(),
            "QUICKSSH_CLI_EOF",
            """
            chmod +x "${d}PREFIX/bin/quickssh"
            echo "[QuickSSH] Installed the 'quickssh' CLI"

            mkdir -p "$skillDir"
            if quickssh skill > "$skillDir/SKILL.md.tmp" && head -n 1 "$skillDir/SKILL.md.tmp" | grep -q '^---'; then
                mv "$skillDir/SKILL.md.tmp" "$skillDir/SKILL.md"
                echo "[QuickSSH] Installed agent skill: $skillDir/SKILL.md"
            else
                rm -f "$skillDir/SKILL.md.tmp"
                echo "[QuickSSH] Warning: could not fetch the agent skill (is the bridge running?)"
            fi

            QS_OC_DIR="${d}HOME/.config/quickssh/opencode"
            mkdir -p "${d}QS_OC_DIR"
            cat > "${d}QS_OC_DIR/v2" << 'QUICKSSH_OPENCODE_EOF'
            """.trimIndent(),
            AgentToolkit.opencodeConfigContent(bridgePort, tokenFile).trimEnd(),
            "QUICKSSH_OPENCODE_EOF",
            "cat > \"${d}QS_OC_DIR/v1\" << 'QUICKSSH_OPENCODE_EOF'",
            AgentToolkit.opencodeConfigContent(bridgePort, tokenFile, legacyFormat = true).trimEnd(),
            "QUICKSSH_OPENCODE_EOF",
            """
            # opencode 2.x reads mcp.servers.<name>, older releases mcp.<name>: use the installed one's layout.
            OC_MAJOR=${d}(opencode --version 2>/dev/null | grep -o '[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*' | head -n 1 | cut -d. -f1)
            OC_LAYOUT=v2
            if [ -n "${d}OC_MAJOR" ] && [ "${d}OC_MAJOR" -lt 2 ]; then OC_LAYOUT=v1; fi
            qs_normalized() { sed 's#127\.0\.0\.1:[0-9]*/mcp#PORT#; s#{file:[^}]*}#FILE#' "${d}1"; }
            # Adds the quickssh entry to a user's own JSON config, keeping their other MCP servers; fails on JSONC.
            qs_merge_mcp() {
                if command -v python3 >/dev/null 2>&1; then
                    python3 -c 'import json, sys
            c = json.load(open(sys.argv[1])); q = json.load(open(sys.argv[2])); m = c.setdefault("mcp", {})
            for k, v in q["mcp"].items():
                if k == "servers" and isinstance(m.get(k), dict): m[k].update(v)
                else: m[k] = v
            json.dump(c, open(sys.argv[1], "w"), indent=2, ensure_ascii=False)' "${d}1" "${d}2"
                elif command -v node >/dev/null 2>&1; then
                    node -e 'const fs = require("fs"); const [f, s] = process.argv.slice(1);
            const c = JSON.parse(fs.readFileSync(f, "utf8")); const q = JSON.parse(fs.readFileSync(s, "utf8")); const m = c.mcp = c.mcp || {};
            for (const [k, v] of Object.entries(q.mcp)) { if (k === "servers" && m[k] && typeof m[k] === "object") Object.assign(m[k], v); else m[k] = v; }
            fs.writeFileSync(f, JSON.stringify(c, null, 2) + "\n");' "${d}1" "${d}2"
                else
                    return 1
                fi
            }
            # Registers the quickssh MCP server in <home>/.config/opencode/opencode.json.
            qs_register_opencode() {
                OC_CONF="${d}1/${AgentToolkit.OPENCODE_CONFIG_HOME_PATH}"
                mkdir -p "${d}(dirname "${d}OC_CONF")"
                OC_GENERATED=0
                if [ -f "${d}OC_CONF" ]; then
                    for layout in v1 v2; do
                        if [ "${d}(qs_normalized "${d}OC_CONF")" = "${d}(qs_normalized "${d}QS_OC_DIR/${d}layout")" ]; then OC_GENERATED=1; fi
                    done
                fi
                cp "${d}QS_OC_DIR/${d}OC_LAYOUT" "${d}OC_CONF.quickssh"
                if [ ! -f "${d}OC_CONF" ] || [ "${d}OC_GENERATED" = 1 ]; then
                    mv "${d}OC_CONF.quickssh" "${d}OC_CONF"
                    echo "[QuickSSH] Registered the quickssh MCP server for opencode (${d}OC_LAYOUT layout): ${d}OC_CONF"
                elif cp "${d}OC_CONF" "${d}OC_CONF.bak-quickssh" && qs_merge_mcp "${d}OC_CONF" "${d}OC_CONF.quickssh" 2>/dev/null; then
                    rm -f "${d}OC_CONF.quickssh"
                    echo "[QuickSSH] Added the quickssh MCP server to your opencode config (${d}OC_LAYOUT layout, backup ${d}OC_CONF.bak-quickssh): ${d}OC_CONF"
                else
                    if [ -f "${d}OC_CONF.bak-quickssh" ]; then mv "${d}OC_CONF.bak-quickssh" "${d}OC_CONF"; fi
                    echo "[QuickSSH] ${d}OC_CONF is your own config; to give opencode the quickssh MCP tools, merge the \"quickssh\" server from ${d}OC_CONF.quickssh into it"
                fi
            }
            qs_register_opencode "${d}HOME"

            # Termux's opencode is often a proot-distro wrapper: that opencode runs as root inside the distro and
            # reads /root, so register the skill and MCP server there too (Termux paths stay bound inside proot).
            OC_BIN=${d}(command -v opencode 2>/dev/null || true)
            if [ -n "${d}OC_BIN" ] && grep -q 'proot-distro' "${d}OC_BIN" 2>/dev/null; then
                OC_DISTRO=${d}(grep -o 'proot-distro \(run\|login\) [A-Za-z0-9._-]*' "${d}OC_BIN" | head -n 1 | awk '{print ${d}3}')
                # proot-distro 4.x (bash) keeps installed-rootfs/<distro>; the Python rewrite containers/<distro>/rootfs.
                OC_ROOT="${d}PREFIX/var/lib/proot-distro/installed-rootfs/${d}OC_DISTRO/root"
                [ -d "${d}OC_ROOT" ] || OC_ROOT="${d}PREFIX/var/lib/proot-distro/containers/${d}OC_DISTRO/rootfs/root"
                if [ -n "${d}OC_DISTRO" ] && [ -d "${d}OC_ROOT" ]; then
                    mkdir -p "${d}OC_ROOT/.agents/skills"
                    ln -sfn "$skillDir" "${d}OC_ROOT/.agents/skills/${AgentToolkit.SKILL_NAME}"
                    echo "[QuickSSH] Linked the agent skill into proot-distro ${d}OC_DISTRO: /root/.agents/skills/${AgentToolkit.SKILL_NAME}"
                    qs_register_opencode "${d}OC_ROOT"
                fi
            fi

            echo "[QuickSSH] Done. Try: quickssh tree   |   remote: ssh -p $termuxSshPort <user>@<phone-ip> quickssh tree"
            """.trimIndent()
        ).joinToString("\n") + "\n"
    }
}
