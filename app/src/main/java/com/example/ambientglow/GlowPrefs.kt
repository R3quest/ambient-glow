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

/** Brand accent used when an app icon yields no usable colour, and the default preview colour. */
const val DEFAULT_GLOW_COLOR = 0xFF00E5FF.toInt()

enum class GlowStyle(@param:StringRes val title: Int) {
    EDGE_FRAME(R.string.style_edge_title),
    CAMERA_RING(R.string.style_ring_title),
    CUSTOM_DOT(R.string.style_dot_title);

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

/** Edge Frame line thickness at full screen. */
enum class EdgeWidth(val stroke: Dp, @param:StringRes val label: Int) {
    HAIRLINE(2.dp, R.string.edge_width_hair),
    THIN(4.dp, R.string.edge_width_thin),
    BOLD(7.dp, R.string.edge_width_bold),
    HEAVY(11.dp, R.string.edge_width_heavy);

    companion object {
        fun fromName(name: String?): EdgeWidth = entries.firstOrNull { it.name == name } ?: THIN
    }
}

/**
 * Soft light spilling inward from the Edge Frame: stacked wider strokes at falling alpha, as
 * (extra width beyond the line, alpha) pairs. No blur, so it costs a few strokes per frame.
 * Fixed widths rather than multiples of the line: a wide line with a strong glow would
 * otherwise light a band over half the screen, and every effect pass paints that whole band.
 */
enum class EdgeGlow(val layers: List<Pair<Dp, Float>>, @param:StringRes val label: Int) {
    OFF(emptyList(), R.string.edge_glow_off),
    SOFT(listOf(8.dp to 0.28f), R.string.edge_glow_soft),
    STRONG(listOf(6.dp to 0.32f, 14.dp to 0.16f, 26.dp to 0.07f), R.string.edge_glow_strong);

    companion object {
        fun fromName(name: String?): EdgeGlow = entries.firstOrNull { it.name == name } ?: SOFT
    }
}

/**
 * How the Edge Frame moves during the new-message effect.
 * - PULSE: the glow breathes out, in, and out again while the line stays crisp.
 * - COMET: one bright head with a fading tail runs around the screen.
 * - TWIN: two heads chase each other from opposite sides.
 */
enum class EdgeMotion(@param:StringRes val label: Int) {
    PULSE(R.string.edge_motion_pulse),
    COMET(R.string.edge_motion_comet),
    TWIN(R.string.edge_motion_twin);

    companion object {
        fun fromName(name: String?): EdgeMotion = entries.firstOrNull { it.name == name } ?: COMET
    }
}

/**
 * Edge Frame colouring.
 * - APP: the message's brand colour.
 * - DUO: the brand colour flowing into a neighbouring hue and back.
 * - SPECTRUM: a turning rainbow that starts at the brand colour.
 */
enum class EdgeColor(@param:StringRes val label: Int) {
    APP(R.string.edge_color_app),
    DUO(R.string.edge_color_duo),
    SPECTRUM(R.string.edge_color_spectrum);

    companion object {
        fun fromName(name: String?): EdgeColor = entries.firstOrNull { it.name == name } ?: APP
    }
}

/**
 * Glass wave: how soft the screen under it goes, as the blur radius at full screen. The black
 * panel has nothing under it to blur, so there it is only the drawn wave and frost.
 */
enum class GlassBlur(val radius: Dp, @param:StringRes val label: Int) {
    OFF(0.dp, R.string.glass_blur_off),
    LIGHT(6.dp, R.string.glass_blur_light),
    MEDIUM(10.dp, R.string.glass_blur_medium),
    STRONG(16.dp, R.string.glass_blur_strong);

