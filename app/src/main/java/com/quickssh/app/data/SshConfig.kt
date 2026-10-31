package com.quickssh.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

data class SshConfig(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: String, // "PASSWORD" or "PRIVATE_KEY"
    val encryptedPassword: String? = null,
    val encryptedPrivateKey: String? = null,
    val workDirectory: String? = null, // SSH workspace path, for example /var/www/html or /home/ubuntu/project
    val postConnectCommand: String? = null,
    val terminalFontSizeSp: Int = 12,
    val terminalWrapEnabled: Boolean? = null,
    val terminalTerm: String = "xterm-256color",
    val terminalShortcuts: String? = null,
    val persistentSessionMode: String = PERSISTENT_SESSION_NONE, // "none", "tmux", "screen", "auto"
    val preConnectTunnelPresetId: Long? = null,
    val updateTime: Long = System.currentTimeMillis(),
    val serverNodeId: Long = 0,
    val serverSortOrder: Int = 0,
    val workspaceSortOrder: Int = 0,
    val serverDisplayName: String? = null,
    val isLocalSession: Boolean = false,
    val folderId: Long? = null
)

/** Sentinel ID representing the special pinned folder. */
const val PINNED_FOLDER_ID: Long = -1L

/** Persistent session mode: no terminal multiplexer wrapping. */
const val PERSISTENT_SESSION_NONE = "none"
/** Persistent session mode: use tmux (Linux/macOS only). */
const val PERSISTENT_SESSION_TMUX = "tmux"
/** Persistent session mode: use GNU screen (Linux/macOS only). */
const val PERSISTENT_SESSION_SCREEN = "screen"
/** Persistent session mode: auto-detect tmux then screen, fall back to none. */
const val PERSISTENT_SESSION_AUTO = "auto"

@Entity(
    tableName = "ssh_server_nodes",
    indices = [
        Index(value = ["host", "port", "username", "authType"])
    ]
)
data class SshServerNode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: String,
    val encryptedPassword: String? = null,
    val encryptedPrivateKey: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    val updateTime: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssh_workspaces",
    foreignKeys = [
        ForeignKey(
            entity = SshServerNode::class,
            parentColumns = ["id"],
            childColumns = ["serverNodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["serverNodeId"])
    ]
)
data class SshWorkspaceProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverNodeId: Long,
    val name: String,
    val workDirectory: String? = null,
    val postConnectCommand: String? = null,
    val terminalFontSizeSp: Int = 12,
    val terminalWrapEnabled: Boolean? = null,
    val terminalTerm: String = "xterm-256color",
    val terminalShortcuts: String? = null,
    @ColumnInfo(defaultValue = "none") val persistentSessionMode: String = PERSISTENT_SESSION_NONE,
    @ColumnInfo(defaultValue = "NULL") val preConnectTunnelPresetId: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    val updateTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val folderId: Long? = null
)

@Entity(
    tableName = "ssh_folders"
)
data class SshFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    val updateTime: Long = System.currentTimeMillis()
)

/**
 * User remark for a remote host on the tunnel page, keyed by [TunnelHostGroup.key] (the host address,
 * plus the port for loopback hosts). The remark is what makes several IP-only machines distinguishable,
 * so it is stored per host instead of per workspace.
 */
@Entity(
    tableName = "tunnel_host_remarks"
)
data class SshTunnelHostRemark(
    @PrimaryKey val hostKey: String,
    val remark: String,
    val updateTime: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ssh_tunnel_presets",
    foreignKeys = [
        ForeignKey(
            entity = SshWorkspaceProfile::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["workspaceId"])
    ]
)
data class SshTunnelPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long,
    val name: String,
    val note: String? = null,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int,
    val localPort: Int = 0,
    val updateTime: Long = System.currentTimeMillis(),
    /**
     * [TUNNEL_DIRECTION_LOCAL] (`ssh -L`: phone 127.0.0.1:[localPort] -> [remoteHost]:[remotePort] seen from the
     * server) or [TUNNEL_DIRECTION_REMOTE] (`ssh -R`: the server listens on [remoteHost]:[remotePort] and forwards
     * to phone 127.0.0.1:[localPort], which is then required).
     */
    val direction: String = TUNNEL_DIRECTION_LOCAL,
    /** Resident tunnel: started with the app and at boot, and reconnected without a retry limit. */
    val autoStart: Boolean = false
)

const val TUNNEL_DIRECTION_LOCAL = "LOCAL"
const val TUNNEL_DIRECTION_REMOTE = "REMOTE"

val SshTunnelPreset.isRemoteForward: Boolean get() = direction == TUNNEL_DIRECTION_REMOTE

/** Unknown or blank directions are treated as local forwards. */
fun normalizedTunnelDirection(value: String?): String =
    if (value.equals(TUNNEL_DIRECTION_REMOTE, ignoreCase = true)) TUNNEL_DIRECTION_REMOTE else TUNNEL_DIRECTION_LOCAL
