package com.quickssh.app.service

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Model Context Protocol server for the Agent Bridge, served as stateless Streamable HTTP at
 * `POST /mcp` (JSON responses only, no SSE stream). Every tool maps onto one `/api/v2` workspace
 * request, so MCP clients (opencode, Claude Code, ...) get the same semantics, validation and
 * transactions as the `quickssh` CLI.
 *
 * [workspaceApi] performs `(method, path, query, body)` against `/api/v2` and returns its
 * response body; [devices] returns the `/api/devices` JSON.
 */
internal class AgentMcpHandler(
    private val serverVersion: String,
    private val workspaceApi: suspend (method: String, path: String, query: Map<String, String>, body: String) -> String,
    private val devices: suspend () -> String
) {

    /** Handles one HTTP body; returns the JSON reply, or null when only notifications were sent (HTTP 202). */
    suspend fun handle(body: String): String? {
        val parsed = try {
            when (val value = org.json.JSONTokener(body.trim()).nextValue()) {
                is JSONObject, is JSONArray -> value
                else -> return error(JSONObject.NULL, PARSE_ERROR, "Expected a JSON-RPC object or batch").toString()
            }
        } catch (e: JSONException) {
            return error(JSONObject.NULL, PARSE_ERROR, "Parse error: ${e.message}").toString()
        }
        if (parsed is JSONArray) {
            val replies = JSONArray()
            for (i in 0 until parsed.length()) {
                val message = parsed.opt(i) as? JSONObject
                val reply = if (message == null) {
                    error(JSONObject.NULL, INVALID_REQUEST, "Batch entries must be objects")
                } else {
                    handleMessage(message)
                }
                reply?.let { replies.put(it) }
            }
            return if (replies.length() == 0) null else replies.toString()
        }
        return handleMessage(parsed as JSONObject)?.toString()
    }

    private suspend fun handleMessage(message: JSONObject): JSONObject? {
        // Responses from the client and notifications get no reply.
        val method = message.optString("method", "").ifEmpty { return null }
        if (!message.has("id")) return null
        val id = message.get("id")
        val params = message.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> result(id, initializeResult(params))
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", toolDefinitions()))
            "tools/call" -> {
                val name = params.optString("name", "")
                if (TOOLS.none { it.name == name }) {
                    error(id, INVALID_PARAMS, "Unknown tool: $name")
                } else {
                    result(id, callTool(name, params.optJSONObject("arguments") ?: JSONObject()))
                }
            }
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion", "")
        return JSONObject()
            .put("protocolVersion", if (requested in SUPPORTED_PROTOCOL_VERSIONS) requested else SUPPORTED_PROTOCOL_VERSIONS.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "quickssh").put("title", "QuickSSH workspace").put("version", serverVersion))
            .put("instructions", INSTRUCTIONS)
    }

    private suspend fun callTool(name: String, args: JSONObject): JSONObject {
        val text = try {
            when (name) {
                "quickssh_tree" -> workspaceApi("GET", "tree", emptyMap(), "")
                "quickssh_export" -> workspaceApi("GET", "export", emptyMap(), "")
                "quickssh_guide" -> workspaceApi("GET", "skill", emptyMap(), "")
                "quickssh_devices" -> devices()
                "quickssh_apply" -> {
                    val document = args.optJSONObject("document")
                        ?: return toolError("'document' must be a quickssh-workspace/v1 JSON object (see quickssh_export)")
                    if (!args.has("dryRun") || args.opt("dryRun") !is Boolean) {
                        return toolError("'dryRun' is required: true to only plan, false to write")
                    }
                    val query = mutableMapOf<String, String>()
                    if (args.getBoolean("dryRun")) query["dryRun"] = "1"
                    if (args.optBoolean("prune", false)) query["prune"] = "1"
                    workspaceApi("POST", "apply", query, document.toString())
                }
                "quickssh_call" -> {
                    val action = args.optString("action", "")
                    if (action !in CALL_ACTIONS) {
                        return toolError("'action' must be one of ${CALL_ACTIONS.joinToString(", ")}")
                    }
                    val callParams = when (val raw = args.opt("params")) {
                        null, JSONObject.NULL -> JSONObject()
                        is JSONObject -> raw
                        else -> return toolError("'params' must be a JSON object")
                    }
                    workspaceApi("POST", action, emptyMap(), callParams.toString())
                }
                else -> return toolError("Unknown tool: $name")
            }
        } catch (e: Exception) {
            return toolError("${e.javaClass.simpleName}: ${e.localizedMessage ?: "tool failed"}")
        }
        val failed = runCatching { JSONObject(text).optBoolean("ok", true) == false }.getOrDefault(false)
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text.trimEnd())))
            .put("isError", failed)
    }

    private fun toolError(message: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    private fun result(id: Any, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun error(id: Any, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    private class Tool(val name: String, val title: String, val description: String, val readOnly: Boolean, val schema: () -> JSONObject)

    private fun toolDefinitions(): JSONArray = JSONArray().apply {
        TOOLS.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("title", tool.title)
                    .put("description", tool.description)
                    .put("inputSchema", tool.schema())
                    .put(
                        "annotations",
                        JSONObject().put("readOnlyHint", tool.readOnly).put("destructiveHint", !tool.readOnly)
                            .put("openWorldHint", false)
                    )
            )
        }
    }

    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /** Newest first; an unknown requested version is answered with the newest. */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        val CALL_ACTIONS = listOf("servers", "workspaces", "folders", "tunnels").flatMap { resource ->
            listOf("create", "update", "delete").map { "$resource/$it" }
        }

        private const val INSTRUCTIONS =
            "Manages the QuickSSH app's own SSH configuration: servers (host + credentials) > workspaces " +
                "(what the user opens) > tunnel presets, plus folders and pinned items. Read with quickssh_tree " +
                "(ids) or quickssh_export (declarative document) before changing anything; never guess ids. " +
                "Small edits: quickssh_call. Bulk edits: quickssh_export, edit, quickssh_apply with dryRun=true, " +
                "show the planned changes, then dryRun=false. Secrets are write-only. Full reference: quickssh_guide."

        private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

        private val TOOLS = listOf(
            Tool(
                "quickssh_tree", "List QuickSSH configuration",
                "Folders, servers, their workspaces and tunnel presets as nested JSON with ids. Credentials are never " +
                    "returned (hasPassword/hasPrivateKey only). Use the ids with quickssh_call.",
                readOnly = true, schema = ::emptySchema
            ),
            Tool(
                "quickssh_export", "Export workspace document",
                "The whole configuration as a declarative quickssh-workspace/v1 document (names, no ids, no secrets). " +
                    "Edit it and pass it to quickssh_apply for bulk changes.",
                readOnly = true, schema = ::emptySchema
            ),
            Tool(
                "quickssh_apply", "Apply workspace document",
                "Converges the configuration to a quickssh-workspace/v1 document atomically and returns " +
                    "{changed, changes[], warnings[]}. Servers match by name then host+port+username+authType, " +
                    "workspaces/tunnels/folders by name; unmatched objects are created; absent keys keep stored values. " +
                    "Always call with dryRun=true first and show the user the planned changes. prune=true deletes " +
                    "everything the document does not list - only use it with an edited full export.",
                readOnly = false
            ) {
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("document", JSONObject().put("type", "object")
                                .put("description", "quickssh-workspace/v1 document: {format, folders[], servers[{name, host, port, username, authType, workspaces[{name, folder, pinned, tunnels[]...}]}]}"))
                            .put("dryRun", JSONObject().put("type", "boolean").put("description", "true = only plan, nothing is written"))
                            .put("prune", JSONObject().put("type", "boolean").put("default", false)
                                .put("description", "Delete objects the document does not list and adopt its order"))
                    )
                    .put("required", JSONArray().put("document").put("dryRun"))
            },
            Tool(
                "quickssh_call", "Change one QuickSSH object",
                "Create, update or delete one server, workspace, folder or tunnel preset. Updates keep absent keys and " +
                    "clear keys set to null; unknown keys are rejected. Examples: folders/create {name}; " +
                    "workspaces/update {id, folder | pinned | workDirectory | persistentSessionMode | preConnectTunnel}; " +
                    "workspaces/create {name, serverId | server:{host, username, authType}}; " +
                    "tunnels/create {workspaceId, remotePort, localPort, name}; servers/update {id, host, password}. " +
                    "Deleting a server's last workspace deletes the server. Call quickssh_guide for every key.",
                readOnly = false
            ) {
                JSONObject().put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(CALL_ACTIONS)))
                            .put("params", JSONObject().put("type", "object")
                                .put("description", "Fields of the object; update/delete need its id from quickssh_tree"))
                    )
                    .put("required", JSONArray().put("action").put("params"))
            },
            Tool(
                "quickssh_devices", "List reachable SSH hosts",
                "SSH hosts known to QuickSSH and Termux (~/.ssh/config, known_hosts), grouped by IP and port.",
                readOnly = true, schema = ::emptySchema
            ),
            Tool(
                "quickssh_guide", "QuickSSH configuration guide",
                "Full reference (markdown): data model, every action and key, document format, matching and prune rules.",
                readOnly = true, schema = ::emptySchema
            )
        )
    }
}
