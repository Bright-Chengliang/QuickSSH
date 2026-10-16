package com.quickssh.app.service

import com.quickssh.app.data.PERSISTENT_SESSION_AUTO
import com.quickssh.app.data.PERSISTENT_SESSION_NONE
import com.quickssh.app.data.PERSISTENT_SESSION_SCREEN
import com.quickssh.app.data.PERSISTENT_SESSION_TMUX
import com.quickssh.app.data.PINNED_FOLDER_ID
import com.quickssh.app.data.SshFolder
import com.quickssh.app.data.SshServerNode
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.SshWorkspaceProfile
import com.quickssh.app.data.WorkspaceSnapshot
import com.quickssh.app.data.WorkspaceStore
import com.quickssh.app.data.snapshot
import com.quickssh.app.data.tunnelPresetDefaultName
import org.json.JSONArray
import org.json.JSONObject

/** A request the workspace API rejects; the message is returned to the agent verbatim. */
class WorkspaceApiException(message: String) : Exception(message)

/**
 * Agent-facing workspace configuration API behind the Agent Bridge `/api/v2` routes.
 *
 * It offers a structured tree view, granular operations with partial-update semantics (an absent
 * key keeps the stored value, an explicit null clears it), and a declarative document that can be
 * exported, edited and applied. Secrets are write-only: no read ever returns a password or key.
 * Everything runs against a [WorkspaceStore], so the bridge applies writes inside one Room
 * transaction and computes dry runs against an in-memory copy with the very same code.
 */