    companion object {
        fun fromName(name: String?): GlassBlur = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/**
 * Glass wave: where the blur is. REVEAL and WAVE need a blur that can follow the wave (One UI's,
 * or the app's own preview); Android's window blur can't, so there they fall back to SCREEN.
 * - REVEAL: the screen lights up frosted and the wave sweeps it clear, so notifications swim
 *   out sharp behind the crest.
 * - WAVE: a blurred band rides under the crest; what it passes goes soft, then sharp again.
 * - SCREEN: the whole screen goes soft as the wave rolls in and clears as it leaves.
 */
enum class GlassArea(@param:StringRes val label: Int, @param:StringRes val body: Int) {
    REVEAL(R.string.glass_area_reveal, R.string.glass_area_reveal_body),
    WAVE(R.string.glass_area_wave, R.string.glass_area_wave_body),
    SCREEN(R.string.glass_area_screen, R.string.glass_area_screen_body);

    companion object {
        fun fromName(name: String?): GlassArea = entries.firstOrNull { it.name == name } ?: REVEAL
    }
}

/** Glass wave: a white mist laid where the blur is, as on breathed-on glass. Drawn, so it shows everywhere. */
enum class GlassFrost(val alpha: Float, @param:StringRes val label: Int) {
    OFF(0f, R.string.glass_frost_off),
    SOFT(0.07f, R.string.glass_frost_soft),
    MILKY(0.16f, R.string.glass_frost_milky);

    companion object {
        fun fromName(name: String?): GlassFrost = entries.firstOrNull { it.name == name } ?: SOFT
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
    /** The LED lights as a ring around the punch-hole camera instead of a dot; [dotSize] sets its thickness. */
    val ledOnCamera: Boolean = false,
    /**
     * The user's fit of the camera hole, applied to the reported cutout ([ScreenGeometry.fitted]):
     * some OEMs (Samsung) report only a rectangle from the top edge, not where the lens is.
     */
    val lensOffsetDp: Float = 0f,
    val lensOffsetXDp: Float = 0f,
    val lensGrowDp: Float = 0f,
    val ledBrightness: LedBrightness = LedBrightness.MAX,
    val arrival: ArrivalMode = ArrivalMode.LOCK_SCREEN,
    /** AirDrop-style intro: a light wave bursts from the camera and ignites the glow as it passes. */
    val spawn: Boolean = true,
    /** The spawn wave rolls in like the iPhone's: a soft, shimmering crest of light and a trailing ripple. */
    val glass: Boolean = false,
    val glassBlur: GlassBlur = GlassBlur.MEDIUM,
    val glassArea: GlassArea = GlassArea.REVEAL,
    val glassFrost: GlassFrost = GlassFrost.SOFT,
    val edgeWidth: EdgeWidth = EdgeWidth.THIN,
    val edgeGlow: EdgeGlow = EdgeGlow.SOFT,
    val edgeMotion: EdgeMotion = EdgeMotion.COMET,
    val edgeColor: EdgeColor = EdgeColor.APP,
) {
    /** How the waiting LED looks in mock-ups: the dot, or the ring around the camera. */
    val ledStyle: GlowStyle get() = if (ledOnCamera) GlowStyle.CAMERA_RING else GlowStyle.CUSTOM_DOT

    companion object {
        const val DEFAULT_DOT_X = 0.06f
        const val DEFAULT_DOT_Y = 0.008f
    }
}

/**
 * This look as the arrival effect sees it: LED-only fields reset, so moving or sizing the LED
 * doesn't restart effect previews. Keep in step with what ArrivalEffect reads (BeaconArrival
 * reads the dot and [GlowSettings.ledOnCamera] for Custom Dot). The lens fit reaches the effect
 * through its geometry instead.
 */
fun GlowSettings.forPreview(): GlowSettings {
    val base = copy(ledBrightness = LedBrightness.MAX, lensOffsetDp = 0f, lensOffsetXDp = 0f, lensGrowDp = 0f)
    return if (style == GlowStyle.CUSTOM_DOT) {
        base
    } else {
        base.copy(
            dotX = GlowSettings.DEFAULT_DOT_X,
            dotY = GlowSettings.DEFAULT_DOT_Y,
            dotSize = DotSize.LED,
            ledOnCamera = false,
        )
    }
}

/** Plain SharedPreferences storage, read once per event and written on user change only. */
object GlowPrefs {
    private const val FILE = "ambient_glow"
    private const val KEY_STYLE = "style"
    private const val KEY_DOT_X = "dot_x"
    private const val KEY_DOT_Y = "dot_y"
    private const val KEY_DOT_SIZE = "dot_size"
    private const val KEY_LED_CAMERA = "led_camera"
    private const val KEY_LENS_OFFSET = "lens_offset_dp"
    private const val KEY_LENS_OFFSET_X = "lens_offset_x_dp"
    private const val KEY_LENS_GROW = "lens_grow_dp"
    private const val KEY_LED_BRIGHTNESS = "led_brightness"
    private const val KEY_ARRIVAL = "arrival"
    private const val KEY_SPAWN = "spawn"
    private const val KEY_GLASS = "glass"
    private const val KEY_GLASS_BLUR = "glass_blur"
    private const val KEY_GLASS_AREA = "glass_area"
    private const val KEY_GLASS_FROST = "glass_frost"
    private const val KEY_EDGE_WIDTH = "edge_width"
    private const val KEY_EDGE_GLOW = "edge_glow"
    private const val KEY_EDGE_MOTION = "edge_motion"
    private const val KEY_EDGE_COLOR = "edge_color"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): GlowSettings {
        val prefs = prefs(context)
        return GlowSettings(
            style = GlowStyle.fromName(prefs.getString(KEY_STYLE, null)),
            dotX = prefs.getFloat(KEY_DOT_X, GlowSettings.DEFAULT_DOT_X).coerceIn(0f, 1f),
            dotY = prefs.getFloat(KEY_DOT_Y, GlowSettings.DEFAULT_DOT_Y).coerceIn(0f, 1f),
            dotSize = DotSize.fromName(prefs.getString(KEY_DOT_SIZE, null)),
            ledOnCamera = prefs.getBoolean(KEY_LED_CAMERA, false),
            lensOffsetDp = prefs.getFloat(KEY_LENS_OFFSET, 0f),
            lensOffsetXDp = prefs.getFloat(KEY_LENS_OFFSET_X, 0f),
            lensGrowDp = prefs.getFloat(KEY_LENS_GROW, 0f),
            ledBrightness = LedBrightness.fromName(prefs.getString(KEY_LED_BRIGHTNESS, null)),
            arrival = ArrivalMode.fromName(prefs.getString(KEY_ARRIVAL, null)),
            spawn = prefs.getBoolean(KEY_SPAWN, true),
            glass = prefs.getBoolean(KEY_GLASS, false),
            glassBlur = GlassBlur.fromName(prefs.getString(KEY_GLASS_BLUR, null)),
            glassArea = GlassArea.fromName(prefs.getString(KEY_GLASS_AREA, null)),
            glassFrost = GlassFrost.fromName(prefs.getString(KEY_GLASS_FROST, null)),
            edgeWidth = EdgeWidth.fromName(prefs.getString(KEY_EDGE_WIDTH, null)),
            edgeGlow = EdgeGlow.fromName(prefs.getString(KEY_EDGE_GLOW, null)),
            edgeMotion = EdgeMotion.fromName(prefs.getString(KEY_EDGE_MOTION, null)),
            edgeColor = EdgeColor.fromName(prefs.getString(KEY_EDGE_COLOR, null)),
        )
    }

    /** Saves the new-message effect choices: the spawn intro, its glass look, and every Edge Frame option. */
    fun saveEffect(context: Context, settings: GlowSettings) {
        prefs(context).edit {
            putBoolean(KEY_SPAWN, settings.spawn)
            putBoolean(KEY_GLASS, settings.glass)
            putString(KEY_GLASS_BLUR, settings.glassBlur.name)
            putString(KEY_GLASS_AREA, settings.glassArea.name)
            putString(KEY_GLASS_FROST, settings.glassFrost.name)
            putString(KEY_EDGE_WIDTH, settings.edgeWidth.name)
            putString(KEY_EDGE_GLOW, settings.edgeGlow.name)
            putString(KEY_EDGE_MOTION, settings.edgeMotion.name)
            putString(KEY_EDGE_COLOR, settings.edgeColor.name)
        }
    }

    fun saveStyle(context: Context, style: GlowStyle) {
        prefs(context).edit { putString(KEY_STYLE, style.name) }
    }

    fun saveDot(context: Context, x: Float, y: Float, onCamera: Boolean) {
        prefs(context).edit {
            putFloat(KEY_DOT_X, x.coerceIn(0f, 1f))
            putFloat(KEY_DOT_Y, y.coerceIn(0f, 1f))
            putBoolean(KEY_LED_CAMERA, onCamera)
        }
    }

    fun saveLensFit(context: Context, offsetXDp: Float, offsetDp: Float, growDp: Float) {
        prefs(context).edit {
            putFloat(KEY_LENS_OFFSET_X, offsetXDp)
            putFloat(KEY_LENS_OFFSET, offsetDp)
            putFloat(KEY_LENS_GROW, growDp)
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
 * The newest message for [ArrivalMode.MESSAGE]. [systemPopsUp]: the system shows it as a heads-up
 * itself (its channel peeks, even if the screen was off); otherwise the glow screen re-posts it
 * once the black panel is up.
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

    /** Distinct colours in arrival order (newest first): one breath each in the LED's round. */
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
