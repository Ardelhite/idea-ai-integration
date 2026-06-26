package ai.sanakan.fugu.core

import ai.sanakan.fugu.settings.McpMode
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Mirrors the MCP servers the user already configured for Claude Code into Codex's
 * `config.toml`, so Karato (which drives Codex) can use the same servers — e.g.
 * Playwright — without the user re-installing or re-declaring them.
 *
 * Claude stores MCP servers in:
 *  - `<project>/.mcp.json`                         → project scope (shared)
 *  - `~/.claude.json` → `projects[<path>].mcpServers` → local scope (this project)
 *  - `~/.claude.json` → `mcpServers`                 → user scope (all projects)
 *
 * We translate those into Codex `[mcp_servers.<name>]` tables inside a managed
 * region so hand-written entries are preserved. All file I/O — call OFF the EDT.
 */
object McpConfig {

    private const val BEGIN = "# >>> karato-managed mcp servers (mirrored from Claude) >>>"
    private const val END = "# <<< karato-managed mcp servers <<<"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

    data class Server(
        val name: String,
        val command: String?,
        val args: List<String>,
        val env: Map<String, String>,
        val url: String?,
    )

    /** Rewrites the managed MCP block to reflect [mode] for [project]. */
    fun apply(project: Project, mode: McpMode) {
        writeManaged(if (mode == McpMode.OFF) emptyList() else collect(project, mode))
    }

    /** The servers that would be enabled for [mode] (for status/preview). */
    fun collect(project: Project, mode: McpMode): List<Server> {
        if (mode == McpMode.OFF) return emptyList()
        val home = File(System.getProperty("user.home"))
        val base = project.basePath
        val merged = LinkedHashMap<String, Server>()

        // project scope — <project>/.mcp.json
        base?.let { readServersObject(File(it, ".mcp.json"))?.let { obj -> parse(obj).forEach { merged[it.name] = it } } }

        val claudeJson = File(home, ".claude.json")
        if (claudeJson.isFile) {
            val root = runCatching { json.parseToJsonElement(claudeJson.readText()).jsonObject }.getOrNull()
            if (root != null) {
                // local scope — ~/.claude.json projects[<path>].mcpServers
                base?.let { b ->
                    root["projects"]?.jsonObject?.get(b)?.jsonObject?.get("mcpServers")?.jsonObject
                        ?.let { parse(it).forEach { s -> merged[s.name] = s } }
                }
                // user scope — only for ALL
                if (mode == McpMode.ALL) {
                    root["mcpServers"]?.jsonObject?.let { parse(it).forEach { s -> merged.putIfAbsent(s.name, s) } }
                }
            }
        }
        return merged.values.toList()
    }

    private fun readServersObject(file: File): JsonObject? {
        if (!file.isFile) return null
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        return root["mcpServers"]?.jsonObject
    }

    private fun parse(servers: JsonObject): List<Server> = servers.mapNotNull { (name, value) ->
        val obj = value as? JsonObject ?: return@mapNotNull null
        val command = obj["command"]?.jsonPrimitive?.contentOrNull
        val url = obj["url"]?.jsonPrimitive?.contentOrNull
        if (command == null && url == null) return@mapNotNull null
        val args = obj["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val env = obj["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        Server(name, command, args, env, url)
    }

    private fun writeManaged(servers: List<Server>) {
        val home = CodexConfig.codexHome()
        if (!home.exists()) home.mkdirs()
        val file = CodexConfig.configFile()
        val existing = if (file.isFile) file.readText() else ""
        val base = stripManaged(existing).trimEnd('\n')

        val block = if (servers.isEmpty()) "" else buildString {
            append(BEGIN).append('\n')
            servers.forEach { append(toToml(it)) }
            append(END).append('\n')
        }
        val out = buildString {
            if (base.isNotBlank()) append(base).append('\n')
            if (block.isNotEmpty()) {
                if (base.isNotBlank()) append('\n')
                append(block)
            }
        }
        file.writeText(out)
    }

    private fun stripManaged(text: String): String {
        val start = text.indexOf(BEGIN)
        if (start < 0) return text
        val endIdx = text.indexOf(END, start)
        val after = if (endIdx < 0) text.length else endIdx + END.length
        return (text.substring(0, start) + text.substring(after)).replace(Regex("\n{3,}"), "\n\n")
    }

    private fun toToml(s: Server): String = buildString {
        val header = if (BARE_KEY.matches(s.name)) s.name else "\"${esc(s.name)}\""
        append("[mcp_servers.").append(header).append("]\n")
        if (s.command != null) {
            append("command = \"").append(esc(s.command)).append("\"\n")
            if (s.args.isNotEmpty()) {
                append("args = [").append(s.args.joinToString(", ") { "\"${esc(it)}\"" }).append("]\n")
            }
        } else if (s.url != null) {
            append("url = \"").append(esc(s.url)).append("\"\n")
        }
        if (s.env.isNotEmpty()) {
            append("[mcp_servers.").append(header).append(".env]\n")
            s.env.forEach { (k, v) ->
                val key = if (BARE_KEY.matches(k)) k else "\"${esc(k)}\""
                append(key).append(" = \"").append(esc(v)).append("\"\n")
            }
        }
        append('\n')
    }

    private fun esc(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")
}
