package ai.sanakan.fugu.core

import java.io.File

/**
 * Manages the Sakana provider block in Codex's config file so the user never has
 * to hand-edit `~/.codex/config.toml` or run the shell installer just to wire the
 * provider. The API key itself stays out of this file — the block references the
 * `SAKANA_API_KEY` environment variable, which the plugin injects into every
 * Codex subprocess from PasswordSafe.
 */
object CodexConfig {

    private const val SECTION_HEADER = "[model_providers.sakana]"

    val PROVIDER_BLOCK: String = """
        $SECTION_HEADER
        name = "Sakana"
        base_url = "https://api.sakana.ai/v1"
        env_key = "SAKANA_API_KEY"
        wire_api = "responses"
        stream_idle_timeout_ms = 7200000
        stream_max_retries = 5
        request_max_retries = 4
    """.trimIndent()

    fun codexHome(): File =
        File(System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.codex"))

    fun configFile(): File = File(codexHome(), "config.toml")

    fun hasSakanaProvider(): Boolean {
        val f = configFile()
        return f.isFile && f.readText().contains(SECTION_HEADER)
    }

    /**
     * Idempotently appends the Sakana provider block. Returns true if it wrote the
     * block, false if it was already present.
     */
    fun ensureSakanaProvider(): Boolean {
        if (hasSakanaProvider()) return false
        val home = codexHome()
        if (!home.exists()) home.mkdirs()
        val f = configFile()
        val existing = if (f.isFile) f.readText() else ""
        val needsNewline = existing.isNotEmpty() && !existing.endsWith("\n")
        f.writeText(buildString {
            append(existing)
            if (needsNewline) append("\n")
            if (existing.isNotEmpty()) append("\n")
            append(PROVIDER_BLOCK)
            append("\n")
        })
        return true
    }
}
