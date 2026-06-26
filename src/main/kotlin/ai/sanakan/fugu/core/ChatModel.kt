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

    /** Full shell command (for command-execution cards). */
    val command: String?
        get() = (input["command"] as? JsonPrimitive)?.contentOrNull

    /** Lines added / removed by a file-change, when known (else 0). */
    val added: Int
        get() = (input["added"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    val removed: Int
        get() = (input["removed"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
}

/** An ordered piece of an assistant turn: either prose or a tool call. */
sealed interface MessageBlock

class TextBlock(initial: String = "") : MessageBlock {
    val text = StringBuilder(initial)
}

class ToolBlock(val call: ToolCall) : MessageBlock

/**
 * One entry in the chat transcript. Assistant entries are an ordered list of
 * [MessageBlock]s, so tool cards render inline at the point they happened rather
 * than piling up at the bottom.
 */
class ChatMessage(
    val role: ChatRole,
    initialText: String = "",
) {
    val blocks = mutableListOf<MessageBlock>()

    /** Model that produced an assistant message (shown as its header). */
    var model: String? = null

    /** True while the assistant turn is still streaming. */
    var streaming: Boolean = false

    init {
        if (initialText.isNotEmpty()) blocks.add(TextBlock(initialText))
    }

    fun addText(s: String) {
        blocks.add(TextBlock(s))
    }

    /** Appends to the trailing text block (or starts one) — for line-by-line notes. */
    fun appendToLastText(s: String) {
        (blocks.lastOrNull() as? TextBlock)?.text?.append(s) ?: blocks.add(TextBlock(s))
    }

    fun addTool(call: ToolCall) {
        blocks.add(ToolBlock(call))
    }

    fun findTool(id: String): ToolCall? =
        blocks.asSequence().filterIsInstance<ToolBlock>().firstOrNull { it.call.id == id }?.call

    val isEmpty: Boolean
        get() = blocks.isEmpty()
}
