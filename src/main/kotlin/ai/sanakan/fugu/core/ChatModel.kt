package ai.sanakan.fugu.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ChatRole { USER, ASSISTANT, SYSTEM, ERROR }

/** A rendered tool invocation shown as a card in the transcript. */
data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
    var result: String? = null,
    var isError: Boolean = false,
) {
    /** A short, human-readable summary of what the tool is acting on. */
    val target: String?
        get() = listOf("file_path", "path", "command", "pattern", "url")
            .firstNotNullOfOrNull { key -> (input[key] as? JsonPrimitive)?.contentOrNull }
}

/**
 * One entry in the chat transcript. Assistant entries accumulate streamed text
 * and any tool calls produced during the turn.
 */
class ChatMessage(
    val role: ChatRole,
    initialText: String = "",
) {
    val text = StringBuilder(initialText)
    val toolCalls = mutableListOf<ToolCall>()

    /** True while the assistant turn is still streaming. */
    var streaming: Boolean = false

    fun appendText(s: String) {
        text.append(s)
    }

    val isEmpty: Boolean
        get() = text.isEmpty() && toolCalls.isEmpty()
}
