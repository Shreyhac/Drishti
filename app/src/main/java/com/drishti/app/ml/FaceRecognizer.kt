package com.drishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-device face recognition using a FaceNet TFLite model. It turns a cropped face image
 * into an embedding vector; two faces of the same person produce nearby vectors. We compare
 * with cosine similarity. This does NOT detect faces — FaceEngine (ML Kit) finds and crops
 * the face first; this only embeds it.
 *
 * Input/output shapes are read from the model at load time so it works whether the model
 * expects 160x160 (FaceNet) or 112x112 (MobileFaceNet).
 */
class FaceRecognizer(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize = 160
    private var embeddingSize = 128

    companion object {
        // Cosine similarity above this = same person. Tune on-device.
        const val MATCH_THRESHOLD = 0.62f
    }

    fun initialize(): Boolean {
        return try {
            val model = loadModelFile("facenet.tflite")
            val opts = Interpreter.Options().apply { numThreads = 4 }
            val itp = Interpreter(model, opts)

            val inShape = itp.getInputTensor(0).shape()   // [1, H, W, 3]
            val outShape = itp.getOutputTensor(0).shape()  // [1, N]
            inputSize = inShape[1]
            embeddingSize = outShape[outShape.size - 1]
            interpreter = itp
            Log.i("FaceRecognizer", "Ready. input=$inputSize embedding=$embeddingSize")
            true
        } catch (e: Exception) {
            Log.e("FaceRecognizer", "init failed", e)
            false
        }
    }

    fun isReady() = interpreter != null

    /** Returns an L2-normalized embedding for a cropped face bitmap, or null on failure. */
    fun embed(faceBitmap: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
            val input = preprocess(resized)
            val output = Array(1) { FloatArray(embeddingSize) }
            itp.run(input, output)
            l2Normalize(output[0])
        } catch (e: Exception) {
            Log.w("FaceRecognizer", "embed failed: ${e.message}")
            null
        }
    }

    // FaceNet expects per-image standardization (whitening): (pixel - mean) / std
    private fun preprocess(bmp: Bitmap): ByteBuffer {
        val pixels = IntArray(inputSize * inputSize)
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val rgb = FloatArray(inputSize * inputSize * 3)
        var sum = 0.0
        var idx = 0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            rgb[idx++] = r; rgb[idx++] = g; rgb[idx++] = b
            sum += r + g + b
        }
        val mean = (sum / rgb.size).toFloat()
        var variance = 0.0
        for (v in rgb) variance += (v - mean) * (v - mean)
        val std = maxOf(sqrt(variance / rgb.size).toFloat(), 1f / sqrt(rgb.size.toFloat()))

        val buffer = ByteBuffer.allocateDirect(rgb.size * 4).order(ByteOrder.nativeOrder())
        for (v in rgb) buffer.putFloat((v - mean) / std)
        buffer.rewind()
        return buffer
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

/** Cosine similarity between two L2-normalized embeddings (so it's just the dot product). */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return -1f
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}
