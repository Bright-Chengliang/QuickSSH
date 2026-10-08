package com.quickssh.app.utils

import com.quickssh.app.data.SshConfig
import com.quickssh.app.service.AUTH_TYPE_LOCAL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class SshHostSource(val displayName: String) {
    QUICKSSH("QuickSSH"),
    TERMUX_CONFIG("Termux Config"),
    TERMUX_KNOWN_HOSTS("Termux Known Hosts")
}

data class DiscoveredSshHost(
    val id: String,
    val alias: String,
    val hostname: String,
    val port: Int = 22,
    val username: String? = null,
    val source: SshHostSource,
    val quickSshConfigId: Long? = null,
    val groupedConfigIds: List<Long> = emptyList(),
    val rawLine: String? = null
) {
    val sshCommand: String
        get() = buildString {
            append("ssh ")
            if (!username.isNullOrBlank()) {
                append(username)
                append("@")
            }
            append(hostname)
            if (port != 22 && port > 0) {
                append(" -p ")
                append(port)
            }
        }
}

object SshHostDiscoveryHelper {

    private const val TERMUX_SSH_DIR = "/data/data/com.termux/files/home/.ssh"

    fun fromQuickSshConfigs(configs: List<SshConfig>): List<DiscoveredSshHost> {
        // Group by IP:port so the overview shows one card per IP rather than one per workspace
        val byIpPort = configs
            .filter { it.authType != AUTH_TYPE_LOCAL && it.host.isNotBlank() }
            .groupBy { "${it.host.trim().lowercase()}:${it.port}" }

        return byIpPort.values.map { group ->
            val primary = group.first()
            val allIds = group.map { it.id }
            // Prefer a server-level display name; fall back to host:port
            val alias = primary.serverDisplayName?.takeIf { it.isNotBlank() }
                ?: "${primary.host.trim()}:${primary.port}"
            DiscoveredSshHost(
                id = "quickssh_ip_${primary.host.trim()}_${primary.port}",
                alias = alias,
                hostname = primary.host.trim(),
                port = primary.port,
                username = primary.username.ifBlank { null },
                source = SshHostSource.QUICKSSH,
                quickSshConfigId = primary.id,
                groupedConfigIds = allIds
            )
        }
    }

    fun parseSshConfigFile(content: String): List<DiscoveredSshHost> {
        val result = mutableListOf<DiscoveredSshHost>()
        var currentHostAlias: String? = null
        var currentHostName: String? = null
        var currentUser: String? = null
        var currentPort = 22

        fun flushCurrent() {
            val alias = currentHostAlias
            if (!alias.isNullOrBlank() && alias != "*") {
                val host = currentHostName ?: alias
                result.add(
                    DiscoveredSshHost(
                        id = "termux_cfg_${alias}_${host}_$currentPort",
                        alias = alias,
                        hostname = host,
                        port = currentPort,
                        username = currentUser,
                        source = SshHostSource.TERMUX_CONFIG
                    )
                )
            }
            currentHostAlias = null
            currentHostName = null
            currentUser = null
            currentPort = 22
        }

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val key = parts[0].lowercase()
            val value = parts[1].trim()

            when (key) {
                "host" -> {
                    flushCurrent()
                    currentHostAlias = value
                }
                "hostname" -> currentHostName = value
                "user" -> currentUser = value
                "port" -> currentPort = value.toIntOrNull() ?: 22
            }
        }
        flushCurrent()
        return result
    }

    fun parseKnownHostsFile(content: String): List<DiscoveredSshHost> {
        val result = mutableListOf<DiscoveredSshHost>()
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val firstToken = trimmed.split(Regex("\\s+")).firstOrNull() ?: continue
            if (firstToken.startsWith("|1|")) {
                // Hashed known_hosts format, skip anonymous hashes
                continue
            }

            val hostEntries = firstToken.split(",")
            for (entry in hostEntries) {
                var host = entry.trim()
                var port = 22
                if (host.startsWith("[") && host.contains("]:")) {
                    val closeBracket = host.indexOf("]:")
                    port = host.substring(closeBracket + 2).toIntOrNull() ?: 22
                    host = host.substring(1, closeBracket)
                }

                if (host.isNotBlank() && !result.any { it.hostname.equals(host, ignoreCase = true) && it.port == port }) {
                    result.add(
                        DiscoveredSshHost(
                            id = "termux_known_${host}_$port",
                            alias = host,
                            hostname = host,
                            port = port,
                            source = SshHostSource.TERMUX_KNOWN_HOSTS,
                            rawLine = trimmed
                        )
                    )
                }
            }
        }
        return result
    }

    fun discoverTermuxHosts(): List<DiscoveredSshHost> {
        val list = mutableListOf<DiscoveredSshHost>()
        runCatching {
            val configFile = File(TERMUX_SSH_DIR, "config")
            if (configFile.exists() && configFile.canRead()) {
                list.addAll(parseSshConfigFile(configFile.readText()))
            }
        }
        runCatching {
            val knownHostsFile = File(TERMUX_SSH_DIR, "known_hosts")
            if (knownHostsFile.exists() && knownHostsFile.canRead()) {
                list.addAll(parseKnownHostsFile(knownHostsFile.readText()))
            }
        }
        return list
    }

    fun aggregateHosts(
        quickSshConfigs: List<SshConfig>,
        termuxHosts: List<DiscoveredSshHost> = discoverTermuxHosts()
    ): List<DiscoveredSshHost> {
        val quickSshHosts = fromQuickSshConfigs(quickSshConfigs)
        val combined = mutableListOf<DiscoveredSshHost>()
        combined.addAll(quickSshHosts)

        for (th in termuxHosts) {
            val alreadyInQuickSsh = combined.any {
                it.hostname.equals(th.hostname, ignoreCase = true) && it.port == th.port
            }
            if (!alreadyInQuickSsh) {
                combined.add(th)
            }
        }
        return combined
    }

    fun toJson(hosts: List<DiscoveredSshHost>): String {
        val array = JSONArray()
        for (h in hosts) {
            val obj = JSONObject()
            obj.put("id", h.id)
            obj.put("alias", h.alias)
            obj.put("hostname", h.hostname)
            obj.put("port", h.port)
            obj.put("username", h.username ?: JSONObject.NULL)
            obj.put("source", h.source.name)
            obj.put("sourceDisplayName", h.source.displayName)
            obj.put("sshCommand", h.sshCommand)
            if (h.quickSshConfigId != null) {
                obj.put("quickSshConfigId", h.quickSshConfigId)
            }
            array.put(obj)
        }
        val wrapper = JSONObject()
        wrapper.put("status", "ok")
        wrapper.put("count", hosts.size)
        wrapper.put("devices", array)
        return wrapper.toString(2)
    }
}
