package com.infinity.comicreader.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Detects a "chop-chop": the quick back-and-forth karate-chop motion of the phone.
 *
 * Three sharp strokes along the same axis, each in the opposite direction to the
 * one before (down-up-down), inside [WINDOW_MS] of each other. A single bump, a
 * walking step or a jolt on the bus never alternates like that, so false triggers
 * are rare. Uses linear acceleration (gravity already removed) when the device has
 * it, otherwise the raw accelerometer with the gravity estimate subtracted.
 *
 * Call [start] from onResume and [stop] from onPause; both are safe to repeat.
 */
class ChopDetector(
    context: Context,
    /** Sensitivity 1..10, read on every sample so a change applies at once. */
    private val sensitivity: () -> Int,
    private val onChop: () -> Unit
) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linear = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val raw = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var running = false
    private var usesLinear = false
    private val gravity = FloatArray(3)

    private var strokes = 0
    private var lastAxis = -1
    private var lastSign = 0
    private var lastTime = 0L
    private var cooldownUntil = 0L

    fun start() {
        if (running) return
        val m = manager ?: return
        usesLinear = linear != null
        val sensor = linear ?: raw ?: return
        reset()
        gravity.fill(0f)
        running = m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(this)
        running = false
        reset()
    }

    private fun reset() {
        strokes = 0
        lastAxis = -1
        lastSign = 0
        lastTime = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val a = FloatArray(3)
        if (usesLinear) {
            for (i in 0..2) a[i] = v[i]
        } else {
            // Low-pass the gravity out of the raw accelerometer.
            for (i in 0..2) {
                gravity[i] = GRAVITY_ALPHA * gravity[i] + (1f - GRAVITY_ALPHA) * v[i]
                a[i] = v[i] - gravity[i]
            }
        }

        var axis = 0
        for (i in 1..2) if (abs(a[i]) > abs(a[axis])) axis = i
        val peak = a[axis]
        if (abs(peak) < thresholdFor(sensitivity())) return

        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntil) return
        val sign = if (peak > 0f) 1 else -1

        if (strokes == 0 || now - lastTime > WINDOW_MS) {
            strokes = 1
        } else if (now - lastTime < MIN_GAP_MS) {
            // Same swing still being measured: neither a new stroke nor a reset.
            return
        } else if (axis == lastAxis && sign == -lastSign) {
            strokes++
        } else {
            strokes = 1
        }
        lastAxis = axis
        lastSign = sign
        lastTime = now

        if (strokes >= STROKES_NEEDED) {
            cooldownUntil = now + COOLDOWN_MS
            reset()
            onChop()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Level 1 needs a hard ~16 m/s^2 stroke, level 5 about 11, level 10 only ~5 (a light flick). */
        fun thresholdFor(level: Int): Float = 17f - 1.2f * level.coerceIn(1, 10)

        const val WINDOW_MS = 700L     // max time between two strokes
        const val MIN_GAP_MS = 70L     // one swing must not count twice
        const val STROKES_NEEDED = 3   // down, up, down
        const val COOLDOWN_MS = 2000L
        const val GRAVITY_ALPHA = 0.9f
    }
}
