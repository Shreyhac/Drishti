package com.drishti.app.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OnDeviceLlm(private val context: Context) {

    private var llm: LlmInference? = null
    private var ready = false

    fun isReady() = ready

    fun initialize(modelPath: String): Boolean {
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(256)
                .setTopK(10)
                .setTemperature(0.2f)  // low = factual, less hallucination
                .setRandomSeed(42)
                .build()
            llm = LlmInference.createFromOptions(context, opts)
            ready = true
            Log.i("OnDeviceLlm", "Loaded: $modelPath")
            true
        } catch (e: Exception) {
            Log.w("OnDeviceLlm", "Load failed ($modelPath): ${e.message}")
            false
        }
    }

    suspend fun generate(userMessage: String): String = withContext(Dispatchers.Default) {
        val model = llm ?: return@withContext "Model not loaded"
        try {
            // Gemma IT models REQUIRE this chat template — without it they echo the prompt
            val formatted = "<start_of_turn>user\n$userMessage<end_of_turn>\n<start_of_turn>model\n"
            val raw = model.generateResponse(formatted) ?: return@withContext "No response"
            raw.cleanGemmaOutput()
        } catch (e: Exception) {
            Log.e("OnDeviceLlm", "Generate failed", e)
            "Error: ${e.message}"
        }
    }

    fun close() {
        llm?.close()
        ready = false
    }

    // Strip Gemma template tokens that sometimes leak into the output
    private fun String.cleanGemmaOutput(): String =
        substringBefore("<end_of_turn>")
            .replace(Regex("<start_of_turn>\\s*(user|model)?\\s*"), "")
            .replace(Regex("^(model|user)\\s*", RegexOption.MULTILINE), "")
            .trim()
}
