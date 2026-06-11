package app.olauncher.data.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a chat completion to the selected provider and returns the assistant
 * reply text. Provider-specific request/response shapes live here so the UI
 * layer stays provider-agnostic.
 *
 * [history] must be in chronological order and contain only "user"/"ai"
 * messages (system messages filtered out by the caller).
 */
object AiClient {

    private const val CONNECT_TIMEOUT_MS = 30000
    private const val READ_TIMEOUT_MS = 60000
    private const val ANTHROPIC_MAX_TOKENS = 1024

    fun complete(provider: AiProvider, model: String, apiKey: String, history: List<ChatMessage>): String {
        return when (provider.api) {
            ProviderApi.GEMINI -> gemini(provider, model, apiKey, history)
            ProviderApi.OPENAI_COMPAT -> openAiCompat(provider, model, apiKey, history)
            ProviderApi.ANTHROPIC -> anthropic(provider, model, apiKey, history)
        }
    }

    private fun gemini(provider: AiProvider, model: String, apiKey: String, history: List<ChatMessage>): String {
        val contents = JSONArray()
        for ((role, text) in alternating(history, assistantRole = "model")) {
            val parts = JSONArray().put(JSONObject().put("text", text))
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        val body = JSONObject().put("contents", contents).toString()
        val url = "${provider.baseUrl}/models/$model:generateContent"
        val response = post(url, mapOf("x-goog-api-key" to apiKey), body)
        return JSONObject(response)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
    }

    private fun openAiCompat(provider: AiProvider, model: String, apiKey: String, history: List<ChatMessage>): String {
        val messages = JSONArray()
        for (msg in history) {
            val role = if (msg.sender == "user") "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.text))
        }
        val body = JSONObject().put("model", model).put("messages", messages).toString()
        val url = "${provider.baseUrl}/chat/completions"
        val response = post(url, mapOf("Authorization" to "Bearer $apiKey"), body)
        return JSONObject(response)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun anthropic(provider: AiProvider, model: String, apiKey: String, history: List<ChatMessage>): String {
        val messages = JSONArray()
        for ((role, text) in alternating(history, assistantRole = "assistant")) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", ANTHROPIC_MAX_TOKENS)
            .put("messages", messages)
            .toString()
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        val response = post("${provider.baseUrl}/messages", headers, body)
        return JSONObject(response)
            .getJSONArray("content").getJSONObject(0)
            .getString("text")
    }

    /**
     * Collapses consecutive same-role turns and drops any leading assistant
     * turns, so the conversation starts with a user turn — required by Gemini
     * and Anthropic.
     */
    private fun alternating(history: List<ChatMessage>, assistantRole: String): List<Pair<String, String>> {
        val merged = mutableListOf<Pair<String, String>>()
        for (msg in history) {
            val role = if (msg.sender == "user") "user" else assistantRole
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                merged[merged.size - 1] = role to (last.second + "\n" + msg.text)
            } else {
                merged.add(role to msg.text)
            }
        }
        while (merged.isNotEmpty() && merged.first().first != "user") {
            merged.removeAt(0)
        }
        return merged
    }

    private fun post(urlStr: String, headers: Map<String, String>, body: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code in 200..299) {
                return InputStreamReader(connection.inputStream, Charsets.UTF_8).use { it.readText() }
            }
            val errorBody = connection.errorStream?.let {
                InputStreamReader(it, Charsets.UTF_8).use { reader -> reader.readText() }
            }
            throw IOException(parseError(errorBody) ?: "HTTP $code")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseError(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            null
        }
    }
}