class WorkspaceConfigApi(
    private val store: WorkspaceStore,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DOCUMENT_FORMAT = "quickssh-workspace/v1"
        const val DEFAULT_WORKSPACE_NAME = "Default workspace"
        const val DEFAULT_TERM = "xterm-256color"

        private val AUTH_TYPES = listOf(AUTH_TYPE_PASSWORD, AUTH_TYPE_PRIVATE_KEY, AUTH_TYPE_LOCAL)
        private val SESSION_MODES = listOf(
            PERSISTENT_SESSION_NONE, PERSISTENT_SESSION_TMUX, PERSISTENT_SESSION_SCREEN, PERSISTENT_SESSION_AUTO
        )

        private val SERVER_KEYS = setOf("id", "name", "host", "port", "username", "authType", "password", "privateKey")
        private val WORKSPACE_KEYS = setOf(
            "id", "serverId", "name", "workDirectory", "postConnectCommand", "terminalFontSizeSp",
            "terminalWrapEnabled", "terminalTerm", "terminalShortcuts", "persistentSessionMode",
            "folder", "folderId", "pinned", "preConnectTunnel", "preConnectTunnelId"
        )
        private val TUNNEL_KEYS = setOf("id", "workspaceId", "name", "note", "remoteHost", "remotePort", "localPort")
    }

    // ---------------------------------------------------------------- reads

    suspend fun tree(): JSONObject {
        val s = store.snapshot()
        return JSONObject()
            .put("folders", JSONArray(s.sortedFolders().map { folderJson(it) }))
            .put("servers", JSONArray(s.sortedServers().map { node ->
                serverJson(node).put("workspaces", JSONArray(s.workspacesOf(node.id).map { workspaceJson(it, s) }))
            }))
    }

    /** Declarative document describing the whole workspace; feeding it back to [apply] is a no-op. */
    suspend fun exportDocument(): JSONObject {
        val s = store.snapshot()
        return JSONObject()
            .put("format", DOCUMENT_FORMAT)
            .put("folders", JSONArray(s.sortedFolders().map { it.name }))
            .put("servers", JSONArray(s.sortedServers().map { node ->
                JSONObject()
                    .put("name", node.displayName)
                    .put("host", node.host)
                    .put("port", node.port)
                    .put("username", node.username)
                    .put("authType", node.authType)
                    .put("credentialStored", hasCredential(node))
                    .put("workspaces", JSONArray(s.workspacesOf(node.id).map { ws ->
                        val tunnels = s.tunnelsOf(ws.id)
                        JSONObject()
                            .put("name", ws.name)
                            .put("workDirectory", ws.workDirectory.orJsonNull())
                            .put("postConnectCommand", ws.postConnectCommand.orJsonNull())
                            .put("terminalFontSizeSp", ws.terminalFontSizeSp)
                            .put("terminalWrapEnabled", ws.terminalWrapEnabled.orJsonNull())
                            .put("terminalTerm", ws.terminalTerm)
                            .put("terminalShortcuts", ws.terminalShortcuts.orJsonNull())
                            .put("persistentSessionMode", ws.persistentSessionMode)
                            .put("folder", s.realFolderName(ws.folderId).orJsonNull())
                            .put("pinned", ws.folderId == PINNED_FOLDER_ID)
                            .put("preConnectTunnel", tunnels.firstOrNull { it.id == ws.preConnectTunnelPresetId }?.name.orJsonNull())
                            .put("tunnels", JSONArray(tunnels.map { tunnelDocumentJson(it) }))
                    }))
            }))
    }

    // ---------------------------------------------------------------- granular operations

    /** Dispatches `POST /api/v2/<resource>/<action>`; returns the affected object. */
    suspend fun call(action: String, body: JSONObject): JSONObject = when (action) {
        "servers/create" -> createServer(body)
        "servers/update" -> updateServer(body)
        "servers/delete" -> deleteServer(body)
        "workspaces/create" -> createWorkspace(body)
        "workspaces/update" -> updateWorkspace(body)
        "workspaces/delete" -> deleteWorkspace(body)
        "folders/create" -> createFolder(body)
        "folders/update" -> updateFolder(body)
        "folders/delete" -> deleteFolder(body)
        "tunnels/create" -> createTunnel(body)
        "tunnels/update" -> updateTunnel(body)
        "tunnels/delete" -> deleteTunnel(body)
        else -> throw WorkspaceApiException(
            "Unknown action '$action'. Valid: servers|workspaces|folders|tunnels + /create|/update|/delete"
        )
    }

    private suspend fun createServer(body: JSONObject): JSONObject {
        body.requireKeys(SERVER_KEYS + "workspaces", "server")
        val s = store.snapshot()
        val node = applyServerFields(blankServer(), body, isNew = true)
        s.servers.firstOrNull { it.sameIdentity(node) }?.let {
            throw WorkspaceApiException(
                "Server #${it.id} '${it.displayName}' already has this host/port/username/authType; " +
                    "add a workspace to it with workspaces/create {\"serverId\":${it.id},...}"
            )
        }
        val serverId = store.insertServer(node.copy(sortOrder = s.nextServerSortOrder(), updateTime = now()))
        val workspaceEntries = body.optJSONArray("workspaces")
        if (workspaceEntries == null || workspaceEntries.length() == 0) {
            insertWorkspace(blankWorkspace(serverId).copy(name = DEFAULT_WORKSPACE_NAME), store.snapshot())
        } else {
            for (index in 0 until workspaceEntries.length()) {
                val entry = workspaceEntries.objectAt(index, "workspaces")
                entry.requireKeys(WORKSPACE_KEYS, "workspace")
                val current = store.snapshot()
                val workspace = applyWorkspaceFields(blankWorkspace(serverId), entry, current)
                insertWorkspace(workspace, current)
            }
        }
        return serverTreeJson(serverId)
    }

    private suspend fun updateServer(body: JSONObject): JSONObject {
        body.requireKeys(SERVER_KEYS, "server")
        val s = store.snapshot()
        val existing = s.serverById(body.requireLong("id"))
        val updated = applyServerFields(existing, body, isNew = false)
        if (serverChanges(existing, updated).isNotEmpty()) {
            store.updateServer(updated.copy(updateTime = now()))
        }
        return serverTreeJson(existing.id)
    }

    private suspend fun deleteServer(body: JSONObject): JSONObject {
        val s = store.snapshot()
        val existing = s.serverById(body.requireLong("id"))
        store.deleteServer(existing.id)
        return JSONObject().put("deleted", "server").put("id", existing.id).put("name", existing.displayName)
            .put("deletedWorkspaces", s.workspacesOf(existing.id).size)
    }

    private suspend fun createWorkspace(body: JSONObject): JSONObject {
        body.requireKeys(WORKSPACE_KEYS + "server", "workspace")
        var s = store.snapshot()
        val serverId = when {
            body.has("serverId") -> s.serverById(body.requireLong("serverId")).id
            body.optJSONObject("server") != null -> {
                val serverBody = body.getJSONObject("server")
                serverBody.requireKeys(SERVER_KEYS, "server")
                val wanted = applyServerFields(blankServer(), serverBody, isNew = true)
                val existing = s.servers.firstOrNull { it.sameIdentity(wanted) }
                if (existing != null) {
                    existing.id
                } else {
                    store.insertServer(wanted.copy(sortOrder = s.nextServerSortOrder(), updateTime = now())).also {
                        s = store.snapshot()
                    }
                }
            }
            else -> throw WorkspaceApiException("workspaces/create needs \"serverId\" or an inline \"server\" object")
        }
        val workspace = applyWorkspaceFields(blankWorkspace(serverId), body.withoutKeys("serverId"), s)
        val id = insertWorkspace(workspace, s)
        return workspaceTreeJson(id)
    }

    private suspend fun updateWorkspace(body: JSONObject): JSONObject {
        body.requireKeys(WORKSPACE_KEYS, "workspace")
        val s = store.snapshot()
        val existing = s.workspaceById(body.requireLong("id"))
        val updated = applyWorkspaceFields(existing, body, s)
        s.workspacesOf(updated.serverNodeId).firstOrNull { it.id != existing.id && it.name == updated.name }?.let {
            throw WorkspaceApiException("Server #${updated.serverNodeId} already has a workspace named '${updated.name}'")
        }
        val resolved = if (updated.serverNodeId != existing.serverNodeId) {
            updated.copy(sortOrder = s.nextWorkspaceSortOrder(updated.serverNodeId))
        } else {
            updated
        }
        if (workspaceChanges(existing, resolved).isNotEmpty()) {
            store.updateWorkspace(resolved.copy(updateTime = now()))
        }
        val serverDeleted = removeServerIfEmpty(existing.serverNodeId)
        return workspaceTreeJson(existing.id).also {
            if (serverDeleted) it.put("removedEmptyServerId", existing.serverNodeId)
        }
    }

    private suspend fun deleteWorkspace(body: JSONObject): JSONObject {
        val s = store.snapshot()
        val existing = s.workspaceById(body.requireLong("id"))
        store.deleteWorkspace(existing.id)
        // Mirrors SshConfigDao.deleteConfig: a server node without workspaces is invisible in the UI.
        val serverDeleted = removeServerIfEmpty(existing.serverNodeId)
        return JSONObject().put("deleted", "workspace").put("id", existing.id).put("name", existing.name)
            .put("serverDeleted", serverDeleted)
    }

    private suspend fun createFolder(body: JSONObject): JSONObject {
        body.requireKeys(setOf("name"), "folder")
        val s = store.snapshot()
        val name = body.requireText("name")
        if (s.folders.any { it.name == name }) throw WorkspaceApiException("Folder '$name' already exists")
        val id = store.insertFolder(SshFolder(name = name, sortOrder = s.nextFolderSortOrder(), updateTime = now()))
        return folderJson(store.snapshot().folders.first { it.id == id })
    }

    private suspend fun updateFolder(body: JSONObject): JSONObject {
        body.requireKeys(setOf("id", "name"), "folder")
        val s = store.snapshot()
        val existing = s.folderById(body.requireLong("id"))
        val name = body.textOrKeep("name", existing.name) ?: throw WorkspaceApiException("Folder name cannot be empty")
        if (s.folders.any { it.id != existing.id && it.name == name }) throw WorkspaceApiException("Folder '$name' already exists")
        if (name != existing.name) store.updateFolder(existing.copy(name = name, updateTime = now()))
        return folderJson(store.snapshot().folders.first { it.id == existing.id })
    }

    private suspend fun deleteFolder(body: JSONObject): JSONObject {
        val s = store.snapshot()
        val existing = s.folderById(body.requireLong("id"))
        store.deleteFolder(existing.id)
        return JSONObject().put("deleted", "folder").put("id", existing.id).put("name", existing.name)
            .put("releasedWorkspaces", s.workspaces.count { it.folderId == existing.id })
    }

    private suspend fun createTunnel(body: JSONObject): JSONObject {
        body.requireKeys(TUNNEL_KEYS, "tunnel")
        val s = store.snapshot()
        val workspace = s.workspaceById(body.requireLong("workspaceId"))
        val preset = applyTunnelFields(SshTunnelPreset(workspaceId = workspace.id, name = "", remotePort = 0), body, isNew = true)
        if (s.tunnelsOf(workspace.id).any { it.name == preset.name }) {
            throw WorkspaceApiException("Workspace #${workspace.id} already has a tunnel named '${preset.name}'")
        }
        val id = store.insertTunnel(preset.copy(updateTime = now()))
        return tunnelJson(store.snapshot().tunnels.first { it.id == id })
    }

    private suspend fun updateTunnel(body: JSONObject): JSONObject {
        body.requireKeys(TUNNEL_KEYS, "tunnel")
        val s = store.snapshot()
        val existing = s.tunnelById(body.requireLong("id"))
        if (body.has("workspaceId") && body.requireLong("workspaceId") != existing.workspaceId) {
            throw WorkspaceApiException("A tunnel cannot move to another workspace; delete it and create a new one")
        }
        val updated = applyTunnelFields(existing, body, isNew = false)
        if (s.tunnelsOf(existing.workspaceId).any { it.id != existing.id && it.name == updated.name }) {
            throw WorkspaceApiException("Workspace #${existing.workspaceId} already has a tunnel named '${updated.name}'")
        }
        if (tunnelChanges(existing, updated).isNotEmpty()) store.updateTunnel(updated.copy(updateTime = now()))
        return tunnelJson(store.snapshot().tunnels.first { it.id == existing.id })
    }

    private suspend fun deleteTunnel(body: JSONObject): JSONObject {
        val s = store.snapshot()
        val existing = s.tunnelById(body.requireLong("id"))
        store.deleteTunnel(existing.id)
        return JSONObject().put("deleted", "tunnel").put("id", existing.id).put("name", existing.name)
    }

    // ---------------------------------------------------------------- declarative apply

    /**
     * Converges the stored workspace toward [document]. Servers match by name, then by
     * host/port/username/authType; workspaces by name within their server; tunnels by name within
     * their workspace; folders by name. Absent keys keep stored values. With [prune] the document is
     * authoritative: unlisted servers, workspaces of listed servers, tunnels of listed workspaces
     * (when a `tunnels` array is given) and folders (when a `folders` array is given) are deleted,
     * and list order becomes display order.
     */
    suspend fun apply(document: JSONObject, prune: Boolean): JSONObject {
        document.requireKeys(setOf("format", "folders", "servers"), "document")
        val format = document.optString("format", DOCUMENT_FORMAT)
        if (format != DOCUMENT_FORMAT) throw WorkspaceApiException("Unsupported document format '$format' (expected $DOCUMENT_FORMAT)")
        val changes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val serverEntries = document.optJSONArray("servers") ?: JSONArray()

        // Folders first so workspaces can reference them; folders referenced only by workspaces are created too.
        val folderNames = LinkedHashSet<String>()
        document.optJSONArray("folders")?.let { array ->
            for (index in 0 until array.length()) {
                val value = array.get(index)
                val name = (if (value is JSONObject) value.optString("name") else value.toString()).trim()
                if (name.isEmpty()) throw WorkspaceApiException("folders[$index] is empty")
                folderNames += name
            }
        }
        for (index in 0 until serverEntries.length()) {
            val workspaces = serverEntries.objectAt(index, "servers").optJSONArray("workspaces") ?: continue
            for (wsIndex in 0 until workspaces.length()) {
                val entry = workspaces.optJSONObject(wsIndex) ?: continue
                if (entry.has("folder") && !entry.isNull("folder")) {
                    folderNames += entry.get("folder").toString().trim()
                }
            }
        }
        for (name in folderNames) {
            val s = store.snapshot()
            if (s.folders.none { it.name == name }) {
                store.insertFolder(SshFolder(name = name, sortOrder = s.nextFolderSortOrder(), updateTime = now()))
                changes += "create folder '$name'"
            }
        }

        val keptServerIds = mutableListOf<Long>()
        for (index in 0 until serverEntries.length()) {
            val entry = serverEntries.objectAt(index, "servers")
            entry.requireKeys(SERVER_KEYS + "workspaces", "servers[$index]")
            var s = store.snapshot()
            val matched = matchServer(entry, s, keptServerIds)
            val serverId: Long
            if (matched == null) {
                if (!entry.has("host") && entry.optString("authType").uppercase() != AUTH_TYPE_LOCAL) {
                    throw WorkspaceApiException("servers[$index]: no server named '${entry.optString("name")}'; give host and username to create it")
                }
                val node = applyServerFields(blankServer(), entry, isNew = true)
                serverId = store.insertServer(node.copy(sortOrder = s.nextServerSortOrder(), updateTime = now()))
                changes += "create server '${node.displayName}' (${node.username}@${node.host}:${node.port})"
                if (!hasCredential(node)) warnings += "server '${node.displayName}' has no stored ${node.authType} credential"
            } else {
                val node = applyServerFields(matched, entry, isNew = false)
                val fields = serverChanges(matched, node)
                if (fields.isNotEmpty()) {
                    store.updateServer(node.copy(updateTime = now()))
                    changes += "update server '${node.displayName}': ${fields.joinToString()}"
                }
                serverId = matched.id
            }
            keptServerIds += serverId
            val serverName = store.snapshot().servers.first { it.id == serverId }.displayName

            val workspaceEntries = entry.optJSONArray("workspaces")
            if (workspaceEntries == null) {
                if (matched == null) {
                    insertWorkspace(blankWorkspace(serverId).copy(name = DEFAULT_WORKSPACE_NAME), store.snapshot())
                    changes += "create workspace '$serverName/$DEFAULT_WORKSPACE_NAME'"
                }
                continue
            }
            val keptWorkspaceIds = mutableListOf<Long>()
            for (wsIndex in 0 until workspaceEntries.length()) {
                val wsEntry = workspaceEntries.objectAt(wsIndex, "servers[$index].workspaces")
                wsEntry.requireKeys(WORKSPACE_KEYS + "tunnels", "servers[$index].workspaces[$wsIndex]")
                val name = wsEntry.requireText("name")
                s = store.snapshot()
                val matchedWs = s.workspacesOf(serverId).firstOrNull { it.name == name && it.id !in keptWorkspaceIds }
                val fields = wsEntry.withoutKeys("id", "serverId", "tunnels", "preConnectTunnel", "preConnectTunnelId")
                val label = "$serverName/$name"
                val workspaceId = if (matchedWs == null) {
                    insertWorkspace(applyWorkspaceFields(blankWorkspace(serverId), fields, s), s).also {
                        changes += "create workspace '$label'"
                    }
                } else {
                    val updated = applyWorkspaceFields(matchedWs, fields, s)
                    val changed = workspaceChanges(matchedWs, updated)
                    if (changed.isNotEmpty()) {
                        store.updateWorkspace(updated.copy(updateTime = now()))
                        changes += "update workspace '$label': ${changed.joinToString()}"
                    }
                    matchedWs.id
                }
                keptWorkspaceIds += workspaceId
                wsEntry.optJSONArray("tunnels")?.let { applyTunnels(workspaceId, label, it, prune, changes) }
                if (wsEntry.has("preConnectTunnel") || wsEntry.has("preConnectTunnelId")) {
                    s = store.snapshot()
                    val workspace = s.workspaceById(workspaceId)
                    val resolved = resolvePreConnect(workspace, wsEntry, s)
                    if (resolved != workspace.preConnectTunnelPresetId) {
                        store.updateWorkspace(workspace.copy(preConnectTunnelPresetId = resolved, updateTime = now()))
                        changes += "update workspace '$label': preConnectTunnel"
                    }
                }
            }
            if (prune) {
                val stale = store.snapshot().workspacesOf(serverId).filter { it.id !in keptWorkspaceIds }
                if (keptWorkspaceIds.isEmpty() && stale.isNotEmpty()) {
                    throw WorkspaceApiException("servers[$index] '$serverName' lists no workspaces; a server needs at least one (remove the server entry to delete it)")
                }
                stale.forEach {
                    store.deleteWorkspace(it.id)
                    changes += "delete workspace '$serverName/${it.name}'"
                }
                reorder(keptWorkspaceIds, store.snapshot().workspaces.associate { it.id to it.sortOrder }) { id, order ->
                    val ws = store.snapshot().workspaceById(id)
                    store.updateWorkspace(ws.copy(sortOrder = order))
                }?.let { changes += "reorder workspaces of '$serverName'" }
            }
        }

        if (prune) {
            store.snapshot().servers.filter { it.id !in keptServerIds }.forEach {
                store.deleteServer(it.id)
                changes += "delete server '${it.displayName}'"
            }
            reorder(keptServerIds, store.snapshot().servers.associate { it.id to it.sortOrder }) { id, order ->
                store.updateServer(store.snapshot().serverById(id).copy(sortOrder = order))
            }?.let { changes += "reorder servers" }
            if (document.has("folders")) {
                store.snapshot().folders.filter { it.name !in folderNames }.forEach {
                    store.deleteFolder(it.id)
                    changes += "delete folder '${it.name}'"
                }
                val folderIds = folderNames.mapNotNull { name -> store.snapshot().folders.firstOrNull { it.name == name }?.id }
                reorder(folderIds, store.snapshot().folders.associate { it.id to it.sortOrder }) { id, order ->
                    store.updateFolder(store.snapshot().folderById(id).copy(sortOrder = order))
                }?.let { changes += "reorder folders" }
            }
        }

        return JSONObject()
            .put("changed", changes.isNotEmpty())
            .put("changes", JSONArray(changes))
            .put("warnings", JSONArray(warnings))
    }

    private suspend fun applyTunnels(
        workspaceId: Long,
        label: String,
        entries: JSONArray,
        prune: Boolean,
        changes: MutableList<String>
    ) {
        val kept = mutableListOf<Long>()
        for (index in 0 until entries.length()) {
            val entry = entries.objectAt(index, "$label tunnels")
            entry.requireKeys(TUNNEL_KEYS, "tunnel of '$label'")
            val s = store.snapshot()
            val draft = applyTunnelFields(SshTunnelPreset(workspaceId = workspaceId, name = "", remotePort = 0), entry, isNew = true)
            val matched = s.tunnelsOf(workspaceId).firstOrNull { it.name == draft.name && it.id !in kept }
            if (matched == null) {
                kept += store.insertTunnel(draft.copy(updateTime = now()))
                changes += "create tunnel '$label/${draft.name}'"
            } else {
                val updated = applyTunnelFields(matched, entry, isNew = false)
                val fields = tunnelChanges(matched, updated)
                if (fields.isNotEmpty()) {
                    store.updateTunnel(updated.copy(updateTime = now()))
                    changes += "update tunnel '$label/${matched.name}': ${fields.joinToString()}"
                }
                kept += matched.id
            }
        }
        if (prune) {
            store.snapshot().tunnelsOf(workspaceId).filter { it.id !in kept }.forEach {
                store.deleteTunnel(it.id)
                changes += "delete tunnel '$label/${it.name}'"
            }
        }
    }

    /** Assigns descending sort orders so [orderedIds] display in list order; returns null when nothing moved. */
    private suspend fun reorder(orderedIds: List<Long>, current: Map<Long, Int>, write: suspend (Long, Int) -> Unit): Unit? {
        var moved = false
        orderedIds.forEachIndexed { index, id ->
            val order = orderedIds.size - index
            if (current[id] != order) {
                write(id, order)
                moved = true
            }
        }
        return if (moved) Unit else null
    }

    private fun matchServer(entry: JSONObject, s: WorkspaceSnapshot, taken: List<Long>): SshServerNode? {
        val candidates = s.servers.filter { it.id !in taken }
        if (entry.has("id") && !entry.isNull("id")) {
            val id = entry.requireLong("id")
            return candidates.firstOrNull { it.id == id } ?: throw WorkspaceApiException("Server #$id does not exist")
        }
        val name = entry.textOrKeep("name", null)
        if (name != null) candidates.firstOrNull { it.displayName == name }?.let { return it }
        if (!entry.has("host") && name == null) throw WorkspaceApiException("A server entry needs \"name\" or \"host\"")
        if (!entry.has("host")) return null
        val wanted = applyServerFields(blankServer(), entry.withoutKeys("password", "privateKey"), isNew = true)
        return candidates.firstOrNull { it.sameIdentity(wanted) }
    }

    // ---------------------------------------------------------------- field mapping

    private fun applyServerFields(base: SshServerNode, obj: JSONObject, isNew: Boolean): SshServerNode {
        val authType = (obj.textOrKeep("authType", base.authType.ifBlank { null }) ?: AUTH_TYPE_PASSWORD).uppercase()
        if (authType !in AUTH_TYPES) throw WorkspaceApiException("authType must be one of ${AUTH_TYPES.joinToString("|")}")
        val local = authType == AUTH_TYPE_LOCAL
        val host = obj.textOrKeep("host", base.host.ifBlank { null })
            ?: if (local) LocalEnvironmentDetector.BUILTIN_SHELL else throw WorkspaceApiException("host is required")
        val port = if (local) 0 else {
            val value = obj.intOrKeep("port", if (isNew || base.port == 0) 22 else base.port)
            if (value !in 1..65535) throw WorkspaceApiException("port must be 1-65535")
            value
        }
        val username = obj.textOrKeep("username", base.username.ifBlank { null })
            ?: if (local) "local" else throw WorkspaceApiException("username is required")
        val displayName = obj.textOrKeep("name", base.displayName.ifBlank { null })
            ?: if (local) "Local Terminal" else "$username@$host:$port"
        return base.copy(
            displayName = displayName,
            host = host,
            port = port,
            username = username,
            authType = authType,
            encryptedPassword = if (local) null else obj.secretOrKeep("password", base.encryptedPassword),
            encryptedPrivateKey = if (local) null else obj.secretOrKeep("privateKey", base.encryptedPrivateKey)
        )
    }

    private fun applyWorkspaceFields(base: SshWorkspaceProfile, obj: JSONObject, s: WorkspaceSnapshot): SshWorkspaceProfile {
        val serverId = if (obj.has("serverId")) s.serverById(obj.requireLong("serverId")).id else base.serverNodeId
        val mode = (obj.textOrKeep("persistentSessionMode", base.persistentSessionMode) ?: PERSISTENT_SESSION_NONE).lowercase()
        if (mode !in SESSION_MODES) throw WorkspaceApiException("persistentSessionMode must be one of ${SESSION_MODES.joinToString("|")}")
        val workspace = base.copy(
            serverNodeId = serverId,
            name = obj.textOrKeep("name", base.name.ifBlank { null }) ?: throw WorkspaceApiException("workspace name is required"),
            workDirectory = obj.textOrKeep("workDirectory", base.workDirectory),
            postConnectCommand = obj.textOrKeep("postConnectCommand", base.postConnectCommand),
            terminalFontSizeSp = obj.intOrKeep("terminalFontSizeSp", base.terminalFontSizeSp).coerceIn(10, 20),
            terminalWrapEnabled = obj.boolOrKeep("terminalWrapEnabled", base.terminalWrapEnabled),
            terminalTerm = obj.textOrKeep("terminalTerm", base.terminalTerm) ?: DEFAULT_TERM,
            terminalShortcuts = obj.textOrKeep("terminalShortcuts", base.terminalShortcuts),
            persistentSessionMode = mode,
            folderId = resolveFolder(base.folderId, obj, s)
        )
        return workspace.copy(preConnectTunnelPresetId = resolvePreConnect(workspace, obj, s))
    }

    private fun resolveFolder(current: Long?, obj: JSONObject, s: WorkspaceSnapshot): Long? {
        var folderId = current
        if (obj.has("folderId")) {
            folderId = if (obj.isNull("folderId")) null else obj.requireLong("folderId")
            if (folderId != null && folderId != PINNED_FOLDER_ID && s.folders.none { it.id == folderId }) {
                throw WorkspaceApiException("Folder #$folderId does not exist")
            }
        }
        if (obj.has("folder")) {
            folderId = if (obj.isNull("folder")) {
                null
            } else {
                val name = obj.get("folder").toString().trim()
                s.folders.firstOrNull { it.name == name }?.id
                    ?: throw WorkspaceApiException("Folder '$name' does not exist; create it with folders/create first")
            }
        }
        if (obj.has("pinned")) {
            val pinned = obj.boolOrKeep("pinned", false) == true
            if (pinned) {
                if (obj.has("folder") && !obj.isNull("folder")) {
                    throw WorkspaceApiException("A workspace is either pinned or in a folder, not both")
                }
                folderId = PINNED_FOLDER_ID
            } else if (folderId == PINNED_FOLDER_ID) {
                folderId = null
            }
        }
        return folderId
    }

    private fun resolvePreConnect(workspace: SshWorkspaceProfile, obj: JSONObject, s: WorkspaceSnapshot): Long? {
        val own = s.tunnelsOf(workspace.id)
        var presetId = workspace.preConnectTunnelPresetId
        if (obj.has("preConnectTunnelId")) {
            presetId = if (obj.isNull("preConnectTunnelId")) null else obj.requireLong("preConnectTunnelId")
            if (presetId != null && own.none { it.id == presetId }) {
                throw WorkspaceApiException("Tunnel #$presetId is not a tunnel of workspace '${workspace.name}'")
            }
        }
        if (obj.has("preConnectTunnel")) {
            presetId = if (obj.isNull("preConnectTunnel")) {
                null
            } else {
                val name = obj.get("preConnectTunnel").toString().trim()
                own.firstOrNull { it.name == name }?.id
                    ?: throw WorkspaceApiException("Workspace '${workspace.name}' has no tunnel named '$name'")
            }
        }
        return presetId
    }

    private fun applyTunnelFields(base: SshTunnelPreset, obj: JSONObject, isNew: Boolean): SshTunnelPreset {
        val remoteHost = obj.textOrKeep("remoteHost", base.remoteHost.ifBlank { null }) ?: "127.0.0.1"
        val remotePort = obj.intOrKeep("remotePort", base.remotePort)
        if (remotePort !in 1..65535) {
            throw WorkspaceApiException(if (isNew && !obj.has("remotePort")) "remotePort is required" else "remotePort must be 1-65535")
        }
        val localPort = obj.intOrKeep("localPort", base.localPort)
        if (localPort !in 0..65535) throw WorkspaceApiException("localPort must be 0-65535 (0 = auto)")
        return base.copy(
            name = obj.textOrKeep("name", base.name.ifBlank { null }) ?: tunnelPresetDefaultName(remoteHost, remotePort),
            note = obj.textOrKeep("note", base.note),
            remoteHost = remoteHost,
            remotePort = remotePort,
            localPort = localPort
        )
    }

    private fun JSONObject.secretOrKeep(key: String, current: String?): String? {
        if (!has(key)) return current
        if (isNull(key)) return null
        val value = get(key).toString()
        if (value.isEmpty()) return null
        // Ciphertext differs per encryption (random IV); keep it when the plaintext is unchanged.
        val unchanged = current != null && runCatching { decrypt(current) == value }.getOrDefault(false)
        return if (unchanged) current else encrypt(value)
    }

    // ---------------------------------------------------------------- change detection

    private fun serverChanges(old: SshServerNode, new: SshServerNode): List<String> = listOfNotNull(
        "name".takeIf { old.displayName != new.displayName },
        "host".takeIf { old.host != new.host },
        "port".takeIf { old.port != new.port },
        "username".takeIf { old.username != new.username },
        "authType".takeIf { old.authType != new.authType },
        "password".takeIf { old.encryptedPassword != new.encryptedPassword },
        "privateKey".takeIf { old.encryptedPrivateKey != new.encryptedPrivateKey }
    )

    private fun workspaceChanges(old: SshWorkspaceProfile, new: SshWorkspaceProfile): List<String> = listOfNotNull(
        "server".takeIf { old.serverNodeId != new.serverNodeId },
        "name".takeIf { old.name != new.name },
        "workDirectory".takeIf { old.workDirectory != new.workDirectory },
        "postConnectCommand".takeIf { old.postConnectCommand != new.postConnectCommand },
        "terminalFontSizeSp".takeIf { old.terminalFontSizeSp != new.terminalFontSizeSp },
        "terminalWrapEnabled".takeIf { old.terminalWrapEnabled != new.terminalWrapEnabled },
        "terminalTerm".takeIf { old.terminalTerm != new.terminalTerm },
        "terminalShortcuts".takeIf { old.terminalShortcuts != new.terminalShortcuts },
        "persistentSessionMode".takeIf { old.persistentSessionMode != new.persistentSessionMode },
        (if (new.folderId == PINNED_FOLDER_ID || old.folderId == PINNED_FOLDER_ID) "pinned" else "folder")
            .takeIf { old.folderId != new.folderId },
        "preConnectTunnel".takeIf { old.preConnectTunnelPresetId != new.preConnectTunnelPresetId }
    )

    private fun tunnelChanges(old: SshTunnelPreset, new: SshTunnelPreset): List<String> = listOfNotNull(
        "name".takeIf { old.name != new.name },
        "note".takeIf { old.note != new.note },
        "remoteHost".takeIf { old.remoteHost != new.remoteHost },
        "remotePort".takeIf { old.remotePort != new.remotePort },
        "localPort".takeIf { old.localPort != new.localPort }
    )

    // ---------------------------------------------------------------- helpers

    private suspend fun insertWorkspace(workspace: SshWorkspaceProfile, s: WorkspaceSnapshot): Long {
        if (s.workspacesOf(workspace.serverNodeId).any { it.name == workspace.name }) {
            throw WorkspaceApiException("Server #${workspace.serverNodeId} already has a workspace named '${workspace.name}'")
        }
        return store.insertWorkspace(
            workspace.copy(sortOrder = s.nextWorkspaceSortOrder(workspace.serverNodeId), updateTime = now())
        )
    }

    private suspend fun removeServerIfEmpty(serverId: Long): Boolean {
        val s = store.snapshot()
        if (s.servers.none { it.id == serverId } || s.workspacesOf(serverId).isNotEmpty()) return false
        store.deleteServer(serverId)
        return true
    }

    private suspend fun serverTreeJson(serverId: Long): JSONObject {
        val s = store.snapshot()
        val node = s.serverById(serverId)
        return serverJson(node).put("workspaces", JSONArray(s.workspacesOf(serverId).map { workspaceJson(it, s) }))
    }

    private suspend fun workspaceTreeJson(workspaceId: Long): JSONObject {
        val s = store.snapshot()
        return workspaceJson(s.workspaceById(workspaceId), s)
    }

    private fun blankServer() = SshServerNode(displayName = "", host = "", port = 22, username = "", authType = "")

    private fun blankWorkspace(serverId: Long) = SshWorkspaceProfile(serverNodeId = serverId, name = "")

    private fun hasCredential(node: SshServerNode): Boolean = when (node.authType) {
        AUTH_TYPE_LOCAL -> true
        AUTH_TYPE_PRIVATE_KEY -> !node.encryptedPrivateKey.isNullOrBlank()
        else -> !node.encryptedPassword.isNullOrBlank()
    }

    private fun SshServerNode.sameIdentity(other: SshServerNode): Boolean =
        host.equals(other.host, ignoreCase = true) && port == other.port &&
            username == other.username && authType == other.authType

    private fun serverJson(node: SshServerNode) = JSONObject()
        .put("id", node.id)
        .put("name", node.displayName)
        .put("host", node.host)
        .put("port", node.port)
        .put("username", node.username)
        .put("authType", node.authType)
        .put("hasPassword", !node.encryptedPassword.isNullOrBlank())
        .put("hasPrivateKey", !node.encryptedPrivateKey.isNullOrBlank())
        .put("sortOrder", node.sortOrder)

    private fun workspaceJson(ws: SshWorkspaceProfile, s: WorkspaceSnapshot): JSONObject {
        val tunnels = s.tunnelsOf(ws.id)
        val folderId = ws.folderId?.takeIf { it != PINNED_FOLDER_ID }
        return JSONObject()
            .put("id", ws.id)
            .put("serverId", ws.serverNodeId)
            .put("name", ws.name)
            .put("workDirectory", ws.workDirectory.orJsonNull())
            .put("postConnectCommand", ws.postConnectCommand.orJsonNull())
            .put("terminalFontSizeSp", ws.terminalFontSizeSp)
            .put("terminalWrapEnabled", ws.terminalWrapEnabled.orJsonNull())
            .put("terminalTerm", ws.terminalTerm)
            .put("terminalShortcuts", ws.terminalShortcuts.orJsonNull())
            .put("persistentSessionMode", ws.persistentSessionMode)
            .put("pinned", ws.folderId == PINNED_FOLDER_ID)
            .put("folderId", folderId.orJsonNull())
            .put("folder", s.realFolderName(ws.folderId).orJsonNull())
            .put("preConnectTunnelId", ws.preConnectTunnelPresetId.orJsonNull())
            .put("preConnectTunnel", tunnels.firstOrNull { it.id == ws.preConnectTunnelPresetId }?.name.orJsonNull())
            .put("sortOrder", ws.sortOrder)
            .put("tunnels", JSONArray(tunnels.map { tunnelJson(it) }))
    }

    private fun folderJson(folder: SshFolder) = JSONObject()
        .put("id", folder.id)
        .put("name", folder.name)
        .put("sortOrder", folder.sortOrder)

    private fun tunnelJson(preset: SshTunnelPreset) = tunnelDocumentJson(preset)
        .put("id", preset.id)
        .put("workspaceId", preset.workspaceId)

    private fun tunnelDocumentJson(preset: SshTunnelPreset) = JSONObject()
        .put("name", preset.name)
        .put("note", preset.note.orJsonNull())
        .put("remoteHost", preset.remoteHost)
        .put("remotePort", preset.remotePort)
        .put("localPort", preset.localPort)
}

