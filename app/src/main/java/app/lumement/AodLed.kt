package app.lumement

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.annotation.RequiresApi

// ---------------------------------------------------------------------------------------------
// The LED on the always-on display, where the phone has one and it shows: the phone dozes, so
// power wakes it on key down straight into the lock screen, and no screen stays lit for it.
// Anywhere else, the lit LED in its own window. Measured on the S23 (One UI 8.5):
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
 * Where a sleep went, read off the display once the phone is asleep: the always-on display
 * (true) or off (false). Still lit for use, it is on its way (One UI keeps the panel on while it
 * goes to the always-on display): null until the display's next change.
 */
internal fun sleepShowsAod(state: Int?): Boolean? = when (state) {
    null, Display.STATE_UNKNOWN, Display.STATE_ON -> null
    else -> dozing(state)
}

/**
 * The system's own always-on display switch, where it can be read: One UI's `aod_mode`, else
 * Android's `doze_always_on` (both hidden settings, read only); null where neither is.
 */
internal fun aodSetting(resolver: ContentResolver): Int? =
    readSetting { Settings.System.getInt(resolver, ONE_UI_AOD_MODE) } ?: readSetting { Settings.Secure.getInt(resolver, DOZE_ALWAYS_ON) }

internal inline fun readSetting(read: () -> Int): Int? = try {
    read()
} catch (_: Exception) {
    null
}

private const val ONE_UI_AOD_MODE = "aod_mode"
private const val DOZE_ALWAYS_ON = "doze_always_on"

/**
 * One UI's always-on display set to "Show for new notifications" (a hidden setting, read only): it
 * shows, in the panel's lowest-power mode, only while a new notification waits, and the display
 * sleeps off otherwise. Measured on the S23: a new message, the phone asleep → the always-on
 * display, held 5+ minutes with no wakes.
 */
internal fun aodForNewNotifications(resolver: ContentResolver): Boolean =
    readSetting { Settings.System.getInt(resolver, ONE_UI_AOD_NEW_NOTIFICATIONS) } == 1

private const val ONE_UI_AOD_NEW_NOTIFICATIONS = "aod_show_for_new_noti"

/** The phone's always-on display, as the dashboard shows it ([aodMode]). */
internal enum class AodMode {
    OFF,

    /** One UI's "Show for new notifications" ([aodForNewNotifications]). */
    NEW_NOTIFICATIONS,

    /** On in any other way: always, on a tap, on a schedule. */
    ON,
}

/** The always-on display's mode, or null where the phone's switch can't be read. */
internal fun aodMode(resolver: ContentResolver): AodMode? {
    val on = aodSetting(resolver) ?: return null
    return when {
        on == 0 -> AodMode.OFF
        aodForNewNotifications(resolver) -> AodMode.NEW_NOTIFICATIONS
        else -> AodMode.ON
    }
}

/** One UI, which has "Show for new notifications": the dashboard suggests it while the always-on display is off. */
internal fun aodForNewNotificationsAvailable(resolver: ContentResolver): Boolean =
    readSetting { Settings.System.getInt(resolver, ONE_UI_AOD_NEW_NOTIFICATIONS) } != null

/**
 * The LED lives on One UI's always-on display alone: it is on, and shown for new notifications
 * ([aodForNewNotifications]). One UI decides when it shows (a new message) and when it doesn't (the
 * message seen once the phone was woken, low light, its timeout), and the LED follows it: no lit
 * LED in its place (the user's choice, 2026-10-13).
 */
internal fun ledOnAodOnly(resolver: ContentResolver): Boolean = (aodSetting(resolver) ?: 0) != 0 && aodForNewNotifications(resolver)

/**
 * Will this sleep, with messages waiting, show the always-on display? As the last sleep did
 * ([lastShowed]) while the system's switch ([aodSetting]) is as it was then ([settingThen]): that
 * also covers modes where it is on but shows only on a tap, or on a schedule. Switched since (or no
 * sleep seen yet), the switch says ([settingNow]): no display event tells a sleep that stays off
 * from one about to doze, so the last sleep's answer would be wrong once, in either direction.
 * Shown only for new notifications ([newOnly], [aodForNewNotifications]), it shows now, whatever
 * the last sleep (with nothing waiting, off) did. Null: not known.
 */
internal fun sleepWillDoze(lastShowed: Boolean?, settingThen: Int?, settingNow: Int?, newOnly: Boolean = false): Boolean? = when {
    newOnly && settingNow != null && settingNow != 0 -> true
    lastShowed != null && settingThen == settingNow -> lastShowed
    settingNow != null -> settingNow != 0
    else -> lastShowed
}

/**
 * The phone dozes with the lock screen in front: may the LED breathe on the always-on display?
 * Only with messages waiting, and nothing that keeps the LED dark: Do Not Disturb, put away, a
 * call, or the last message just read.
 */
internal fun ledOnAod(waiting: Boolean, resting: Boolean, putAway: Boolean, inCall: Boolean, ending: Boolean): Boolean =
    waiting && !resting && !putAway && !inCall && !ending

/**
 * The LED's round in [surface]: a breath per waiting app, as the lit LED breathes them
 * ([ledPauseAfter]), each drawn while the light is out. Over the always-on display ([dozing]), a
 * draw wake lock keeps the panel taking frames through each breath. Over the lit panel, [onFade]
 * asks for the breath's frame rate [LED_RATE_PREROLL_MS] before it lights and lets go once it is
 * dark, as the glow screen's own LED does.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class LedRound(
    private val context: Context,
    val surface: LedSurface,
    private val dozing: Boolean,
    private val onFade: (fast: Boolean) -> Unit = {},
) {
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
    private val prerolled = Runnable { light() }

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
        light()
    }

    /** The light goes out at once and the round stops; the surface stays, for the next. */
    fun stop() {
        running = false
        timers.removeCallbacks(nextBreath)
        timers.removeCallbacks(prerolled)
        if (drawLock.isHeld) drawLock.release()
        surface.stop()
        onFade(false)
    }

    /** The next breath's rate first, then the breath once it is in place (dozing: at once). */
    private fun breathe() {
        if (dozing) {
            light()
            return
        }
        onFade(true)
        timers.postDelayed(prerolled, LED_RATE_PREROLL_MS)
    }

    /** This breath's colour, drawn while the light is out, then lit; at its end, the dark until the next. */
    private fun light() {
        val palette = colors()
        if (!running || palette.isEmpty() || !surface.ready) {
            stop()
            return
        }
        surface.show(settings, geometry, palette[cycle % palette.size], cycle)
        if (dozing) {
            // The panel takes frames for the breath; the CPU stays up to the next one (Handler
            // time stops while it sleeps). Lit, the screen keeps both up.
            drawLock.acquire(LED_BREATH_MS.toLong() + LOCK_MARGIN_MS)
            DarkHold.acquire(context, LED_BREATH_MS.toLong() + LED_DARK_MS + LOCK_MARGIN_MS)
        }
        surface.breathe {
            if (drawLock.isHeld) drawLock.release()
            val pause = ledPauseAfter(cycle, palette.size)
            cycle++
            if (dozing) {
                timers.postDelayed(nextBreath, pause)
            } else {
                onFade(false)
                timers.postDelayed(nextBreath, pause - LED_RATE_PREROLL_MS)
            }
        }
    }

    private companion object {
        const val DRAW_WAKE_LOCK = 128
        const val LOCK_MARGIN_MS = 500L
    }
}
