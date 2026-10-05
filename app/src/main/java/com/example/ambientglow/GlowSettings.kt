package com.example.ambientglow

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Minimum gap between two screen wakes for new messages, so a burst wakes the panel once. */
const val WAKE_DEBOUNCE_MS = 3_500L

/** Brand accent used when an app icon yields no usable colour, and the default preview colour. */
const val DEFAULT_GLOW_COLOR = 0xFF00E5FF.toInt()

/**
 * An option the dashboard lists by its name. Options a folded summary describes also carry a
 * `phrase`, which reads inside a sentence ("thin line").
 */
interface Labeled {
    @get:StringRes
    val label: Int
}

enum class GlowStyle(@param:StringRes override val label: Int) : Labeled {
    EDGE_FRAME(R.string.style_edge_title),
    CAMERA_RING(R.string.style_ring_title),
    CUSTOM_DOT(R.string.style_dot_title),
}

/**
 * LED dot sizes, as on-screen radius. [LED] matches the ~1 mm notification LEDs that older
 * phones had in the bezel; the others step up for easier visibility.
 */
enum class DotSize(val radius: Dp, @param:StringRes override val label: Int) : Labeled {
    LED(3.dp, R.string.dot_size_led),
    SMALL(4.5.dp, R.string.dot_size_s),
    MEDIUM(6.5.dp, R.string.dot_size_m),
    LARGE(9.dp, R.string.dot_size_l),
}

/** Window brightness while only the LED dot is lit. On AMOLED only the dot's pixels draw power. */
enum class LedBrightness(val level: Float, @param:StringRes override val label: Int) : Labeled {
    SOFT(0.35f, R.string.led_brightness_soft),
    BRIGHT(0.7f, R.string.led_brightness_bright),
    MAX(1f, R.string.led_brightness_max),
}

/** Edge Frame line thickness at full screen. */
enum class EdgeWidth(
    val stroke: Dp,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    HAIRLINE(2.dp, R.string.edge_width_hair, R.string.edge_width_hair_phrase),
    THIN(4.dp, R.string.edge_width_thin, R.string.edge_width_thin_phrase),
    BOLD(7.dp, R.string.edge_width_bold, R.string.edge_width_bold_phrase),
    HEAVY(11.dp, R.string.edge_width_heavy, R.string.edge_width_heavy_phrase),
}

/**
 * Soft light spilling inward from the Edge Frame, brightest at the line ([peak] alpha) and falling
 * off as a Gaussian over [reach]. Drawn as thin rings, no blur, so it costs a few strokes per
 * frame. Fixed reaches rather than multiples of the line: a wide line with a strong glow would
 * otherwise light a band over half the screen, and every effect pass paints that whole band.
 */
enum class EdgeGlow(
    val reach: Dp,
    val peak: Float,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    OFF(0.dp, 0f, R.string.edge_glow_off, R.string.edge_glow_off_phrase),
    SOFT(12.dp, 0.34f, R.string.edge_glow_soft, R.string.edge_glow_soft_phrase),
    STRONG(24.dp, 0.42f, R.string.edge_glow_strong, R.string.edge_glow_strong_phrase),
}

/**
 * How the Edge Frame moves during the new-message effect.
 * - PULSE: the glow breathes out, in, and out again while the line stays crisp.
 * - COMET: one bright head with a fading tail runs up the left side to the camera.
 * - TWIN: two lights rise from the bottom up both sides and meet at the camera.
 */
enum class EdgeMotion(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    PULSE(R.string.edge_motion_pulse, R.string.edge_motion_pulse_body, R.string.edge_motion_pulse_phrase),
    COMET(R.string.edge_motion_comet, R.string.edge_motion_comet_body, R.string.edge_motion_comet_phrase),
    TWIN(R.string.edge_motion_twin, R.string.edge_motion_twin_body, R.string.edge_motion_twin_phrase),
}

/**
 * Edge Frame colouring.
 * - APP: the message's brand colour.
 * - DUO: the brand colour flowing into a neighbouring hue and back.
 * - SPECTRUM: a turning rainbow that starts at the brand colour.
 */
enum class EdgeColor(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    APP(R.string.edge_color_app, R.string.edge_color_app_body, R.string.edge_color_app_phrase),
    DUO(R.string.edge_color_duo, R.string.edge_color_duo_body, R.string.edge_color_duo_phrase),
    SPECTRUM(R.string.edge_color_spectrum, R.string.edge_color_spectrum_body, R.string.edge_color_spectrum_phrase),
}

/**
 * The element the spawn wave takes after; the wave always has one. Water is the glass wave; the
 * others have no look of their own yet ([ready] is false) and play the bare wave until they do.
 * [premium] ones are marked as such but free to try for now: nothing checks a purchase yet.
 */
enum class SpawnElement(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    val ready: Boolean,
    val premium: Boolean,
) : Labeled {
    FIRE(R.string.element_fire, R.string.element_fire_body, ready = false, premium = true),
    WATER(R.string.element_water, R.string.element_water_body, ready = true, premium = false),
    AIR(R.string.element_air, R.string.element_air_body, ready = false, premium = true),
    EARTH(R.string.element_earth, R.string.element_earth_body, ready = false, premium = true),
}

/**
 * Glass wave: how soft the screen under it goes, as the blur radius at full screen. The black
 * panel has nothing under it to blur, so there it is only the drawn wave and frost.
 */
enum class GlassBlur(
    val radius: Dp,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    OFF(0.dp, R.string.glass_blur_off, R.string.glass_blur_off_phrase),
    LIGHT(6.dp, R.string.glass_blur_light, R.string.glass_blur_light_phrase),
    MEDIUM(10.dp, R.string.glass_blur_medium, R.string.glass_blur_medium_phrase),
    STRONG(16.dp, R.string.glass_blur_strong, R.string.glass_blur_strong_phrase),
}

