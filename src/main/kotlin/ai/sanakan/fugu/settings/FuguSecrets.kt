package ai.sanakan.fugu.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Secure storage for the Sakana API key via IntelliJ's PasswordSafe (Keychain on
 * macOS), rather than the plain settings XML. Sakana has no programmatic way to
 * fetch a key (no OAuth/device-code), so the user pastes one created in the
 * console; we keep it out of cleartext config and inject it into the Codex
 * subprocess as `SAKANA_API_KEY`.
 */
object FuguSecrets {

    const val ENV_VAR: String = "SAKANA_API_KEY"
    const val CONSOLE_KEYS_URL: String = "https://console.sakana.ai/api-keys"

    private fun attributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName("Sanakan AI Fugu", ENV_VAR))

    fun getApiKey(): String? = PasswordSafe.instance.getPassword(attributes())?.takeIf { it.isNotBlank() }

    fun setApiKey(key: String?) {
        val attr = attributes()
        if (key.isNullOrBlank()) {
            PasswordSafe.instance.set(attr, null)
        } else {
            PasswordSafe.instance.set(attr, Credentials(ENV_VAR, key))
        }
    }
}

/** Builds the extra environment injected into every Codex subprocess. */
object FuguEnv {
    fun codexEnvironment(): Map<String, String> {
        val key = FuguSecrets.getApiKey()
        return if (key.isNullOrBlank()) emptyMap() else mapOf(FuguSecrets.ENV_VAR to key)
    }
}
