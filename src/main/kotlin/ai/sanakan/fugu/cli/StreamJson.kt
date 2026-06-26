package ai.sanakan.fugu.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Models for the JSONL event stream produced by `codex exec --json`.
 *
 * Fugu is reached through the Codex CLI configured with the Sakana provider, so
 * the wire format is Codex's experimental `thread.* / turn.* / item.*` schema.
 * Parsing is intentionally lenient ([FuguJson]) and tolerates both the nested
 * `{"type":"item.completed","item":{...}}` envelope and older flat events, since
 * the schema is explicitly experimental and shifts between CLI versions.
 */
object FuguJson {
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }
}

/** A parsed inbound event, normalized away from Codex's raw envelope. */
sealed interface FuguEvent {
    /** Thread/session started; [sessionId] is reused to `resume` the next turn. */
    data class Init(val sessionId: String?, val model: String?) : FuguEvent

    /** A completed assistant message. */
    data class AgentMessage(val text: String) : FuguEvent

    /** A tool/command item began running. */
    data class ToolStarted(val id: String, val name: String, val input: JsonObject) : FuguEvent

    /** A tool/command item finished (may arrive without a preceding [ToolStarted]). */
    data class ToolCompleted(
        val id: String,
        val name: String,
        val input: JsonObject,
        val result: String,
        val isError: Boolean,
    ) : FuguEvent

    /** Terminal event for the turn (success or failure). */
    data class Result(val isError: Boolean, val text: String?, val outputTokens: Long?) : FuguEvent

    /** A modeled-but-uninteresting event (reasoning, turn.started, …). */
    data class Other(val type: String) : FuguEvent

    /** A non-JSON line (CLI diagnostics on stdout). */
    data class Raw(val line: String) : FuguEvent
}

object StreamJsonParser {

    fun parseLine(line: String): FuguEvent {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return FuguEvent.Raw(line)
        val obj = runCatching { FuguJson.lenient.parseToJsonElement(trimmed).jsonObject }
            .getOrNull() ?: return FuguEvent.Raw(line)

        return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "thread.started" -> FuguEvent.Init(threadId(obj), obj["model"].str())
            "turn.completed" -> FuguEvent.Result(false, null, outputTokens(obj))
            "turn.failed" -> FuguEvent.Result(true, errorMessage(obj), null)
            "error" -> FuguEvent.Result(true, errorMessage(obj), null)
            "item.started", "item.updated" -> parseItem(obj, completed = false)
            "item.completed" -> parseItem(obj, completed = true)
            // Flat-event fallbacks for older CLI versions.
            "agent_message" -> FuguEvent.AgentMessage(obj["text"].str() ?: "")
            null -> FuguEvent.Other("unknown")
            else -> FuguEvent.Other(type)
        }
    }

    private fun parseItem(obj: JsonObject, completed: Boolean): FuguEvent {
        // Either {"item":{...}} or a flat item object carrying item_type itself.
        val item = obj["item"]?.jsonObject ?: obj
        val itemType = (item["item_type"] ?: item["type"]).str() ?: return FuguEvent.Other("item")
        val id = item["id"].str() ?: itemType

        return when (itemType) {
            "agent_message" ->
                if (completed) FuguEvent.AgentMessage(item["text"].str() ?: "")
                else FuguEvent.Other("agent_message.partial")

            "reasoning" -> FuguEvent.Other("reasoning")

            "command_execution" -> tool(
                completed, id, name = "Shell",
                input = buildJsonObject { item["command"].str()?.let { put("command", it) } },
                result = item["aggregated_output"].str() ?: item["output"].str() ?: "",
                isError = (item["exit_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) != 0,
            )

            "file_change" -> tool(
                completed, id, name = "Edit",
                input = buildJsonObject { fileChangeTarget(item)?.let { put("file_path", it) } },
                result = fileChangeSummary(item),
                isError = false,
            )

            "mcp_tool_call" -> tool(
                completed, id,
                name = listOfNotNull(item["server"].str(), item["tool"].str()).joinToString("/")
                    .ifEmpty { item["name"].str() ?: "MCP" },
                input = item["arguments"]?.jsonObject ?: JsonObject(emptyMap()),
                result = stringify(item["result"]),
                isError = item["is_error"].str() == "true",
            )

            "web_search" -> tool(
                completed, id, name = "WebSearch",
                input = buildJsonObject { item["query"].str()?.let { put("pattern", it) } },
                result = "", isError = false,
            )

            "todo_list" -> tool(
                completed, id, name = "Plan",
                input = JsonObject(emptyMap()),
                result = stringify(item["items"]), isError = false,
            )

            else -> FuguEvent.Other(itemType)
        }
    }

    private fun tool(
        completed: Boolean,
        id: String,
        name: String,
        input: JsonObject,
        result: String,
        isError: Boolean,
    ): FuguEvent =
        if (completed) FuguEvent.ToolCompleted(id, name, input, result, isError)
        else FuguEvent.ToolStarted(id, name, input)

    // --- field helpers ---------------------------------------------------------

    private fun threadId(obj: JsonObject): String? =
        obj["thread_id"].str()
            ?: obj["session_id"].str()
            ?: obj["thread"]?.jsonObject?.get("id").str()
            ?: obj["id"].str()

    private fun outputTokens(obj: JsonObject): Long? =
        obj["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun errorMessage(obj: JsonObject): String? =
        obj["message"].str()
            ?: obj["error"]?.jsonObject?.get("message").str()
            ?: obj["error"].str()

    private fun fileChangeTarget(item: JsonObject): String? {
        item["path"].str()?.let { return it }
        val changes = item["changes"] as? JsonArray ?: return null
        return (changes.firstOrNull() as? JsonObject)?.get("path").str()
    }

    private fun fileChangeSummary(item: JsonObject): String {
        val changes = item["changes"] as? JsonArray ?: return item["path"].str() ?: ""
        return changes.mapNotNull { (it as? JsonObject) }.joinToString("\n") { c ->
            val kind = c["kind"].str() ?: c["type"].str() ?: "change"
            val path = c["path"].str() ?: "?"
            "$kind: $path"
        }
    }

    private fun stringify(el: JsonElement?): String = when (el) {
        null -> ""
        is JsonPrimitive -> el.contentOrNull ?: ""
        is JsonArray -> el.joinToString("\n") { stringify(it) }
        else -> el.toString()
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
