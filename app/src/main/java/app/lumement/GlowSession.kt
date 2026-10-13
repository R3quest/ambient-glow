package app.lumement

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Base64

/**
 * One unread message the LED is waiting on, the app it came from, and the colour it blinks in.
 * [newestAt] is the timestamp of the newest message it carried, so an update can be told apart
 * from a new message.
 */
@Immutable
data class PendingGlow(val key: String, val pkg: String, val color: Int, val newestAt: Long = 0L)

/**
 * The newest message for [ArrivalMode.MESSAGE]. [systemPopsUp]: the system shows it as a heads-up
 * itself (its channel peeks, even if the screen was off); otherwise the glow screen re-posts it
 * once the black panel is up.
 */
class PendingMessage(val sbn: StatusBarNotification, val systemPopsUp: Boolean)

/**
 * Unread messages the LED is waiting on: the single source of truth shared by the listener
 * (writes) and the glow screen (observes). Main thread only. An entry leaves only when its
 * notification is dismissed or cleared by the app (read).
 *
 * Kept in a file too ([keepIn]), so the LED outlives its process: an app update or the system
 * killing it would otherwise forget every waiting message, and the LED would stay dark for them
 * with no sign. The listener brings the list back when it connects again ([restore]).
 */
object GlowPending {
    /** Newest first. Snapshot state, so the glow screen recomposes when it changes. */
    val entries = mutableStateListOf<PendingGlow>()

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The newest message's colour, or the default when nothing is waiting. */
    val newestColor: Int get() = entries.firstOrNull()?.color ?: DEFAULT_GLOW_COLOR

    /** The newest message, for [ArrivalMode.MESSAGE] only; dropped once it is read or dismissed. */
    var message: PendingMessage? by mutableStateOf(null)

    /** Where the list is kept across a restart of the process; set by the listener ([keepIn]). */
    private var store: SharedPreferences? = null

    fun put(entry: PendingGlow) {
        val was = entries.firstOrNull()
        entries.removeAll { it.key == entry.key }
        entries.add(0, entry)
        // A chat updating the newest message, in the same colour: the LED shows nothing new, so the
        // file isn't written for it (a busy group posts every few seconds). Its kept [newestAt] is
        // then older: after a restart, at most one update plays as a new message.
        if (was?.key != entry.key || was.color != entry.color) save()
    }

    fun remove(key: String): Boolean {
        if (message?.sbn?.key == key) message = null
        return entries.removeAll { it.key == key }.also { if (it) save() }
    }

    /** [pkg] was muted: its waiting messages leave the LED at once. True if any did. */
    fun removeApp(pkg: String): Boolean {
        if (message?.sbn?.packageName == pkg) message = null
        return entries.removeAll { it.pkg == pkg }.also { if (it) save() }
    }

    /** [pkg] was given a new colour: its waiting messages blink in it from the next breath. */
    fun recolor(pkg: String, color: Int) {
        var changed = false
        for (i in entries.indices) {
            if (entries[i].pkg == pkg && entries[i].color != color) {
                entries[i] = entries[i].copy(color = color)
                changed = true
            }
        }
        if (changed) save()
    }

    /** Drops every entry whose notification is no longer [active] (a removal missed, e.g. during a rebind). */
    fun keepOnly(active: Set<String>) {
        if (entries.removeAll { it.key !in active }) save()
    }

    /** Distinct colours in arrival order (newest first): one breath each in the LED's round. */
    fun colors(): List<Int> = entries.map { it.color }.distinct()

    /** From now on every change is written to [prefs] (applied in the background). */
    fun keepIn(prefs: SharedPreferences) {
        store = prefs
    }

    /**
     * After a restart of the process, the list as it was kept, less what [stillShowing] no longer
     * finds in the shade (read or dismissed meanwhile): it is given the kept keys, and answers
     * which still show, or null if it can't tell (then nothing is brought back, and the file is
     * left for the next try). Only into an empty list: a list already here is newer. True if any
     * came back.
     */
    fun restore(stillShowing: (List<String>) -> Set<String>?): Boolean {
        val prefs = store ?: return false
        if (!isEmpty) {
            save()
            return false
        }
        val kept = decodePending(prefs.getString(KEY_PENDING, null))
        if (kept.isEmpty()) return false
        val showing = stillShowing(kept.map { it.key }) ?: return false
        entries.addAll(kept.filter { it.key in showing })
        save()
        return !isEmpty
    }

    private fun save() {
        val prefs = store ?: return
        prefs.edit { if (isEmpty) remove(KEY_PENDING) else putString(KEY_PENDING, encodePending(entries)) }
    }

    private const val KEY_PENDING = "pending"
}

/**
 * [entries] as one line each, their fields comma-separated: the key and package in Base64 (a key
 * may hold any character, its tag is the posting app's), the colour and time as numbers.
 */
