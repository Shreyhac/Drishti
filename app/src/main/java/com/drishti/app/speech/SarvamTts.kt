package com.drishti.app.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.drishti.app.AppLanguage
import com.drishti.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SarvamTts(private val context: Context) {

    private val apiKey get() = BuildConfig.SARVAM_API_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var currentPlayer: MediaPlayer? = null

    // Returns true on success, false if caller should fall back to Android TTS
    suspend fun speak(text: String, lang: AppLanguage): Boolean {
        if (apiKey.isBlank()) return false
        return try {
            val bytes = fetchAudio(text, lang)
            playBytes(bytes)
            true
        } catch (e: Exception) {
            Log.w("SarvamTTS", "Failed, falling back: ${e.message}")
            false
        }
    }

    fun stop() {
        currentPlayer?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        currentPlayer = null
    }

    fun release() = stop()

    private suspend fun fetchAudio(text: String, lang: AppLanguage): ByteArray = withContext(Dispatchers.IO) {
        val (langCode, speaker) = when (lang) {
            AppLanguage.HINDI    -> "hi-IN"  to "meera"
            AppLanguage.ENGLISH  -> "en-IN"  to "meera"
            AppLanguage.KANNADA  -> "kn-IN"  to "meera"
        }

        val body = JSONObject().apply {
            put("inputs", JSONArray().put(text))
            put("target_language_code", langCode)
            put("speaker", speaker)
            put("pitch", 0)
            put("pace", 1.1)
            put("loudness", 1.5)
            put("speech_sample_rate", 22050)
            put("enable_preprocessing", true)
            put("model", "bulbul:v1")
        }

        val req = Request.Builder()
            .url("https://api.sarvam.ai/text-to-speech")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("API-Subscription-Key", apiKey)
            .build()

        client.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string() ?: throw Exception("Empty body")
            if (!resp.isSuccessful) {
                Log.w("SarvamTTS", "HTTP ${resp.code}: $rawBody")
                throw Exception("HTTP ${resp.code}")
            }
            val b64 = JSONObject(rawBody).getJSONArray("audios").getString(0)
            Base64.decode(b64, Base64.DEFAULT)
        }
    }

    private suspend fun playBytes(bytes: ByteArray) = suspendCancellableCoroutine<Unit> { cont ->
        // Write to a fixed-name cache file — overwrite each time, no disk leak
        val tmpFile = File(context.cacheDir, "sarvam_out.wav")
        tmpFile.writeBytes(bytes)

        val mp = MediaPlayer()
        currentPlayer = mp

        mp.setOnCompletionListener {
            if (currentPlayer == it) currentPlayer = null
            it.release()
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setOnErrorListener { it, what, extra ->
            if (currentPlayer == it) currentPlayer = null
            it.release()
            if (cont.isActive) cont.resumeWithException(Exception("MediaPlayer $what/$extra"))
            true
        }

        cont.invokeOnCancellation {
            try { mp.stop(); mp.release() } catch (_: Exception) {}
            if (currentPlayer == mp) currentPlayer = null
        }

        try {
            mp.setDataSource(tmpFile.absolutePath)
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            if (currentPlayer == mp) currentPlayer = null
            mp.release()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}
