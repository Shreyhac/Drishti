package com.drishti.app.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drishti.app.AppLanguage
import com.drishti.app.AppState
import com.drishti.app.camera.CameraManager
import com.drishti.app.camera.SurveillanceAnalyzer
import com.drishti.app.ml.CloudFallback
import com.drishti.app.ml.DownloadState
import com.drishti.app.ml.ModelDownloader
import com.drishti.app.ml.OnDeviceLlm
import com.drishti.app.ocr.OcrEngine
import com.drishti.app.speech.SpeechInput
import com.drishti.app.speech.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrishtiViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application.applicationContext

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _language = MutableStateFlow(AppLanguage.HINDI)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _statusText = MutableStateFlow("Initializing…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _modelStatus = MutableStateFlow("No local model")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    private var ollamaAvailable = false

    private var lastBitmap: Bitmap? = null
    private var lastContextText: String = ""

    // Track active inference+speak job so new scans can cancel it immediately
    private var activeJob: Job? = null

    private val ocrEngine = OcrEngine(ctx)
    private val onDeviceLlm = OnDeviceLlm(ctx)
    private val cloudFallback = CloudFallback()
    private val speechInput = SpeechInput(ctx)
    private val speechOutput = SpeechOutput(ctx)

    val cameraManager = CameraManager(ctx)

    val surveillanceAnalyzer = SurveillanceAnalyzer { _ ->
        viewModelScope.launch {
            if (_state.value is AppState.Ready) {
                buzz(BuzzPattern.ALERT)
                _statusText.value = "Scene changed — press to describe"
            }
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        viewModelScope.launch {
            _isOnline.value = cloudFallback.checkConnectivity(ctx)
            val loaded = tryLoadLocalModel()
            if (!loaded) {
                _modelStatus.value = "No local model"
                if (_isOnline.value) startModelDownload()
            }
            _statusText.value = if (_isOnline.value) "Ready — tap or Vol↓ to scan" else "Offline — tap to scan"
            _state.value = AppState.Ready
        }

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                ollamaAvailable = cloudFallback.isOllamaReachable()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    private suspend fun tryLoadLocalModel(): Boolean {
        return withContext(Dispatchers.Default) {
            listOf(
                ModelDownloader.modelPath(),
                "/sdcard/Download/gemma3-1b-it-int4.bin",
                "/sdcard/Download/gemma-2b-it-gpu-int4.bin"
            ).any { path -> onDeviceLlm.initialize(path) }
        }.also { if (it) _modelStatus.value = "Local model ready" }
    }

    fun startModelDownload() {
        viewModelScope.launch {
            ModelDownloader.downloadProgress(ctx).collect { dl ->
                when (dl) {
                    is DownloadState.Downloading -> _modelStatus.value = "Downloading ${dl.percent}%"
                    is DownloadState.Done -> { _modelStatus.value = "Loading model…"; tryLoadLocalModel() }
                    is DownloadState.Failed -> _modelStatus.value = "Download failed"
                }
            }
        }
    }

    fun onScanTap(bitmap: Bitmap) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            speechOutput.stop()
            _state.value = AppState.Scanning()
            buzz(BuzzPattern.CAPTURE)
            surveillanceAnalyzer.reset()
            lastBitmap = bitmap
            _statusText.value = "Scanning…"

            val (ocrText, devaText, labels) = coroutineScope {
                val o = async { ocrEngine.extractText(bitmap) }
                val d = async { ocrEngine.extractDevanagariText(bitmap) }
                val l = async { ocrEngine.labelImage(bitmap) }
                Triple(o.await(), d.await(), l.await())
            }

            val contextText = buildString {
                val allText = listOf(ocrText, devaText).filter { it.isNotBlank() }.joinToString(" | ")
                if (allText.isNotBlank()) append("Text: $allText. ")
                if (labels.isNotEmpty()) append("Objects: ${labels.joinToString(", ")}.")
            }
            lastContextText = contextText

            if (contextText.isBlank()) {
                _statusText.value = "Nothing detected — point at something"
                _state.value = AppState.Ready
                return@launch
            }

            runInference(contextText, bitmap, question = null)
        }
    }

    fun onVoiceQuery(voiceText: String) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            if (lastContextText.isBlank() && lastBitmap == null) {
                _statusText.value = "Scan something first, then ask"
                buzz(BuzzPattern.ERROR)
                _state.value = AppState.Ready
                return@launch
            }
            _statusText.value = "\"$voiceText\""
            runInference(lastContextText, lastBitmap, question = voiceText)
        }
    }

    fun startListening() {
        val cur = _state.value
        if (cur is AppState.Loading || cur is AppState.Scanning || cur is AppState.Thinking) return
        if (cur is AppState.Speaking) { speechOutput.stop(); activeJob?.cancel() }

        _state.value = AppState.Listening
        buzz(BuzzPattern.LISTEN)
        _statusText.value = "Listening…"

        speechInput.startListening(
            language = _language.value,
            onPartial = { partial ->
                viewModelScope.launch { _statusText.value = partial }
            },
            onResult = { text ->
                viewModelScope.launch { onVoiceQuery(text) }
            },
            onError = { msg ->
                viewModelScope.launch {
                    _state.value = AppState.Ready
                    _statusText.value = msg
                    buzz(BuzzPattern.ERROR)
                }
            }
        )
    }

    fun stopListening() {
        speechInput.stopListening()
        if (_state.value is AppState.Listening) {
            _state.value = AppState.Ready
            _statusText.value = "Ready — tap or Vol↓ to scan"
        }
    }

    fun cycleLanguage() {
        val langs = AppLanguage.entries
        val next = langs[(langs.indexOf(_language.value) + 1) % langs.size]
        _language.value = next
        speechOutput.setLanguage(next)
        _statusText.value = "Language: ${next.displayName}"
    }

    private suspend fun runInference(context: String, bitmap: Bitmap?, question: String?) {
        _state.value = AppState.Thinking
        _statusText.value = "Thinking…"

        val online = cloudFallback.checkConnectivity(ctx)
        _isOnline.value = online

        val cloudPrompt = buildCloudPrompt(context, question, _language.value)
        val localPrompt = buildLocalPrompt(context, question, _language.value)

        val response =
            tryOllama(ollamaAvailable, cloudPrompt)
            ?: tryCloud(online, bitmap, cloudPrompt)
            ?: tryLocal(localPrompt)
            ?: fallbackReadout(context)

        val clean = response.trim()
        _statusText.value = clean
        _state.value = AppState.Speaking(clean)
        buzz(BuzzPattern.RESULT)
        speechOutput.speak(clean, _language.value, online)  // Sarvam if online, Android TTS if offline
        _state.value = AppState.Ready
    }

    private suspend fun tryOllama(reachable: Boolean, prompt: String): String? {
        if (!reachable) return null
        return try { cloudFallback.ollamaCall(prompt).takeIfValid() } catch (_: Exception) { null }
    }

    private suspend fun tryCloud(online: Boolean, bitmap: Bitmap?, prompt: String): String? {
        if (!online) return null
        return try {
            val r = if (bitmap != null) cloudFallback.analyzeImage(bitmap, prompt)
                    else cloudFallback.freeTextCall(prompt)
            r.takeIfValid()
        } catch (_: Exception) { null }
    }

    private suspend fun tryLocal(prompt: String): String? {
        if (!onDeviceLlm.isReady()) return null
        return try { onDeviceLlm.generate(prompt).takeIfValid() } catch (_: Exception) { null }
    }

    private fun fallbackReadout(context: String) = when (_language.value) {
        AppLanguage.HINDI    -> "दिख रहा है: $context"
        AppLanguage.KANNADA  -> "ಕಾಣಿಸುತ್ತಿದೆ: $context"
        AppLanguage.ENGLISH  -> "I can see: $context"
    }

    // Cloud prompt — vivid, cool descriptions with personality
    private fun buildCloudPrompt(context: String, question: String?, lang: AppLanguage): String {
        val langLine = when (lang) {
            AppLanguage.HINDI   -> "Reply in Hindi."
            AppLanguage.KANNADA -> "Reply in Kannada."
            AppLanguage.ENGLISH -> "Reply in English."
        }
        return if (question != null) {
            """You are Drishti, a cool AI guide for blind users.
$langLine Answer in max 12 words. Be direct and vivid.
Context from camera: $context
Question: $question"""
        } else {
            """You are Drishti, a cool AI guide for blind users. Describe what you see naturally and vividly.
$langLine Max 15 words. Rules:
- Describe people with appearance + action (e.g. "pretty woman in white kurta standing close to you" / "old man with glasses reading")
- Read text aloud directly
- Mention object positions (left, right, ahead, close)
- Flag hazards (step, obstacle, gap)
- Nearest thing first. No filler words. Sound natural not robotic.
Camera data: $context"""
        }
    }

    // Local prompt — ultra-short; small on-device models can't follow long instructions
    private fun buildLocalPrompt(context: String, question: String?, lang: AppLanguage): String {
        val langWord = when (lang) {
            AppLanguage.HINDI   -> "Hindi mein:"
            AppLanguage.KANNADA -> "Kannada nalli:"
            AppLanguage.ENGLISH -> "Describe:"
        }
        return if (question != null) {
            "$langWord answer '$question' about: $context. Max 10 words."
        } else {
            "$langWord $context. 10 words. Nearest object first, describe people vividly."
        }
    }

    private fun String.takeIfValid(): String? {
        val bad = listOf("cloud error", "api error", "ollama", "error:", "quota_exceeded",
                         "model not loaded", "empty response", "no api key")
        if (isBlank()) return null
        if (bad.any { lowercase().startsWith(it) }) return null
        return this
    }

    private fun buzz(pattern: BuzzPattern) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val effect = when (pattern) {
            BuzzPattern.CAPTURE -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
            BuzzPattern.RESULT  -> VibrationEffect.createWaveform(longArrayOf(0, 50, 60, 80), -1)
            BuzzPattern.LISTEN  -> VibrationEffect.createWaveform(longArrayOf(0, 30, 40, 30), -1)
            BuzzPattern.ERROR   -> VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
            BuzzPattern.ALERT   -> VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 120), -1)
        }
        vibrator?.vibrate(effect)
    }

    private enum class BuzzPattern { CAPTURE, RESULT, LISTEN, ERROR, ALERT }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
        speechInput.release()
        speechOutput.release()
        onDeviceLlm.close()
    }
}
