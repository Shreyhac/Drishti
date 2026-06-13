package com.drishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.drishti.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CloudFallback {

    // Tier 1 — vision model (paid, cheap at $0.0000005/token)
    private val VISION_MODEL = "google/gemini-3.1-flash-image-preview"

    // Tier 2 — DeepSeek V3, completely FREE, significantly better Hindi/reasoning than Gemma
    private val FREE_MODEL = "deepseek/deepseek-chat-v3-0324:free"

    // Ollama local network base URL — set your laptop IP here when on same WiFi
    // Run: ollama serve   then: ollama pull deepseek-r1:1.5b  (or phi4-mini, llama3.2:3b)
    var ollamaBaseUrl: String = "http://192.168.1.100:11434"
    var ollamaModel: String = "deepseek-r1:1.5b"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // Shorter timeout for Ollama reachability probe — don't stall the chain
    private val ollamaClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val apiKey get() = BuildConfig.OPENROUTER_API_KEY

    fun checkConnectivity(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Check if Ollama is reachable on the local network
    fun isOllamaReachable(): Boolean {
        return try {
            val req = Request.Builder().url("$ollamaBaseUrl/api/tags").get().build()
            ollamaClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    // Ollama local inference — uses OpenAI-compatible endpoint
    suspend fun ollamaCall(prompt: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", ollamaModel)
            put("stream", false)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
            put("options", JSONObject().apply {
                put("num_predict", 80)
                put("temperature", 0.3)
            })
        }
        try {
            val req = Request.Builder()
                .url("$ollamaBaseUrl/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            ollamaClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@withContext "Empty response"
                if (!resp.isSuccessful) return@withContext "Ollama error ${resp.code}"
                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                    .removeThinkTags()
            }
        } catch (e: Exception) {
            "Ollama unreachable: ${e.message}"
        }
    }

    // Vision call — sends image to Gemini. Falls back to DeepSeek free on quota error.
    suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext freeTextCall(prompt)

        val resized = resizeForUpload(bitmap, maxDim = 720)
        val stream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("model", VISION_MODEL)
            put("max_tokens", 80)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().put("type", "image_url").put(
                            "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")
                        ))
                        put(JSONObject().put("type", "text").put("text", prompt))
                    })
                }
            ))
        }

        val result = postOpenRouter(body.toString())
        if (result.isQuotaError()) freeTextCall(prompt) else result
    }

    // DeepSeek V3 free — better multilingual than old Gemma 4 31B
    suspend fun freeTextCall(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "No API key configured."
        val body = JSONObject().apply {
            put("model", FREE_MODEL)
            put("max_tokens", 80)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        }
        postOpenRouter(body.toString())
    }

    suspend fun chat(prompt: String): String = freeTextCall(prompt)

    private fun postOpenRouter(bodyJson: String): String {
        return try {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "com.drishti.app")
                .addHeader("X-Title", "Drishti")
                .build()

            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return "Empty response"
                when (resp.code) {
                    429, 402 -> "QUOTA_EXCEEDED"
                    200 -> JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    else -> "API error ${resp.code}"
                }
            }
        } catch (e: Exception) {
            "Cloud error: ${e.message}"
        }
    }

    private fun resizeForUpload(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    // DeepSeek R1 wraps reasoning in <think>...</think> — strip it before TTS
    private fun String.removeThinkTags(): String =
        replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()

    private fun String.isQuotaError() =
        this == "QUOTA_EXCEEDED" || startsWith("API error 429") || startsWith("API error 402")
}
