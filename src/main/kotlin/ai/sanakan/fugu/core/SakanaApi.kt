package ai.sanakan.fugu.core

import com.intellij.util.io.HttpRequests

/**
 * Minimal client for validating a Sakana API key from the GUI, so the user can
 * confirm setup without opening a terminal. Hits the OpenAI-compatible
 * `GET /v1/models` endpoint with the bearer key; a 2xx means the key works.
 */
object SakanaApi {

    private const val MODELS_URL = "https://api.sakana.ai/v1/models"

    /** Verifies the key. Returns success, or a human-readable failure message. Run off the EDT. */
    fun verifyKey(key: String): Result<Unit> = runCatching {
        HttpRequests.request(MODELS_URL)
            .tuner { it.setRequestProperty("Authorization", "Bearer $key") }
            .connectTimeout(10_000)
            .readTimeout(15_000)
            .readString()
        Unit
    }
}
