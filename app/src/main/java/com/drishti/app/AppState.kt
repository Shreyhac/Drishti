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
    HINDI("hi-IN", "HI", "hi-IN"),
    ENGLISH("en-IN", "EN", "en-IN"),
    KANNADA("kn-IN", "KN", "kn-IN"),
}
