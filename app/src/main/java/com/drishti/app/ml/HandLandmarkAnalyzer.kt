package com.drishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * Feeds live CameraX frames to MediaPipe's GestureRecognizer in LIVE_STREAM mode so the
 * hand skeleton can be drawn in real time (not just on a tap). Each result delivers the
 * 21 normalised landmarks plus, when confident, a recognised sign and a frame brightness
 * estimate (used to auto-toggle the torch in low light).
 */
class HandLandmarkAnalyzer(
    private val context: Context,
    private val onResult: (
        landmarks: List<Pair<Float, Float>>,
        speech: String?,
        label: String?,
        luminance: Int
    ) -> Unit
) : ImageAnalysis.Analyzer {

    private var recognizer: GestureRecognizer? = null
    private var lastLuminance = 128

    fun initialize(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setResultListener(this::onLiveResult)
                .setErrorListener { e -> Log.w("HandLandmark", "MP error: ${e.message}") }
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            Log.e("HandLandmark", "init failed", e)
            false
        }
    }

    fun isReady() = recognizer != null

    override fun analyze(imageProxy: ImageProxy) {
        val rec = recognizer ?: run { imageProxy.close(); return }
        val frameTime = SystemClock.uptimeMillis()
        val w = imageProxy.width
        val h = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees

        val buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            buffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        } catch (e: Exception) {
            Log.w("HandLandmark", "copy failed: ${e.message}")
            imageProxy.close()
            return
        } finally {
            imageProxy.close()
        }

        // Rotate to upright so landmark coordinates line up with the on-screen preview
        val rotated = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(buffer, 0, 0, w, h, m, true)
        } else buffer

        lastLuminance = estimateLuminance(rotated)

        try {
            val mpImage: MPImage = BitmapImageBuilder(rotated).build()
            rec.recognizeAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.w("HandLandmark", "recognizeAsync failed: ${e.message}")
        }
    }

    private fun onLiveResult(result: GestureRecognizerResult, input: MPImage) {
        val landmarks = result.landmarks().firstOrNull()
            ?.map { Pair(it.x(), it.y()) }
            ?: emptyList()

        var speech: String? = null
        var label: String? = null

        if (result.gestures().isNotEmpty() && result.gestures()[0].isNotEmpty()) {
            val top = result.gestures()[0][0]
            if (top.categoryName() != "None" && top.score() >= 0.55f) {
                gestureLabelToSpeech(top.categoryName())?.let {
                    speech = it
                    label = top.categoryName()
                }
            }
        }

        // No trained-model match → try rule-based finger counting
        if (speech == null && landmarks.size == 21) {
            SignClassifier.classify(landmarks)?.let {
                speech = it.speech
                label = it.label
            }
        }

        onResult(landmarks, speech, label, lastLuminance)
    }

    private fun estimateLuminance(bitmap: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
        val px = IntArray(32 * 32)
        s.getPixels(px, 0, 32, 0, 0, 32, 32)
        s.recycle()
        var sum = 0L
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        return (sum / px.size).toInt()
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
