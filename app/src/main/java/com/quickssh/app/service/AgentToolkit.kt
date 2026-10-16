package com.quickssh.app.service

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Installs the agent-facing toolkit: the `quickssh` CLI, the bridge token env file it reads, the
 * `quickssh-workspace` agent skill and an opencode MCP registration for the bridge's `/mcp` endpoint. The CLI and skill ship as assets so the built-in terminal,
 * the Termux setup script and `GET /api/v2/skill` all serve the same bytes.
 */
object AgentToolkit {
    const val SKILL_NAME = "quickssh-workspace"
    private const val CLI_ASSET = "agent/quickssh"
    private const val SKILL_ASSET = "agent/skills/$SKILL_NAME/SKILL.md"

    /** Skill path relative to HOME; opencode and other agents discover `~/.agents/skills/<name>/SKILL.md`. */
    const val SKILL_HOME_PATH = ".agents/skills/$SKILL_NAME/SKILL.md"

    /** Env file path relative to HOME, in the app sandbox and in Termux alike. */
    const val ENV_HOME_PATH = ".config/quickssh/bridge.env"

    /** Raw token (no newline) referenced by the opencode MCP entry as `{file:~/...}`. */
    const val TOKEN_HOME_PATH = ".config/quickssh/bridge.token"

    /** opencode's global config, relative to HOME. */
    const val OPENCODE_CONFIG_HOME_PATH = ".config/opencode/opencode.json"

    const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/sh"
    const val SYSTEM_SHELL = "/system/bin/sh"

    fun cliTemplate(context: Context): String = readAsset(context, CLI_ASSET)

    fun skillMarkdown(context: Context): String = readAsset(context, SKILL_ASSET)

    fun cliScript(template: String, shebang: String, envFile: String, bridgePort: Int): String = template
        .replace("@QUICKSSH_SHEBANG@", shebang)
        .replace("@QUICKSSH_ENV_FILE@", envFile)
        .replace("@QUICKSSH_DEFAULT_URL@", "http://127.0.0.1:$bridgePort")

    fun envFileContent(bridgePort: Int, token: String): String =
        "export QUICKSSH_BRIDGE_URL=http://127.0.0.1:$bridgePort\nexport QUICKSSH_BRIDGE_TOKEN=$token\n"

    fun localEnvFile(context: Context): File = File(context.filesDir, ENV_HOME_PATH)

    /**
     * opencode config registering the bridge's `/mcp` endpoint as the `quickssh` MCP server. The token is
     * read from [tokenFile] (absolute: opencode's `{file:~/...}` expansion proved unreliable) at opencode
     * start, so regenerating it needs no config rewrite. opencode 2.x nests servers under `mcp.servers`;
     * [legacyFormat] writes the 0.x/1.x layout (`mcp.<name>`), which 2.x silently ignores.
     */
    fun opencodeConfigContent(bridgePort: Int, tokenFile: String, legacyFormat: Boolean = false): String {
        val server = """
            "quickssh": {
              "type": "remote",
              "url": "http://127.0.0.1:$bridgePort/mcp",
              "enabled": true,
              "oauth": false,
              "headers": {
                "Authorization": "Bearer {file:$tokenFile}"
              }
            }
        """.trimIndent()
        val mcp = if (legacyFormat) server else "\"servers\": {\n${server.prependIndent("  ")}\n}"
        return "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  \"mcp\": {\n${mcp.prependIndent("    ")}\n  }\n}\n"
    }

    private val generatedConfigVariables = listOf(
        Regex("""127\.0\.0\.1:\d+/mcp""") to "PORT",
        Regex("""\{file:[^}]*\}""") to "FILE"
    )

    private fun normalizeOpencodeConfig(text: String): String =
        generatedConfigVariables.fold(text.trim()) { acc, (regex, placeholder) -> acc.replace(regex, placeholder) }

    /** True when [text] is a config this app generated (any port, token path or layout), so it may be rewritten. */
    fun isGeneratedOpencodeConfig(text: String): Boolean {
        val normalized = normalizeOpencodeConfig(text)
        return listOf(false, true).any { normalizeOpencodeConfig(opencodeConfigContent(0, "", it)) == normalized }
    }

    /** Writes the env file the in-app CLI sources and the raw token file; owner-only because the token unlocks stored credentials. */
    fun writeLocalBridgeEnv(context: Context, bridgePort: Int, token: String) {
        writeOwnerOnly(localEnvFile(context), envFileContent(bridgePort, token))
        writeOwnerOnly(File(context.filesDir, TOKEN_HOME_PATH), token)
    }

    private fun writeOwnerOnly(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    /** Registers the MCP server for opencode unless the user already keeps their own opencode config. */
    private fun installLocalOpencodeConfig(context: Context, bridgePort: Int) {
        val file = File(context.filesDir, OPENCODE_CONFIG_HOME_PATH)
        if (file.exists() && !isGeneratedOpencodeConfig(file.readText())) return
        file.parentFile?.mkdirs()
        file.writeText(opencodeConfigContent(bridgePort, File(context.filesDir, TOKEN_HOME_PATH).absolutePath))
    }

    /**
     * Installs the CLI into [binDir] and the skill under the app home used by the built-in terminal.
     * Called on every local terminal start so port or token changes are picked up.
     */
    fun installLocalToolkit(context: Context, binDir: File) {
        val port = runCatching { AgentBridgeService.configuredBridgePort(context) }
            .getOrDefault(AgentBridgeService.DEFAULT_BRIDGE_PORT)
        runCatching {
            val cli = File(binDir, "quickssh")
            cli.writeText(cliScript(cliTemplate(context), SYSTEM_SHELL, localEnvFile(context).absolutePath, port))
            cli.setExecutable(true, false)
        }
        runCatching { writeLocalBridgeEnv(context, port, AgentBridgeService.bridgeToken(context)) }
        runCatching { installLocalOpencodeConfig(context, port) }
        runCatching {
            val skill = File(context.filesDir, SKILL_HOME_PATH)
            skill.parentFile?.mkdirs()
            skill.writeText(skillMarkdown(context))
        }
    }

    /** Shell command the user pastes into Termux; it carries the token, so it is only shown in Settings. */
    fun termuxSetupCommand(bridgePort: Int, token: String): String =
        "curl -fsS -H 'Authorization: Bearer $token' http://127.0.0.1:$bridgePort/install.sh | bash"

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).use { String(it.readBytes(), StandardCharsets.UTF_8) }
}
