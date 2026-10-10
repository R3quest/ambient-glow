package app.lumement

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// The LED on the always-on display, where the phone has one and it shows: the phone dozes, so
// power wakes it on key down straight into the lock screen, and no breath costs a wake and a
// sleep (the blink's). Anywhere else, the blink. Measured on the S23 (One UI 8.5):
// - One UI skips every app window's redraw while dozing ("performDraw() was skipped by
//   AOD_SHOW_STATE"), and a window only shows once it has drawn. So [GlowShield]'s window goes up
//   while the screen is on, transparent and untouchable, holding a SurfaceView: that surface is
//   the app's own, drawn straight rather than by the window, and its alpha is set on the
//   compositor. The breath is that alpha, on the LED's own grid.
// - In DOZE_SUSPEND the panel shows a held image and takes no frames: the light vanished in
//   One UI's suspended stretches. A draw wake lock (what Android's own ambient display holds
//   while it draws) keeps the panel in DOZE; held only through each breath.
// - On top of the always-on display, its clock and icons stay; the light is the LED's own.
// ---------------------------------------------------------------------------------------------

/** The always-on display (or another doze screen) is showing: the phone is asleep, the panel lit low. */
internal fun dozing(state: Int?): Boolean = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND

/**
 * The phone dozes with the lock screen in front: may the LED breathe on the always-on display?
 * Only with messages waiting, and nothing that keeps the LED dark: Do Not Disturb, put away, a
 * call, or the last message just read.
 */
internal fun ledOnAod(waiting: Boolean, resting: Boolean, putAway: Boolean, inCall: Boolean, ending: Boolean): Boolean =
    waiting && !resting && !putAway && !inCall && !ending

/**
 * The LED's round in [surface], over the always-on display: a breath per waiting app, as the lit
 * LED breathes them ([ledPauseAfter]), each drawn at its peak while the light is out and breathed
 * by the surface's alpha. [ready] once the surface exists.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class AodBreath(private val context: Context, private val surface: SurfaceView) : SurfaceHolder.Callback {
    private val timers = Handler(Looper.getMainLooper())
    private val alphaChange = SurfaceControl.Transaction()

    // PowerManager.DRAW_WAKE_LOCK, hidden: keeps a dozing panel taking frames (DOZE, not DOZE_SUSPEND).
    @SuppressLint("WrongConstant")
    private val drawLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(DRAW_WAKE_LOCK, "Lumement:aod")
        .apply { setReferenceCounted(false) }

    private var settings = GlowSettings()
    private var geometry = ScreenGeometry.Unknown
    private var colors: () -> List<Int> = { emptyList() }
    private var count = 1
    private var cycle = 0
    private var breathFrom = 0L
    private var level = -1f

    /** The surface exists: the round can start. */
    var ready = false
        private set

    /** The round is on. */
    var running = false
        private set

    private val step = Runnable { step() }
    private val nextBreath = Runnable { breathe() }

    /**
     * Starts the round with [settings] (as the screen shows it) and [geometry] (fitted), from
     * breath [round]; [colors] are read before each breath.
     */
    fun start(settings: GlowSettings, geometry: ScreenGeometry, colors: () -> List<Int>, round: Int) {
        stop()
        if (!ready) return
        this.settings = settings
        this.geometry = geometry
        this.colors = colors
        cycle = round
        running = true
        breathe()
    }

    /** The light goes out at once and the round stops; the surface stays, for the next doze. */
    fun stop() {
        running = false
        timers.removeCallbacks(step)
        timers.removeCallbacks(nextBreath)
        if (drawLock.isHeld) drawLock.release()
        setLevel(0f)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        level = -1f
        setLevel(0f)
        ready = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        stop()
    }

    /** Draws this breath's colour while the light is out, then lights it. */
    private fun breathe() {
        val palette = colors()
        if (!running || palette.isEmpty()) {
            stop()
            return
        }
        count = palette.size
        draw(palette[cycle % count])
        // The panel takes frames for the breath; the CPU stays up to the next one (Handler time
        // stops while it sleeps).
        drawLock.acquire(LED_BREATH_MS.toLong() + LOCK_MARGIN_MS)
        DarkHold.acquire(context, LED_BREATH_MS.toLong() + LED_DARK_MS + LOCK_MARGIN_MS)
        breathFrom = SystemClock.uptimeMillis()
        step()
    }

    /** One step of the breath on the LED's grid; at its end, the dark until the next. */
    private fun step() {
        if (!running) return
        val at = (SystemClock.uptimeMillis() - breathFrom).toFloat()
        if (at < LED_BREATH_MS) {
            setLevel(ledBreathAt((at / FRAME_MS).roundToInt() * FRAME_MS))
            timers.postDelayed(step, FRAME_MS.toLong())
            return
        }
        setLevel(0f)
        if (drawLock.isHeld) drawLock.release()
        val pause = ledPauseAfter(cycle, count)
        cycle++
        timers.postDelayed(nextBreath, pause)
    }

    private fun setLevel(to: Float) {
        if (to == level) return
        val control = surface.surfaceControl ?: return
        level = to
        alphaChange.setAlpha(control, to).apply()
    }

    /** The LED at its peak in [color], where the screen shows it at breath [cycle] (burn-in step included). */
    private fun draw(color: Int) {
        val holder = surface.holder
        if (!holder.surface.isValid) return
        val canvas = holder.lockHardwareCanvas()
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val density = context.resources.displayMetrics.density
            val size = Size(surface.width.toFloat(), surface.height.toFloat())
            val onCamera = settings.ledOnCamera
            val light = ledLight(
                core = settings.dotSize.radius.value * density,
                onCamera = onCamera,
                lens = geometry.lensRadius(density),
                ringGrowPx = if (onCamera) RING_SHIFTS[cycle % RING_SHIFTS.size] else 0f,
                ringGap = LED_RING_GAP.value * density,
            )
            val shift = if (onCamera) Offset.Zero else PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size]
            val center = ledCenter(onCamera, geometry, settings.dotX, settings.dotY, light, size, density) + shift
            val look = LedLook(ledMaterial(settings, elementFramesSupported), Color(color), light, onCamera)
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(canvas), size) {
                // A material that moves stays at its peak frame, with none of its one-off moments.
                look.draw(this, center, 1f, LED_RISE_MS, accents = false)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val DRAW_WAKE_LOCK = 128
        const val LOCK_MARGIN_MS = 500L
        const val FRAME_MS = 1_000f / LED_FADE_HZ
    }
}
