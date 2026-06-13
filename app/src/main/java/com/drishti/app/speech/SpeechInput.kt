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

    fun startListening(
        language: AppLanguage,
        onPartial: (String) -> Unit,      // called as user speaks — shows live text
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // SpeechRecognizer must be created and called on the main thread
        mainHandler.post {
            if (isListening) {
                recognizer?.stopListening()
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Speech recognition not available on this device")
                return@post
            }

            // Create fresh instance each time to avoid "recognizer busy" error 8
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)

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
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (text.isNotBlank()) {
                        Log.d("STT", "Result: $text")
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
                    onError(msg)
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.code)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.code)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // live partial feedback
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Silence timeouts — give user time to think
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
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
