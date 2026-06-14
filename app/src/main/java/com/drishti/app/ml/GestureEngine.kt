package com.drishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

class GestureEngine(private val context: Context) {

    private var recognizer: GestureRecognizer? = null

    fun initialize(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun recognize(bitmap: Bitmap): GestureResult {
        val r = recognizer ?: return GestureResult.NoModel
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = r.recognize(mpImage)

            if (result.gestures().isEmpty() || result.gestures()[0].isEmpty()) {
                return GestureResult.NoHands
            }

            // Extract normalized landmarks (x,y in 0..1) for the first hand
            val landmarks: List<Pair<Float, Float>> = result.landmarks()
                .firstOrNull()
                ?.map { lm -> Pair(lm.x(), lm.y()) }
                ?: emptyList()

            val topGesture = result.gestures()[0][0]
            val label = topGesture.categoryName()
            val score = topGesture.score()

            if (label == "None") return GestureResult.HandsPresent(landmarks)
            if (score < 0.65f) return GestureResult.HandsPresent(landmarks)

            val speech = gestureToSpeech(label) ?: return GestureResult.HandsPresent(landmarks)
            GestureResult.Known(speech, label, score, landmarks)
        } catch (e: Exception) {
            GestureResult.NoModel
        }
    }

    private fun gestureToSpeech(categoryName: String): String? = gestureLabelToSpeech(categoryName)

    fun isReady() = recognizer != null

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}

sealed class GestureResult {
    data class Known(
        val speech: String,
        val label: String,
        val score: Float,
        val landmarks: List<Pair<Float, Float>> = emptyList()
    ) : GestureResult()
    data class HandsPresent(val landmarks: List<Pair<Float, Float>> = emptyList()) : GestureResult()
    object NoHands : GestureResult()
    object NoModel : GestureResult()
}

// Maps MediaPipe's 8 built-in gesture labels to spoken meaning. Shared by the
// on-demand IMAGE recognizer (GestureEngine) and the real-time LIVE_STREAM analyzer.
fun gestureLabelToSpeech(categoryName: String): String? = when (categoryName) {
    "Thumb_Up"    -> "Yes, good, okay"
    "Thumb_Down"  -> "No, not okay, bad"
    "Open_Palm"   -> "Hello, stop, wait"
    "Pointing_Up" -> "Wait, one moment"
    "Victory"     -> "I am fine, peace"
    "ILoveYou"    -> "I love you"
    "Closed_Fist" -> "Stop"
    else          -> null
}

// MediaPipe hand landmark connection pairs (indices into 21-point skeleton)
val HAND_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,         // thumb
    0 to 5, 5 to 6, 6 to 7, 7 to 8,          // index
    0 to 9, 9 to 10, 10 to 11, 11 to 12,     // middle
    0 to 13, 13 to 14, 14 to 15, 15 to 16,   // ring
    0 to 17, 17 to 18, 18 to 19, 19 to 20,   // pinky
    5 to 9, 9 to 13, 13 to 17                 // knuckle arch
)
