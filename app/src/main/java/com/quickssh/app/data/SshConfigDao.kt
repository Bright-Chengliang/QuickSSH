package com.quickssh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.quickssh.app.service.AUTH_TYPE_LOCAL
import kotlinx.coroutines.flow.Flow

@Dao
interface SshConfigDao {
    @Query(CONFIG_JOIN_QUERY + " ORDER BY s.sortOrder DESC, s.id DESC, w.sortOrder DESC, w.id DESC")
    fun getAllConfigsFlow(): Flow<List<SshConfig>>

    @Query(CONFIG_JOIN_QUERY + " ORDER BY s.sortOrder DESC, s.id DESC, w.sortOrder DESC, w.id DESC")
    suspend fun getAllConfigs(): List<SshConfig>

    @Query(CONFIG_JOIN_QUERY + " WHERE w.id = :id")
    suspend fun getConfigById(id: Long): SshConfig?

    @Query("SELECT * FROM ssh_tunnel_presets ORDER BY updateTime DESC, id DESC")
    fun getAllTunnelPresetsFlow(): Flow<List<SshTunnelPreset>>

    @Query("SELECT * FROM ssh_tunnel_presets ORDER BY updateTime DESC, id DESC")
    suspend fun getAllTunnelPresets(): List<SshTunnelPreset>

    @Query("SELECT * FROM ssh_tunnel_presets WHERE workspaceId = :workspaceId ORDER BY updateTime DESC, id DESC")
    suspend fun getTunnelPresetsForWorkspace(workspaceId: Long): List<SshTunnelPreset>

