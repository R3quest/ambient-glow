package app.lumement

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.annotation.RequiresApi

// ---------------------------------------------------------------------------------------------
// The LED on the always-on display, where the phone has one and it shows: the phone dozes, so
// power wakes it on key down straight into the lock screen, and no breath costs a wake and a
// sleep (the blink's). Anywhere else, the blink. Measured on the S23 (One UI 8.5):
// - One UI skips every app window's redraw while dozing ("performDraw() was skipped by
//   AOD_SHOW_STATE"), and a window only shows once it has drawn. So [GlowShield]'s window goes up
//   while the screen is on, transparent and untouchable, holding the LED's own surface
//   ([LedSurface]): drawn straight rather than by the window, and breathed by its alpha on the
//   compositor, on the LED's own grid.
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
 * The LED's round over the always-on display, in [surface]: a breath per waiting app, as the lit
 * LED breathes them ([ledPauseAfter]), each drawn while the light is out. A draw wake lock keeps
 * the panel taking frames through each breath.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class AodBreath(private val context: Context, val surface: LedSurface) {
    private val timers = Handler(Looper.getMainLooper())

    // PowerManager.DRAW_WAKE_LOCK, hidden: keeps a dozing panel taking frames (DOZE, not DOZE_SUSPEND).
    @SuppressLint("WrongConstant")
    private val drawLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(DRAW_WAKE_LOCK, "Lumement:aod")
        .apply { setReferenceCounted(false) }

    private var settings = GlowSettings()
    private var geometry = ScreenGeometry.Unknown
    private var colors: () -> List<Int> = { emptyList() }
    private var cycle = 0

    /** The round is on. */
    var running = false
        private set

    private val nextBreath = Runnable { breathe() }

    /**
     * Starts the round with [settings] (as the screen shows it) and [geometry] (fitted), from
     * breath [round]; [colors] are read before each breath. Nothing while the surface isn't ready.
     */
    fun start(settings: GlowSettings, geometry: ScreenGeometry, colors: () -> List<Int>, round: Int) {
        stop()
        if (!surface.ready) return
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
        timers.removeCallbacks(nextBreath)
        if (drawLock.isHeld) drawLock.release()
        surface.stop()
    }

    /** This breath's colour, drawn while the light is out, then lit; at its end, the dark until the next. */
    private fun breathe() {
        val palette = colors()
        if (!running || palette.isEmpty() || !surface.ready) {
            stop()
            return
        }
        surface.show(settings, geometry, palette[cycle % palette.size], cycle)
        // The panel takes frames for the breath; the CPU stays up to the next one (Handler time
        // stops while it sleeps).
        drawLock.acquire(LED_BREATH_MS.toLong() + LOCK_MARGIN_MS)
        DarkHold.acquire(context, LED_BREATH_MS.toLong() + LED_DARK_MS + LOCK_MARGIN_MS)
        surface.breathe {
            if (drawLock.isHeld) drawLock.release()
            val pause = ledPauseAfter(cycle, palette.size)
            cycle++
            timers.postDelayed(nextBreath, pause)
        }
    }

    private companion object {
        const val DRAW_WAKE_LOCK = 128
        const val LOCK_MARGIN_MS = 500L
    }
}
