package com.example.ambientglow

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Put away: with the LED up, the phone laid face down (on any surface: a glass desk lets the
// proximity sensor see straight through) or covered (pocket, bag). Then nobody can see the LED, and
// the panel and the CPU should sleep until it is taken out again.
// ---------------------------------------------------------------------------------------------

/** Gravity out of the screen below minus this (m/s²) is face down: within ~35° of lying flat on it. */
internal const val FACE_DOWN_Z = 8f

/** Above minus this (~52° off flat) it is no longer face down; in between, it stays as it was. */
internal const val TURNED_Z = 6f

/** Face down and still this long before it counts: put down on purpose, not turned over in the hand. */
internal const val FACE_DOWN_MS = 1_500L

/**
 * Still: gravity moves less than this (m/s², summed over the axes) from one sample to the next.
 * Measured on the S23: lying on a desk 0.00-0.04; held face down above someone lying in bed, as
 * still as they could, never under 0.56. A phone held like that is being looked at.
 */
internal const val STILL_DELTA = 0.2f

/**
 * Covered this long before it counts. The proximity lock already has the panel off; this only
 * lets the CPU sleep too, so a hand passing over the phone isn't worth a sleep and a relight.
 */
internal const val COVERED_MS = 10_000L

/**
 * Tells, from samples while the LED is lit, when the phone has been put away: face down and still
 * for [FACE_DOWN_MS], or covered for [COVERED_MS] (a pocket moves as its owner walks, so covering
 * needn't be still). [faceDownCounts] is false where nothing could wake the phone again when it is
 * turned back over: then only covering counts.
 */
internal class StowDetector(private val faceDownCounts: Boolean) {
    private var faceDownSince = NONE
    private var coveredSince = NONE
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN

    /**
     * Gravity [x], [y], [z] (m/s², z out of the screen) and the panel [covered], at [now] (ms):
     * true once it is put away.
     */
    fun sample(now: Long, x: Float, y: Float, z: Float, covered: Boolean): Boolean {
        // NaN before the first sample: not still yet.
        val still = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ) < STILL_DELTA
        lastX = x
        lastY = y
        lastZ = z
        faceDownSince = when {
            !faceDownCounts -> NONE
            z < -FACE_DOWN_Z -> if (faceDownSince == NONE || !still) now else faceDownSince
            z > -TURNED_Z -> NONE
            !still -> now.takeIf { faceDownSince != NONE } ?: NONE
            else -> faceDownSince
        }
        coveredSince = if (!covered) NONE else if (coveredSince == NONE) now else coveredSince
        return (faceDownSince != NONE && now - faceDownSince >= FACE_DOWN_MS) ||
            (coveredSince != NONE && now - coveredSince >= COVERED_MS)
    }

    fun reset() {
        faceDownSince = NONE
        coveredSince = NONE
        lastX = Float.NaN
        lastY = Float.NaN
        lastZ = Float.NaN
    }

    private companion object {
        const val NONE = -1L
    }
}

/** Taken out: nothing over the screen, and no longer face down. */
internal fun takenOut(z: Float, near: Boolean): Boolean = !near && z > -TURNED_Z

/**
 * The sensors behind [StowDetector], in two modes. [watchLit]: while the LED is lit (the CPU is
 * awake anyway), the accelerometer at a few samples a second; [onStow] once the phone is put away.
 * [watchStowed]: once the panel sleeps, only wake-up sensors that fire on a change: proximity, and
 * the sensor hub's tilt detector (35° of turn) or, without one, significant motion. Each event
 * takes one accelerometer sample; [onTakenOut] if the phone is out again. Nothing runs between
 * events, so a phone lying face down overnight costs no wake-ups.
 */
internal class StowWatch(
    private val context: Context,
    private val covered: () -> Boolean,
    private val onStow: () -> Unit,
    private val onTakenOut: () -> Unit,
) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximity = sensors?.let { it.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: it.getDefaultSensor(Sensor.TYPE_PROXIMITY) }

    // Sensor.TYPE_TILT_DETECTOR is hidden from the SDK, but the sensor is public where it exists.
    private val tilt = sensors?.getDefaultSensor(TYPE_TILT_DETECTOR, true)
    private val motion = if (tilt == null) sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) else null
    private val detector = StowDetector(faceDownCounts = tilt != null || motion != null)
    private val main = Handler(Looper.getMainLooper())

    private var mode = Mode.OFF
    private var near = false

    private enum class Mode { OFF, LIT, STOWED }

    private val lit = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (mode == Mode.LIT && detector.sample(SystemClock.elapsedRealtime(), v[0], v[1], v[2], covered())) onStow()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val nearby = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            near = event.values[0] < event.sensor.maximumRange
            if (!near) checkSoon()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val turned = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = checkSoon()
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val moved = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (mode != Mode.STOWED) return
            motion?.let { sensors?.requestTriggerSensor(this, it) } // one-shot: ask again
            checkSoon()
        }
    }

    // One sample, then let go: is it still face down?
    private val probe = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            sensors?.unregisterListener(this)
            val z = event.values[2]
            GlowLog.d { "stow check z=$z near=$near" }
            if (mode == Mode.STOWED && takenOut(z, near)) onTakenOut()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    // A pocket's fabric can uncover the sensor for a moment: only a phone that stays out counts.
    private val check = Runnable {
        if (mode == Mode.STOWED && !near) accelerometer?.let { sensors?.registerListener(probe, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    /** The LED is lit: watch for it being put away. */
    fun watchLit() {
        if (mode == Mode.LIT || accelerometer == null) return
        stop()
        mode = Mode.LIT
        detector.reset()
        sensors?.registerListener(lit, accelerometer, LIT_SAMPLE_US)
    }

    /** The LED is no longer lit (unless it was put away): stop watching for that. */
    fun stopLit() {
        if (mode == Mode.LIT) stop()
    }

    /** Put away, the panel going to sleep: watch, with wake-up sensors only, for it being taken out. */
    fun watchStowed() {
        stop()
        mode = Mode.STOWED
        near = covered()
        GlowLog.d { "stow watch tilt=${tilt != null} motion=${motion != null} proximity=${proximity != null}" }
        proximity?.let { sensors?.registerListener(nearby, it, SensorManager.SENSOR_DELAY_NORMAL) }
        tilt?.let { sensors?.registerListener(turned, it, SensorManager.SENSOR_DELAY_NORMAL) }
        motion?.let { sensors?.requestTriggerSensor(moved, it) }
    }

    fun stop() {
        if (mode == Mode.OFF) return
        mode = Mode.OFF
        main.removeCallbacks(check)
        sensors?.unregisterListener(lit)
        sensors?.unregisterListener(nearby)
        sensors?.unregisterListener(turned)
        sensors?.unregisterListener(probe)
        motion?.let { sensors?.cancelTriggerSensor(moved, it) }
    }

    private fun checkSoon() {
        if (mode != Mode.STOWED) return
        // Woken from sleep by the sensor: keep the CPU up until the check has its sample.
        DarkHold.acquire(context)
        main.removeCallbacks(check)
        main.postDelayed(check, SETTLE_MS)
    }

    private companion object {
        /** Sensor.TYPE_TILT_DETECTOR (hidden): a wake-up event each time the phone turns by 35° or more. */
        const val TYPE_TILT_DETECTOR = 22

        /** Four samples a second while lit: [FACE_DOWN_MS] is then six of them. */
        const val LIT_SAMPLE_US = 250_000

        /** How long the sensor has to stay uncovered, or the turn to settle, before it is checked. */
        const val SETTLE_MS = 400L
    }
}
