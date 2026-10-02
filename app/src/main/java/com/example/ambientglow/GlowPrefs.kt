package com.example.ambientglow

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** Minimum gap between two screen wakes for new messages, so a burst wakes the panel once. */
const val WAKE_DEBOUNCE_MS = 3_500L

/** Brand accent used when an app icon yields no usable colour, and for the Test Preview. */
const val DEFAULT_GLOW_COLOR = 0xFF00E5FF.toInt()

enum class GlowStyle(@param:StringRes val title: Int, @param:StringRes val caption: Int) {
    EDGE_FRAME(R.string.style_edge_title, R.string.style_edge_caption),
    CAMERA_RING(R.string.style_ring_title, R.string.style_ring_caption),
    CUSTOM_DOT(R.string.style_dot_title, R.string.style_dot_caption);

    companion object {
        fun fromName(name: String?): GlowStyle = entries.firstOrNull { it.name == name } ?: CUSTOM_DOT
    }
}

/**
 * LED dot sizes, as on-screen radius. [LED] matches the ~1 mm notification LEDs that older
 * phones had in the bezel; the others step up for easier visibility.
 */
enum class DotSize(val radius: Dp, @param:StringRes val label: Int) {
    LED(3.dp, R.string.dot_size_led),
    SMALL(4.5.dp, R.string.dot_size_s),
    MEDIUM(6.5.dp, R.string.dot_size_m),
    LARGE(9.dp, R.string.dot_size_l);

    companion object {
        fun fromName(name: String?): DotSize = entries.firstOrNull { it.name == name } ?: LED
    }
}

/** Window brightness while only the LED dot is lit. On AMOLED only the dot's pixels draw power. */
enum class LedBrightness(val level: Float, @param:StringRes val label: Int) {
    SOFT(0.35f, R.string.led_brightness_soft),
    BRIGHT(0.7f, R.string.led_brightness_bright),
    MAX(1f, R.string.led_brightness_max);

    companion object {
        fun fromName(name: String?): LedBrightness = entries.firstOrNull { it.name == name } ?: MAX
    }
}

/**
 * What a new message looks like on a locked phone.
 * - LOCK_SCREEN: the lock screen lights up with all its notifications and the effect plays over
 *   it (needs [GlowShield]), then the LED dot covers it.
 * - BLACK: the screen comes on black and only the effect plays, then the LED dot.
 * - MESSAGE: the screen comes on black, the effect plays and the system pops up only the new
 *   message (its own heads-up, so its own layout and lock-screen privacy), then the LED dot.
 */
enum class ArrivalMode(@param:StringRes val label: Int, @param:StringRes val body: Int) {
    LOCK_SCREEN(R.string.arrival_lock_screen, R.string.arrival_lock_screen_body),
    BLACK(R.string.arrival_black, R.string.arrival_black_body),
    MESSAGE(R.string.arrival_message, R.string.arrival_message_body);

    /** Lights the black LED face for the arrival rather than the system lock screen. */
    val onBlack: Boolean get() = this != LOCK_SCREEN

    companion object {
        fun fromName(name: String?): ArrivalMode = entries.firstOrNull { it.name == name } ?: LOCK_SCREEN
    }
}

/**
 * The user's saved choices. Dot coordinates are stored as 0..1 fractions of the screen so a
 * position chosen on the dashboard preview maps exactly onto any physical resolution.
 */
@Immutable
data class GlowSettings(
    val style: GlowStyle = GlowStyle.CUSTOM_DOT,
    val dotX: Float = DEFAULT_DOT_X,
    val dotY: Float = DEFAULT_DOT_Y,
    val dotSize: DotSize = DotSize.LED,
    val ledBrightness: LedBrightness = LedBrightness.MAX,
    val arrival: ArrivalMode = ArrivalMode.LOCK_SCREEN,
) {
    companion object {
        const val DEFAULT_DOT_X = 0.06f
        const val DEFAULT_DOT_Y = 0.008f
    }
}

