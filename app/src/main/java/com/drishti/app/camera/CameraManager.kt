package com.drishti.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.Camera
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

    // What the single live ImageAnalysis stream is doing right now.
    // HANDS = MediaPipe hand tracking (always-on default, RGBA frames).
    // OBJECTS = ML Kit object detection for obstacle/find modes (YUV frames).
    enum class LiveMode { HANDS, OBJECTS }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var handAnalyzer: ImageAnalysis.Analyzer? = null
    private var objectAnalyzer: ImageAnalysis.Analyzer? = null
    private var liveMode = LiveMode.HANDS

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        handAnalyzer: ImageAnalysis.Analyzer? = null,
        objectAnalyzer: ImageAnalysis.Analyzer? = null
    ) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        this.handAnalyzer = handAnalyzer
        this.objectAnalyzer = objectAnalyzer
        rebind()
    }

    /**
     * Switches what the live stream analyzes. HANDS and OBJECTS need different pixel
     * formats (RGBA vs YUV), so we rebind the camera rather than swap analyzers.
     */
    fun setLiveMode(mode: LiveMode) {
        if (liveMode == mode) return
        liveMode = mode
        if (mode != LiveMode.OBJECTS) setTorch(false)
        rebind()
    }

    private fun rebind() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = when (liveMode) {
                LiveMode.OBJECTS -> ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { ia -> objectAnalyzer?.let { ia.setAnalyzer(analysisExecutor, it) } }

                LiveMode.HANDS -> ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { ia -> handAnalyzer?.let { ia.setAnalyzer(analysisExecutor, it) } }
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    owner,
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

    fun setTorch(on: Boolean) {
        try { camera?.cameraControl?.enableTorch(on) } catch (_: Exception) {}
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    suspend fun captureFrame(forceFlash: Boolean = false): Bitmap? = suspendCoroutine { cont ->
        val capture = imageCapture ?: run { cont.resume(null); return@suspendCoroutine }
        capture.flashMode =
            if (forceFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
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