/**
 * Glass wave: where the blur is. REVEAL and WAVE need a blur that can follow the wave (One UI's,
 * or the app's own preview); Android's window blur can't, so there they fall back to SCREEN.
 * - REVEAL: the screen lights up frosted and the wave sweeps it clear, so notifications swim
 *   out sharp behind the crest.
 * - WAVE: a blurred band rides under the crest; what it passes goes soft, then sharp again.
 * - SCREEN: the whole screen goes soft as the wave rolls in and clears as it leaves.
 */
enum class GlassArea(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    REVEAL(R.string.glass_area_reveal, R.string.glass_area_reveal_body, R.string.glass_area_reveal_phrase),
    WAVE(R.string.glass_area_wave, R.string.glass_area_wave_body, R.string.glass_area_wave_phrase),
    SCREEN(R.string.glass_area_screen, R.string.glass_area_screen_body, R.string.glass_area_screen_phrase),
}

/**
 * Glass wave: a grey diffusing mist laid over the glass area ([GlassArea]), as on breathed-on
 * glass. Drawn, so it shows everywhere.
 */
enum class GlassFrost(
    val alpha: Float,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    OFF(0f, R.string.glass_frost_off, R.string.glass_frost_off_phrase),
    SOFT(0.16f, R.string.glass_frost_soft, R.string.glass_frost_soft_phrase),
    MILKY(0.30f, R.string.glass_frost_milky, R.string.glass_frost_milky_phrase),
}

/**
 * What a new message looks like on a locked phone.
 * - LOCK_SCREEN: the lock screen lights up with all its notifications and the effect plays over
 *   it (needs [GlowShield]), then the LED dot covers it.
 * - BLACK: the screen comes on black and only the effect plays, then the LED dot.
 * - MESSAGE: the screen comes on black, the effect plays and the system pops up only the new
 *   message (its own heads-up, so its own layout and lock-screen privacy), then the LED dot.
 * - LED_ONLY: no effect: the screen comes on black straight into the LED dot.
 */
enum class ArrivalMode(@param:StringRes override val label: Int, @param:StringRes val body: Int) : Labeled {
    LOCK_SCREEN(R.string.arrival_lock_screen, R.string.arrival_lock_screen_body),
    BLACK(R.string.arrival_black, R.string.arrival_black_body),
    MESSAGE(R.string.arrival_message, R.string.arrival_message_body),
    LED_ONLY(R.string.arrival_led_only, R.string.arrival_led_only_body);

    /** Lights the black LED face for the arrival rather than the system lock screen. */
    val onBlack: Boolean get() = this != LOCK_SCREEN

    /** A new message plays the effect before the LED; without it, the dot is all there is. */
    val playsEffect: Boolean get() = this != LED_ONLY
}

/**
 * The user's saved choices; the defaults here are also what a fresh install loads. Dot
 * coordinates are stored as 0..1 fractions of the screen so a position chosen on the dashboard
 * preview maps exactly onto any physical resolution. A new setting needs a field here and one
 * line each in [GlowPrefs.load] and [GlowPrefs.save].
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
    /** The element the spawn wave takes after; Water, the glass wave, is the free one. */
    val element: SpawnElement = SpawnElement.WATER,
    val glassBlur: GlassBlur = GlassBlur.MEDIUM,
    val glassArea: GlassArea = GlassArea.REVEAL,
    val glassFrost: GlassFrost = GlassFrost.SOFT,
    val edgeWidth: EdgeWidth = EdgeWidth.THIN,
    val edgeGlow: EdgeGlow = EdgeGlow.SOFT,
    val edgeMotion: EdgeMotion = EdgeMotion.COMET,
    val edgeColor: EdgeColor = EdgeColor.APP,
) {
    /** The spawn wave rolls in like the iPhone's: a soft, shimmering crest of light and a trailing ripple. */
    val glass: Boolean get() = element == SpawnElement.WATER

    /** How the waiting LED looks in mock-ups: the dot, or the ring around the camera. */
    val ledStyle: GlowStyle get() = if (ledOnCamera) GlowStyle.CAMERA_RING else GlowStyle.CUSTOM_DOT

    companion object {
        const val DEFAULT_DOT_X = 0.06f
        const val DEFAULT_DOT_Y = 0.008f
    }
}

/**
 * This look as the arrival effect sees it: LED-only fields reset, so moving or sizing the LED
 * doesn't restart effect previews. Keep in step with what ArrivalEffect reads: BeaconArrival
 * reads the dot and [GlowSettings.ledOnCamera] for Custom Dot, and for Camera Ring the LED's size
 * and [GlowSettings.ledOnCamera], since its ember becomes the LED ring. The lens fit reaches the
 * effect through its geometry instead.
 */
fun GlowSettings.forPreview(): GlowSettings {
    val base = copy(ledBrightness = LedBrightness.MAX, lensOffsetDp = 0f, lensOffsetXDp = 0f, lensGrowDp = 0f)
    return when (style) {
        GlowStyle.CUSTOM_DOT -> base
        GlowStyle.CAMERA_RING -> base.copy(dotX = GlowSettings.DEFAULT_DOT_X, dotY = GlowSettings.DEFAULT_DOT_Y)
        GlowStyle.EDGE_FRAME -> base.copy(
            dotX = GlowSettings.DEFAULT_DOT_X,
            dotY = GlowSettings.DEFAULT_DOT_Y,
            dotSize = DotSize.LED,
            ledOnCamera = false,
        )
    }
}
