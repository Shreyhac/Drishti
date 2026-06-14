package com.drishti.app.speech

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.drishti.app.AppLanguage
import com.drishti.app.TtsSpeed
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class SpeechOutput(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var androidReady = false
    val sarvam = SarvamTts(context)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedVolume: Int? = null

    // Boost media stream to full so a blind user hears clearly over background noise.
    // Original volume is restored once speech finishes.
    private fun boostVolume() {
        if (savedVolume != null) return // already boosted
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        } catch (e: SecurityException) {
            Log.w("TTS", "Could not raise volume (Do Not Disturb?): ${e.message}")
        }
    }

    private fun restoreVolume() {
        val v = savedVolume ?: return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: SecurityException) {}
        savedVolume = null
    }

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Register listener inside the init callback — engine is ready now
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w("TTS", "Error on utterance: $utteranceId")
                    }
                })
                setLanguage(AppLanguage.HINDI)
                androidReady = true
                Log.i("TTS", "Android TTS engine ready")
            } else {
                Log.e("TTS", "Android TTS init failed: $status")
            }
        }
    }

    fun setLanguage(lang: AppLanguage) {
        val locale = Locale.forLanguageTag(lang.ttsLocaleTag)
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
            val base = Locale.forLanguageTag(lang.code.substringBefore("-"))
            val fallback = tts?.setLanguage(base)
            if (fallback == TextToSpeech.LANG_NOT_SUPPORTED || fallback == TextToSpeech.LANG_MISSING_DATA) {
                tts?.setLanguage(Locale.ENGLISH)
            }
        }
    }

    // Tries Sarvam TTS (online, much better Indian voices) → falls back to Android TTS (offline)
    suspend fun speak(text: String, lang: AppLanguage, isOnline: Boolean, speed: TtsSpeed = TtsSpeed.NORMAL) {
        if (text.isBlank()) return
        stop()
        boostVolume()
        try {
            if (isOnline) {
                val ok = sarvam.speak(text, lang, speed.sarvamPace)
                if (ok) return
                // Sarvam failed — fall through to Android TTS
            }
            speakAndroid(text, lang, speed.androidRate)
        } finally {
            restoreVolume()
        }
    }

    // Suspend until Android TTS finishes speaking
    private suspend fun speakAndroid(text: String, lang: AppLanguage, rate: Float = 0.92f) = suspendCancellableCoroutine<Unit> { cont ->
        if (!androidReady) { cont.resume(Unit); return@suspendCancellableCoroutine }

        val id = "drishti_${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
        })

        cont.invokeOnCancellation { tts?.stop() }

        setLanguage(lang)
        tts?.setSpeechRate(rate)
        tts?.setPitch(0.95f)   // slightly lower pitch = calmer, less robotic
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        sarvam.stop()
        tts?.stop()
        restoreVolume()
    }

    fun release() {
        sarvam.release()
        tts?.stop()
        tts?.shutdown()
        tts = null
        androidReady = false
    }
}
