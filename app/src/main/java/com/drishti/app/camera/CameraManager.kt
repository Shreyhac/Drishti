package com.drishti.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        surveillanceAnalyzer: SurveillanceAnalyzer? = null
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Continuous local analysis for surveillance
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            if (surveillanceAnalyzer != null) {
                imageAnalysis.setAnalyzer(analysisExecutor, surveillanceAnalyzer)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraManager", "Bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun captureFrame(): Bitmap? = suspendCoroutine { cont ->
        val capture = imageCapture ?: run { cont.resume(null); return@suspendCoroutine }
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                val bmp = proxy.toBitmap()
                proxy.close()
                cont.resume(bmp)
            }
            override fun onError(e: ImageCaptureException) {
                Log.e("CameraManager", "Capture failed", e)
                cont.resume(null)
            }
        })
    }
}
