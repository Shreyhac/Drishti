package com.drishti.app.ml

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/** One detected thing in the live camera frame, in normalized coordinates. */
data class DetectedThing(
    val label: String,        // best-guess label, or "object" if unknown
    val centerX: Float,       // 0 (left) .. 1 (right)
    val centerY: Float,       // 0 (top) .. 1 (bottom)
    val areaFraction: Float   // box area / frame area — proxy for closeness
)

/**
 * Live object detector (ML Kit) used for the obstacle/walking assistant and find-my-object.
 * Reports detected objects with bounding boxes so the ViewModel can work out direction
 * ("to your left") and rough closeness (bigger box = nearer). Throttled to ~3 fps.
 */
class ObjectAnalyzer(
    private val onObjects: (List<DetectedThing>, luminance: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .build()
    )

    private var lastRun = 0L
    private val throttleMs = 300L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastRun < throttleMs) { imageProxy.close(); return }
        lastRun = now

        val media = imageProxy.image
        if (media == null) { imageProxy.close(); return }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val frameW = input.width.toFloat()
        val frameH = input.height.toFloat()

        detector.process(input)
            .addOnSuccessListener { objects ->
                val things = objects.map { obj ->
                    val box = obj.boundingBox
                    val label = obj.labels.maxByOrNull { it.confidence }?.text ?: "object"
                    DetectedThing(
                        label = label,
                        centerX = (box.exactCenterX() / frameW).coerceIn(0f, 1f),
                        centerY = (box.exactCenterY() / frameH).coerceIn(0f, 1f),
                        areaFraction = ((box.width() * box.height()) / (frameW * frameH)).coerceIn(0f, 1f)
                    )
                }
                onObjects(things, estimateLuminance(media))
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // Cheap luminance from the Y plane (first plane of YUV) — average of a sample of bytes
    private fun estimateLuminance(image: android.media.Image): Int {
        return try {
            val yBuffer = image.planes[0].buffer
            val n = yBuffer.remaining()
            if (n == 0) return 128
            var sum = 0L
            var count = 0
            val step = (n / 2048).coerceAtLeast(1) // sample ~2000 pixels
            var i = 0
            while (i < n) {
                sum += (yBuffer.get(i).toInt() and 0xFF)
                count++
                i += step
            }
            if (count == 0) 128 else (sum / count).toInt()
        } catch (_: Exception) { 128 }
    }
}
