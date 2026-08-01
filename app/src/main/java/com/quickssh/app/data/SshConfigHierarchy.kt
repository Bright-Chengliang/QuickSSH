package com.quickssh.app.data

data class SshServerGroup(
    val key: String,
    val displayName: String,
    val hostLabel: String,
    val workspaces: List<SshConfig>
)

fun SshConfig.serverIdentityKey(): String {
    if (serverNodeId > 0L) return "server-node:$serverNodeId"
    return listOf(host.trim().lowercase(), port.toString(), username.trim(), authType).joinToString("|")
}

fun SshConfig.serverNodeLabel(): String {
    return "$username@$host:$port"
}

fun SshConfig.workspaceLabel(): String {
    return name.ifBlank { workDirectory?.takeIf { it.isNotBlank() } ?: "Default workspace" }
}

fun SshConfig.transferContextLabel(): String {
    return "${serverNodeLabel()} / ${workspaceLabel()}"
}

fun serverNodeFieldsDiffer(left: SshConfig, right: SshConfig): Boolean {
    return left.host != right.host ||
        left.port != right.port ||
        left.username != right.username ||
        left.authType != right.authType ||
        left.encryptedPassword != right.encryptedPassword ||
        left.encryptedPrivateKey != right.encryptedPrivateKey
}

fun SshConfig.withServerNodeFieldsFrom(serverConfig: SshConfig): SshConfig {
    return copy(
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
            SshServerGroup(
                key = key,
                displayName = first.serverNodeLabel(),
                hostLabel = first.serverNodeLabel(),
                workspaces = sortedWorkspaces
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
            config.name,
            config.host,
            config.username,
            config.port.toString(),
            config.workDirectory.orEmpty(),
            config.postConnectCommand.orEmpty()
        ).any { it.lowercase().contains(normalizedQuery) }
    }
}
