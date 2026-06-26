package ai.sanakan.fugu.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Plain mutable beans persisted via IntelliJ's XML serializer. They mirror the
 * runtime [ChatMessage] / [ToolCall] model but use only serializer-friendly
 * fields (no [StringBuilder], no [JsonObject]). The full tool `input` is reduced
 * to a single [target] string, which is all the UI needs to re-render and to
 * re-open a changed file.
 */
class PersistedTool {
    var name: String = ""
    var target: String? = null
    var result: String? = null
    var isError: Boolean = false
}

class PersistedMessage {
    var role: String = ChatRole.ASSISTANT.name
    var text: String = ""
    var tools: MutableList<PersistedTool> = mutableListOf()
}

class FuguSessionState {
    var threadId: String? = null
    var messages: MutableList<PersistedMessage> = mutableListOf()
}

// --- mapping between runtime and persisted models ------------------------------

internal fun ChatMessage.toPersisted(): PersistedMessage = PersistedMessage().also { p ->
    p.role = role.name
    p.text = text.toString()
    p.tools = toolCalls.mapTo(mutableListOf()) { call ->
        PersistedTool().also {
            it.name = call.name
            it.target = call.target
            it.result = call.result
            it.isError = call.isError
        }
    }
}

internal fun PersistedMessage.toRuntime(): ChatMessage {
    val runtimeRole = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.ASSISTANT)
    val msg = ChatMessage(runtimeRole, text)
    tools.forEach { t ->
        msg.toolCalls.add(ToolCall(id = t.name + "@" + (t.target ?: ""), name = t.name, input = inputFor(t.name, t.target), result = t.result, isError = t.isError))
    }
    return msg
}

/** Reconstructs a minimal tool `input` so [ToolCall.target] resolves after reload. */
private fun inputFor(name: String, target: String?): JsonObject {
    if (target == null) return JsonObject(emptyMap())
    val key = when (name) {
        "Edit" -> "file_path"
        "WebSearch" -> "pattern"
        else -> "command"
    }
    return buildJsonObject { put(key, target) }
}
