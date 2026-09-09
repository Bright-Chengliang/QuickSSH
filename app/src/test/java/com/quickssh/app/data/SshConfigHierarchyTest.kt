package com.quickssh.app.data

import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import org.junit.Assert.assertEquals
import com.quickssh.app.data.TransferHistoryEntry
import org.junit.Test

class SshConfigHierarchyTest {
    @Test
    fun groupedSshServersUsesPersistedSortOrder() {
        val configs = listOf(
            config(id = 1, name = "Other", host = "other.example.com", serverSortOrder = 1, workspaceSortOrder = 1, updateTime = 30),
            config(id = 2, name = "API", workDirectory = "/srv/api", serverSortOrder = 2, workspaceSortOrder = 1, updateTime = 10),
            config(id = 3, name = "Logs", workDirectory = "/var/log", serverSortOrder = 2, workspaceSortOrder = 2, updateTime = 20)
        )

        val groups = groupedSshServers(configs)

        assertEquals(2, groups.size)
        assertEquals(
            listOf("root@example.com:22", "root@other.example.com:22"),
            groups.map { it.displayName }
        )
        val exampleGroup = groups.first { it.displayName == "root@example.com:22" }
        assertEquals(listOf("Logs", "API"), exampleGroup.workspaces.map { it.name })
    }

    @Test
    fun transferContextLabelIncludesServerAndWorkspace() {
        val config = config(name = "Deploy", workDirectory = "/srv/app")

        assertEquals("root@example.com:22 / Deploy", config.transferContextLabel())
    }

    @Test
    fun serverNodeFieldsDifferIgnoresWorkspaceOnlyChanges() {
        val original = config(name = "Deploy", workDirectory = "/srv/app")
        val renamedWorkspace = original.copy(name = "Logs", workDirectory = "/var/log")

        assertEquals(false, serverNodeFieldsDiffer(original, renamedWorkspace))
    }

    @Test
    fun serverNodeFieldsDifferDetectsCredentialChanges() {
        val original = config(name = "Deploy", workDirectory = "/srv/app")
        val changedCredential = original.copy(encryptedPassword = "new-secret")

        assertEquals(true, serverNodeFieldsDiffer(original, changedCredential))
    }

    @Test
    fun withServerNodeFieldsFromKeepsWorkspaceFieldsIndependent() {
        val workspace = config(name = "Logs", workDirectory = "/var/log", postConnectCommand = "journalctl -f")
        val server = config(
            name = "Deploy",
            host = "prod.example.com",
            workDirectory = "/srv/app",
            serverSortOrder = 7,
            updateTime = 42
        ).copy(
            username = "deploy",
            port = 2222,
            encryptedPassword = "rotated-secret"
        )

        val result = workspace.withServerNodeFieldsFrom(server)

        assertEquals("Logs", result.name)
        assertEquals("/var/log", result.workDirectory)
        assertEquals("journalctl -f", result.postConnectCommand)
        assertEquals("prod.example.com", result.host)
        assertEquals(2222, result.port)
        assertEquals("deploy", result.username)
        assertEquals("rotated-secret", result.encryptedPassword)
        assertEquals(7, result.serverSortOrder)
        assertEquals(0, result.workspaceSortOrder)
        assertEquals(42, result.updateTime)
    }

    @Test
    fun copiedWorkspaceNameAvoidsExistingNames() {
        assertEquals(
            "Deploy Copy 3",
            copiedWorkspaceName("Deploy", setOf("Deploy Copy", "Deploy Copy 2"))
        )
    }

    @Test
    fun filterSshConfigsMatchesHostWorkspaceAndCommandFields() {
        val configs = listOf(
            config(name = "Deploy", host = "prod.example.com", workDirectory = "/srv/api", updateTime = 1),
            config(name = "Logs", host = "logs.example.com", workDirectory = "/var/log", updateTime = 2, postConnectCommand = "journalctl -f")
        )

        assertEquals(listOf("Deploy"), filterSshConfigs(configs, "srv/api").map { it.name })
        assertEquals(listOf("Logs"), filterSshConfigs(configs, "journalctl").map { it.name })
        assertEquals(configs, filterSshConfigs(configs, "   "))
    }

    @Test
    fun groupedSshServersUsesCustomServerDisplayNameWhenPresent() {
        val configs = listOf(
            config(
                id = 1,
                name = "Deploy",
                serverDisplayName = "生产服务器",
                serverSortOrder = 1
            ),
            config(
                id = 2,
                name = "Logs",
                serverDisplayName = "生产服务器",
                serverSortOrder = 1
            )
        )

        val groups = groupedSshServers(configs)

        assertEquals(1, groups.size)
        assertEquals("生产服务器", groups.first().displayName)
        assertEquals("root@example.com:22", groups.first().hostLabel)
    }

    @Test
    fun filterSshConfigsMatchesServerDisplayName() {
        val configs = listOf(
            config(name = "Deploy", serverDisplayName = "生产服务器"),
            config(name = "Logs", serverDisplayName = "测试机")
        )

        assertEquals(listOf("Deploy"), filterSshConfigs(configs, "生产").map { it.name })
        assertEquals(listOf("Logs"), filterSshConfigs(configs, "测试").map { it.name })
    }

    @Test
    fun serverNodeFieldsDifferDetectsServerDisplayNameChange() {
        val original = config(name = "Deploy", serverDisplayName = "生产服务器")
        val changed = original.copy(serverDisplayName = "测试服务器")

        assertEquals(true, serverNodeFieldsDiffer(original, changed))
    }

    @Test
    fun withServerNodeFieldsFromCopiesServerDisplayName() {
        val workspace = config(name = "Logs")
        val server = config(name = "Deploy", serverDisplayName = "生产服务器")

        val result = workspace.withServerNodeFieldsFrom(server)
        assertEquals("生产服务器", result.serverDisplayName)
    }

    @Test
    fun localSessionIdentityKeyAndLabel() {
        val localConfig = SshConfig(
            id = 5,
            name = "My Local Shell",
            host = "/system/bin/sh",
            port = 0,
            username = "local",
            authType = com.quickssh.app.service.AUTH_TYPE_LOCAL,
            isLocalSession = true,
            serverDisplayName = "本机"
        )

        assertEquals("local-node", localConfig.serverIdentityKey())
        assertEquals("本机", localConfig.serverNodeLabel())
        assertEquals("本机 / My Local Shell", localConfig.transferContextLabel())
    }

    @Test
    fun transferHistoryDefaultsSplitServerAndWorkspaceLabels() {
        val entry = TransferHistoryEntry(
            fileName = "app.apk",
            direction = "Upload",
            serverName = "root@example.com:22 / Deploy",
            status = "Success"
        )

        assertEquals("root@example.com:22", entry.serverNodeName)
        assertEquals("Deploy", entry.workspaceName)
    }

    private fun config(
        id: Long = 0,
        name: String,
        host: String = "example.com",
        workDirectory: String? = null,
        updateTime: Long = 0,
        postConnectCommand: String? = null,
        serverSortOrder: Int = 0,
        workspaceSortOrder: Int = 0,
        serverDisplayName: String? = null
    ): SshConfig {
        return SshConfig(
            id = id,
            name = name,
            host = host,
            port = 22,
            username = "root",
            authType = AUTH_TYPE_PASSWORD,
            encryptedPassword = "secret",
            workDirectory = workDirectory,
            postConnectCommand = postConnectCommand,
            updateTime = updateTime,
            serverSortOrder = serverSortOrder,
            workspaceSortOrder = workspaceSortOrder,
            serverDisplayName = serverDisplayName
        )
    }
}