private fun Any?.orJsonNull(): Any = this ?: JSONObject.NULL

private fun WorkspaceSnapshot.sortedServers() =
    servers.sortedWith(compareByDescending<SshServerNode> { it.sortOrder }.thenByDescending { it.id })

private fun WorkspaceSnapshot.sortedFolders() =
    folders.sortedWith(compareByDescending<SshFolder> { it.sortOrder }.thenByDescending { it.id })

private fun WorkspaceSnapshot.workspacesOf(serverId: Long) = workspaces
    .filter { it.serverNodeId == serverId }
    .sortedWith(compareByDescending<SshWorkspaceProfile> { it.sortOrder }.thenByDescending { it.id })

private fun WorkspaceSnapshot.tunnelsOf(workspaceId: Long) = tunnels
    .filter { it.workspaceId == workspaceId }
    .sortedWith(compareByDescending<SshTunnelPreset> { it.updateTime }.thenByDescending { it.id })

private fun WorkspaceSnapshot.realFolderName(folderId: Long?): String? =
    folderId?.takeIf { it != PINNED_FOLDER_ID }?.let { id -> folders.firstOrNull { it.id == id }?.name }

private fun WorkspaceSnapshot.nextServerSortOrder() = (servers.maxOfOrNull { it.sortOrder } ?: 0) + 1
private fun WorkspaceSnapshot.nextFolderSortOrder() = (folders.maxOfOrNull { it.sortOrder } ?: 0) + 1
private fun WorkspaceSnapshot.nextWorkspaceSortOrder(serverId: Long) =
    (workspaces.filter { it.serverNodeId == serverId }.maxOfOrNull { it.sortOrder } ?: 0) + 1

