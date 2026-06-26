package ai.sanakan.fugu.cli

import ai.sanakan.fugu.settings.FuguEnv
import ai.sanakan.fugu.settings.FuguSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.util.io.BaseOutputReader
import com.intellij.util.text.nullize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives `codex app-server`: a long-lived JSON-RPC service (newline-delimited
 * JSON, the `jsonrpc` field omitted) that supports interactive approvals.
 *
 * Per-turn flow: a single process is started and handshaked once
 * (`initialize` → `initialized` → `thread/start`|`thread/resume`); each user turn
 * is a `turn/start`. Server→client `item/.../requestApproval` requests are surfaced
 * via [FuguAgentListener.onApproval] and answered with a `{ "decision": … }`.
 *
 * NOTE: app-server is an experimental protocol; the `approvalPolicy`/`sandbox`
 * enum string casing is version-sensitive (kebab-case in source, camelCase in
 * some docs). Values here follow the `main` source; pin against your binary via
 * `codex app-server generate-json-schema` if a turn fails to start.
 */
class FuguAppServerClient(
    private val workingDir: String,
    private val listener: FuguAgentListener,
) : FuguTransport {

    private val log = logger<FuguAppServerClient>()

    private var handler: OSProcessHandler? = null
    private var stdin: BufferedWriter? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (result: JsonObject?, error: JsonObject?) -> Unit>()

    private enum class Stage { IDLE, HANDSHAKING, READY }
    @Volatile private var stage = Stage.IDLE

    override var threadId: String? = null
        private set

    /** A prompt awaiting handshake completion. */
    private var queuedPrompt: String? = null
    private var model: String = "fugu"

    /** Agent-message item ids that streamed via deltas, so item/completed won't repeat them. */
    private val streamedItems = ConcurrentHashMap.newKeySet<String>()

    override val isRunning: Boolean
        get() = handler?.let { !it.isProcessTerminated } ?: false

    override fun restoreThread(id: String?) {
        threadId = id
    }

    override fun reset() {
        stop()
        threadId = null
    }

    @Synchronized
    override fun send(prompt: String, model: String) {
        this.model = model
        when (stage) {
            Stage.READY -> startTurn(prompt)
            Stage.HANDSHAKING -> queuedPrompt = prompt
            Stage.IDLE -> {
                queuedPrompt = prompt
                if (!start()) return
            }
        }
    }

    override fun stop() {
        pending.clear()
        queuedPrompt = null
        streamedItems.clear()
        stage = Stage.IDLE
        runCatching { stdin?.close() }
        handler?.destroyProcess()
        handler = null
        stdin = null
    }

    // --- process lifecycle -----------------------------------------------------

    private fun start(): Boolean {
        val settings = FuguSettings.getInstance()
        val exe = settings.cliPath.nullize(nullizeSpaces = true) ?: "codex"
        val cmd = GeneralCommandLine(exe).apply {
            addParameter("app-server")
            // The workspace-write sandbox blocks network by default; allow it (gh/curl/npm)
            // at server start. Network stays off only in read-only; full-access is open anyway.
            if (settings.allowNetwork) {
                addParameters("-c", "sandbox_workspace_write.network_access=true")
            }
            setWorkDirectory(workingDir)
            charset = StandardCharsets.UTF_8
            withEnvironment(FuguEnv.codexEnvironment())
        }
        return try {
            val osHandler = object : OSProcessHandler(cmd) {
                override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forMostlySilentProcess()
            }
            osHandler.addProcessListener(Pump())
            stdin = BufferedWriter(OutputStreamWriter(osHandler.process.outputStream, StandardCharsets.UTF_8))
            osHandler.startNotify()
            handler = osHandler
            stage = Stage.HANDSHAKING
            handshake()
            log.info("codex app-server started: ${cmd.commandLineString}")
            true
        } catch (t: Throwable) {
            stage = Stage.IDLE
            log.warn("Failed to start codex app-server", t)
            listener.onStartFailed(
                "Could not start 'codex app-server' ('$exe'). Install the Codex CLI and verify the path " +
                    "in Settings → Tools → Karato.\n${t.message ?: t.javaClass.simpleName}",
            )
            false
        }
    }

    private fun handshake() {
        val initParams = buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "Karato")
                put("title", "Karato - Agent for Sakana AI fugu")
                put("version", "0.1.0")
            })
            put("capabilities", buildJsonObject { put("experimentalApi", false) })
        }
        request("initialize", initParams) { _, error ->
            if (error != null) {
                listener.onStartFailed("app-server initialize failed: ${error["message"].str()}")
                return@request
            }
            notify("initialized", null)
            openThread()
        }
    }

    private fun openThread() {
        val settings = FuguSettings.getInstance()
        val mode = settings.permissionModeEnum
        val params = buildJsonObject {
            threadId?.let { put("threadId", it) }
            put("model", model)
            if (settings.sakanaProvider) put("modelProvider", "sakana")
            put("cwd", workingDir)
            put("approvalPolicy", if (mode.bypass) "never" else mode.approval)
            put("sandbox", mode.sandbox)
        }
        val method = if (threadId != null) "thread/resume" else "thread/start"
        request(method, params) { result, error ->
            if (error != null) {
                // A stale / invalid / expired thread id (e.g. left over from the mock or a
                // different provider) can't be resumed — start a fresh thread instead.
                if (method == "thread/resume") {
                    threadId = null
                    openThread()
                    return@request
                }
                listener.onStartFailed("$method failed: ${error["message"].str()}")
                stage = Stage.IDLE
                return@request
            }
            val id = result?.get("thread")?.jsonObject?.get("id").str()
            if (id != null) {
                threadId = id
                listener.onEvent(FuguEvent.Init(id, model))
            }
            stage = Stage.READY
            queuedPrompt?.let { queuedPrompt = null; startTurn(it) }
        }
    }

    private fun startTurn(prompt: String) {
        val tid = threadId ?: return
        val mode = FuguSettings.getInstance().permissionModeEnum
        val params = buildJsonObject {
            put("threadId", tid)
            // Apply the current mode per-turn so the chat "Mode" dropdown takes effect
            // without restarting the thread. (Sandbox is fixed at thread start; a full
            // sandbox change — e.g. Plan/Agent — applies on the next New Conversation.)
            put("approvalPolicy", if (mode.bypass) "never" else mode.approval)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                })
            })
        }
        request("turn/start", params) { _, error ->
            if (error != null) listener.onEvent(FuguEvent.Result(true, "turn/start failed: ${error["message"].str()}", null))
        }
    }

    // --- JSON-RPC plumbing -----------------------------------------------------

    private fun request(method: String, params: JsonObject?, onResult: (JsonObject?, JsonObject?) -> Unit) {
        val id = nextId.getAndIncrement()
        pending[id] = onResult
        writeLine(buildJsonObject {
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        })
    }

    private fun notify(method: String, params: JsonObject?) {
        writeLine(buildJsonObject {
            put("method", method)
            if (params != null) put("params", params)
        })
    }

    private fun respondResult(id: JsonElement, result: JsonObject) {
        writeLine(buildJsonObject {
            put("id", id)
            put("result", result)
        })
    }

    private fun respondError(id: JsonElement, code: Int, message: String) {
        writeLine(buildJsonObject {
            put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        })
    }

    @Synchronized
    private fun writeLine(obj: JsonObject) {
        val writer = stdin ?: return
        try {
            writer.write(FuguJson.lenient.encodeToString(JsonObject.serializer(), obj))
            writer.write("\n")
            writer.flush()
        } catch (t: Throwable) {
            log.warn("Failed to write to app-server stdin", t)
        }
    }

    // --- inbound dispatch ------------------------------------------------------

    private fun dispatch(line: String) {
        val obj = runCatching { FuguJson.lenient.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val method = obj["method"].str()
        val id = obj["id"]
        when {
            method != null && id != null -> handleServerRequest(id, method, obj["params"]?.jsonObject)
            method != null -> handleNotification(method, obj["params"]?.jsonObject)
            id is JsonPrimitive -> {
                val key = id.contentOrNull?.toIntOrNull() ?: return
                pending.remove(key)?.invoke(obj["result"]?.jsonObject, obj["error"]?.jsonObject)
            }
        }
    }

    private fun handleServerRequest(id: JsonElement, method: String, params: JsonObject?) {
        when (method) {
            "item/fileChange/requestApproval" -> approve(id, ApprovalKind.FILE_CHANGE, params)
            "item/commandExecution/requestApproval" -> approve(id, ApprovalKind.COMMAND, params)
            "mcpServer/elicitation/request" -> elicit(id, params)
            "item/tool/requestUserInput" -> requestUserInput(id, params)
            else -> respondError(id, -32601, "unsupported request: $method")
        }
    }

    private fun approve(id: JsonElement, kind: ApprovalKind, params: JsonObject?) {
        val reason = params?.get("reason").str()
        val summary = when (kind) {
            ApprovalKind.COMMAND -> "Run command: ${params?.get("command").str() ?: "?"}"
            ApprovalKind.FILE_CHANGE -> "Apply file changes" + (params?.get("grantRoot").str()?.let { " under $it" } ?: "")
        }
        listener.onApproval(ApprovalRequest(kind, summary, reason)) { decision ->
            respondResult(id, buildJsonObject { put("decision", decision.wire) })
        }
    }

    // --- structured user-input prompts -----------------------------------------

    /** MCP elicitation: render the requested schema as a form, reply {action, content}. */
    private fun elicit(id: JsonElement, params: JsonObject?) {
        // "url" mode: just open the link and accept — there is no form to fill.
        if (params?.get("mode").str() == "url") {
            val url = params?.get("url").str()
            val prompt = UserPrompt(UserPromptKind.ELICITATION, params?.get("message").str(),
                listOfNotNull(url?.let { PromptField("url", "Open URL", it, PromptFieldType.TEXT, required = false) }))
            listener.onUserInput(prompt) { action, _ ->
                respondResult(id, buildJsonObject { put("action", actionWire(action)) })
            }
            return
        }
        val schema = params?.get("requestedSchema")?.jsonObject
        val fields = parseElicitationSchema(schema)
        val prompt = UserPrompt(UserPromptKind.ELICITATION, params?.get("message").str(), fields)
        listener.onUserInput(prompt) { action, answers ->
            respondResult(id, buildJsonObject {
                put("action", actionWire(action))
                if (action == PromptAction.ACCEPT) put("content", elicitationContent(fields, answers))
            })
        }
    }

    /** Codex tool requestUserInput (experimental): questions with options, reply {answers}. */
    private fun requestUserInput(id: JsonElement, params: JsonObject?) {
        val questions = params?.get("questions") as? JsonArray ?: JsonArray(emptyList())
        val fields = questions.mapNotNull { q -> (q as? JsonObject)?.let { parseQuestion(it) } }
        val prompt = UserPrompt(UserPromptKind.TOOL_INPUT, null, fields)
        listener.onUserInput(prompt) { action, answers ->
            val use = if (action == PromptAction.ACCEPT) answers else emptyMap()
            respondResult(id, buildJsonObject {
                put("answers", buildJsonObject {
                    for ((qid, values) in use) {
                        put(qid, buildJsonObject { put("answers", buildJsonArray { values.forEach { add(it) } }) })
                    }
                })
            })
        }
    }

    private fun actionWire(a: PromptAction) = when (a) {
        PromptAction.ACCEPT -> "accept"
        PromptAction.DECLINE -> "decline"
        PromptAction.CANCEL -> "cancel"
    }

    private fun parseQuestion(q: JsonObject): PromptField {
        val opts = (q["options"] as? JsonArray)?.mapNotNull { o ->
            (o as? JsonObject)?.let { PromptOption(it["label"].str() ?: "", it["label"].str() ?: "") }
        } ?: emptyList()
        val type = when {
            opts.isNotEmpty() -> PromptFieldType.SINGLE_SELECT
            else -> PromptFieldType.TEXT
        }
        return PromptField(
            id = q["id"].str() ?: q["header"].str() ?: "answer",
            title = q["header"].str() ?: q["question"].str() ?: "",
            description = q["question"].str(),
            type = type,
            options = opts,
            allowOther = q["is_other"].str() == "true" || opts.isEmpty(),
            secret = q["is_secret"].str() == "true",
        )
    }

    private fun parseElicitationSchema(schema: JsonObject?): List<PromptField> {
        val props = schema?.get("properties")?.jsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)?.mapNotNull { it.str() }?.toSet() ?: emptySet()
        return props.entries.map { (key, value) ->
            val o = value.jsonObject
            val title = o["title"].str() ?: key
            val desc = o["description"].str()
            val typeStr = o["type"].str()
            val enumVals = (o["enum"] as? JsonArray)?.mapNotNull { it.str() }
            val oneOf = (o["oneOf"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            val enumNames = (o["enumNames"] as? JsonArray)?.mapNotNull { it.str() }
            when {
                typeStr == "array" -> {
                    val items = o["items"]?.jsonObject
                    val itemEnum = (items?.get("enum") as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
                    PromptField(key, title, desc, PromptFieldType.MULTI_SELECT,
                        itemEnum.map { PromptOption(it, it) }, required = key in required)
                }
                !oneOf.isNullOrEmpty() -> PromptField(key, title, desc, PromptFieldType.SINGLE_SELECT,
                    oneOf.map { PromptOption(it["const"].str() ?: "", it["title"].str() ?: it["const"].str() ?: "") },
                    required = key in required)
                enumVals != null -> PromptField(key, title, desc, PromptFieldType.SINGLE_SELECT,
                    enumVals.mapIndexed { i, v -> PromptOption(v, enumNames?.getOrNull(i) ?: v) },
                    required = key in required)
                typeStr == "boolean" -> PromptField(key, title, desc, PromptFieldType.BOOLEAN, required = key in required)
                typeStr == "number" || typeStr == "integer" -> PromptField(key, title, desc, PromptFieldType.NUMBER, required = key in required)
                else -> PromptField(key, title, desc, PromptFieldType.TEXT, required = key in required)
            }
        }
    }

    private fun elicitationContent(fields: List<PromptField>, answers: Map<String, List<String>>): JsonObject =
        buildJsonObject {
            for (field in fields) {
                val values = answers[field.id] ?: continue
                when (field.type) {
                    PromptFieldType.MULTI_SELECT -> put(field.id, buildJsonArray { values.forEach { add(it) } })
                    PromptFieldType.BOOLEAN -> put(field.id, values.firstOrNull() == "true")
                    PromptFieldType.NUMBER -> values.firstOrNull()?.toDoubleOrNull()?.let { put(field.id, it) }
                    else -> values.firstOrNull()?.let { put(field.id, it) }
                }
            }
        }

    private fun handleNotification(method: String, params: JsonObject?) {
        when (method) {
            "turn/completed" -> {
                streamedItems.clear()
                listener.onEvent(FuguEvent.Result(false, null, turnTokens(params)))
            }
            "turn/started" -> streamedItems.clear()
            "item/started" -> params?.get("item")?.jsonObject?.let { emitItem(it, completed = false) }
            "item/completed" -> params?.get("item")?.jsonObject?.let { emitItem(it, completed = true) }
            // Stream the text token-by-token; item/completed then skips this id (see emitItem)
            // so the message isn't appended twice.
            "item/agentMessage/delta" -> {
                val itemId = params?.get("itemId").str()
                val delta = params?.get("delta").str()
                if (itemId != null && delta != null) {
                    streamedItems.add(itemId)
                    listener.onEvent(FuguEvent.AgentMessageDelta(itemId, delta))
                }
            }
            "error" -> {
                val willRetry = params?.get("willRetry").str() == "true"
                val msg = params?.get("error")?.jsonObject?.get("message").str() ?: "agent error"
                if (willRetry) listener.onStderr(msg) else listener.onEvent(FuguEvent.Result(true, msg, null))
            }
            else -> Unit
        }
    }

    private fun emitItem(item: JsonObject, completed: Boolean) {
        val type = item["type"].str() ?: return
        val id = item["id"].str() ?: type
        when (type) {
            // If this message already streamed via deltas, don't re-append it on completion.
            "agentMessage" -> if (completed && id !in streamedItems) listener.onEvent(FuguEvent.AgentMessage(item["text"].str() ?: ""))
            "commandExecution" -> toolEvent(
                completed, id, "Shell",
                buildJsonObject { item["command"].str()?.let { put("command", it) } },
                item["aggregatedOutput"].str() ?: "",
                failed(item),
            )
            "fileChange" -> {
                val (added, removed) = fileChangeStats(item)
                toolEvent(
                    completed, id, "Edit",
                    buildJsonObject {
                        fileTarget(item)?.let { put("file_path", it) }
                        put("added", added)
                        put("removed", removed)
                    },
                    fileSummary(item),
                    failed(item),
                )
            }
            "mcpToolCall" -> toolEvent(completed, id, item["tool"].str() ?: "MCP", JsonObject(emptyMap()), "", failed(item))
            "webSearch" -> toolEvent(
                completed, id, "WebSearch",
                buildJsonObject { item["query"].str()?.let { put("pattern", it) } }, "", false,
            )
            else -> Unit
        }
    }

    private fun toolEvent(completed: Boolean, id: String, name: String, input: JsonObject, result: String, isError: Boolean) {
        listener.onEvent(
            if (completed) FuguEvent.ToolCompleted(id, name, input, result, isError)
            else FuguEvent.ToolStarted(id, name, input),
        )
    }

    private fun failed(item: JsonObject): Boolean {
        val status = item["status"].str()
        if (status == "failed" || status == "declined") return true
        return (item["exitCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) != 0
    }

    private fun fileTarget(item: JsonObject): String? {
        val changes = item["changes"] as? JsonArray ?: return null
        return (changes.firstOrNull() as? JsonObject)?.get("path").str()
    }

    private fun fileSummary(item: JsonObject): String {
        val changes = item["changes"] as? JsonArray ?: return ""
        return changes.mapNotNull { it as? JsonObject }.joinToString("\n") { c ->
            "${changeKind(c) ?: "change"}: ${c["path"].str() ?: "?"}"
        }
    }

    /** Codex sends `kind` as `{"type":"add"}` (object) or, in some builds, a bare string. */
    private fun changeKind(c: JsonObject): String? =
        (c["kind"] as? JsonObject)?.get("type").str() ?: c["kind"].str()

    /**
     * Lines added / removed for a file change. Codex's `diff` is the full file
     * content for an add/delete (no `+`/`-` prefixes) and a unified diff for an
     * update, so the count depends on `kind`.
     */
    private fun fileChangeStats(item: JsonObject): Pair<Int, Int> {
        val changes = item["changes"] as? JsonArray ?: return 0 to 0
        var add = 0
        var rem = 0
        for (c in changes.mapNotNull { it as? JsonObject }) {
            val a = intOf(c, "additions", "added", "linesAdded", "insertions")
            val r = intOf(c, "deletions", "removed", "linesRemoved")
            if (a != null || r != null) {
                add += a ?: 0
                rem += r ?: 0
                continue
            }
            val diff = listOf("diff", "unifiedDiff", "unified_diff", "patch").firstNotNullOfOrNull { c[it].str() }
                ?: continue
            when (changeKind(c)) {
                "add", "added", "create", "new" -> add += lineCount(diff)
                "delete", "deleted", "remove", "removed" -> rem += lineCount(diff)
                else -> {
                    var la = 0
                    var lr = 0
                    for (line in diff.lineSequence()) {
                        if (line.startsWith("+") && !line.startsWith("+++")) la++
                        else if (line.startsWith("-") && !line.startsWith("---")) lr++
                    }
                    if (la == 0 && lr == 0) add += lineCount(diff) else { add += la; rem += lr }
                }
            }
        }
        return add to rem
    }

    private fun lineCount(s: String): Int =
        if (s.isEmpty()) 0 else s.trimEnd('\n').count { it == '\n' } + 1

    private fun intOf(o: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }

    private fun turnTokens(params: JsonObject?): Long? =
        params?.get("turn")?.jsonObject?.get("usage")?.jsonObject?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    // --- stdout pump -----------------------------------------------------------

    private inner class Pump : ProcessAdapter() {
        private val buffer = StringBuilder()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when (outputType) {
                ProcessOutputType.STDOUT -> {
                    buffer.append(event.text)
                    var nl = buffer.indexOf("\n")
                    while (nl >= 0) {
                        val line = buffer.substring(0, nl)
                        buffer.delete(0, nl + 1)
                        if (line.isNotBlank()) dispatch(line)
                        nl = buffer.indexOf("\n")
                    }
                }
                ProcessOutputType.STDERR -> event.text.lineSequence().forEach {
                    if (it.isNotEmpty()) listener.onStderr(it)
                }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            stage = Stage.IDLE
            pending.clear()
            handler = null
            stdin = null
            listener.onProcessTerminated(event.exitCode)
        }
    }
}
