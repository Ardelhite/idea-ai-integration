package ai.sanakan.fugu.core

import ai.sanakan.fugu.settings.FuguEnv
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Detects the installed Codex version and whether a newer one is available, so the UI
 * can offer an "Update codex" action and nag (yellow gear) when it's out of date.
 *
 * "Latest" is the published `@openai/codex` npm version. The result is cached for the IDE
 * session (invalidated after an update). All calls block — run OFF the EDT.
 */
object CodexVersion {

    private const val LATEST_URL = "https://registry.npmjs.org/@openai/codex/latest"
    private val json = Json { ignoreUnknownKeys = true }
    private val SEMVER = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")

    @Volatile
    private var cached: Info? = null

    data class Info(val path: String?, val current: String?, val latest: String?) {
        val installed: Boolean get() = path != null
        val known: Boolean get() = current != null && latest != null
        /** True only when both versions are known AND current < latest (fail-safe: no false nag). */
        val outdated: Boolean get() = known && compare(current!!, latest!!) < 0
    }

    /** Cached check; pass force=true to re-probe (e.g. right after an update). */
    fun check(force: Boolean = false): Info {
        if (!force) cached?.let { return it }
        val exe = CodexInstaller.resolve()
        val info = Info(
            path = exe?.absolutePath,
            current = exe?.let { readVersion(it) },
            latest = fetchLatest(),
        )
        cached = info
        return info
    }

    fun invalidate() { cached = null }

    private fun readVersion(exe: File): String? = runCatching {
        val cmd = GeneralCommandLine(exe.absolutePath, "--version").withEnvironment(FuguEnv.codexEnvironment())
        val out = ExecUtil.execAndGetOutput(cmd)
        SEMVER.find(out.stdout)?.value ?: SEMVER.find(out.stderr)?.value
    }.getOrNull()

    private fun fetchLatest(): String? = runCatching {
        val body = HttpRequests.request(LATEST_URL).connectTimeout(8000).readTimeout(8000).readString()
        val version = json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.contentOrNull
        version?.let { SEMVER.find(it)?.value }
    }.getOrNull()

    private fun parts(v: String): IntArray? =
        SEMVER.find(v)?.groupValues?.let { intArrayOf(it[1].toInt(), it[2].toInt(), it[3].toInt()) }

    private fun compare(a: String, b: String): Int {
        val pa = parts(a) ?: return 0
        val pb = parts(b) ?: return 0
        for (i in 0..2) if (pa[i] != pb[i]) return pa[i] - pb[i]
        return 0
    }
}