internal fun encodePending(entries: List<PendingGlow>): String = entries.joinToString("\n") {
    listOf(base64(it.key), base64(it.pkg), it.color.toString(), it.newestAt.toString()).joinToString(",")
}

/** What [encodePending] wrote; a line that doesn't read is skipped, and nothing gives an empty list. */
internal fun decodePending(text: String?): List<PendingGlow> {
    if (text.isNullOrEmpty()) return emptyList()
    return text.lineSequence().mapNotNull { line ->
        val fields = line.split(',')
        if (fields.size != 4) return@mapNotNull null
        runCatching {
            PendingGlow(
                key = unbase64(fields[0]),
                pkg = unbase64(fields[1]),
                color = fields[2].toInt(),
                newestAt = fields[3].toLong(),
            )
        }.getOrNull()
    }.distinctBy { it.key }.toList()
}

private fun base64(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun unbase64(text: String): String = String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)

/**
 * How the glow screen opens.
 * - WAKE: get on top of the lock screen without covering it, so the system lock screen shows
 *   the message with its own notifications (and light the panel if it is off).
 * - LED: cover the lock screen with the black panel and the blinking LED.
 * - ARRIVAL: like LED, but first play the effect or show the message on the black panel
 *   ([ArrivalMode.onBlack]).
 * - AOD: asleep, the always-on display showing: get on top in the dark, the lock screen in front
 *   and the LED on the always-on display, with no wake.
 */
enum class WakeMode { WAKE, LED, ARRIVAL, AOD }

/**
 * How a new message opens the glow screen when none is on top: on the lock screen without
 * covering it, so every later switch can happen in place. With a black arrival and the screen
 * off it lights straight into the black panel instead: into the effect, or with no effect, into
 * the LED itself.
 */
internal fun wakeModeFor(arrival: ArrivalMode, screenOn: Boolean): WakeMode = when {
    screenOn || !arrival.onBlack -> WakeMode.WAKE
    arrival.playsEffect -> WakeMode.ARRIVAL
    else -> WakeMode.LED
}

/** What the listener does about the LED once the screen is off ([relightPlan]). */
internal enum class Relight {
    /** Light it now. */
    NOW,

    /** Nothing waiting, the screen on, Do Not Disturb, or a glow screen on top that sees to it. */
    NONE,

    /** In a call: once it is over. */
    AFTER_CALL,

    /** Not locked yet (a lock delay after the screen times out): once it locks. */
    AFTER_LOCK,
}

/**
 * With no glow screen on top, the LED is the listener's to light when the screen goes off. A call
 * or a lock delay only puts it off: each has a way back ([AfterCall], the wait for the lock), so a
 * message never waits unseen behind either.
 */
internal fun relightPlan(screenOn: Boolean, waiting: Boolean, resting: Boolean, hostOnTop: Boolean, inCall: Boolean, locked: Boolean): Relight = when {
    screenOn || !waiting || resting || hostOnTop -> Relight.NONE
    inCall -> Relight.AFTER_CALL
    !locked -> Relight.AFTER_LOCK
    else -> Relight.NOW
}

/**
 * When to look whether a phone that went dark unlocked has locked, in ms after the screen went off.
 * Nothing runs between looks, but the CPU is held up to each one (Handler time stops while it
 * sleeps), so the last look is how long the wait costs. With the lock delay known ([delayMs],
 * from its setting), just after it, and once more in case it ran late. Unknown (a phone may hide or
 * not have the setting), a few looks backing off to [MAX_LOCK_WAIT_MS]. Past that, none: a delay
 * that long isn't waited out, and the next wake brings the LED back.
 */
internal fun lockChecks(delayMs: Long?): List<Long> = when {
    delayMs == null -> listOf(DEFAULT_LOCK_DELAY_MS + LOCK_MARGIN_MS, MAX_LOCK_WAIT_MS / 2, MAX_LOCK_WAIT_MS)
    delayMs > MAX_LOCK_WAIT_MS -> emptyList()
    else -> listOf(delayMs + LOCK_MARGIN_MS, delayMs + LOCK_MARGIN_MS + LOCK_GRACE_MS)
}

/** One UI's lock delay after a screen timeout, when the setting can't be read. */
internal const val DEFAULT_LOCK_DELAY_MS = 5_000L
private const val LOCK_MARGIN_MS = 600L
private const val LOCK_GRACE_MS = 2_000L
internal const val MAX_LOCK_WAIT_MS = 30_000L

/**
 * The watchdog's question, a few seconds after the screen went off with a glow screen on top:
 * messages are waiting, nothing that rightly keeps the LED dark holds (Do Not Disturb, a call, the
 * phone put away, the last message just read), and yet the panel is still off.
 */
internal fun ledMissing(screenOn: Boolean, waiting: Boolean, resting: Boolean, inCall: Boolean, putAway: Boolean, ending: Boolean): Boolean =
    !screenOn && waiting && !resting && !inCall && !putAway && !ending

