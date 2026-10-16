package com.quickssh.app.data

/**
 * Row-level access to the workspace hierarchy (server nodes, workspaces, folders, tunnel presets)
 * used by the agent-facing workspace API. The Room implementation writes the real database; the
 * in-memory implementation backs dry runs and unit tests so both share one code path.
 */
interface WorkspaceStore {
    suspend fun servers(): List<SshServerNode>
    suspend fun workspaces(): List<SshWorkspaceProfile>
    suspend fun folders(): List<SshFolder>
    suspend fun tunnels(): List<SshTunnelPreset>

    suspend fun insertServer(node: SshServerNode): Long
    suspend fun updateServer(node: SshServerNode)

    /** Deletes the server node together with its workspaces and their tunnel presets. */
    suspend fun deleteServer(id: Long)

    suspend fun insertWorkspace(workspace: SshWorkspaceProfile): Long
    suspend fun updateWorkspace(workspace: SshWorkspaceProfile)

    /** Deletes the workspace and its tunnel presets; the server node is left alone. */
    suspend fun deleteWorkspace(id: Long)

    suspend fun insertFolder(folder: SshFolder): Long
    suspend fun updateFolder(folder: SshFolder)

    /** Deletes the folder and moves its workspaces out of it. */
    suspend fun deleteFolder(id: Long)

    suspend fun insertTunnel(preset: SshTunnelPreset): Long
    suspend fun updateTunnel(preset: SshTunnelPreset)

    /** Deletes the preset and clears any workspace pre-connect reference to it. */
    suspend fun deleteTunnel(id: Long)
}

data class WorkspaceSnapshot(
    val servers: List<SshServerNode> = emptyList(),
    val workspaces: List<SshWorkspaceProfile> = emptyList(),
    val folders: List<SshFolder> = emptyList(),
    val tunnels: List<SshTunnelPreset> = emptyList()
)

suspend fun WorkspaceStore.snapshot(): WorkspaceSnapshot =
    WorkspaceSnapshot(servers(), workspaces(), folders(), tunnels())

class RoomWorkspaceStore(private val dao: SshConfigDao) : WorkspaceStore {
    override suspend fun servers(): List<SshServerNode> = dao.getAllServerNodes()
    override suspend fun workspaces(): List<SshWorkspaceProfile> = dao.getAllWorkspaces()
    override suspend fun folders(): List<SshFolder> = dao.getAllFolders()
    override suspend fun tunnels(): List<SshTunnelPreset> = dao.getAllTunnelPresets()

    override suspend fun insertServer(node: SshServerNode): Long = dao.insertServerNode(node.copy(id = 0L))
    override suspend fun updateServer(node: SshServerNode) = dao.updateServerNode(node)

    override suspend fun deleteServer(id: Long) {
        dao.getAllWorkspaces().filter { it.serverNodeId == id }.forEach { deleteWorkspace(it.id) }
        dao.deleteServerNodeById(id)
    }

    override suspend fun insertWorkspace(workspace: SshWorkspaceProfile): Long =
        dao.insertWorkspace(workspace.copy(id = 0L))

    override suspend fun updateWorkspace(workspace: SshWorkspaceProfile) = dao.updateWorkspace(workspace)

    override suspend fun deleteWorkspace(id: Long) {
        dao.deleteTunnelPresetsForWorkspace(id)
        dao.deleteWorkspaceById(id)
    }

    override suspend fun insertFolder(folder: SshFolder): Long = dao.insertFolder(folder.copy(id = 0L))
    override suspend fun updateFolder(folder: SshFolder) = dao.updateFolder(folder)
    override suspend fun deleteFolder(id: Long) = dao.deleteFolder(id)

    override suspend fun insertTunnel(preset: SshTunnelPreset): Long = dao.insertTunnelPreset(preset.copy(id = 0L))

    override suspend fun updateTunnel(preset: SshTunnelPreset) {
        dao.updateTunnelPreset(preset)
    }

    override suspend fun deleteTunnel(id: Long) {
        dao.clearPreConnectTunnelReferences(id)
        dao.deleteTunnelPresetById(id)
    }
}

class InMemoryWorkspaceStore(initial: WorkspaceSnapshot = WorkspaceSnapshot()) : WorkspaceStore {
    private val serverRows = initial.servers.associateBy { it.id }.toMutableMap()
    private val workspaceRows = initial.workspaces.associateBy { it.id }.toMutableMap()
    private val folderRows = initial.folders.associateBy { it.id }.toMutableMap()
    private val tunnelRows = initial.tunnels.associateBy { it.id }.toMutableMap()
    private var nextId = (
        initial.servers.map { it.id } + initial.workspaces.map { it.id } +
            initial.folders.map { it.id } + initial.tunnels.map { it.id }
        ).maxOrNull()?.coerceAtLeast(0L)?.plus(1L) ?: 1L

    private fun allocateId(): Long = nextId++

    override suspend fun servers(): List<SshServerNode> = serverRows.values.toList()
    override suspend fun workspaces(): List<SshWorkspaceProfile> = workspaceRows.values.toList()
    override suspend fun folders(): List<SshFolder> = folderRows.values.toList()
    override suspend fun tunnels(): List<SshTunnelPreset> = tunnelRows.values.toList()

    override suspend fun insertServer(node: SshServerNode): Long {
        val id = allocateId()
        serverRows[id] = node.copy(id = id)
        return id
    }

    override suspend fun updateServer(node: SshServerNode) {
        if (node.id in serverRows) serverRows[node.id] = node
    }

    override suspend fun deleteServer(id: Long) {
        workspaceRows.values.filter { it.serverNodeId == id }.forEach { deleteWorkspace(it.id) }
        serverRows.remove(id)
    }

    override suspend fun insertWorkspace(workspace: SshWorkspaceProfile): Long {
        val id = allocateId()
        workspaceRows[id] = workspace.copy(id = id)
        return id
    }

    override suspend fun updateWorkspace(workspace: SshWorkspaceProfile) {
        if (workspace.id in workspaceRows) workspaceRows[workspace.id] = workspace
    }

    override suspend fun deleteWorkspace(id: Long) {
        tunnelRows.values.filter { it.workspaceId == id }.forEach { tunnelRows.remove(it.id) }
        workspaceRows.remove(id)
    }

    override suspend fun insertFolder(folder: SshFolder): Long {
        val id = allocateId()
        folderRows[id] = folder.copy(id = id)
        return id
    }

    override suspend fun updateFolder(folder: SshFolder) {
        if (folder.id in folderRows) folderRows[folder.id] = folder
    }

    override suspend fun deleteFolder(id: Long) {
        folderRows.remove(id)
        workspaceRows.values.filter { it.folderId == id }.forEach {
            workspaceRows[it.id] = it.copy(folderId = null)
        }
    }

    override suspend fun insertTunnel(preset: SshTunnelPreset): Long {
        val id = allocateId()
        tunnelRows[id] = preset.copy(id = id)
        return id
    }

    override suspend fun updateTunnel(preset: SshTunnelPreset) {
        if (preset.id in tunnelRows) tunnelRows[preset.id] = preset
    }

    override suspend fun deleteTunnel(id: Long) {
        tunnelRows.remove(id)
        workspaceRows.values.filter { it.preConnectTunnelPresetId == id }.forEach {
            workspaceRows[it.id] = it.copy(preConnectTunnelPresetId = null)
        }
    }
}
