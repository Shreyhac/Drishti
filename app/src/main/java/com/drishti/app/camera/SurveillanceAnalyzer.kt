package com.drishti.app.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

class SurveillanceAnalyzer(
    private val onSignificantChange: (newLabels: Set<String>) -> Unit
) : ImageAnalysis.Analyzer {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.70f).build()
    )

    private var lastLabels = emptySet<String>()
    private var lastAnalysisTime = 0L
    private var frameCount = 0

    // Run ML Kit every 1.5s — enough to catch real-world changes, saves battery
    private val THROTTLE_MS = 1500L

    // Minimum labels needed before we start comparing (avoids false alerts at startup)
    private val MIN_FRAMES_BEFORE_ALERT = 3

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < THROTTLE_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        labeler.process(inputImage)
            .addOnSuccessListener { labels ->
                val newLabels = labels
                    .filter { it.confidence > 0.70f }
                    .map { it.text }
                    .toSet()

                frameCount++
                if (frameCount >= MIN_FRAMES_BEFORE_ALERT && lastLabels.isNotEmpty()) {
                    if (isSignificantChange(lastLabels, newLabels)) {
                        onSignificantChange(newLabels)
                    }
                }
                if (newLabels.isNotEmpty()) lastLabels = newLabels
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun isSignificantChange(prev: Set<String>, curr: Set<String>): Boolean {
        if (prev.isEmpty() || curr.isEmpty()) return false
        val intersection = prev.intersect(curr).size.toFloat()
        val union = prev.union(curr).size.toFloat()
        val similarity = if (union > 0) intersection / union else 1f
        // < 50% overlap = significant scene change
        return similarity < 0.50f
    }

    fun reset() {
        lastLabels = emptySet()
        frameCount = 0
    }
}
