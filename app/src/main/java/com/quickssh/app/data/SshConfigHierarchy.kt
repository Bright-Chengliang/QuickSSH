package com.quickssh.app.data

data class SshServerGroup(
    val key: String,
    val displayName: String,
    val hostLabel: String,
    val workspaces: List<SshConfig>,
    val serverNodeId: Long = 0L
)

fun SshConfig.serverIdentityKey(): String {
    if (serverNodeId > 0L) return "server-node:$serverNodeId"
    if (isLocalSession || authType == "LOCAL") return "local-node"
    return listOf(host.trim().lowercase(), port.toString(), username.trim(), authType).joinToString("|")
}

fun SshConfig.serverNodeLabel(): String {
    if (isLocalSession || authType == "LOCAL") {
        return serverDisplayName?.takeIf { it.isNotBlank() } ?: "Local Terminal"
    }
    return "$username@$host:$port"
}

fun SshConfig.workspaceLabel(): String {
    return name.ifBlank { workDirectory?.takeIf { it.isNotBlank() } ?: "Default workspace" }
}

fun SshConfig.transferContextLabel(): String {
    return "${serverNodeLabel()} / ${workspaceLabel()}"
}

/**
 * Workspaces of a home-screen group (folder, pinned or server card) that a unified pre-connect tunnel
 * applies to. Local shells never tunnel, and the workspace owning [preset] is the jump host the tunnel
 * runs through, so it keeps connecting directly instead of depending on itself.
 */
fun groupPreConnectTargetIds(workspaces: List<SshConfig>, preset: SshTunnelPreset?): List<Long> =
    workspaces
        .filter { !it.isLocalSession && it.authType != "LOCAL" && it.id != preset?.workspaceId }
        .map { it.id }

/**
 * Tunnel page entry: every workspace that reaches the same remote host IP. A tunnel only needs an SSH
 * login on that machine, so workspaces (and their presets) are merged per host instead of listed apart.
 *
 * [label] is what the tunnel page shows first: the user's remark for the host, otherwise the server
 * remarks of the workspaces on it, and only then the bare address. [hostAddress] always keeps the
 * address visible so an entry stays identifiable.
 */
data class TunnelHostGroup(
    val key: String,
    val hostAddress: String,
    val label: String,
    val workspaces: List<SshConfig>,
    val remark: String? = null
) {
    /** Workspace whose SSH login carries newly created tunnels for this host (first in display order). */
    val primary: SshConfig get() = workspaces.first()

    /** True when [label] is a saved remark rather than a derived server name or the address. */
    val hasRemark: Boolean get() = !remark.isNullOrBlank()
}

/**
 * Preferred title of a tunnel host: a saved remark wins, then the distinct server remarks of the
 * workspaces sharing that host, and finally the address itself.
 */
fun tunnelHostTitle(workspaces: List<SshConfig>, hostAddress: String, remark: String?): String {
    remark?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val serverNames = workspaces
        .mapNotNull { it.serverDisplayName?.trim()?.takeIf { name -> name.isNotEmpty() } }
        .distinct()
    return serverNames.joinToString(" / ").ifBlank { hostAddress }
}

/**
 * Host IP, lower-cased. Loopback hosts also key on the port: `127.0.0.1:<port>` workspaces are usually
 * different machines reached through different pre-connect tunnels.
 */
fun SshConfig.tunnelHostKey(): String {
    val normalized = host.trim().lowercase()
    val loopback = normalized == "localhost" || normalized == "::1" || normalized.startsWith("127.")
    return if (loopback) "$normalized:$port" else normalized
}

/** Remote hosts in home-screen order; local shells cannot forward ports and are left out. */
fun tunnelHostGroups(configs: List<SshConfig>, remarks: List<SshTunnelHostRemark> = emptyList()): List<TunnelHostGroup> {
    val remarksByHost = remarks.associate { it.hostKey to it.remark }
    return groupedSshServers(configs.filterNot { it.isLocalSession || it.authType == "LOCAL" })
        .flatMap { it.workspaces }
        .groupBy { it.tunnelHostKey() }
        .map { (key, workspaces) ->
            val remark = remarksByHost[key]
            TunnelHostGroup(
                key = key,
                hostAddress = key,
                label = tunnelHostTitle(workspaces, key, remark),
                workspaces = workspaces,
                remark = remark?.takeIf { it.isNotBlank() }
            )
        }
}

/** Presets of every workspace on [group]'s host; identical forwards saved under several workspaces are listed once. */
fun tunnelPresetsForHost(presets: List<SshTunnelPreset>, group: TunnelHostGroup?): List<SshTunnelPreset> {
    val workspaceIds = group?.workspaces?.map { it.id }?.toSet() ?: return emptyList()
    return presets
        .filter { it.workspaceId in workspaceIds }
        .distinctBy { listOf(it.name.trim(), it.remoteHost.trim().lowercase(), it.remotePort, it.localPort) }
}

fun serverNodeFieldsDiffer(left: SshConfig, right: SshConfig): Boolean {
    return left.serverDisplayName != right.serverDisplayName ||
        left.host != right.host ||
        left.port != right.port ||
        left.username != right.username ||
        left.authType != right.authType ||
        left.encryptedPassword != right.encryptedPassword ||
        left.encryptedPrivateKey != right.encryptedPrivateKey
}

fun SshConfig.withServerNodeFieldsFrom(serverConfig: SshConfig): SshConfig {
    return copy(
        serverDisplayName = serverConfig.serverDisplayName,
        host = serverConfig.host,
        port = serverConfig.port,
        username = serverConfig.username,
        authType = serverConfig.authType,
        encryptedPassword = serverConfig.encryptedPassword,
        encryptedPrivateKey = serverConfig.encryptedPrivateKey,
        serverSortOrder = serverConfig.serverSortOrder,
        updateTime = serverConfig.updateTime
    )
}

fun groupedSshServers(configs: List<SshConfig>): List<SshServerGroup> {
    return configs
        .groupBy { it.serverIdentityKey() }
        .map { (key, workspaces) ->
            val sortedWorkspaces = workspaces.sortedWith(
                compareByDescending<SshConfig> { it.workspaceSortOrder }
                    .thenByDescending { it.updateTime }
                    .thenByDescending { it.id }
            )
            val first = sortedWorkspaces.first()
            val serverTitle = first.serverDisplayName?.takeIf { it.isNotBlank() } ?: first.serverNodeLabel()
            SshServerGroup(
                key = key,
                displayName = serverTitle,
                hostLabel = first.serverNodeLabel(),
                workspaces = sortedWorkspaces,
                serverNodeId = first.serverNodeId
            )
        }
        .sortedWith(
            compareByDescending<SshServerGroup> { it.workspaces.first().serverSortOrder }
                .thenBy { it.displayName.lowercase() }
        )
}

fun copiedWorkspaceName(sourceName: String, existingNames: Collection<String>): String {
    val baseName = sourceName.ifBlank { "Workspace" }
    val copyName = "$baseName Copy"
    if (copyName !in existingNames) return copyName

    var index = 2
    while ("$copyName $index" in existingNames) {
        index++
    }
    return "$copyName $index"
}

fun filterSshConfigs(configs: List<SshConfig>, query: String): List<SshConfig> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return configs
    return configs.filter { config ->
        listOf(
            config.serverDisplayName.orEmpty(),
            config.name,
            config.host,
            config.username,
            config.port.toString(),
            config.workDirectory.orEmpty(),
            config.postConnectCommand.orEmpty()
        ).any { it.lowercase().contains(normalizedQuery) }
    }
}