/** Plain SharedPreferences storage, read once per event and written on user change only. */
object GlowPrefs {
    private const val FILE = "ambient_glow"
    private const val KEY_STYLE = "style"
    private const val KEY_DOT_X = "dot_x"
    private const val KEY_DOT_Y = "dot_y"
    private const val KEY_DOT_SIZE = "dot_size"
    private const val KEY_LED_BRIGHTNESS = "led_brightness"
    private const val KEY_ARRIVAL = "arrival"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): GlowSettings {
        val prefs = prefs(context)
        return GlowSettings(
            style = GlowStyle.fromName(prefs.getString(KEY_STYLE, null)),
            dotX = prefs.getFloat(KEY_DOT_X, GlowSettings.DEFAULT_DOT_X).coerceIn(0f, 1f),
            dotY = prefs.getFloat(KEY_DOT_Y, GlowSettings.DEFAULT_DOT_Y).coerceIn(0f, 1f),
            dotSize = DotSize.fromName(prefs.getString(KEY_DOT_SIZE, null)),
            ledBrightness = LedBrightness.fromName(prefs.getString(KEY_LED_BRIGHTNESS, null)),
            arrival = ArrivalMode.fromName(prefs.getString(KEY_ARRIVAL, null)),
        )
    }

    fun saveStyle(context: Context, style: GlowStyle) {
        prefs(context).edit { putString(KEY_STYLE, style.name) }
    }

    fun saveDot(context: Context, x: Float, y: Float) {
        prefs(context).edit {
            putFloat(KEY_DOT_X, x.coerceIn(0f, 1f))
            putFloat(KEY_DOT_Y, y.coerceIn(0f, 1f))
        }
    }

    fun saveDotSize(context: Context, size: DotSize) {
        prefs(context).edit { putString(KEY_DOT_SIZE, size.name) }
    }

    fun saveLedBrightness(context: Context, brightness: LedBrightness) {
        prefs(context).edit { putString(KEY_LED_BRIGHTNESS, brightness.name) }
    }

    fun saveArrival(context: Context, arrival: ArrivalMode) {
        prefs(context).edit { putString(KEY_ARRIVAL, arrival.name) }
    }
}

/**
 * One unread message the LED is waiting on, and the brand colour it blinks in. [newestAt] is the
 * timestamp of the newest message it carried, so an update can be told apart from a new message.
 */
@Immutable
data class PendingGlow(val key: String, val color: Int, val newestAt: Long = 0L)

/**
 * The newest message for [ArrivalMode.MESSAGE]. [systemPopsUp]: the system already shows it as a
 * heads-up (the screen was on); otherwise the glow screen re-posts it once the black panel is up.
 */
class PendingMessage(val sbn: StatusBarNotification, val systemPopsUp: Boolean)

/**
 * Unread messages the LED is waiting on: the single source of truth shared by the listener
 * (writes) and the glow screen (observes). Memory only, main thread only, never persisted.
 * An entry leaves only when its notification is dismissed or cleared by the app (read).
 */
object GlowPending {
    /** Newest first. Snapshot state, so the glow screen recomposes when it changes. */
    val entries = mutableStateListOf<PendingGlow>()

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The newest message, for [ArrivalMode.MESSAGE] only; dropped once it is read or dismissed. */
    var message: PendingMessage? by mutableStateOf(null)

    fun put(entry: PendingGlow) {
        entries.removeAll { it.key == entry.key }
        entries.add(0, entry)
    }

    fun remove(key: String): Boolean {
        if (message?.sbn?.key == key) message = null
        return entries.removeAll { it.key == key }
    }

    /** Distinct colours in arrival order (newest first): one blink each in the LED loop. */
    fun colors(): List<Int> = entries.map { it.color }.distinct()
}

/**
 * How the glow screen opens.
 * - WAKE: get on top of the lock screen without covering it, so the system lock screen shows
 *   the message with its own notifications (and light the panel if it is off).
 * - LED: cover the lock screen with the black panel and the blinking LED.
 * - ARRIVAL: like LED, but first play the effect or show the message on the black panel
 *   ([ArrivalMode.onBlack]).
 */
enum class WakeMode { WAKE, LED, ARRIVAL }

/**
 * In-process link between the listener and a live glow screen (both on the main thread of
 * one process). While the glow screen is on top of the lock screen it handles every lock-flow
 * change itself; the listener only takes over when there is no glow screen on top.
 */
object GlowSession {
    interface Host {
        /** The glow screen is alive but behind other apps (the phone was unlocked). */
        val isAway: Boolean

        /** A new message arrived while the glow screen is on top of the lock screen. */
        fun onNewMessage()

        /** A waiting message was read or dismissed. */
        fun onPendingChanged()
    }

    var host: Host? = null
        private set

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }
}
