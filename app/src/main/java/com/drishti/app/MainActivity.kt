package com.drishti.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.drishti.app.ui.DrishtiScreen
import com.drishti.app.ui.theme.DrishtiTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    val cameraGranted = MutableStateFlow(false)
    val micGranted = MutableStateFlow(false)

    // Volume DOWN = capture photo
    val scanTrigger = MutableStateFlow(0)

    // Volume UP held = push to talk; release = stop listening
    val pttStartTrigger = MutableStateFlow(0)
    val pttStopTrigger = MutableStateFlow(0)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        cameraGranted.value = perms[Manifest.permission.CAMERA] == true
        micGranted.value = perms[Manifest.permission.RECORD_AUDIO] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraGranted.value = hasPermission(Manifest.permission.CAMERA)
        micGranted.value = hasPermission(Manifest.permission.RECORD_AUDIO)

        if (!cameraGranted.value || !micGranted.value) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }

        setContent {
            DrishtiTheme {
                DrishtiScreen(
                    cameraGrantedFlow = cameraGranted,
                    micGrantedFlow = micGranted,
                    scanTriggerFlow = scanTrigger,
                    pttStartFlow = pttStartTrigger,
                    pttStopFlow = pttStopTrigger,
                    onRequestPermissions = {
                        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                )
            }
        }
    }

    // Volume DOWN → capture; Volume UP keyDown → start PTT
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { scanTrigger.value++; true }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // Only fire once even if key is held (autoRepeat)
                if (event.repeatCount == 0) pttStartTrigger.value++
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // Volume UP release → stop PTT
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            pttStopTrigger.value++
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