/**
 * May the lit LED go into its window, the lock screen readied under it? Once the LED face is in
 * front alone ([ledInFront]: resumed, no alarm or call over it), lit and [settled], and nothing
 * else holds the screen: no arrival playing, the last message not just read, not covered or put
 * away (the LED's stowing runs on its own face), no call, and no post that wants the screen
 * ([screenTaken]: a ringing call shows as a pop-up, which the window would hide).
 */
internal fun ledWindowWanted(
    ledInFront: Boolean,
    screenOn: Boolean,
    settled: Boolean,
    arriving: Boolean,
    ending: Boolean,
    covered: Boolean,
    putAway: Boolean,
    inCall: Boolean,
    screenTaken: Boolean,
): Boolean = ledInFront && screenOn && settled && !arriving && !ending && !covered && !putAway && !inCall && !screenTaken

/**
 * With the lock screen ready under the LED's window, must the window make way? For a post that
 * wants the screen ([screenTaken]), a call, or the last message just read. Covered or put away,
 * the LED's own face takes the lock screen over again instead; a power press opens it.
 */
internal fun ledWindowEnds(screenTaken: Boolean, inCall: Boolean, ending: Boolean): Boolean = screenTaken || inCall || ending

/**
 * The system's next alarm clock changed: did the one it named, due at [dueAt], just ring? Due by
 * [now] it rang; still ahead, it was moved or cancelled. Null: none was set.
 */
internal fun alarmRang(dueAt: Long?, now: Long): Boolean = dueAt != null && dueAt <= now

/**
 * Do Not Disturb, in any of its modes, rests the LED ([GlowSession.resting]); all alerts allowed,
 * or a filter not known yet, doesn't.
 */
internal fun restsUnder(interruptionFilter: Int): Boolean = when (interruptionFilter) {
    NotificationListenerService.INTERRUPTION_FILTER_PRIORITY,
    NotificationListenerService.INTERRUPTION_FILTER_NONE,
    NotificationListenerService.INTERRUPTION_FILTER_ALARMS,
    -> true
    else -> false
}

/**
 * How a message arrives: as the user chose, but while the LED rests one that breaks through Do Not
 * Disturb lights the lock screen, the way the system shows it, and no LED follows it.
 */
internal fun arrivalFor(chosen: ArrivalMode, resting: Boolean): ArrivalMode = if (resting) ArrivalMode.LOCK_SCREEN else chosen

/**
 * In-process link between the listener and a live glow screen (both on the main thread of
 * one process). While the glow screen is on top of the lock screen it handles every lock-flow
 * change itself; the listener only takes over when there is no glow screen on top.
 */
object GlowSession {
    interface Host {
        /** The glow screen is alive but behind other apps (the phone was unlocked). */
        val isAway: Boolean

        /** The phone is put away (a pocket, face down): its sleeps say nothing of the always-on display. */
        val isPutAway: Boolean

        /** A new message arrived while the glow screen is on top of the lock screen. */
        fun onNewMessage()

        /** A waiting message was read or dismissed. */
        fun onPendingChanged()

        /** Do Not Disturb came on or went off: [resting] changed. */
        fun onRestChanged()

        /** The screen has been off a while with messages waiting: if the LED isn't up, and should be, light it. */
        fun ensureLed()

        /** A post that wants the screen came or went: [screenTakers] changed. */
        fun onScreenTakersChanged()

        /** [GlowShield] went (turned off, or unbound by the system), and its windows with it. */
        fun onShieldGone()
    }

    var host: Host? = null
        private set

    /**
     * Do Not Disturb is on, so the LED rests: it neither holds the panel on nor relights it, and a
     * message that breaks through lights the lock screen only ([arrivalFor]). When it goes off, the
     * LED lights again for whatever is still unread. Set by the listener, main thread only.
     */
    var resting = false

    /**
     * The always-on display showed at the phone's last sleep ([sleepShowsAod]); null until one is
     * seen, or while it is not known. The listener watches every sleep, with a glow screen up or
     * not: the always-on display is turned on or off in Settings, unlocked, mostly with no glow
     * screen left to see the next sleep, and a stale answer would stick, since the lit LED lets
     * no lock-screen sleep finish. Main thread only.
     */
    var sleepDozes: Boolean? = null

    /** The always-on display's switches ([aodSwitches]) when [sleepDozes] was seen. */
    var sleepDozesSetting: Int? = null

    /** Will the next sleep show the always-on display ([sleepWillDoze])? Null: not known. */
    fun expectsAod(context: Context): Boolean? {
        val resolver = context.contentResolver
        return sleepWillDoze(sleepDozes, sleepDozesSetting, aodSwitches(resolver), newOnly = aodForNewNotifications(resolver))
    }

    /** Keys of other apps' posts showing now that want the screen ([takesScreen]). Set by the listener, main thread only. */
    val screenTakers = mutableSetOf<String>()

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }
}
