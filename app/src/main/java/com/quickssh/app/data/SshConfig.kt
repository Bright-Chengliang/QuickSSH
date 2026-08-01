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
    val updateTime: Long = System.currentTimeMillis(),
    val serverNodeId: Long = 0,
    val serverSortOrder: Int = 0,
    val workspaceSortOrder: Int = 0
)

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
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
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
    val updateTime: Long = System.currentTimeMillis()
)
