package com.drishti.app.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drishti.app.ml.HandLandmarkAnalyzer
import com.drishti.app.ml.ObjectAnalyzer
import com.drishti.app.ml.DetectedThing
import com.drishti.app.ml.FaceInfo
import com.drishti.app.ml.FaceRecognizer
import com.drishti.app.ml.FaceStore
import com.drishti.app.camera.CameraManager.LiveMode
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.drishti.app.AppLanguage
import com.drishti.app.AppState
import com.drishti.app.ScanMode
import com.drishti.app.ScanRecord
import com.drishti.app.TtsSpeed
import com.drishti.app.camera.CameraManager
import com.drishti.app.camera.SurveillanceAnalyzer
import com.drishti.app.ml.BarcodeEngine
import com.drishti.app.ml.CloudFallback
import com.drishti.app.ml.ColorAnalyzer
import com.drishti.app.ml.DownloadState
import com.drishti.app.ml.FaceEngine
import com.drishti.app.ml.GestureEngine
import com.drishti.app.ml.GestureResult
import com.drishti.app.ml.ModelDownloader
import com.drishti.app.ml.OnDeviceLlm
import com.drishti.app.ocr.OcrEngine
import com.drishti.app.speech.SpeechInput
import com.drishti.app.speech.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrishtiViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application.applicationContext
    private val prefs = ctx.getSharedPreferences("drishti_prefs", Context.MODE_PRIVATE)

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

    private val _scanMode = MutableStateFlow(ScanMode.NORMAL)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _ttsSpeed = MutableStateFlow(TtsSpeed.NORMAL)
    val ttsSpeed: StateFlow<TtsSpeed> = _ttsSpeed.asStateFlow()

    private val _scanHistory = MutableStateFlow<List<ScanRecord>>(emptyList())
    val scanHistory: StateFlow<List<ScanRecord>> = _scanHistory.asStateFlow()

    private val _isAutoScanning = MutableStateFlow(false)
    val isAutoScanning: StateFlow<Boolean> = _isAutoScanning.asStateFlow()

    private val _sosContact = MutableStateFlow(prefs.getString("sos_contact", "") ?: "")
    val sosContact: StateFlow<String> = _sosContact.asStateFlow()

    // Normalized (0..1) hand landmarks, updated in real time while in SIGN mode
    private val _handLandmarks = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val handLandmarks: StateFlow<List<Pair<Float, Float>>> = _handLandmarks.asStateFlow()

    // Most recent sign recognised by the live analyzer, with timestamp
    private var liveSign: String? = null
    private var liveSignTime = 0L
    private var torchOn = false

    // Auto-announce throttle for always-on hand gestures
    private var lastAnnouncedSign = ""
    private var lastAnnouncedTime = 0L

    // Obstacle / walking assistant
    private val _obstacleMode = MutableStateFlow(false)
    val obstacleMode: StateFlow<Boolean> = _obstacleMode.asStateFlow()
    private var lastObstacleSpeak = 0L
    private var lastObstacleDir = ""

    // Find-my-object
    private val _findTarget = MutableStateFlow<String?>(null)
    val findTarget: StateFlow<String?> = _findTarget.asStateFlow()
    private var lastFindSpeak = 0L

    // Fall detection countdown (0 = inactive, else seconds remaining)
    private val _fallCountdown = MutableStateFlow(0)
    val fallCountdown: StateFlow<Int> = _fallCountdown.asStateFlow()
    private var fallJob: Job? = null

    private var ollamaAvailable = false
    private var lastLuminance = 128
    private var liveSpeakJob: Job? = null
    private var lastBitmap: Bitmap? = null
    private var lastContextText: String = ""
    private var activeJob: Job? = null
    private var autoScanJob: Job? = null

    // Dedup state for auto-scan
    private var lastAutoSpokenText = ""
    private var lastAutoSpeakTime = 0L

    private val ocrEngine = OcrEngine(ctx)
    private val onDeviceLlm = OnDeviceLlm(ctx)
    private val cloudFallback = CloudFallback()
    private val barcodeEngine = BarcodeEngine()
    private val faceEngine = FaceEngine()
    private val faceRecognizer = FaceRecognizer(ctx)
    private val faceStore = FaceStore(ctx)
    private val gestureEngine = GestureEngine(ctx)
    private val speechInput = SpeechInput(ctx)
    private val speechOutput = SpeechOutput(ctx)

    // Always-on real-time hand skeleton + sign recognition (LIVE_STREAM).
    // Draws the skeleton whenever a hand is in view and, when idle, auto-announces
    // a recognised gesture so the user doesn't have to tap Sign mode.
    val handAnalyzer = HandLandmarkAnalyzer(ctx) { landmarks, speech, _, luminance ->
        lastLuminance = luminance
        _handLandmarks.value = landmarks
        if (speech != null) {
            liveSign = speech
            liveSignTime = System.currentTimeMillis()
            maybeAnnounceSign(speech)
        }
        // Auto-torch when a hand is present in low light (hysteresis avoids flicker)
        val handPresent = landmarks.isNotEmpty()
        if (handPresent && !torchOn && luminance < 45) { torchOn = true; cameraManager.setTorch(true) }
        else if (torchOn && (!handPresent || luminance > 90)) { torchOn = false; cameraManager.setTorch(false) }
    }

    // Live object detection — drives the obstacle assistant and find-my-object
    val objectAnalyzer = ObjectAnalyzer { things, lum ->
        lastLuminance = lum
        when {
            _obstacleMode.value      -> handleObstacle(things)
            _findTarget.value != null -> handleFind(things, _findTarget.value!!)
        }
    }

    val cameraManager = CameraManager(ctx)

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
            _state.value = AppState.Ready
            val languageConfirmed = prefs.getBoolean("language_confirmed", false)
            if (!languageConfirmed) {
                // First launch — ask preferred language
                speakResult(
                    "Welcome to Drishti. What language would you like me to speak in? " +
                    "Say Hindi, English, Kannada, Tamil, Telugu, or Bengali.",
                    addToHistory = false
                )
                startListening()
            } else {
                speakResult(
                    "Drishti ready. ${if (_isOnline.value) "Online." else "Offline."} " +
                    "Tap screen, shake, or press Volume Down to scan. " +
                    "Hold Volume Up to ask a question. Say help to hear all options.",
                    addToHistory = false
                )
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                ollamaAvailable = cloudFallback.isOllamaReachable()
                delay(30_000)
            }
        }

        // Initialize gesture engines in background (IMAGE fallback + LIVE_STREAM)
        viewModelScope.launch(Dispatchers.IO) {
            gestureEngine.initialize()
            handAnalyzer.initialize()
            faceRecognizer.initialize()
        }

        // Pre-create the speech recognizer so the first Vol-Up press responds instantly
        speechInput.warmUp()
    }

    // ---------- Public API ----------

    fun setScanMode(mode: ScanMode) {
        if (mode == ScanMode.AUTO) { toggleAutoScan(); return }
        if (_isAutoScanning.value) stopAutoScan()
        applyScanMode(mode)
        _statusText.value = if (mode == ScanMode.SIGN)
            "Sign mode — show your hand to the camera"
        else
            "${mode.label} mode — tap to scan"
    }

    // Hand tracking is always-on, so changing scan mode no longer rebinds the camera.
    // Leaving an active obstacle/find session returns the live stream to hand tracking.
    private fun applyScanMode(mode: ScanMode) {
        if (_obstacleMode.value) toggleObstacle()
        if (_findTarget.value != null) stopFind()
        _scanMode.value = mode
    }

    fun cycleTtsSpeed() {
        val speeds = TtsSpeed.entries
        val next = speeds[(speeds.indexOf(_ttsSpeed.value) + 1) % speeds.size]
        _ttsSpeed.value = next
        _statusText.value = "Speed: ${next.label}"
    }

    fun toggleAutoScan() {
        if (_isAutoScanning.value) { stopAutoScan(); return }
        _isAutoScanning.value = true
        _scanMode.value = ScanMode.AUTO
        lastAutoSpokenText = ""
        lastAutoSpeakTime = 0L
        autoScanJob = viewModelScope.launch {
            _statusText.value = "Auto-scan on — scanning every 5s"
            while (isActive) {
                if (_state.value == AppState.Ready) {
                    val bmp = cameraManager.captureFrame()
                    if (bmp != null) performScan(bmp, ScanMode.NORMAL, autoMode = true)
                }
                delay(5_000)
            }
        }
    }

    private fun stopAutoScan() {
        autoScanJob?.cancel()
        autoScanJob = null
        _isAutoScanning.value = false
        _scanMode.value = ScanMode.NORMAL
        _statusText.value = "Auto-scan off — tap to scan"
    }

    // ---------- Always-on gesture announcing ----------

    private fun maybeAnnounceSign(speech: String) {
        if (_obstacleMode.value || _findTarget.value != null) return
        if (_state.value !is AppState.Ready) return
        val now = System.currentTimeMillis()
        if (speech == lastAnnouncedSign && now - lastAnnouncedTime < 6000) return
        if (now - lastAnnouncedTime < 2500) return
        lastAnnouncedSign = speech
        lastAnnouncedTime = now
        buzz(BuzzPattern.RESULT)
        quickSpeak(speech)
    }

    // Short spoken interjection that doesn't disturb the main scan/Q&A state machine
    private fun quickSpeak(text: String) {
        liveSpeakJob?.cancel()
        liveSpeakJob = viewModelScope.launch {
            speechOutput.speak(text, _language.value, _isOnline.value, _ttsSpeed.value)
        }
    }

    // ---------- Obstacle / walking assistant ----------

    fun toggleObstacle() {
        if (_obstacleMode.value) {
            _obstacleMode.value = false
            cameraManager.setLiveMode(LiveMode.HANDS)
            viewModelScope.launch { speakResult("Walking assistant off.", addToHistory = false) }
            return
        }
        if (_findTarget.value != null) stopFind()
        if (_isAutoScanning.value) stopAutoScan()
        _obstacleMode.value = true
        lastObstacleSpeak = 0L
        lastObstacleDir = ""
        cameraManager.setLiveMode(LiveMode.OBJECTS)
        viewModelScope.launch {
            speakResult(
                "Walking assistant on. Hold the phone up and I'll warn you about things in your path. Say stop to turn off.",
                addToHistory = false
            )
        }
    }

    private fun handleObstacle(things: List<DetectedThing>) {
        val closest = things.maxByOrNull { it.areaFraction } ?: return
        if (closest.areaFraction < 0.10f) return  // nothing big enough to matter

        val now = System.currentTimeMillis()
        val dir = when {
            closest.centerX < 0.35f -> "left"
            closest.centerX > 0.65f -> "right"
            else -> "ahead"
        }
        val urgent = closest.areaFraction > 0.35f
        val minGap = if (urgent) 1500L else 3000L
        if (now - lastObstacleSpeak < minGap && dir == lastObstacleDir) return
        lastObstacleSpeak = now
        lastObstacleDir = dir

        val label = closest.label.takeIf { it.isNotBlank() && it.lowercase() != "object" } ?: "obstacle"
        buzz(if (urgent) BuzzPattern.ALERT else BuzzPattern.LISTEN)
        val msg = when (dir) {
            "ahead" -> if (urgent) "$label very close ahead" else "$label ahead"
            else    -> "$label on your $dir"
        }
        quickSpeak(msg)
    }

    // ---------- Find my object ----------

    fun startFind(target: String) {
        if (_obstacleMode.value) toggleObstacle()
        if (_isAutoScanning.value) stopAutoScan()
        _findTarget.value = target
        lastFindSpeak = 0L
        cameraManager.setLiveMode(LiveMode.OBJECTS)
        viewModelScope.launch {
            speakResult(
                "Looking for your $target. Move the phone around slowly. Say stop to cancel.",
                addToHistory = false
            )
        }
    }

    fun stopFind() {
        if (_findTarget.value == null) return
        _findTarget.value = null
        cameraManager.setLiveMode(LiveMode.HANDS)
    }

    private fun handleFind(things: List<DetectedThing>, target: String) {
        val now = System.currentTimeMillis()
        if (now - lastFindSpeak < 2000) return
        val t = target.lowercase()
        val match = things.firstOrNull {
            val l = it.label.lowercase()
            l.isNotBlank() && l != "object" && (l.contains(t) || t.contains(l))
        }
        if (match == null) {
            if (now - lastFindSpeak > 4000) {
                lastFindSpeak = now
                quickSpeak("Still looking for your $target")
            }
            return
        }
        lastFindSpeak = now
        val dir = when {
            match.centerX < 0.35f -> "to your left"
            match.centerX > 0.65f -> "to your right"
            else -> "right in front of you"
        }
        val near = if (match.areaFraction > 0.3f) ", very close" else ""
        buzz(BuzzPattern.RESULT)
        quickSpeak("Found your $target $dir$near")
    }

    // ---------- Light / day-night detector ----------

    fun describeLight() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val bmp = cameraManager.captureFrame()
            val avg = if (bmp != null) avgLuminance(bmp) else lastLuminance
            val desc = when {
                avg < 25  -> "It's very dark here. The light appears to be off."
                avg < 60  -> "It's dim. There's only a little light."
                avg < 130 -> "There's moderate light. A light is probably on."
                else      -> "It's bright. A light is on, or it's daytime."
            }
            speakResult(desc, addToHistory = false)
        }
    }

    // ---------- Fall detection ----------

    fun onFallDetected() {
        if (_fallCountdown.value > 0) return  // already counting down
        if (_sosContact.value.isBlank()) {
            viewModelScope.launch {
                speakResult(
                    "I think you may have fallen, but no emergency contact is set. Say: set emergency contact, then the number.",
                    addToHistory = false
                )
            }
            return
        }
        fallJob?.cancel()
        fallJob = viewModelScope.launch {
            buzz(BuzzPattern.ALERT)
            quickSpeak("Fall detected. Sending emergency S O S in 10 seconds. Tap the screen or press a volume key to cancel.")
            for (n in 10 downTo 1) {
                _fallCountdown.value = n
                buzz(BuzzPattern.SAME)
                delay(1000)
            }
            _fallCountdown.value = 0
            triggerSos()
        }
    }

    fun cancelFall() {
        if (_fallCountdown.value <= 0) return
        fallJob?.cancel()
        _fallCountdown.value = 0
        viewModelScope.launch {
            buzz(BuzzPattern.READY)
            speakResult("Cancelled. I'm glad you're okay.", addToHistory = false)
        }
    }

    fun triggerSos() {
        val contact = _sosContact.value
        if (contact.isBlank()) {
            activeJob = viewModelScope.launch {
                buzz(BuzzPattern.ERROR)
                val msg = when (_language.value) {
                    AppLanguage.HINDI   -> "कोई आपातकालीन नंबर नहीं। कहें: इमरजेंसी कॉन्टैक्ट सेट करो"
                    AppLanguage.KANNADA -> "ತುರ್ತು ಸಂಪರ್ಕ ಇಲ್ಲ. ಹೇಳಿ: set emergency contact"
                    else                -> "No emergency contact saved. Say: set emergency contact followed by the number"
                }
                speakResult(msg, addToHistory = false)
            }
            return
        }
        buzz(BuzzPattern.ALERT)
        activeJob = viewModelScope.launch {
            _statusText.value = "Sending emergency alert…"
            val loc = getLastLocation()
            val locText = if (loc != null)
                "https://maps.google.com/?q=${loc.first},${loc.second}"
            else
                "(location unavailable)"
            val message = "EMERGENCY! I need help. My live location: $locText — sent from Drishti."

            // Primary: silent SMS (works offline, no per-send tap after one permission grant)
            val smsSent = sendSms(contact, message)
            if (smsSent) {
                speakResult(
                    if (loc != null) "Emergency alert with your location sent to your contact."
                    else "Emergency alert sent, but I couldn't get your location.",
                    addToHistory = false
                )
                return@launch
            }

            // Fallback: open WhatsApp pre-filled, then dialer
            val opened = openWhatsAppPrefilled(contact, message)
            if (opened) {
                speakResult("Could not send SMS. WhatsApp is open — tap send to alert your contact.", addToHistory = false)
            } else {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$contact")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {}
                speakResult("Could not send a message. Opening the dialer to call your contact.", addToHistory = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastLocation(): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val priority = if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            // Fresh fix first; fall back to last known
            val fresh = suspendCancellableCoroutine<android.location.Location?> { cont ->
                val cts = CancellationTokenSource()
                client.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
            val loc = fresh ?: suspendCancellableCoroutine<android.location.Location?> { cont ->
                client.lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
            loc?.let { Pair(it.latitude, it.longitude) }
        } catch (e: Exception) {
            android.util.Log.e("SOS", "Location failed", e)
            null
        }
    }

    private fun sendSms(number: String, message: String): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ctx.getSystemService(SmsManager::class.java)
            else
                @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(message)
            sm.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            android.util.Log.e("SOS", "SMS failed", e)
            false
        }
    }

    private fun openWhatsAppPrefilled(contact: String, message: String): Boolean {
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$contact&text=${Uri.encode(message)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            true
        } catch (_: Exception) { false }
    }

    fun setSosContact(number: String) {
        _sosContact.value = number
        prefs.edit().putString("sos_contact", number).apply()
    }

    fun cycleLanguage() {
        val langs = AppLanguage.entries
        val next = langs[(langs.indexOf(_language.value) + 1) % langs.size]
        _language.value = next
        speechOutput.setLanguage(next)
        _statusText.value = "Language: ${next.displayName}"
    }

    fun replayScan(record: ScanRecord) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            speechOutput.stop()
            speakResult(record.text, addToHistory = false)
        }
    }

    fun onScanTap(bitmap: Bitmap) {
        if (_isAutoScanning.value) stopAutoScan()
        activeJob?.cancel()
        activeJob = viewModelScope.launch { performScan(bitmap, _scanMode.value) }
    }

    // Stop TTS immediately — called when Vol DOWN pressed during speech
    fun stopSpeaking() {
        speechOutput.stop()
        activeJob?.cancel()
        _state.value = AppState.Ready
        viewModelScope.launch {
            buzz(BuzzPattern.READY)
            _statusText.value = "Ready — tap, shake or Vol↓ to scan"
        }
    }

    fun onVoiceQuery(voiceText: String) {
        val lower = voiceText.lowercase().trim()

        // First-launch language selection
        if (!prefs.getBoolean("language_confirmed", false)) {
            val detected = detectLanguageFromSpeech(lower)
            if (detected != null) {
                _language.value = detected
                speechOutput.setLanguage(detected)
                prefs.edit().putString("app_language", detected.name).putBoolean("language_confirmed", true).apply()
                activeJob = viewModelScope.launch {
                    speakResult(
                        "Great! I'll speak in ${detected.displayName}. " +
                        "Tap screen, shake, or press Volume Down to scan. Say help for options.",
                        addToHistory = false
                    )
                }
            } else {
                activeJob = viewModelScope.launch {
                    speakResult("Sorry, I didn't catch that. Please say Hindi, English, Kannada, Tamil, Telugu, or Bengali.", addToHistory = false)
                    startListening()
                }
            }
            return
        }

        // Emergency contact
        if (lower.contains("emergency contact") || lower.contains("set emergency") || lower.contains("इमरजेंसी")) {
            val digits = voiceText.filter { it.isDigit() }
            if (digits.length >= 10) {
                setSosContact(digits)
                activeJob = viewModelScope.launch {
                    speakResult("Emergency contact saved: $digits", addToHistory = false)
                }
                return
            }
        }

        // Help / what can you do
        if (lower.contains("help") || lower.contains("what can you do") || lower.contains("modes") || lower.contains("options")) {
            activeJob = viewModelScope.launch {
                speakResult(
                    "Say: Scene to describe surroundings. Read for text. Currency for money. " +
                    "Walking assistant to warn about obstacles. Find my, then an object, to locate it. " +
                    "Is the light on, to check lighting. Who is this, to recognise a person. " +
                    "Save this as a name, to remember someone. Colors, Faces, QR, or Sign for those. " +
                    "Auto to keep scanning. Stop to pause.",
                    addToHistory = false
                )
            }
            return
        }

        // Forget a saved person
        Regex("(?:forget|remove|delete)\\s+(.+)").find(lower)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('?', '.', '!')
            if (name.isNotBlank()) { forgetFace(name.replaceFirstChar { it.uppercase() }); return }
        }

        // Save / remember a person's face
        val saveMatch = Regex("(?:save|remember)\\s+(?:this\\s+)?(?:person\\s+|face\\s+)?(?:as\\s+|named\\s+|called\\s+)?(.+)").find(lower)
            ?: Regex("(?:this is|his name is|her name is|name is)\\s+(.+)").find(lower)
        if (saveMatch != null) {
            val name = saveMatch.groupValues[1].trim().trimEnd('?', '.', '!')
            if (name.isNotBlank() && name != "face" && name != "this") {
                saveFace(name.replaceFirstChar { it.uppercase() })
                return
            }
        }

        // Who is this — immediate face scan
        if (lower.contains("who is this") || lower.contains("who's this") || lower.contains("who is in front") ||
            lower.contains("who is it") || lower.contains("identify") || lower.contains("recognize")) {
            applyScanMode(ScanMode.FACE)
            activeJob = viewModelScope.launch {
                val bmp = cameraManager.captureFrame()
                if (bmp != null) performScan(bmp, ScanMode.FACE)
                else speakResult("Couldn't take a picture. Please try again.", addToHistory = false)
            }
            return
        }

        // Walking / obstacle assistant
        if (lower.contains("obstacle") || lower.contains("walking assistant") || lower.contains("walk") ||
            lower.contains("navigate") || lower.contains("navigation") || lower.contains("guide me") ||
            lower.contains("my path") || lower.contains("in front")) {
            toggleObstacle()
            return
        }

        // Light / day-night detector
        if (lower.contains("light on") || lower.contains("is the light") || lower.contains("the light") ||
            lower.contains("is it dark") || lower.contains("is it bright") || lower.contains("day or night") ||
            lower.contains("daytime") || lower == "light") {
            describeLight()
            return
        }

        // Find my object
        val findMatch = Regex("(?:find|locate|where is|where's|search for)\\s+(?:my\\s+|the\\s+|a\\s+)?(.+)").find(lower)
        if (findMatch != null) {
            val target = findMatch.groupValues[1].trim().trimEnd('?', '.', '!')
            if (target.isNotBlank()) {
                startFind(target)
                return
            }
        }

        // Mode switching via voice
        val modeSwitch = detectModeCommand(lower)
        if (modeSwitch != null) {
            activeJob = viewModelScope.launch {
                if (modeSwitch == ScanMode.AUTO) {
                    toggleAutoScan()
                    speakResult("Auto scan ${if (_isAutoScanning.value) "on" else "off"}", addToHistory = false)
                } else {
                    if (_isAutoScanning.value) stopAutoScan()
                    applyScanMode(modeSwitch)
                    speakResult("${modeSwitch.label} mode. Tap, shake or press Volume Down to scan.", addToHistory = false)
                }
            }
            return
        }

        // Stop command — cancels whatever is currently running
        if (lower == "stop" || lower == "cancel" || lower == "रुको" || lower == "बंद करो") {
            if (_fallCountdown.value > 0) { cancelFall(); return }
            stopSpeaking()
            if (_isAutoScanning.value) stopAutoScan()
            if (_obstacleMode.value) toggleObstacle()
            if (_findTarget.value != null) stopFind()
            return
        }

        // Regular question about last scene
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            if (lastContextText.isBlank() && lastBitmap == null) {
                speakResult("Scan something first, then ask your question.", addToHistory = false)
                return@launch
            }
            _statusText.value = "\"$voiceText\""
            runInference(lastContextText, lastBitmap, question = voiceText)
        }
    }

    private fun detectLanguageFromSpeech(lower: String): AppLanguage? = when {
        lower.contains("hindi") || lower.contains("हिंदी") || lower.contains("हिन्दी") -> AppLanguage.HINDI
        lower.contains("english") || lower.contains("अंग्रेजी") -> AppLanguage.ENGLISH
        lower.contains("kannada") || lower.contains("ಕನ್ನಡ") -> AppLanguage.KANNADA
        lower.contains("tamil") || lower.contains("தமிழ்") -> AppLanguage.TAMIL
        lower.contains("telugu") || lower.contains("తెలుగు") -> AppLanguage.TELUGU
        lower.contains("bengali") || lower.contains("বাংলা") -> AppLanguage.BENGALI
        else -> null
    }

    // Fix ALL CAPS ("CLOUDFLARE" → "Cloudflare") and spaced letters ("C L O U D" → "Cloud")
    private fun preprocessForTts(text: String): String {
        // Join spaced single capital letters: "C L O U D F L A R E" → "CLOUDFLARE"
        val joined = text.replace(Regex("(?<=[A-Z]) (?=[A-Z])(?![a-z])"), "")
        // Convert ALL_CAPS words (3+ chars) to Title Case so TTS reads them as words not letters
        return joined.replace(Regex("\\b[A-Z][A-Z0-9]{2,}\\b")) { match ->
            match.value.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun detectModeCommand(lower: String): ScanMode? = when {
        lower.contains("scene") || lower.contains("describe") || lower.contains("normal") || lower.contains("surroundings") -> ScanMode.NORMAL
        lower.contains("qr") || lower.contains("barcode") || lower.contains("code") || lower.contains("scan code") -> ScanMode.BARCODE
        lower.contains("currency") || lower.contains("money") || lower.contains("note") || lower.contains("cash") || lower.contains("rupee") -> ScanMode.CURRENCY
        lower.contains("face") || lower.contains("person") || lower.contains("people") || lower.contains("who") -> ScanMode.FACE
        lower.contains("color") || lower.contains("colour") -> ScanMode.COLOR
        lower.contains("read") || lower.contains("document") || lower.contains("text") || lower.contains("reading") -> ScanMode.DOCUMENT
        lower.contains("sign") || lower.contains("gesture") || lower.contains("deaf") || lower.contains("hand") -> ScanMode.SIGN
        lower.contains("auto") || lower.contains("automatic") || lower.contains("keep scanning") -> ScanMode.AUTO
        else -> null
    }

    fun startListening() {
        val cur = _state.value
        if (cur is AppState.Loading || cur is AppState.Scanning || cur is AppState.Thinking) return

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            if (cur is AppState.Speaking) {
                speechOutput.stop()
                delay(350) // Brief settle so TTS tail isn't picked up as input
            }
            _state.value = AppState.Listening
            buzz(BuzzPattern.LISTEN)
            _statusText.value = "Listening…"
            speechInput.startListening(
                language = _language.value,
                onPartial = { partial -> _statusText.value = partial },
                onResult = { text -> viewModelScope.launch { onVoiceQuery(text) } },
                onError = { msg ->
                    viewModelScope.launch {
                        _state.value = AppState.Ready
                        _statusText.value = msg
                        buzz(BuzzPattern.ERROR)
                    }
                }
            )
        }
    }

    fun stopListening() {
        speechInput.stopListening()
        if (_state.value is AppState.Listening) {
            _state.value = AppState.Ready
            _statusText.value = "Ready — tap, shake or Vol↓ to scan"
        }
    }

    fun startModelDownload() {
        viewModelScope.launch {
            ModelDownloader.downloadProgress(ctx).collect { dl ->
                when (dl) {
                    is DownloadState.Downloading -> _modelStatus.value = "Downloading ${dl.percent}%"
                    is DownloadState.Done        -> { _modelStatus.value = "Loading model…"; tryLoadLocalModel() }
                    is DownloadState.Failed      -> _modelStatus.value = "Download failed"
                }
            }
        }
    }

    // ---------- Scan routing ----------

    private suspend fun performScan(bitmap: Bitmap, mode: ScanMode, autoMode: Boolean = false) {
        speechOutput.stop()
        _state.value = AppState.Scanning()
        buzz(BuzzPattern.CAPTURE)
        _statusText.value = "Scanning…"

        // Auto-flash: if the frame is too dark, recapture with the torch firing.
        var working = bitmap
        var avg = avgLuminance(working)
        if (avg < 35 && cameraManager.hasFlash() && !autoMode) {
            _statusText.value = "Low light — using flash…"
            val lit = cameraManager.captureFrame(forceFlash = true)
            if (lit != null) {
                working = lit
                avg = avgLuminance(working)
            }
        }
        lastBitmap = working

        // Still bad after flash → guide the user instead of wasting an API call
        val warning = qualityWarning(avg)
        if (warning != null) {
            speakResult(warning, addToHistory = false)
            return
        }

        when (mode) {
            ScanMode.BARCODE   -> scanBarcode(working)
            ScanMode.CURRENCY  -> scanCurrency(working)
            ScanMode.FACE      -> scanFaces(working)
            ScanMode.COLOR     -> scanColors(working)
            ScanMode.DOCUMENT  -> scanDocument(working)
            ScanMode.SIGN      -> scanSign(working)
            ScanMode.NORMAL, ScanMode.AUTO -> scanScene(working, autoMode)
        }
    }

    // Average weighted luminance (0..255) of a downscaled copy
    private fun avgLuminance(bitmap: Bitmap): Int {
        val scaled = Bitmap.createScaledBitmap(bitmap, 80, 80, false)
        val pixels = IntArray(80 * 80)
        scaled.getPixels(pixels, 0, 80, 0, 0, 80, 80)
        scaled.recycle()
        var sum = 0L
        pixels.forEach { p ->
            sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toLong()
        }
        return (sum / pixels.size).toInt()
    }

    // Returns guidance string if brightness is still unusable, null if fine
    private fun qualityWarning(avg: Int): String? = when {
        avg < 35  -> when (_language.value) {
            AppLanguage.HINDI -> "बहुत अंधेरा है, रोशनी में जाएं"
            else -> "Too dark — please move to better lighting"
        }
        avg > 240 -> when (_language.value) {
            AppLanguage.HINDI -> "बहुत तेज रोशनी है, कैमरा घुमाएं"
            else -> "Too bright — move away from direct light"
        }
        else -> null
    }

    private suspend fun scanScene(bitmap: Bitmap, autoMode: Boolean = false) {
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
            return
        }
        runInference(contextText, bitmap, question = null, autoMode = autoMode)
    }

    private suspend fun scanSign(bitmap: Bitmap) {
        _statusText.value = "Reading sign language…"

        // The live LIVE_STREAM analyzer is already recognising the hand continuously.
        // If it caught a sign in the last 2 seconds, just announce that — instant, no
        // extra inference. The on-screen skeleton is already being drawn live.
        val recentLive = liveSign?.takeIf { System.currentTimeMillis() - liveSignTime < 2000 }
        if (recentLive != null) {
            speakResult("Person is saying: $recentLive")
            return
        }

        // Fallback: one-shot IMAGE recognition on the captured frame
        if (!gestureEngine.isReady()) {
            speakResult("Sign language mode not ready yet. Please wait a moment.")
            return
        }

        val result = withContext(Dispatchers.Default) { gestureEngine.recognize(bitmap) }

        // If the live analyzer isn't running, show the one-shot skeleton briefly
        if (_handLandmarks.value.isEmpty()) {
            val landmarks = when (result) {
                is GestureResult.Known        -> result.landmarks
                is GestureResult.HandsPresent -> result.landmarks
                else                          -> emptyList()
            }
            if (landmarks.isNotEmpty()) {
                _handLandmarks.value = landmarks
                viewModelScope.launch {
                    delay(4000)
                    if (liveSign == null) _handLandmarks.value = emptyList()
                }
            }
        }

        when (result) {
            is GestureResult.Known -> {
                speakResult("Person is saying: ${result.speech}")
            }
            is GestureResult.HandsPresent -> {
                val online = cloudFallback.checkConnectivity(ctx)
                if (online) {
                    val prompt = "A deaf person in front of a blind user is showing a hand sign or gesture. What are they trying to communicate? Be very brief, max 8 words."
                    val geminiResult = cloudFallback.analyzeImage(bitmap, prompt).takeIfValid()
                    speakResult(geminiResult ?: "I see hands but cannot identify the sign clearly. Please hold the gesture still.")
                } else {
                    speakResult("I can see hands. Please hold the gesture still and go online for full sign language reading.")
                }
            }
            is GestureResult.NoHands -> {
                speakResult(when (_language.value) {
                    AppLanguage.HINDI -> "कोई हाथ नहीं दिख रहा। कैमरे के सामने हाथ दिखाएं"
                    else -> "No hands detected. Point camera at the person's hands"
                })
            }
            is GestureResult.NoModel -> {
                speakResult("Sign language model loading. Please try again in a moment.")
            }
        }
    }

    private suspend fun scanBarcode(bitmap: Bitmap) {
        _statusText.value = "Scanning QR / barcode…"
        val raw = barcodeEngine.scan(bitmap)
        if (raw.isBlank()) {
            speakResult(when (_language.value) {
                AppLanguage.HINDI   -> "कोई QR कोड या बारकोड नहीं मिला"
                AppLanguage.KANNADA -> "QR ಕೋಡ್ ಅಥವಾ ಬಾರ್‌ಕೋಡ್ ಕಂಡುಬಂದಿಲ್ಲ"
                AppLanguage.TAMIL   -> "QR குறியீடு அல்லது பார்கோடு இல்லை"
                AppLanguage.TELUGU  -> "QR కోడ్ లేదా బార్‌కోడ్ కనుగొనబడలేదు"
                AppLanguage.BENGALI -> "কোনো QR কোড বা বারকোড পাওয়া যায়নি"
                else                -> "No QR code or barcode found"
            })
            return
        }
        lastContextText = raw
        val online = cloudFallback.checkConnectivity(ctx)
        val prompt = "Briefly explain this in max 15 words. ${langLine()} Data: $raw"
        val response = if (online) {
            tryOllama(ollamaAvailable, prompt) ?: cloudFallback.freeTextCall(prompt).takeIfValid()
        } else null
        speakResult(response ?: raw)
    }

    private suspend fun scanCurrency(bitmap: Bitmap) {
        _statusText.value = "Identifying currency…"
        val online = cloudFallback.checkConnectivity(ctx)
        if (!online) {
            speakResult("Go online to identify currency"); return
        }
        val prompt = """You are a currency identifier for visually impaired users in India.
${langLine()} Look at the image and identify Indian currency (notes: ₹10, ₹20, ₹50, ₹100, ₹200, ₹500, ₹2000 or coins).
State denomination clearly. List multiple if visible. Say none if no currency. Max 10 words."""
        val result = cloudFallback.analyzeImage(bitmap, prompt)
        speakResult(result.takeIfValid() ?: "Could not identify currency")
    }

    private suspend fun scanFaces(bitmap: Bitmap) {
        _statusText.value = "Detecting faces…"
        val faces = withContext(Dispatchers.Default) { faceEngine.detectDetailed(bitmap) }
        if (faces.isEmpty()) {
            speakResult(when (_language.value) {
                AppLanguage.HINDI   -> "कोई चेहरा नहीं मिला"
                AppLanguage.KANNADA -> "ಯಾವ ಮುಖವೂ ಕಂಡುಬಂದಿಲ್ಲ"
                else                -> "No faces detected"
            })
            return
        }

        val faceDesc = buildString {
            append("${faces.size} ${if (faces.size == 1) "person" else "people"} detected. ")
            faces.forEachIndexed { i, f ->
                if (faces.size > 1) append("Person ${i + 1}: ")

                // Recognize this face against saved people
                val name = recognizeFace(bitmap, f)
                if (name != null) append("$name, ")

                val cx = f.box.centerX().toFloat()
                val pos = when {
                    cx < bitmap.width * 0.33f -> "on your left"
                    cx > bitmap.width * 0.67f -> "on your right"
                    else                      -> "in front of you"
                }
                val faceRatio = f.box.width().toFloat() / bitmap.width
                val dist = when {
                    faceRatio > 0.45f -> "very close"
                    faceRatio > 0.25f -> "nearby"
                    faceRatio > 0.10f -> "a few meters away"
                    else              -> "far away"
                }
                append("$dist $pos")
                if (f.smiling) append(", smiling")
                if (f.eyesClosed) append(", eyes closed")
                append(". ")
            }
        }.trim()

        lastContextText = faceDesc
        val online = cloudFallback.checkConnectivity(ctx)
        // Only enrich with clothing colors when no saved person matched (keep names crisp)
        val prompt = "You are Drishti. ${langLine()} Describe these people for a blind user in max 15 words, include clothing colors if visible: $faceDesc"
        val response = if (online) cloudFallback.analyzeImage(bitmap, prompt).takeIfValid() else null
        speakResult(response ?: faceDesc)
    }

    // Crops a face from the frame and matches it to a saved person; null if no match
    private fun recognizeFace(bitmap: Bitmap, face: FaceInfo): String? {
        if (!faceRecognizer.isReady() || faceStore.all().isEmpty()) return null
        val crop = cropFace(bitmap, face) ?: return null
        val emb = faceRecognizer.embed(crop) ?: return null
        return faceStore.match(emb, FaceRecognizer.MATCH_THRESHOLD)?.first
    }

    // Crops the face box with ~20% padding, clamped to image bounds
    private fun cropFace(bitmap: Bitmap, face: FaceInfo): Bitmap? {
        return try {
            val b = face.box
            val padX = (b.width() * 0.2f).toInt()
            val padY = (b.height() * 0.2f).toInt()
            val left = (b.left - padX).coerceAtLeast(0)
            val top = (b.top - padY).coerceAtLeast(0)
            val right = (b.right + padX).coerceAtMost(bitmap.width)
            val bottom = (b.bottom + padY).coerceAtMost(bitmap.height)
            val w = right - left
            val h = bottom - top
            if (w < 20 || h < 20) return null
            Bitmap.createBitmap(bitmap, left, top, w, h)
        } catch (_: Exception) { null }
    }

    // Capture a frame and save the largest face under the given name
    fun saveFace(name: String) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            if (!faceRecognizer.isReady()) {
                speakResult("Face recognition is still loading. Please try again in a moment.", addToHistory = false)
                return@launch
            }
            _statusText.value = "Saving $name's face…"
            val bmp = cameraManager.captureFrame() ?: run {
                speakResult("Couldn't take a picture. Please try again.", addToHistory = false); return@launch
            }
            val faces = withContext(Dispatchers.Default) { faceEngine.detectDetailed(bmp) }
            val largest = faces.firstOrNull()
            if (largest == null) {
                speakResult("I don't see a face. Point the camera at the person and try again.", addToHistory = false)
                return@launch
            }
            val crop = cropFace(bmp, largest)
            val emb = crop?.let { withContext(Dispatchers.Default) { faceRecognizer.embed(it) } }
            if (emb == null) {
                speakResult("Couldn't read the face clearly. Try again with better lighting.", addToHistory = false)
                return@launch
            }
            faceStore.add(name, emb)
            speakResult("Saved. I'll remember $name from now on.", addToHistory = false)
        }
    }

    fun forgetFace(name: String) {
        val removed = faceStore.remove(name)
        viewModelScope.launch {
            speakResult(
                if (removed) "Forgotten $name." else "I don't have anyone saved as $name.",
                addToHistory = false
            )
        }
    }

    private suspend fun scanColors(bitmap: Bitmap) {
        _statusText.value = "Analyzing colors…"
        val colorDesc = withContext(Dispatchers.Default) { ColorAnalyzer.analyze(bitmap) }
        lastContextText = colorDesc
        val online = cloudFallback.checkConnectivity(ctx)
        val prompt = "You are Drishti. ${langLine()} Describe dominant colors for a blind user in max 10 words: $colorDesc"
        val response = if (online) cloudFallback.freeTextCall(prompt).takeIfValid() else null
        speakResult(response ?: "Dominant colors: $colorDesc")
    }

    private suspend fun scanDocument(bitmap: Bitmap) {
        _statusText.value = "Reading document…"
        val (latin, deva) = coroutineScope {
            val l = async { ocrEngine.extractText(bitmap) }
            val d = async { ocrEngine.extractDevanagariText(bitmap) }
            l.await() to d.await()
        }
        val fullText = listOf(latin, deva).filter { it.isNotBlank() }.joinToString("\n").trim()
        if (fullText.isBlank()) {
            speakResult(when (_language.value) {
                AppLanguage.HINDI -> "कोई टेक्स्ट नहीं मिला"
                else              -> "No text found in the image"
            })
            return
        }
        lastContextText = fullText
        speakResult(fullText)
    }

    // ---------- Inference ----------

    private suspend fun runInference(context: String, bitmap: Bitmap?, question: String?, autoMode: Boolean = false) {
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

        speakResult(response.trim(), autoMode = autoMode)
    }

    private suspend fun speakResult(text: String, addToHistory: Boolean = true, autoMode: Boolean = false) {
        val clean = preprocessForTts(text.trim())

        // Dedup: in auto-scan, skip if same result spoken recently
        if (autoMode) {
            val now = System.currentTimeMillis()
            if (wordOverlap(clean, lastAutoSpokenText) > 0.6f && now - lastAutoSpeakTime < 8_000L) {
                buzz(BuzzPattern.SAME)  // subtle pulse = "scene unchanged"
                _state.value = AppState.Ready
                return
            }
            lastAutoSpokenText = clean
            lastAutoSpeakTime = now
        }

        _statusText.value = clean
        _state.value = AppState.Speaking(clean)
        if (addToHistory && clean.isNotBlank()) {
            val record = ScanRecord(clean, System.currentTimeMillis(), _scanMode.value)
            _scanHistory.value = (listOf(record) + _scanHistory.value).take(5)
        }
        buzz(BuzzPattern.RESULT)
        val online = cloudFallback.checkConnectivity(ctx)
        speechOutput.speak(clean, _language.value, online, _ttsSpeed.value)

        // TTS done — double-buzz signals user they can speak or scan again
        buzz(BuzzPattern.READY)
        _state.value = AppState.Ready
        val modeHint = if (_scanMode.value != ScanMode.NORMAL && !autoMode)
            " — ${_scanMode.value.label} mode active"
        else ""
        _statusText.value = "Ready$modeHint — tap, shake or Vol↓ to scan"
    }

    // ---------- Helpers ----------

    private fun wordOverlap(a: String, b: String): Float {
        val wordsA = a.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val wordsB = b.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0f
        return wordsA.intersect(wordsB).size.toFloat() / maxOf(wordsA.size, wordsB.size)
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
        AppLanguage.HINDI   -> "दिख रहा है: $context"
        AppLanguage.KANNADA -> "ಕಾಣಿಸುತ್ತಿದೆ: $context"
        AppLanguage.TAMIL   -> "தெரிகிறது: $context"
        AppLanguage.TELUGU  -> "కనిపిస్తోంది: $context"
        AppLanguage.BENGALI -> "দেখা যাচ্ছে: $context"
        AppLanguage.ENGLISH -> "I can see: $context"
    }

    private fun langLine() = when (_language.value) {
        AppLanguage.HINDI   -> "Reply in Hindi."
        AppLanguage.KANNADA -> "Reply in Kannada."
        AppLanguage.TAMIL   -> "Reply in Tamil."
        AppLanguage.TELUGU  -> "Reply in Telugu."
        AppLanguage.BENGALI -> "Reply in Bengali."
        AppLanguage.ENGLISH -> "Reply in English."
    }

    private fun buildCloudPrompt(context: String, question: String?, lang: AppLanguage): String {
        val langLine = langLine()
        return if (question != null) {
            """You are Drishti, a cool AI guide for blind users.
$langLine Answer in max 12 words. Be direct and vivid.
Context from camera: $context
Question: $question"""
        } else {
            """You are Drishti, a cool AI guide for blind users. Describe what you see naturally and vividly.
$langLine Max 15 words. Rules:
- Describe people with appearance + action (e.g. "pretty woman in white kurta standing close to you")
- Read text aloud directly
- Mention object positions (left, right, ahead, close)
- Flag hazards (step, obstacle, gap)
- Nearest thing first. No filler words. Sound natural not robotic.
Camera data: $context"""
        }
    }

    private fun buildLocalPrompt(context: String, question: String?, lang: AppLanguage): String {
        val langWord = when (lang) {
            AppLanguage.HINDI   -> "Hindi mein:"
            AppLanguage.KANNADA -> "Kannada nalli:"
            AppLanguage.TAMIL   -> "Tamil il:"
            AppLanguage.TELUGU  -> "Telugu lo:"
            AppLanguage.BENGALI -> "Banglay:"
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
            BuzzPattern.READY   -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
            BuzzPattern.SAME    -> VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator?.vibrate(effect)
    }

    private enum class BuzzPattern { CAPTURE, RESULT, LISTEN, ERROR, ALERT, READY, SAME }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
        autoScanJob?.cancel()
        speechInput.release()
        speechOutput.release()
        onDeviceLlm.close()
        gestureEngine.close()
    }
}
