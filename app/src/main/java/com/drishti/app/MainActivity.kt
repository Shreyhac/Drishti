package com.drishti.app

import android.Manifest
import android.content.Intent
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

    // Vol DOWN or shake → scan (or stop TTS if speaking — handled in DrishtiScreen)
    val scanTrigger = MutableStateFlow(0)

    // Vol UP held = push to talk; release = stop listening
    val pttStartTrigger = MutableStateFlow(0)
    val pttStopTrigger = MutableStateFlow(0)

    // Accelerometer fall detected → DrishtiScreen starts the cancelable SOS countdown
    val fallTrigger = MutableStateFlow(0)

    private lateinit var shakeDetector: ShakeDetector
    private lateinit var fallDetector: FallDetector

    // Camera + mic are required; location + SMS are requested for the emergency SOS feature
    private val requestedPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    )

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

        // Request anything not yet granted (camera, mic, location, SMS)
        val missing = requestedPermissions.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        }

        // Shake fires same trigger as Vol DOWN; DrishtiScreen decides stop-vs-scan based on state
        shakeDetector = ShakeDetector(this) { scanTrigger.value++ }
        fallDetector = FallDetector(this) { fallTrigger.value++ }

        setContent {
            DrishtiTheme {
                DrishtiScreen(
                    cameraGrantedFlow = cameraGranted,
                    micGrantedFlow = micGranted,
                    scanTriggerFlow = scanTrigger,
                    pttStartFlow = pttStartTrigger,
                    pttStopFlow = pttStopTrigger,
                    fallFlow = fallTrigger,
                    onRequestPermissions = {
                        permLauncher.launch(requestedPermissions)
                    }
                )
            }
        }

        // Launched from Quick Settings tile
        if (intent.getBooleanExtra("TILE_SCAN", false)) {
            scanTrigger.value++
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("TILE_SCAN", false)) {
            scanTrigger.value++
        }
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.register()
        fallDetector.register()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.unregister()
        fallDetector.unregister()
    }

    // Vol DOWN → scan / stop TTS (state check happens in DrishtiScreen)
    // Vol UP held → push-to-talk; release → stop listening
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { scanTrigger.value++; true }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) pttStartTrigger.value++
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

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
