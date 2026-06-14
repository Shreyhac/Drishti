package com.drishti.app

sealed class AppState {
    object Loading : AppState()
    object Ready : AppState()
    data class Scanning(val message: String = "Scanning…") : AppState()
    object Thinking : AppState()
    data class Speaking(val text: String) : AppState()
    object Listening : AppState()
    data class Error(val message: String) : AppState()
}

enum class AppLanguage(val code: String, val displayName: String, val ttsLocaleTag: String) {
    HINDI("hi-IN",   "HI", "hi-IN"),
    ENGLISH("en-IN", "EN", "en-IN"),
    KANNADA("kn-IN", "KN", "kn-IN"),
    TAMIL("ta-IN",   "TA", "ta-IN"),
    TELUGU("te-IN",  "TE", "te-IN"),
    BENGALI("bn-IN", "BN", "bn-IN"),
}

enum class ScanMode(val label: String) {
    NORMAL("Scene"),
    BARCODE("QR"),
    CURRENCY("Currency"),
    FACE("Faces"),
    COLOR("Colors"),
    DOCUMENT("Read"),
    SIGN("Sign"),
    AUTO("Auto"),
}

enum class TtsSpeed(val androidRate: Float, val sarvamPace: Double, val label: String) {
    SLOW(0.65f,   0.70, "Slow"),
    NORMAL(0.85f, 0.85, "Calm"),   // calmer, more relaxed default
    FAST(1.2f,    1.2,  "Fast"),
}

data class ScanRecord(
    val text: String,
    val timestamp: Long,
    val mode: ScanMode,
)