private fun WorkspaceSnapshot.serverById(id: Long) =
    servers.firstOrNull { it.id == id } ?: throw WorkspaceApiException("Server #$id does not exist")

private fun WorkspaceSnapshot.workspaceById(id: Long) =
    workspaces.firstOrNull { it.id == id } ?: throw WorkspaceApiException("Workspace #$id does not exist")

private fun WorkspaceSnapshot.folderById(id: Long) =
    folders.firstOrNull { it.id == id } ?: throw WorkspaceApiException("Folder #$id does not exist")

private fun WorkspaceSnapshot.tunnelById(id: Long) =
    tunnels.firstOrNull { it.id == id } ?: throw WorkspaceApiException("Tunnel #$id does not exist")

/** Keys reads emit for information only; writes accept and ignore them so read output can be echoed back. */
private val READ_ONLY_KEYS = setOf("hasPassword", "hasPrivateKey", "credentialStored", "sortOrder")

/** Rejects unknown keys so a typo such as "workdir" fails loudly instead of being ignored. */
private fun JSONObject.requireKeys(allowed: Set<String>, context: String) {
    val unknown = keys().asSequence().filter { it !in allowed && it !in READ_ONLY_KEYS }.toList()
    if (unknown.isNotEmpty()) {
        throw WorkspaceApiException("Unknown key(s) ${unknown.joinToString()} in $context; allowed: ${allowed.sorted().joinToString()}")
    }
}

