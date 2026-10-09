package app.lumement

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
 * Timed from the display turning off ([WakeScreenActivity]), one timer, no sampling.
 */
internal const val COVERED_MS = 10_000L

/**
 * Tells, from accelerometer samples, when the phone has been laid face down: face down and still
 * for [FACE_DOWN_MS]. Covering is told by the display instead ([COVERED_MS]).
 */
internal class StowDetector {
    private var faceDownSince = NONE
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN

    /** Gravity [x], [y], [z] (m/s², z out of the screen) at [now] (ms): true once it lies face down. */
    fun sample(now: Long, x: Float, y: Float, z: Float): Boolean {
        // NaN before the first sample: not still yet.
        val still = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ) < STILL_DELTA
        lastX = x
        lastY = y
        lastZ = z
        faceDownSince = when {
            z < -FACE_DOWN_Z -> if (faceDownSince == NONE || !still) now else faceDownSince
            z > -TURNED_Z -> NONE
            !still -> now.takeIf { faceDownSince != NONE } ?: NONE
            else -> faceDownSince
        }
        return faceDownSince != NONE && now - faceDownSince >= FACE_DOWN_MS
    }

    fun reset() {
        faceDownSince = NONE
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
 * A look lasts this long after the last turn: time to be set down (~1-2 s, the jolt included) and
 * lie still for [FACE_DOWN_MS]. Still held after it, the phone is in a hand; the next turn looks again.
 */
internal const val LOOK_MS = 6_000L

/**
 * A short look at how the phone lies, opened by a turn: [StowDetector] on the samples until
 * [LOOK_MS] after the last turn. A turn while it is open holds it open, its samples kept: a phone
 * picked up and laid face down late in a look must still be seen lying there, or it would stay
 * lit until the next turn, which a phone lying still never makes.
 */
internal class FaceDownLook {
    private val detector = StowDetector()
    private var until = 0L

    /** Sampling: the accelerometer is wanted until this goes false. */
    var open = false
        private set

    /** A turn at [now]: true if it opens the look (start sampling); one already open is held open. */
    fun turned(now: Long): Boolean {
        until = now + LOOK_MS
        if (open) return false
        open = true
        detector.reset()
        return true
    }

    /** A sample at [now]: true once it lies face down. Then, or past its time, the look closes. */
    fun sample(now: Long, x: Float, y: Float, z: Float): Boolean {
        if (!open) return false
        val down = detector.sample(now, x, y, z)
        if (down || now >= until) open = false
        return down
    }

    fun close() {
        open = false
    }
}

/**
 * The sensors behind [StowDetector], all event-driven: nothing samples while the phone lies still.
 *
 * - [watchLit]: while the LED is lit, the sensor hub's tilt detector, which fires only when the
 *   phone turns by 35° or more, as laying it face down always does. A turn (or the LED lighting
 *   up, in case it already lies face down) opens a short [FaceDownLook]: the accelerometer until
 *   [LOOK_MS] after the last turn, or until it lies face down and still ([onStow]).
 * - [watchStowed]: once the panel sleeps, wake-up sensors that fire on a change: proximity and the
 *   tilt detector. Each event takes one accelerometer sample; [onTakenOut] if the phone is out.
 *
 * Every sensor is optional. Without a wake-up tilt detector nothing would see the phone laid down
 * or turned back up, so face down isn't watched at all (significant motion is no stand-in: it is
 * made for walking, and turning a phone over on a desk seldom trips it); covered still counts, told
 * by the display, and the system itself lights the panel when it is uncovered. With no sensors at
 * all, the LED stays lit as it always did.
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
    private val look = FaceDownLook()
    private val main = Handler(Looper.getMainLooper())

    private var mode = Mode.OFF
    private var near = false

    private enum class Mode { OFF, LIT, STOWED }

    // Samples only while a look is open.
    private val lying = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (mode != Mode.LIT) return
            val v = event.values
            val down = look.sample(SystemClock.elapsedRealtime(), v[0], v[1], v[2])
            if (!look.open) sensors?.unregisterListener(this)
            if (down) onStow()
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
        override fun onSensorChanged(event: SensorEvent) {
            when (mode) {
                Mode.LIT -> lookAgain()
                Mode.STOWED -> checkSoon()
                Mode.OFF -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
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

    /**
     * The LED is lit: watch for it being laid face down. Nothing to watch without a tilt detector.
     * Asked again while watching (a stow that had to wait for an arrival), it looks again.
     */
    fun watchLit() {
        if (mode == Mode.LIT) {
            lookAgain()
            return
        }
        stop()
        mode = Mode.LIT
        if (tilt == null || accelerometer == null) return
        sensors?.registerListener(turned, tilt, SensorManager.SENSOR_DELAY_NORMAL)
        lookAgain() // it may lie face down already
    }

    /** A turn, or the LED lighting: a [FaceDownLook], held open if one already is. */
    private fun lookAgain() {
        if (mode != Mode.LIT || tilt == null) return
        val sensor = accelerometer ?: return
        if (look.turned(SystemClock.elapsedRealtime())) sensors?.registerListener(lying, sensor, LOOK_SAMPLE_US)
    }

    private fun endLook() {
        if (!look.open) return
        look.close()
        sensors?.unregisterListener(lying)
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
        GlowLog.d { "stow watch tilt=${tilt != null} proximity=${proximity != null}" }
        proximity?.let { sensors?.registerListener(nearby, it, SensorManager.SENSOR_DELAY_NORMAL) }
        tilt?.let { sensors?.registerListener(turned, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (mode == Mode.OFF) return
        mode = Mode.OFF
        endLook()
        main.removeCallbacks(check)
        sensors?.unregisterListener(nearby)
        sensors?.unregisterListener(turned)
        sensors?.unregisterListener(probe)
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

        /** Ten samples a second during a look. */
        const val LOOK_SAMPLE_US = 100_000

        /** How long the sensor has to stay uncovered, or the turn to settle, before it is checked. */
        const val SETTLE_MS = 400L
    }
}
