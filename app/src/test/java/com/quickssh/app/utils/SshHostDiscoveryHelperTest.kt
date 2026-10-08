package com.quickssh.app.utils

import com.quickssh.app.data.SshConfig
import com.quickssh.app.service.AUTH_TYPE_LOCAL
import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostDiscoveryHelperTest {

    @Test
    fun testParseSshConfigFile() {
        val sshConfigContent = """
            # Sample SSH Config
            Host dev-box
                HostName 192.168.1.120
                User developer
                Port 2222

            Host prod-api
                HostName api.example.com
                User admin
                Port 22

            Host *
                ServerAliveInterval 60
        """.trimIndent()

        val discovered = SshHostDiscoveryHelper.parseSshConfigFile(sshConfigContent)
        assertEquals(2, discovered.size)

        val dev = discovered.find { it.alias == "dev-box" }
        assertNotNull(dev)
        assertEquals("192.168.1.120", dev?.hostname)
        assertEquals("developer", dev?.username)
        assertEquals(2222, dev?.port)
        assertEquals(SshHostSource.TERMUX_CONFIG, dev?.source)
        assertEquals("ssh developer@192.168.1.120 -p 2222", dev?.sshCommand)

        val prod = discovered.find { it.alias == "prod-api" }
        assertNotNull(prod)
        assertEquals("api.example.com", prod?.hostname)
        assertEquals("admin", prod?.username)
        assertEquals(22, prod?.port)
        assertEquals("ssh admin@api.example.com", prod?.sshCommand)
    }

    @Test
    fun testParseKnownHostsFile() {
        val knownHostsContent = """
            192.168.1.100,myserver.local ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAA...
            [10.0.0.5]:8022 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...
            |1|hash1|hash2= ecdsa-sha2-nistp256 AAAAE2VjZHNh...
        """.trimIndent()

        val discovered = SshHostDiscoveryHelper.parseKnownHostsFile(knownHostsContent)
        // 192.168.1.100 and myserver.local from first line, [10.0.0.5]:8022 from second, hashed skipped
        assertEquals(3, discovered.size)

        val port8022Host = discovered.find { it.port == 8022 }
        assertNotNull(port8022Host)
        assertEquals("10.0.0.5", port8022Host?.hostname)
        assertEquals(SshHostSource.TERMUX_KNOWN_HOSTS, port8022Host?.source)
    }

    @Test
    fun testFromQuickSshConfigsExcludesLocalShell() {
        val configs = listOf(
            SshConfig(
                id = 1L,
                name = "Local",
                host = "/system/bin/sh",
                port = 0,
                username = "local",
                authType = AUTH_TYPE_LOCAL
            ),
            SshConfig(
                id = 2L,
                name = "Cloud VPS",
                host = "cloud.example.org",
                port = 22,
                username = "ubuntu",
                authType = AUTH_TYPE_PASSWORD
            )
        )

        val discovered = SshHostDiscoveryHelper.fromQuickSshConfigs(configs)
        assertEquals(1, discovered.size)
        assertEquals("cloud.example.org", discovered[0].hostname)
        assertEquals(SshHostSource.QUICKSSH, discovered[0].source)
        assertEquals(2L, discovered[0].quickSshConfigId)
    }

    @Test
    fun testAggregateHostsDeduplication() {
        val quickSshConfigs = listOf(
            SshConfig(
                id = 10L,
                name = "My Node",
                host = "192.168.1.50",
                port = 22,
                username = "root",
                authType = AUTH_TYPE_PASSWORD
            )
        )

        val termuxHosts = listOf(
            DiscoveredSshHost(
                id = "termux_1",
                alias = "my-node-termux",
                hostname = "192.168.1.50",
                port = 22,
                source = SshHostSource.TERMUX_CONFIG
            ),
            DiscoveredSshHost(
                id = "termux_2",
                alias = "other-box",
                hostname = "192.168.1.99",
                port = 22,
                source = SshHostSource.TERMUX_CONFIG
            )
        )

        val combined = SshHostDiscoveryHelper.aggregateHosts(quickSshConfigs, termuxHosts)
        // 192.168.1.50 is in both, so it should keep QuickSSH and not duplicate
        assertEquals(2, combined.size)
        assertTrue(combined.any { it.source == SshHostSource.QUICKSSH && it.hostname == "192.168.1.50" })
        assertTrue(combined.any { it.source == SshHostSource.TERMUX_CONFIG && it.hostname == "192.168.1.99" })
    }

    @Test
    fun testFromQuickSshConfigsGroupsByIp() {
        val configs = listOf(
            SshConfig(
                id = 1L,
                name = "Workspace A",
                host = "192.168.1.10",
                port = 22,
                username = "root",
                authType = AUTH_TYPE_PASSWORD
            ),
            SshConfig(
                id = 2L,
                name = "Workspace B",
                host = "192.168.1.10",
                port = 22,
                username = "root",
                authType = AUTH_TYPE_PASSWORD
            ),
            SshConfig(
                id = 3L,
                name = "Other host",
                host = "10.0.0.7",
                port = 22,
                username = "root",
                authType = AUTH_TYPE_PASSWORD
            )
        )

        val discovered = SshHostDiscoveryHelper.fromQuickSshConfigs(configs)
        assertEquals(2, discovered.size)

        val grouped = discovered.first { it.hostname == "192.168.1.10" }
        assertEquals(listOf(1L, 2L), grouped.groupedConfigIds)
        assertEquals(1L, grouped.quickSshConfigId)

        val other = discovered.first { it.hostname == "10.0.0.7" }
        assertEquals(listOf(3L), other.groupedConfigIds)
    }

    @Test
    fun testToJsonFormat() {
        val hosts = listOf(
            DiscoveredSshHost(
                id = "host1",
                alias = "Server 1",
                hostname = "1.2.3.4",
                port = 2222,
                username = "admin",
                source = SshHostSource.QUICKSSH,
                quickSshConfigId = 99L
            )
        )

        val jsonStr = SshHostDiscoveryHelper.toJson(hosts)
        val json = JSONObject(jsonStr)
        assertEquals("ok", json.getString("status"))
        assertEquals(1, json.getInt("count"))

        val device = json.getJSONArray("devices").getJSONObject(0)
        assertEquals("Server 1", device.getString("alias"))
        assertEquals("1.2.3.4", device.getString("hostname"))
        assertEquals(2222, device.getInt("port"))
        assertEquals("admin", device.getString("username"))
        assertEquals("QUICKSSH", device.getString("source"))
        assertEquals(99L, device.getLong("quickSshConfigId"))
    }
}
