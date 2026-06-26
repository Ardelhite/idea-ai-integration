package ai.sanakan.fugu.core

import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Minimal client for the OpenAI-compatible Sakana API: verifying a key and
 * listing the available models, via `GET /v1/models` with the bearer key.
 */
object SakanaApi {

    private const val MODELS_URL = "https://api.sakana.ai/v1/models"
    private val json = Json { ignoreUnknownKeys = true }

    /** Verifies the key. Returns success, or a human-readable failure message. Run off the EDT. */
    fun verifyKey(key: String): Result<Unit> = runCatching {
        get(key)
        Unit
    }

    /** Lists the model ids the account can use (e.g. fugu, fugu-ultra, fugu-ultra-YYYYMMDD). */
    fun listModels(key: String): Result<List<String>> = runCatching {
        val data = json.parseToJsonElement(get(key)).jsonObject["data"] as? JsonArray ?: return@runCatching emptyList()
        data.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }.distinct()
    }

    private fun get(key: String): String =
        HttpRequests.request(MODELS_URL)
            .tuner { it.setRequestProperty("Authorization", "Bearer $key") }
            .connectTimeout(10_000)
            .readTimeout(15_000)
            .readString()
}
