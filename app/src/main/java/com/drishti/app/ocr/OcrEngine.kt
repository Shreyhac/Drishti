package com.drishti.app.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrEngine(context: Context) {

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devaRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )

    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        latinRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text.trim()) }
            .addOnFailureListener { cont.resume("") }
    }

    suspend fun extractDevanagariText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        devaRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text.trim()) }
            .addOnFailureListener { cont.resume("") }
    }

    suspend fun labelImage(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                cont.resume(
                    labels.filter { it.confidence > 0.55f }.take(6).map { it.text }
                )
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }
}
