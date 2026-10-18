package app.lumement

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

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
//   while it draws) keeps the panel in DOZE; held only while the LED rises. The panel then holds
//   the lit LED by itself in DOZE_SUSPEND, the CPU asleep: the LED is still, as a notification
//   LED was. Breathing there pulled the panel out of DOZE_SUSPEND every 3.4 s and cost 42 mA.
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

/** The phone's always-on display, as the dashboard shows it ([AodSwitches.mode]). */
internal enum class AodMode {
    OFF,

    /** One UI's "Show for new notifications" ([aodForNewNotifications]). */
    NEW_NOTIFICATIONS,

    /** On in any other way: always, on a tap, on a schedule. */
    ON,
}

/**
 * The always-on display's switches: the system's ([setting], [aodSetting]), and with it on, One
 * UI's "Show for new notifications" ([forNew], [aodForNewNotifications]). Compared whole to tell
 * when they changed since a sleep was seen ([sleepWillDoze]): switching between One UI's modes
 * leaves the system's on, and the last sleep's answer would be stale once.
 */
internal data class AodSwitches(val setting: Int, val forNew: Boolean) {
    val on: Boolean get() = setting != 0

    val mode: AodMode get() = when {
        !on -> AodMode.OFF
        forNew -> AodMode.NEW_NOTIFICATIONS
        else -> AodMode.ON
    }
}

/**
 * Where the switches ([aodSwitches]) live, to watch: the dashboard's always-on display row follows
 * them while it shows (the quick settings tile, Power saving switching it off).
 */
internal fun aodSwitchUris(): List<Uri> = listOf(
    Settings.System.getUriFor(ONE_UI_AOD_MODE),
    Settings.System.getUriFor(ONE_UI_AOD_NEW_NOTIFICATIONS),
    Settings.Secure.getUriFor(DOZE_ALWAYS_ON),
)

/** The switches as set now, or null where the system's can't be read. */
internal fun aodSwitches(resolver: ContentResolver): AodSwitches? = aodSwitchesOf(aodSetting(resolver), aodForNewNotifications(resolver))

/** [AodSwitches] from the settings: "Show for new notifications" counts only while it is on. */
internal fun aodSwitchesOf(setting: Int?, forNew: Boolean): AodSwitches? = setting?.let { AodSwitches(it, forNew = it != 0 && forNew) }

/** The always-on display's mode, or null where its switch can't be read. */
internal fun aodMode(resolver: ContentResolver): AodMode? = aodSwitches(resolver)?.mode

/** One UI, which has "Show for new notifications": the dashboard suggests it while the always-on display is off. */
internal fun aodForNewNotificationsAvailable(resolver: ContentResolver): Boolean =
    readSetting { Settings.System.getInt(resolver, ONE_UI_AOD_NEW_NOTIFICATIONS) } != null

/**
 * One UI's always-on display is on and shown for new notifications ([aodForNewNotifications]). One
 * UI decides when it shows: for a message that arrived while the phone slept, even after its lock
 * screen was then lit for 10 minutes, and not for one that arrived on a lit screen (measured on
 * the S23, 2026-10-17), nor once the message was seen (the phone woken, low light, its timeout).
 * After its bright phase the LED follows it and takes only the doze a new message brings: no lit
 * LED in its place (the user's choice, 2026-10-13).
 */
internal fun ledOnAodOnly(resolver: ContentResolver): Boolean = aodSwitches(resolver)?.forNew == true

/**
 * Will this sleep, with messages waiting, show the always-on display? As the last sleep did
 * ([lastShowed]) while the switches are as they were then ([settingThen]): that also covers modes
 * where it is on but shows only on a tap, or on a schedule. Switched since (or no sleep seen yet),
 * the switches say ([settingNow]): no display event tells a sleep that stays off from one about to
 * doze, so the last sleep's answer would be wrong once, in either direction. Shown only for new
 * notifications ([AodSwitches.forNew]), it shows now, whatever the last sleep (with nothing
 * waiting, off) did. Null: not known.
 */
