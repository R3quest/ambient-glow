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

/**
 * How a new message arrives. [BEACON] lights where the LED waits, so it takes the LED's form: a
 * dot, or a ring round the camera ([GlowSettings.ledOnCamera]). Every phone has a place for it.
 */
enum class GlowStyle(@param:StringRes override val label: Int, @param:StringRes val body: Int) : Labeled {
    BEACON(R.string.style_beacon_title, R.string.style_beacon_body),
    EDGE_FRAME(R.string.style_edge_title, R.string.style_edge_body),
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

/**
 * How bright the LED dot's own light is, as its alpha over the black panel, at the system
 * brightness (on the LED screen and in the dashboard preview alike), so it follows the room's
 * light like the rest of the phone and this dims the dot and nothing else.
 */
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
 * What the Edge Frame is made of.
 * - NEON: a line of light in the colours [EdgeColor] picks.
 * - ELEMENT: the element the spawn wave takes after ([ElementFrame]): Fire a burning fuse, Water
 *   liquid light in a glass tube, Air a slipstream of wind lines, Earth a crack mended in gold.
 *   It takes the element's colours, so [EdgeColor] has no say. Needs the spawn wave, whose element
 *   it is, and runtime shaders (Android 13+); without either it plays as NEON.
 */
enum class EdgeMaterial(@param:StringRes override val label: Int) : Labeled {
    NEON(R.string.edge_material_neon),
    ELEMENT(R.string.edge_material_element),
}

/**
 * The element the spawn wave takes after; the wave always has one. Water is the glass wave, Fire
 * the ring of fire, Air a gust of wind, Earth a quake breaking the ground open; one with no look
 * of its own yet ([ready] is false) plays the bare wave until it has one. [premium] ones are
 * marked as such but free to try for now: nothing checks a purchase yet.
 */
enum class SpawnElement(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    val ready: Boolean,
    val premium: Boolean,
) : Labeled {
    FIRE(R.string.element_fire, R.string.element_fire_body, ready = true, premium = true),
    WATER(R.string.element_water, R.string.element_water_body, ready = true, premium = false),
    AIR(R.string.element_air, R.string.element_air_body, ready = true, premium = true),
    EARTH(R.string.element_earth, R.string.element_earth_body, ready = true, premium = true),
}

/**
 * Glass wave, and Air's gust: how soft the screen under it goes, as the blur radius at full
 * screen. The black panel has nothing under it to blur, so there it is only what is drawn.
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
 * Fire: how the flames trailing the burning front burn. [height] is the tallest tongue's length
 * at full screen, [warp] how far the tongues sway and curl, [rise] how fast they flicker and
 * throw off wisps.
 */
enum class FireFlames(
    val height: Dp,
    val warp: Float,
    val rise: Float,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    GENTLE(40.dp, 0.3f, 2.2f, R.string.fire_flames_gentle, R.string.fire_flames_gentle_phrase),
    BLAZE(72.dp, 0.55f, 3f, R.string.fire_flames_blaze, R.string.fire_flames_blaze_phrase),
    INFERNO(116.dp, 0.85f, 4f, R.string.fire_flames_inferno, R.string.fire_flames_inferno_phrase),
}

/**
 * Fire: the flames' colours, white-hot at the front in all of them ([firePalette]).
 * - NATURAL: real fire, the same for every app.
 * - APP: the message's brand colour, from white-hot to deep.
 * - BLEND: real fire whose tips take the brand colour, as metal salts colour a flame.
 */
enum class FireColor(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    NATURAL(R.string.fire_color_natural, R.string.fire_color_natural_body, R.string.fire_color_natural_phrase),
    APP(R.string.fire_color_app, R.string.fire_color_app_body, R.string.fire_color_app_phrase),
    BLEND(R.string.fire_color_blend, R.string.fire_color_blend_body, R.string.fire_color_blend_phrase),
}

/** Fire: sparks thrown up behind the front, as the share of spark cells that hold one. */
enum class FireSparks(
    val density: Float,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    OFF(0f, R.string.fire_sparks_off, R.string.fire_sparks_off_phrase),
    FEW(0.12f, R.string.fire_sparks_few, R.string.fire_sparks_few_phrase),
    SHOWER(0.38f, R.string.fire_sparks_shower, R.string.fire_sparks_shower_phrase),
}

/**
 * Fire: what the front leaves behind it.
 * - BURN: the screen lights up dark as char and the fire burns it open, like burning paper.
 *   On the black panel there is no char to see, only the embers smouldering at its edge.
 * - COALS: the ground the fire crossed glows on in cracks, then cools.
 * - CLEAN: only the wall of flame.
 */
enum class FireWake(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    BURN(R.string.fire_wake_burn, R.string.fire_wake_burn_body, R.string.fire_wake_burn_phrase),
    COALS(R.string.fire_wake_coals, R.string.fire_wake_coals_body, R.string.fire_wake_coals_phrase),
    CLEAN(R.string.fire_wake_clean, R.string.fire_wake_clean_body, R.string.fire_wake_clean_phrase),
}

/**
 * Air: how hard the gust blows. Its wind lines are up to [length] long and [width] thick at full
 * screen, about [spacing] apart where the gust's front is, and play out at [pace].
 */
enum class AirGust(
    val length: Dp,
    val width: Dp,
    val spacing: Dp,
    val pace: Float,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    BREEZE(72.dp, 1.8.dp, 80.dp, 0.8f, R.string.air_gust_breeze, R.string.air_gust_breeze_phrase),
    GUST(100.dp, 2.3.dp, 60.dp, 1f, R.string.air_gust_gust, R.string.air_gust_gust_phrase),
    GALE(128.dp, 2.8.dp, 48.dp, 1.3f, R.string.air_gust_gale, R.string.air_gust_gale_phrase),
}

/**
 * Air: how its wind lines run. [pitch] is how far they lean off straight out (the tangent of the
 * angle, so 0 is straight), [curl] how big the curl at their heads is, and [turn] the share of
 * curls that turn the way the wind does (the rest turn back).
 * - STREAKS: straight lines rushing out of the camera, like speed lines.
 * - CURLS: lines sweeping out and curling at their heads, as animation draws wind.
 * - VORTEX: the whole gust spirals out like a whirlwind, every curl turning with it.
 */
enum class AirFlow(
    val pitch: Float,
    val curl: Dp,
    val turn: Float,
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    STREAKS(0f, 0.dp, 0.5f, R.string.air_flow_streaks, R.string.air_flow_streaks_body, R.string.air_flow_streaks_phrase),
    CURLS(0.2f, 11.dp, 0.7f, R.string.air_flow_curls, R.string.air_flow_curls_body, R.string.air_flow_curls_phrase),
    VORTEX(0.75f, 9.dp, 1f, R.string.air_flow_vortex, R.string.air_flow_vortex_body, R.string.air_flow_vortex_phrase),
}

/**
 * Air: the wind's colours ([airPalette]), white at the head of every line in all of them.
 * - CLEAR: clear air, white with a pale sky-blue glow, the same for every app.
 * - APP: the message's brand colour.
 * - BLEND: white wind trailing the brand colour.
 */
enum class AirColor(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    CLEAR(R.string.air_color_clear, R.string.air_color_clear_body, R.string.air_color_clear_phrase),
    APP(R.string.air_color_app, R.string.air_color_app_body, R.string.air_color_app_phrase),
    BLEND(R.string.air_color_blend, R.string.air_color_blend_body, R.string.air_color_blend_phrase),
}

/**
 * Air: what the gust carries, tumbling out with it and drifting down as it dies: [density] is
 * the share of cells that hold one, [size] how big one is at full screen.
 */
enum class AirCarry(
    val density: Float,
    val size: Dp,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    NONE(0f, 0.dp, R.string.air_carry_none, R.string.air_carry_none_phrase),
    DUST(0.4f, 1.1.dp, R.string.air_carry_dust, R.string.air_carry_dust_phrase),
    PETALS(0.22f, 4.6.dp, R.string.air_carry_petals, R.string.air_carry_petals_phrase),
    LEAVES(0.3f, 7.dp, R.string.air_carry_leaves, R.string.air_carry_leaves_phrase),
}

/**
 * Earth: how hard the ground is struck. It breaks into stones about [cell] across at full screen,
 * its cracks about [crack] wide, and the earth jolts up to [shake] as it is struck.
 */
enum class EarthForce(
    val cell: Dp,
    val crack: Dp,
    val shake: Dp,
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    TREMOR(26.dp, 1.3.dp, 1.5.dp, R.string.earth_force_tremor, R.string.earth_force_tremor_phrase),
    QUAKE(36.dp, 1.8.dp, 3.dp, R.string.earth_force_quake, R.string.earth_force_quake_phrase),
    UPHEAVAL(48.dp, 2.4.dp, 5.dp, R.string.earth_force_upheaval, R.string.earth_force_upheaval_phrase),
}

/**
 * Earth: what the ground breaks into behind the front.
 * - FAULTS: only cracks, light pouring out of them; nothing covers the screen.
 * - SLABS: plates of stone heave up between glowing cracks, then crumble.
 * - SPIRES: shards of rock burst up and out, as earth is bent in anime, then sink back.
 */
enum class EarthForm(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    FAULTS(R.string.earth_form_faults, R.string.earth_form_faults_body, R.string.earth_form_faults_phrase),
    SLABS(R.string.earth_form_slabs, R.string.earth_form_slabs_body, R.string.earth_form_slabs_phrase),
    SPIRES(R.string.earth_form_spires, R.string.earth_form_spires_body, R.string.earth_form_spires_phrase),
}

/**
 * Earth: its colours ([earthPalette]).
 * - STONE: grey stone with golden light in its cracks, like pottery mended with gold. The same for every app.
 * - APP: stone tinged with the message's brand colour, its cracks glowing in it.
 * - CRYSTAL: the ground breaks open into crystal in the brand colour, as a geode does.
 */
enum class EarthColor(
    @param:StringRes override val label: Int,
    @param:StringRes val body: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    STONE(R.string.earth_color_stone, R.string.earth_color_stone_body, R.string.earth_color_stone_phrase),
    APP(R.string.earth_color_app, R.string.earth_color_app_body, R.string.earth_color_app_phrase),
    CRYSTAL(R.string.earth_color_crystal, R.string.earth_color_crystal_body, R.string.earth_color_crystal_phrase),
}

/**
 * Earth: what the quake throws up. DUST is smoke: a billowing wall of it kicked up behind the
 * front, thinning into wisps as it settles. RUBBLE is chunks of stone thrown up, tumbling and
 * falling back.
 */
enum class EarthDebris(
    @param:StringRes override val label: Int,
    @param:StringRes val phrase: Int,
) : Labeled {
    NONE(R.string.earth_debris_none, R.string.earth_debris_none_phrase),
    DUST(R.string.earth_debris_dust, R.string.earth_debris_dust_phrase),
    RUBBLE(R.string.earth_debris_rubble, R.string.earth_debris_rubble_phrase),
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
    val style: GlowStyle = GlowStyle.BEACON,
    val dotX: Float = DEFAULT_DOT_X,
    val dotY: Float = DEFAULT_DOT_Y,
    val dotSize: DotSize = DotSize.LED,
    /** The LED lights as a ring around the punch-hole camera instead of a dot; [dotSize] sets its thickness. */
    val ledOnCamera: Boolean = true,
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
    val fireFlames: FireFlames = FireFlames.BLAZE,
    val fireColor: FireColor = FireColor.NATURAL,
    val fireSparks: FireSparks = FireSparks.FEW,
    val fireWake: FireWake = FireWake.BURN,
    val airGust: AirGust = AirGust.GUST,
    val airFlow: AirFlow = AirFlow.CURLS,
    val airColor: AirColor = AirColor.CLEAR,
    val airCarry: AirCarry = AirCarry.PETALS,
    /** How much the gust blurs the screen it passes over, in a band behind its front. */
    val airBlur: GlassBlur = GlassBlur.LIGHT,
    val earthForce: EarthForce = EarthForce.QUAKE,
    val earthForm: EarthForm = EarthForm.SPIRES,
    val earthColor: EarthColor = EarthColor.STONE,
    val earthDebris: EarthDebris = EarthDebris.DUST,
    val edgeWidth: EdgeWidth = EdgeWidth.THIN,
    val edgeGlow: EdgeGlow = EdgeGlow.SOFT,
    val edgeMotion: EdgeMotion = EdgeMotion.COMET,
    val edgeColor: EdgeColor = EdgeColor.APP,
    val edgeMaterial: EdgeMaterial = EdgeMaterial.ELEMENT,
) {
    /**
     * The Edge Frame is made of the element: chosen, and the spawn wave is on to carry it.
     * Below Android 13 it still plays as Neon, as every shader falls back ([elementFrame]).
     */
    val elementalEdge: Boolean get() = edgeMaterial == EdgeMaterial.ELEMENT && spawn

    /** The spawn wave rolls in like the iPhone's: a soft, shimmering crest of light and a trailing ripple. */
    val glass: Boolean get() = element == SpawnElement.WATER

    /** The spawn wave bursts out as a ring of fire: a burning front trailing tongues of flame. */
    val fire: Boolean get() = element == SpawnElement.FIRE

    /** The spawn wave bursts out as a gust: lines of wind sweeping out of the camera, carrying what it picks up. */
    val air: Boolean get() = element == SpawnElement.AIR

    /** The spawn wave strikes the ground: a shock ring runs out of the camera and the ground behind it breaks open in light and stone. */
    val earth: Boolean get() = element == SpawnElement.EARTH

    /** How the waiting LED looks in mock-ups: the dot, or the ring around the camera. */
    val ledForm: GlowForm get() = if (ledOnCamera) GlowForm.RING else GlowForm.DOT

    /** On a screen with no camera (the default ring, or one restored from another phone), the ring is a dot where it sat. */
    fun forScreen(geometry: ScreenGeometry): GlowSettings =
        if (ledOnCamera && geometry.noCamera) copy(ledOnCamera = false) else this

    companion object {
        // Top centre on the status bar's line: where the lens usually is, so a phone without one
        // (the ring falls back to a dot, see forScreen) lights between the clock and the battery.
        const val DEFAULT_DOT_X = 0.5f
        const val DEFAULT_DOT_Y = 0.008f
    }
}

/**
 * This look as the arrival effect sees it: LED-only fields reset, so changing the LED's brightness
 * or the lens fit doesn't restart effect previews. Where the LED sits and its size stay: the
 * beacon plays there and becomes it, and the Edge Frame's last light flies to it ([LedHandOff]).
 * The lens fit reaches the effect through its geometry instead.
 */
fun GlowSettings.forPreview(): GlowSettings =
    copy(ledBrightness = LedBrightness.MAX, lensOffsetDp = 0f, lensOffsetXDp = 0f, lensGrowDp = 0f)
