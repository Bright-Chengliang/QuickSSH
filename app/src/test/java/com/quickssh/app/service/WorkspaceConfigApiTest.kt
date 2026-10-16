package com.quickssh.app.service

import com.quickssh.app.data.InMemoryWorkspaceStore
import com.quickssh.app.data.PINNED_FOLDER_ID
import com.quickssh.app.data.SshFolder
import com.quickssh.app.data.SshServerNode
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.SshWorkspaceProfile
import com.quickssh.app.data.WorkspaceSnapshot
import com.quickssh.app.data.snapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceConfigApiTest {

    private fun seededStore() = InMemoryWorkspaceStore(
        WorkspaceSnapshot(
            servers = listOf(
                SshServerNode(id = 1, displayName = "gpu", host = "10.0.0.5", port = 22, username = "ubuntu",
                    authType = AUTH_TYPE_PASSWORD, encryptedPassword = "enc:secret", sortOrder = 2),
                SshServerNode(id = 2, displayName = "web", host = "web.example.net", port = 2222, username = "deploy",
                    authType = AUTH_TYPE_PRIVATE_KEY, encryptedPrivateKey = "enc:SSHKEYDATA", sortOrder = 1)
            ),
            workspaces = listOf(
                SshWorkspaceProfile(id = 10, serverNodeId = 1, name = "train", workDirectory = "/data/train",
                    persistentSessionMode = "tmux", terminalFontSizeSp = 14, folderId = 100, preConnectTunnelPresetId = 1000, sortOrder = 2),
                SshWorkspaceProfile(id = 11, serverNodeId = 1, name = "logs", workDirectory = "/var/log", sortOrder = 1),
                SshWorkspaceProfile(id = 20, serverNodeId = 2, name = "site", folderId = PINNED_FOLDER_ID, sortOrder = 1)
            ),
            folders = listOf(SshFolder(id = 100, name = "GPU", sortOrder = 1)),
            tunnels = listOf(SshTunnelPreset(id = 1000, workspaceId = 10, name = "jupyter", remotePort = 8888, localPort = 18888))
        )
    )

    private fun api(store: InMemoryWorkspaceStore) =
        WorkspaceConfigApi(store, encrypt = { "enc:$it" }, decrypt = { it.removePrefix("enc:") }, now = { 42L })

    private fun expectError(fragment: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected WorkspaceApiException containing '$fragment'")
        } catch (e: WorkspaceApiException) {
            assertTrue(e.message, e.message!!.contains(fragment))
        }
    }

    @Test
    fun treeNestsHierarchyAndNeverExposesSecrets() = runBlocking {
        val tree = api(seededStore()).tree()
        val text = tree.toString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("SSHKEYDATA"))

        val gpu = tree.getJSONArray("servers").getJSONObject(0)
        assertEquals("gpu", gpu.getString("name"))
        assertTrue(gpu.getBoolean("hasPassword"))
        val train = gpu.getJSONArray("workspaces").getJSONObject(0)
        assertEquals("train", train.getString("name"))
        assertEquals("GPU", train.getString("folder"))
        assertEquals("jupyter", train.getString("preConnectTunnel"))
        assertEquals(8888, train.getJSONArray("tunnels").getJSONObject(0).getInt("remotePort"))
        val site = tree.getJSONArray("servers").getJSONObject(1).getJSONArray("workspaces").getJSONObject(0)
        assertTrue(site.getBoolean("pinned"))
        assertTrue(site.isNull("folderId"))
    }

    @Test
    fun workspaceUpdateKeepsAbsentFieldsAndClearsExplicitNulls() = runBlocking {
        val store = seededStore()
        api(store).call("workspaces/update", JSONObject("""{"id":10,"workDirectory":null,"persistentSessionMode":"screen"}"""))

        val ws = store.workspaces().first { it.id == 10L }
        assertNull(ws.workDirectory)
        assertEquals("screen", ws.persistentSessionMode)
        assertEquals(14, ws.terminalFontSizeSp)
        assertEquals(100L, ws.folderId)
        assertEquals(1000L, ws.preConnectTunnelPresetId)
    }

    @Test
    fun serverUpdateKeepsCredentialUnlessGiven() = runBlocking {
        val store = seededStore()
        api(store).call("servers/update", JSONObject("""{"id":1,"host":"10.0.0.6"}"""))
        assertEquals("enc:secret", store.servers().first { it.id == 1L }.encryptedPassword)

        api(store).call("servers/update", JSONObject("""{"id":1,"password":"new-pass"}"""))
        assertEquals("enc:new-pass", store.servers().first { it.id == 1L }.encryptedPassword)
        assertEquals("10.0.0.6", store.servers().first { it.id == 1L }.host)
    }

    @Test
    fun unknownKeysAndInvalidValuesAreRejected() {
        val store = seededStore()
        expectError("workdir") { api(store).call("workspaces/update", JSONObject("""{"id":10,"workdir":"/x"}""")) }
        expectError("persistentSessionMode") { api(store).call("workspaces/update", JSONObject("""{"id":10,"persistentSessionMode":"zellij"}""")) }
        expectError("does not exist") { api(store).call("workspaces/update", JSONObject("""{"id":10,"folder":"Nope"}""")) }
        expectError("either pinned or in a folder") { api(store).call("workspaces/update", JSONObject("""{"id":10,"folder":"GPU","pinned":true}""")) }
        expectError("Unknown action") { api(store).call("hosts/create", JSONObject()) }
        expectError("does not exist") { api(store).call("workspaces/update", JSONObject("""{"id":11,"preConnectTunnelId":4242}""")) }
        expectError("No tunnel 'nope'") { api(store).call("workspaces/update", JSONObject("""{"id":11,"preConnectTunnel":"nope"}""")) }
    }

    @Test
    fun preConnectTunnelMayBelongToAnotherWorkspaceAndSurvivesExportApply() = runBlocking {
        val store = seededStore()
        // "site" on server web is reached through the jupyter tunnel of gpu/train (a jump host).
        api(store).call("workspaces/update", JSONObject("""{"id":20,"preConnectTunnel":"gpu/train/jupyter"}"""))
        assertEquals(1000L, store.workspaces().first { it.id == 20L }.preConnectTunnelPresetId)
        api(store).call("workspaces/update", JSONObject("""{"id":20,"preConnectTunnel":null}"""))
        api(store).call("workspaces/update", JSONObject("""{"id":20,"preConnectTunnel":"jupyter"}""")) // unique name
        assertEquals(1000L, store.workspaces().first { it.id == 20L }.preConnectTunnelPresetId)

        val document = api(store).exportDocument()
        val site = document.getJSONArray("servers").getJSONObject(1).getJSONArray("workspaces").getJSONObject(0)
        assertEquals("gpu/train/jupyter", site.getString("preConnectTunnel"))
        val before = store.snapshot()
        assertFalse(api(store).apply(document, prune = true).getBoolean("changed"))
        assertEquals(before, store.snapshot())

        // Deleting the jump workspace removes its tunnel and the reference to it.
        api(store).call("workspaces/delete", JSONObject("""{"id":10}"""))
        assertNull(store.workspaces().first { it.id == 20L }.preConnectTunnelPresetId)
    }

    @Test
    fun applyResolvesPreConnectToTunnelCreatedLaterInTheDocument() = runBlocking {
        val store = seededStore()
        val result = api(store).apply(
            JSONObject(
                """
                {"servers":[
                  {"name":"home","host":"127.0.0.1","port":22022,"username":"me","authType":"PASSWORD",
                   "workspaces":[{"name":"pc","preConnectTunnel":"bastion/main/home-ssh"}]},
                  {"name":"bastion","host":"1.2.3.4","username":"root","authType":"PASSWORD",
                   "workspaces":[{"name":"main","tunnels":[{"name":"home-ssh","remoteHost":"10.0.0.2","remotePort":22,"localPort":22022}]}]}
                ]}
                """.trimIndent()
            ),
            prune = false
        )
        assertTrue(result.toString(), result.getBoolean("changed"))
        val pc = store.workspaces().first { it.name == "pc" }
        assertEquals(store.tunnels().first { it.name == "home-ssh" }.id, pc.preConnectTunnelPresetId)
    }

    @Test
    fun treeOutputCanBeEchoedBackIntoUpdate() = runBlocking {
        val store = seededStore()
        val tunnel = api(store).tree().getJSONArray("servers").getJSONObject(0)
            .getJSONArray("workspaces").getJSONObject(0).getJSONArray("tunnels").getJSONObject(0)
        tunnel.put("localPort", 19999)
        api(store).call("tunnels/update", tunnel)
        assertEquals(19999, store.tunnels().single().localPort)
    }

    @Test
    fun deletingLastWorkspaceRemovesServerAndTunnelDeleteClearsPreConnect() = runBlocking {
        val store = seededStore()
        val result = api(store).call("workspaces/delete", JSONObject("""{"id":20}"""))
        assertTrue(result.getBoolean("serverDeleted"))
        assertTrue(store.servers().none { it.id == 2L })

        api(store).call("tunnels/delete", JSONObject("""{"id":1000}"""))
        assertNull(store.workspaces().first { it.id == 10L }.preConnectTunnelPresetId)
    }

    @Test
    fun createWorkspaceWithInlineServerReusesMatchingNode() = runBlocking {
        val store = seededStore()
        api(store).call(
            "workspaces/create",
            JSONObject("""{"name":"eval","folder":"GPU","server":{"host":"10.0.0.5","username":"ubuntu","authType":"PASSWORD"}}""")
        )
        assertEquals(2, store.servers().size)
        val eval = store.workspaces().first { it.name == "eval" }
        assertEquals(1L, eval.serverNodeId)
        assertEquals(100L, eval.folderId)

        expectError("already has a workspace named") {
            api(store).call("workspaces/create", JSONObject("""{"serverId":1,"name":"eval"}"""))
        }
    }

    @Test
    fun exportedDocumentAppliesAsNoOp() = runBlocking {
        val store = seededStore()
        val before = store.snapshot()
        val result = api(store).apply(api(store).exportDocument(), prune = true)
        assertFalse(result.toString(), result.getBoolean("changed"))
        assertEquals(before, store.snapshot())
    }

    @Test
    fun applyCreatesNestedObjectsAndIsIdempotent() = runBlocking {
        val store = seededStore()
        val document = JSONObject(
            """
            {"format":"quickssh-workspace/v1","servers":[
              {"name":"lab","host":"192.168.1.20","username":"me","authType":"PASSWORD","password":"pw",
               "workspaces":[{"name":"notebooks","folder":"Lab","pinned":false,"preConnectTunnel":"lab-ui",
                 "tunnels":[{"name":"lab-ui","remotePort":8080}]}]}
            ]}
            """.trimIndent()
        )
        val first = api(store).apply(document, prune = false)
        assertTrue(first.getBoolean("changed"))
        val lab = store.servers().first { it.displayName == "lab" }
        assertEquals("enc:pw", lab.encryptedPassword)
        val notebooks = store.workspaces().first { it.serverNodeId == lab.id }
        assertEquals(store.folders().first { it.name == "Lab" }.id, notebooks.folderId)
        assertEquals(store.tunnels().first { it.name == "lab-ui" }.id, notebooks.preConnectTunnelPresetId)
        assertEquals(3, store.servers().size)

        val second = api(store).apply(document, prune = false)
        assertFalse(second.toString(), second.getBoolean("changed"))
    }

    @Test
    fun applyMatchesRenamedServerByIdentity() = runBlocking {
        val store = seededStore()
        val result = api(store).apply(
            JSONObject("""{"servers":[{"name":"gpu-a100","host":"10.0.0.5","port":22,"username":"ubuntu","authType":"PASSWORD"}]}"""),
            prune = false
        )
        assertEquals(listOf("update server 'gpu-a100': name"), (0 until result.getJSONArray("changes").length()).map {
            result.getJSONArray("changes").getString(it)
        })
        assertEquals(2, store.workspaces().count { it.serverNodeId == 1L })
    }

    @Test
    fun pruneDeletesUnlistedObjectsAndFollowsListOrder() = runBlocking {
        val store = seededStore()
        val document = api(store).exportDocument()
        val servers = document.getJSONArray("servers")
        val gpu = servers.getJSONObject(0)
        gpu.getJSONArray("workspaces").remove(1) // drop "logs"
        servers.remove(1) // drop "web"
        document.put("folders", org.json.JSONArray())
        gpu.getJSONArray("workspaces").getJSONObject(0).put("folder", JSONObject.NULL)

        val before = store.snapshot()
        val dryRunStore = InMemoryWorkspaceStore(before)
        val plan = api(dryRunStore).apply(document, prune = true)
        assertEquals(before, store.snapshot())
        val changes = plan.getJSONArray("changes").toString()
        assertTrue(changes, changes.contains("delete workspace 'gpu/logs'"))
        assertTrue(changes, changes.contains("delete server 'web'"))
        assertTrue(changes, changes.contains("delete folder 'GPU'"))

        api(store).apply(document, prune = true)
        assertEquals(listOf(1L), store.servers().map { it.id })
        assertEquals(listOf(10L), store.workspaces().map { it.id })
        assertTrue(store.folders().isEmpty())
    }

    @Test
    fun pruneRefusesToEmptyAServer() {
        val store = seededStore()
        expectError("needs at least one") {
            api(store).apply(
                JSONObject("""{"servers":[{"name":"gpu","workspaces":[]},{"name":"web"}]}"""),
                prune = true
            )
        }
    }

    @Test
    fun applyRejectsUnknownServerWithoutHost() {
        expectError("give host and username") {
            api(seededStore()).apply(JSONObject("""{"servers":[{"name":"ghost"}]}"""), prune = false)
        }
    }
}