internal fun sleepWillDoze(lastShowed: Boolean?, settingThen: AodSwitches?, settingNow: AodSwitches?): Boolean? = when {
    settingNow?.forNew == true -> true
    lastShowed != null && settingThen == settingNow -> lastShowed
    settingNow != null -> settingNow.on
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
 * The LED's round in [surface].
 *
 * Over the lit panel it breathes, a breath per waiting app ([ledPauseAfter]), each drawn while the
 * light is out; [onFade] asks for the breath's frame rate [LED_RATE_PREROLL_MS] before it lights
 * and lets go once it is dark, as the glow screen's own LED does.
 *
 * Over the always-on display ([dozing]) it is lit and still: one rise into the light, then the
 * panel holds that frame by itself (DOZE_SUSPEND), with nothing drawn and no CPU held, so the phone
 * sleeps as it does with the always-on display alone. Breathing there cost the phone 42 mA more
 * than the always-on display alone (measured on the S23: 130 against 88): a breath needs the CPU
 * held to the next one and the panel taking frames. A draw wake lock lets the panel take the rise's
 * frames. It shows every waiting app's colour at once ([LedLook]). Every [STILL_STEP_MS] it rises
 * again a step over (burn-in), woken by an alarm that may fire while the phone idles.
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

    /** The still LED's rise is on the panel: let it hold the frame. */
    private val held = Runnable { if (drawLock.isHeld) drawLock.release() }

    private val alarms by lazy(LazyThreadSafetyMode.NONE) { context.getSystemService(AlarmManager::class.java) }
    private val stepAction = "${context.packageName}.LED_STEP"
    private val stepIntent by lazy(LazyThreadSafetyMode.NONE) {
        PendingIntent.getBroadcast(context, 0, Intent(stepAction).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    }
    private var stepping = false
    private val stepped = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            cycle++
            light()
        }
    }

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
        if (dozing) watchSteps(true)
        light()
    }

    /** The light goes out at once and the round stops; the surface stays, for the next. */
    fun stop() {
        running = false
        timers.removeCallbacks(nextBreath)
        timers.removeCallbacks(prerolled)
        timers.removeCallbacks(held)
        watchSteps(false)
        if (drawLock.isHeld) drawLock.release()
        surface.stop()
        onFade(false)
    }

    /** The next breath's rate first, then the breath once it is in place. */
    private fun breathe() {
        onFade(true)
        timers.postDelayed(prerolled, LED_RATE_PREROLL_MS)
    }

    /** This breath's colour, drawn while the light is out, then lit: a breath, or the still LED's rise. */
    private fun light() {
        val palette = colors()
        if (!running || palette.isEmpty() || !surface.ready) {
            stop()
            return
        }
        // On the always-on display the frame waits at its calmest; over the lit panel it quiets
        // down through the bright phase.
        val calm = if (dozing) 1f else GlowSession.bright.settledAt(SystemClock.elapsedRealtime())
        surface.show(settings, geometry, palette[cycle % palette.size], cycle, calm, still = palette.takeIf { dozing })
        if (dozing) {
            rise()
        } else {
            surface.breathe {
                val pause = ledPauseAfter(cycle, palette.size)
                cycle++
                onFade(false)
                timers.postDelayed(nextBreath, pause - LED_RATE_PREROLL_MS)
            }
        }
    }

    /**
     * Over the always-on display: the panel takes frames for the rise (and the CPU stays up for it),
     * then holds the lit LED alone, a few frames after its peak is drawn.
     */
    private fun rise() {
        timers.removeCallbacks(held)
        drawLock.acquire(LED_RISE_MS.toLong() + LOCK_MARGIN_MS)
        DarkHold.acquire(context, LED_RISE_MS.toLong() + LOCK_MARGIN_MS)
        surface.glow { timers.postDelayed(held, HOLD_FRAMES * (1_000L / LED_FADE_HZ.toLong())) }
        scheduleStep()
    }

    private fun watchSteps(on: Boolean) {
        if (on == stepping) return
        stepping = on
        if (on) {
            ContextCompat.registerReceiver(context, stepped, IntentFilter(stepAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            alarms.cancel(stepIntent)
            context.unregisterReceiver(stepped)
        }
    }

    /** The next step, [STILL_STEP_MS] from now; an alarm allowed while idle, so the LED moves on even then. */
    private fun scheduleStep() {
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + STILL_STEP_MS, stepIntent)
    }

    private companion object {
        const val DRAW_WAKE_LOCK = 128
        const val LOCK_MARGIN_MS = 500L

        /** Frames the panel takes after the peak is drawn, before it is let hold it. */
        const val HOLD_FRAMES = 3L

        /**
         * The still LED moves a step (burn-in) this often. An alarm allowed while idle fires at
         * most once in 9 minutes for an app.
         */
        const val STILL_STEP_MS = 10 * 60_000L
    }
}
