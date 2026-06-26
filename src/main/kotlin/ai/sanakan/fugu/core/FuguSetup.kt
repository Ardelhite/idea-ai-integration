package ai.sanakan.fugu.core

import ai.sanakan.fugu.settings.FuguSecrets

/**
 * Aggregates the three things that must be in place before Fugu can run, all of
 * which the plugin can now satisfy from its GUI (no terminal required):
 *  1. the Codex CLI binary is installed,
 *  2. the Sakana provider is configured in `config.toml`,
 *  3. a Sakana API key is stored.
 */
object FuguSetup {
    fun codexInstalled(): Boolean = CodexInstaller.isInstalled()
    fun providerConfigured(): Boolean = CodexConfig.hasSakanaProvider()
    fun apiKeyPresent(): Boolean = FuguSecrets.getApiKey() != null

    fun isReady(): Boolean = codexInstalled() && providerConfigured() && apiKeyPresent()
}
