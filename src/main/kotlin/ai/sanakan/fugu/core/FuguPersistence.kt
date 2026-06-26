package ai.sanakan.fugu.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Plain mutable beans persisted via IntelliJ's XML serializer. They mirror the
 * runtime [ChatMessage] block model with serializer-friendly fields.
 */
class PersistedBlock {
    var kind: String = "text" // "text" | "tool"
    var text: String = ""
    var toolName: String = ""
    var toolTarget: String? = null
    var toolResult: String? = null
    var toolError: Boolean = false
    var added: Int = 0
    var removed: Int = 0
}

class PersistedMessage {
    var role: String = ChatRole.ASSISTANT.name
    var model: String? = null
    var blocks: MutableList<PersistedBlock> = mutableListOf()
}

class FuguSessionState {
    var threadId: String? = null
    var messages: MutableList<PersistedMessage> = mutableListOf()
}

// --- mapping between runtime and persisted models ------------------------------

internal fun ChatMessage.toPersisted(): PersistedMessage = PersistedMessage().also { p ->
    p.role = role.name
    p.model = model
    p.blocks = blocks.mapTo(mutableListOf()) { b ->
        PersistedBlock().also { pb ->
            when (b) {
                is TextBlock -> {
                    pb.kind = "text"
                    pb.text = b.text.toString()
                }
                is ToolBlock -> {
                    pb.kind = "tool"
                    pb.toolName = b.call.name
                    pb.toolTarget = b.call.target
                    pb.toolResult = b.call.result
                    pb.toolError = b.call.isError
                    pb.added = b.call.added
                    pb.removed = b.call.removed
                }
            }
        }
    }
}

internal fun PersistedMessage.toRuntime(): ChatMessage {
    val runtimeRole = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.ASSISTANT)
    val msg = ChatMessage(runtimeRole).apply { model = this@toRuntime.model }
    blocks.forEach { pb ->
        if (pb.kind == "tool") {
            msg.addTool(
                ToolCall(
                    id = pb.toolName + "@" + (pb.toolTarget ?: ""),
                    name = pb.toolName,
                    input = inputFor(pb),
                    result = pb.toolResult,
                    isError = pb.toolError,
                ),
            )
        } else {
            msg.addText(pb.text)
        }
    }
    return msg
}

/** Reconstructs a minimal tool `input` so [ToolCall] accessors resolve after reload. */
private fun inputFor(pb: PersistedBlock): JsonObject = buildJsonObject {
    pb.toolTarget?.let {
        val key = when (pb.toolName) {
            "Edit" -> "file_path"
            "WebSearch" -> "pattern"
            else -> "command"
        }
        put(key, it)
    }
    put("added", pb.added)
    put("removed", pb.removed)
}
