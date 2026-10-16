package com.quickssh.app.service

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMcpHandlerTest {

    private data class Call(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private val calls = mutableListOf<Call>()
    private var nextReply = """{"ok":true,"data":{"changed":false}}"""

    private val handler = AgentMcpHandler(
        serverVersion = "9.9.9",
        workspaceApi = { method, path, query, body ->
            calls += Call(method, path, query, body)
            nextReply
        },
        devices = { """[{"host":"10.0.0.5"}]""" }
    )

    private fun rpc(method: String, params: JSONObject? = null, id: Any = 1): JSONObject {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        params?.let { request.put("params", it) }
        return JSONObject(runBlocking { handler.handle(request.toString()) }!!)
    }

    private fun callTool(name: String, arguments: JSONObject = JSONObject()) =
        rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments)).getJSONObject("result")

    @Test
    fun initializeNegotiatesProtocolAndAdvertisesTools() {
        val result = rpc("initialize", JSONObject().put("protocolVersion", "2025-03-26")).getJSONObject("result")
        assertEquals("2025-03-26", result.getString("protocolVersion"))
        assertEquals("quickssh", result.getJSONObject("serverInfo").getString("name"))
        assertEquals("9.9.9", result.getJSONObject("serverInfo").getString("version"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))

        val unknown = rpc("initialize", JSONObject().put("protocolVersion", "1999-01-01")).getJSONObject("result")
        assertEquals(AgentMcpHandler.SUPPORTED_PROTOCOL_VERSIONS.first(), unknown.getString("protocolVersion"))
    }

    @Test
    fun notificationsAndClientResponsesGetNoReply() = runBlocking {
        assertNull(handler.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(handler.handle("""{"jsonrpc":"2.0","id":4,"result":{}}"""))
        assertNull(handler.handle("""[{"jsonrpc":"2.0","method":"notifications/initialized"}]"""))
    }

    @Test
    fun toolsListHasSchemasAndReadOnlyHints() {
        val tools = rpc("tools/list").getJSONObject("result").getJSONArray("tools")
        val byName = (0 until tools.length()).map { tools.getJSONObject(it) }.associateBy { it.getString("name") }
        assertEquals(
            setOf("quickssh_tree", "quickssh_export", "quickssh_apply", "quickssh_call", "quickssh_devices", "quickssh_guide"),
            byName.keys
        )
        byName.values.forEach { assertEquals("object", it.getJSONObject("inputSchema").getString("type")) }
        assertTrue(byName.getValue("quickssh_tree").getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(byName.getValue("quickssh_apply").getJSONObject("annotations").getBoolean("readOnlyHint"))
        val actions = byName.getValue("quickssh_call").getJSONObject("inputSchema").getJSONObject("properties")
            .getJSONObject("action").getJSONArray("enum")
        assertEquals(12, actions.length())
    }

    @Test
    fun applyMapsToWorkspaceApiAndRequiresExplicitDryRun() {
        val document = JSONObject().put("servers", JSONArray())
        val missing = callTool("quickssh_apply", JSONObject().put("document", document))
        assertTrue(missing.getBoolean("isError"))
        assertTrue(calls.isEmpty())

        callTool("quickssh_apply", JSONObject().put("document", document).put("dryRun", true).put("prune", true))
        assertEquals(Call("POST", "apply", mapOf("dryRun" to "1", "prune" to "1"), document.toString()), calls.single())

        callTool("quickssh_apply", JSONObject().put("document", document).put("dryRun", false))
        assertEquals(emptyMap<String, String>(), calls.last().query)
    }

    @Test
    fun callForwardsActionAndFlagsApiErrors() {
        nextReply = """{"ok":false,"error":"Unknown key 'workdir'"}"""
        val result = callTool(
            "quickssh_call",
            JSONObject().put("action", "workspaces/update").put("params", JSONObject().put("id", 10).put("workdir", "/x"))
        )
        assertEquals("workspaces/update", calls.single().path)
        assertTrue(JSONObject(calls.single().body).similar(JSONObject("""{"id":10,"workdir":"/x"}""")))
        assertTrue(result.getBoolean("isError"))
        assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("workdir"))

        val bad = callTool("quickssh_call", JSONObject().put("action", "hosts/create").put("params", JSONObject()))
        assertTrue(bad.getBoolean("isError"))
        assertEquals(1, calls.size)
    }

    @Test
    fun readToolsReturnText() {
        val tree = callTool("quickssh_tree")
        assertFalse(tree.getBoolean("isError"))
        assertEquals(Call("GET", "tree", emptyMap(), ""), calls.single())
        val devices = callTool("quickssh_devices")
        assertTrue(devices.getJSONArray("content").getJSONObject(0).getString("text").contains("10.0.0.5"))
    }

    @Test
    fun protocolErrors() = runBlocking {
        val unknownTool = rpc("tools/call", JSONObject().put("name", "rm_rf"))
        assertEquals(AgentMcpHandler.INVALID_PARAMS, unknownTool.getJSONObject("error").getInt("code"))
        assertEquals(AgentMcpHandler.METHOD_NOT_FOUND, rpc("resources/list").getJSONObject("error").getInt("code"))
        val parse = JSONObject(handler.handle("{not json")!!)
        assertEquals(AgentMcpHandler.PARSE_ERROR, parse.getJSONObject("error").getInt("code"))
        assertTrue(parse.isNull("id"))

        val batch = JSONArray(handler.handle("""[{"jsonrpc":"2.0","id":"a","method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")!!)
        assertEquals(1, batch.length())
        assertEquals("a", batch.getJSONObject(0).getString("id"))
    }
}
