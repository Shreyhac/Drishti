package com.drishti.app.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A single detected face with its box and attributes (no identity). */
data class FaceInfo(
    val box: Rect,
    val smiling: Boolean,
    val eyesClosed: Boolean
)

class FaceEngine {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    /** Returns structured info for every detected face, largest first. */
    suspend fun detectDetailed(bitmap: Bitmap): List<FaceInfo> = suspendCancellableCoroutine { cont ->
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                val list = faces.map { f ->
                    FaceInfo(
                        box = f.boundingBox,
                        smiling = (f.smilingProbability ?: 0f) > 0.65f,
                        eyesClosed = (f.leftEyeOpenProbability ?: 1f) < 0.30f
                    )
                }.sortedByDescending { it.box.width() * it.box.height() }
                cont.resume(list)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    suspend fun detect(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) { cont.resume(""); return@addOnSuccessListener }

                val desc = buildString {
                    append("${faces.size} ${if (faces.size == 1) "person" else "people"} detected. ")
                    faces.forEachIndexed { i, face ->
                        if (faces.size > 1) append("Person ${i + 1}: ")

                        val cx = face.boundingBox.centerX().toFloat()
                        val pos = when {
                            cx < bitmap.width * 0.33f -> "on your left"
                            cx > bitmap.width * 0.67f -> "on your right"
                            else                      -> "in front of you"
                        }
                        val faceRatio = face.boundingBox.width().toFloat() / bitmap.width
                        val dist = when {
                            faceRatio > 0.45f -> "very close"
                            faceRatio > 0.25f -> "nearby"
                            faceRatio > 0.10f -> "a few meters away"
                            else              -> "far away"
                        }
                        append("$dist $pos")

                        face.smilingProbability?.let { p ->
                            if (p > 0.65f) append(", smiling")
                        }
                        face.leftEyeOpenProbability?.let { p ->
                            if (p < 0.30f) append(", eyes closed")
                        }
                        append(". ")
                    }
                }
                cont.resume(desc.trim())
            }
            .addOnFailureListener { cont.resume("") }
    }
}
