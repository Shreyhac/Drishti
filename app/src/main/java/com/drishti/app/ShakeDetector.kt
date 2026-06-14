package com.drishti.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    companion object {
        // 2.7G is the experimentally validated threshold that avoids false triggers
        private const val THRESHOLD = 2.7f * SensorManager.GRAVITY_EARTH   // ~26.5 m/s²
        private const val SHAKE_WINDOW_MS = 1500L    // count shakes within 1.5s
        private const val SHAKE_SLOP_MS = 200L       // min gap between two shake peaks
        private const val COOLDOWN_MS = 1000L        // min gap between two "scan" fires
        private const val MIN_PEAKS = 2              // need 2 threshold crossings = back-and-forth
    }

    // High-pass filter state — removes steady-state gravity component
    private val gravity = FloatArray(3) { 0f }
    private val alpha = 0.8f   // smoothing factor

    private var peakCount = 0
    private var windowStart = 0L
    private var lastPeakMs = 0L
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

        // High-pass filter: isolate linear acceleration, strip gravity
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        val lx = event.values[0] - gravity[0]
        val ly = event.values[1] - gravity[1]
        val lz = event.values[2] - gravity[2]
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)

        if (magnitude > THRESHOLD && now - lastPeakMs > SHAKE_SLOP_MS) {
            lastPeakMs = now

            if (now - windowStart > SHAKE_WINDOW_MS) {
                // Start fresh window
                peakCount = 1
                windowStart = now
            } else {
                peakCount++
                if (peakCount >= MIN_PEAKS && now - lastFiredMs > COOLDOWN_MS) {
                    lastFiredMs = now
                    peakCount = 0
                    onShake()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
