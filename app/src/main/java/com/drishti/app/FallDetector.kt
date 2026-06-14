package com.drishti.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects a likely fall from the accelerometer using the classic free-fall → impact pattern:
 *   1. a brief period of near-weightlessness (total acceleration drops well below 1G), then
 *   2. a sharp impact spike shortly after.
 * Requiring both stages (not just a single spike) keeps the phone being set down or bumped
 * from triggering. The actual SOS still goes through a cancelable countdown in the ViewModel.
 */
class FallDetector(context: Context, private val onFall: () -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    companion object {
        private const val FREEFALL_THRESHOLD = 3.5f                       // m/s² — near weightless
        private const val IMPACT_THRESHOLD = 2.6f * SensorManager.GRAVITY_EARTH // ~25.5 m/s²
        private const val IMPACT_WINDOW_MS = 1200L   // impact must follow free-fall within this
        private const val COOLDOWN_MS = 8000L        // don't re-fire for 8s
    }

    private var freefallAt = 0L
    private var lastFiredMs = 0L

    fun register() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis()

        // Total acceleration magnitude (includes gravity)
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        // Stage 1: free fall — phone is briefly weightless
        if (magnitude < FREEFALL_THRESHOLD) {
            freefallAt = now
            return
        }

        // Stage 2: impact spike shortly after a free fall
        if (magnitude > IMPACT_THRESHOLD &&
            freefallAt != 0L &&
            now - freefallAt in 1..IMPACT_WINDOW_MS &&
            now - lastFiredMs > COOLDOWN_MS
        ) {
            lastFiredMs = now
            freefallAt = 0L
            onFall()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
