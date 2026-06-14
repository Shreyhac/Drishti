package com.drishti.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.drishti.app.AppLanguage

class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    // Pre-create the recognizer at startup so the first PTT press is instant.
    // Recreating it on every call (the old approach) added ~300-500ms of cold-start lag.
    fun warmUp() {
        mainHandler.post {
            if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }
        }
    }

    fun startListening(
        language: AppLanguage,
        onPartial: (String) -> Unit,      // called as user speaks — shows live text
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // SpeechRecognizer must be created and called on the main thread
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Speech recognition not available on this device")
                return@post
            }

            // Reuse the warmed-up instance; cancel() resets it without the cost of
            // destroy()+create(). Only create if it doesn't exist yet.
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                recognizer?.cancel()
            }
            isListening = false

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    Log.d("STT", "Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d("STT", "Speech detected")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    Log.d("STT", "End of speech")
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partial: Bundle?) {
                    val text = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) onPartial(text)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?: arrayListOf()
                    // Pick the longest non-blank result (more words = better capture)
                    val text = candidates.filter { it.isNotBlank() }
                        .maxByOrNull { it.length }
                        .orEmpty()
                        .trim()
                    if (text.isNotBlank()) {
                        Log.d("STT", "Result: $text (candidates: $candidates)")
                        onResult(text)
                    } else {
                        onError("Nothing heard — please try again")
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO             -> "Mic error — check permissions"
                        SpeechRecognizer.ERROR_CLIENT            -> "App error — please retry"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                        SpeechRecognizer.ERROR_NETWORK           -> "Network error — check internet"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT   -> "Network timeout — check internet"
                        SpeechRecognizer.ERROR_NO_MATCH          -> "Didn't catch that — speak clearly"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY   -> "Mic busy — wait a moment"
                        SpeechRecognizer.ERROR_SERVER            -> "Server error — retry"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT    -> "No speech detected"
                        else -> "Mic error ($error)"
                    }
                    Log.w("STT", "Error $error: $msg")
                    // A busy/client error means the reused instance is in a bad state —
                    // destroy it so the next press creates a clean one.
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT) {
                        recognizer?.destroy()
                        recognizer = null
                    }
                    onError(msg)
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Always accept English for commands regardless of TTS language.
                // EXTRA_LANGUAGE = device default (usually en-IN), EXTRA_LANGUAGE_PREFERENCE = app TTS language.
                // This way "QR mode" in English is always understood even if TTS is set to Hindi.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.code)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            }
            recognizer?.startListening(intent)
        }
    }

    fun stopListening() {
        mainHandler.post {
            recognizer?.stopListening()
            isListening = false
        }
    }

    fun release() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
            isListening = false
        }
    }
}