private fun JSONObject.withoutKeys(vararg names: String): JSONObject {
    val copy = JSONObject(toString())
    names.forEach { copy.remove(it) }
    return copy
}

private fun JSONArray.objectAt(index: Int, context: String): JSONObject =
    optJSONObject(index) ?: throw WorkspaceApiException("$context[$index] must be an object")

private fun JSONObject.requireLong(key: String): Long {
    if (!has(key) || isNull(key)) throw WorkspaceApiException("\"$key\" is required")
    val value = get(key)
    return (value as? Number)?.toLong() ?: value.toString().trim().toLongOrNull()
        ?: throw WorkspaceApiException("\"$key\" must be an integer")
}

private fun JSONObject.requireText(key: String): String =
    textOrKeep(key, null) ?: throw WorkspaceApiException("\"$key\" is required")

/** Absent key keeps [current]; explicit null or blank clears it. */
private fun JSONObject.textOrKeep(key: String, current: String?): String? = when {
    !has(key) -> current
    isNull(key) -> null
    else -> get(key).toString().trim().ifEmpty { null }
}

private fun JSONObject.intOrKeep(key: String, current: Int): Int {
    if (!has(key) || isNull(key)) return current
    val value = get(key)
    return (value as? Number)?.toInt() ?: value.toString().trim().toIntOrNull()
        ?: throw WorkspaceApiException("\"$key\" must be an integer")
}

private fun JSONObject.boolOrKeep(key: String, current: Boolean?): Boolean? = when {
    !has(key) -> current
    isNull(key) -> null
    else -> when (val value = get(key)) {
        is Boolean -> value
        else -> value.toString().trim().lowercase().toBooleanStrictOrNull()
            ?: throw WorkspaceApiException("\"$key\" must be true, false or null")
    }
}