    @Query(
        """
        SELECT * FROM ssh_tunnel_presets
        WHERE workspaceId = :workspaceId
            AND name = :name
            AND lower(remoteHost) = lower(:remoteHost)
            AND remotePort = :remotePort
            AND localPort = :localPort
        ORDER BY updateTime DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findTunnelPreset(
        workspaceId: Long,
        name: String,
        remoteHost: String,
        remotePort: Int,
        localPort: Int
    ): SshTunnelPreset?

    @Transaction
    suspend fun insertConfig(config: SshConfig): Long {
        val now = normalizedUpdateTime(config.updateTime)
        val serverId = resolveServerNodeId(config, now)
        return insertWorkspace(config.toWorkspaceProfile(serverId, now, nextWorkspaceSortOrder(serverId)))
    }

    @Transaction
    suspend fun updateConfig(config: SshConfig) {
        val existingConfig = getConfigById(config.id) ?: return
        val existingWorkspace = getWorkspaceById(existingConfig.id) ?: return
        val serverId = when {
            config.serverNodeId > 0L -> config.serverNodeId
            existingConfig.serverNodeId > 0L -> existingConfig.serverNodeId
            else -> resolveServerNodeId(config, normalizedUpdateTime(config.updateTime))
        }
        val now = normalizedUpdateTime(config.updateTime)
        val existingServer = getServerNodeById(serverId)
        val serverSortOrder = existingServer?.sortOrder ?: nextServerNodeSortOrder()
        val workspaceSortOrder = if (serverId == existingWorkspace.serverNodeId) {
            existingWorkspace.sortOrder
        } else {
            nextWorkspaceSortOrder(serverId)
        }
        val serverDisplayName = config.serverDisplayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: existingServer?.displayName
        updateServerNode(
            config.toServerNode(
                id = serverId,
                updateTime = now,
                sortOrder = serverSortOrder,
                defaultDisplayName = serverDisplayName
            )
        )
        updateWorkspace(config.toWorkspaceProfile(serverId, now, workspaceSortOrder))
    }

    @Transaction
    suspend fun updateServerNodeCredentials(
        serverNodeId: Long,
        displayName: String,
        host: String,
        port: Int,
        username: String,
        authType: String,
        encryptedPassword: String?,
        encryptedPrivateKey: String?
    ) {
        val existing = getServerNodeById(serverNodeId) ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            displayName = displayName.trim().ifBlank { "$username@$host:$port" },
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            encryptedPassword = encryptedPassword,
            encryptedPrivateKey = encryptedPrivateKey,
            updateTime = now
        )
        updateServerNode(updated)
    }

    @Transaction
    suspend fun deleteConfig(config: SshConfig) {
        val existingConfig = getConfigById(config.id) ?: return
        deleteWorkspaceById(existingConfig.id)
        if (countWorkspacesForServer(existingConfig.serverNodeId) == 0) {
            deleteServerNodeById(existingConfig.serverNodeId)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerNode(node: SshServerNode): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkspace(workspace: SshWorkspaceProfile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTunnelPreset(preset: SshTunnelPreset): Long

    @Update
    suspend fun updateServerNode(node: SshServerNode)

    @Update
    suspend fun updateWorkspace(workspace: SshWorkspaceProfile)

    @Update
    suspend fun updateTunnelPreset(preset: SshTunnelPreset): Int

    @Transaction
    suspend fun saveTunnelPreset(preset: SshTunnelPreset): Long {
        if (preset.id > 0L && updateTunnelPreset(preset) > 0) {
            return preset.id
        }
        return insertTunnelPreset(preset.copy(id = 0L))
    }

    @Query("SELECT * FROM ssh_server_nodes WHERE id = :id")
    suspend fun getServerNodeById(id: Long): SshServerNode?

    @Query("SELECT * FROM ssh_workspaces WHERE id = :id")
    suspend fun getWorkspaceById(id: Long): SshWorkspaceProfile?

    @Query(
        """
        SELECT * FROM ssh_server_nodes
        WHERE lower(host) = lower(:host)
            AND port = :port
            AND username = :username
            AND authType = :authType
        ORDER BY sortOrder DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findServerNode(host: String, port: Int, username: String, authType: String): SshServerNode?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM ssh_server_nodes")
    suspend fun nextServerNodeSortOrder(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM ssh_workspaces WHERE serverNodeId = :serverNodeId")
    suspend fun nextWorkspaceSortOrder(serverNodeId: Long): Int

    @Query("UPDATE ssh_server_nodes SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateServerNodeSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE ssh_workspaces SET sortOrder = :sortOrder WHERE serverNodeId = :serverNodeId AND id = :id")
    suspend fun updateWorkspaceSortOrder(serverNodeId: Long, id: Long, sortOrder: Int)

    @Transaction
    suspend fun reorderServerNodes(serverNodeIds: List<Long>) {
        val orderedIds = serverNodeIds.filter { it > 0L }.distinct()
        orderedIds.forEachIndexed { index, id ->
            updateServerNodeSortOrder(id, orderedIds.size - index)
        }
    }

    @Transaction
    suspend fun reorderWorkspaces(serverNodeId: Long, workspaceIds: List<Long>) {
        if (serverNodeId <= 0L) return
        val orderedIds = workspaceIds.filter { it > 0L }.distinct()
        orderedIds.forEachIndexed { index, id ->
            updateWorkspaceSortOrder(serverNodeId, id, orderedIds.size - index)
        }
    }

    @Query("DELETE FROM ssh_workspaces WHERE id = :id")
    suspend fun deleteWorkspaceById(id: Long)

    @Query("DELETE FROM ssh_server_nodes WHERE id = :id")
    suspend fun deleteServerNodeById(id: Long)

    @Query("DELETE FROM ssh_tunnel_presets WHERE id = :id")
    suspend fun deleteTunnelPresetById(id: Long)

    @Query("SELECT COUNT(*) FROM ssh_workspaces WHERE serverNodeId = :serverNodeId")
    suspend fun countWorkspacesForServer(serverNodeId: Long): Int

    private suspend fun resolveServerNodeId(config: SshConfig, updateTime: Long): Long {
        val existingById = config.serverNodeId
            .takeIf { it > 0L }
            ?.let { getServerNodeById(it) }
        if (existingById != null) {
            updateServerNode(
                config.toServerNode(
                    id = existingById.id,
                    updateTime = updateTime,
                    sortOrder = existingById.sortOrder,
                    defaultDisplayName = existingById.displayName
                )
            )
            return existingById.id
        }

        val existingByIdentity = findServerNode(
            host = config.host.trim(),
            port = config.port,
            username = config.username.trim(),
            authType = config.authType
        )
        if (existingByIdentity != null) {
            updateServerNode(
                config.toServerNode(
                    id = existingByIdentity.id,
                    updateTime = updateTime,
                    sortOrder = existingByIdentity.sortOrder,
                    defaultDisplayName = existingByIdentity.displayName
                )
            )
            return existingByIdentity.id
        }

        return insertServerNode(
            config.toServerNode(
                id = 0L,
                updateTime = updateTime,
                sortOrder = nextServerNodeSortOrder(),
                defaultDisplayName = config.serverDisplayName?.takeIf { it.isNotBlank() } ?: config.name
            )
        )
    }

    private fun SshConfig.toServerNode(
        id: Long,
        updateTime: Long,
        sortOrder: Int,
        defaultDisplayName: String? = null
    ): SshServerNode {
        val normalizedHost = host.trim()
        val normalizedUsername = username.trim()
        val resolvedAuthType = if (isLocalSession || authType == AUTH_TYPE_LOCAL) AUTH_TYPE_LOCAL else authType
        val resolvedDisplayName = serverDisplayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultDisplayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (resolvedAuthType == AUTH_TYPE_LOCAL) "Local Terminal" else "$normalizedUsername@$normalizedHost:$port"
        return SshServerNode(
            id = id,
            displayName = resolvedDisplayName,
            host = normalizedHost,
            port = port,
            username = normalizedUsername,
            authType = resolvedAuthType,
            encryptedPassword = encryptedPassword,
            encryptedPrivateKey = encryptedPrivateKey,
            sortOrder = sortOrder,
            updateTime = updateTime
        )
    }

    private fun SshConfig.toWorkspaceProfile(serverNodeId: Long, updateTime: Long, sortOrder: Int): SshWorkspaceProfile {
        return SshWorkspaceProfile(
            id = id,
            serverNodeId = serverNodeId,
            name = name.trim().ifBlank { "Default workspace" },
            workDirectory = workDirectory?.trim()?.takeIf { it.isNotEmpty() },
            postConnectCommand = postConnectCommand?.trim()?.takeIf { it.isNotEmpty() },
            terminalFontSizeSp = terminalFontSizeSp.coerceIn(10, 20),
            terminalWrapEnabled = terminalWrapEnabled,
            terminalTerm = terminalTerm.trim().ifBlank { "xterm-256color" },
            terminalShortcuts = terminalShortcuts?.trim()?.takeIf { it.isNotEmpty() },
            persistentSessionMode = persistentSessionMode.trim().ifBlank { PERSISTENT_SESSION_NONE },
            sortOrder = sortOrder,
            updateTime = updateTime
        )
    }

    private fun normalizedUpdateTime(updateTime: Long): Long {
        return updateTime.takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    companion object {
        private const val CONFIG_JOIN_QUERY = """
            SELECT
                w.id AS id,
                w.name AS name,
                s.displayName AS serverDisplayName,
                s.host AS host,
                s.port AS port,
                s.username AS username,
                s.authType AS authType,
                s.encryptedPassword AS encryptedPassword,
                s.encryptedPrivateKey AS encryptedPrivateKey,
                w.workDirectory AS workDirectory,
                w.postConnectCommand AS postConnectCommand,
                w.terminalFontSizeSp AS terminalFontSizeSp,
                w.terminalWrapEnabled AS terminalWrapEnabled,
                w.terminalTerm AS terminalTerm,
                w.terminalShortcuts AS terminalShortcuts,
                w.persistentSessionMode AS persistentSessionMode,
                w.updateTime AS updateTime,
                s.id AS serverNodeId,
                s.sortOrder AS serverSortOrder,
                w.sortOrder AS workspaceSortOrder,
                (CASE WHEN s.authType = 'LOCAL' THEN 1 ELSE 0 END) AS isLocalSession
            FROM ssh_workspaces AS w
            INNER JOIN ssh_server_nodes AS s ON s.id = w.serverNodeId
        """
    }
}
