package com.example.ambientglow

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.enums.enumEntries

/**
 * Plain SharedPreferences storage for [GlowSettings], read once per event and written on user
 * change only. Missing or unknown values fall back to [GlowSettings]' own defaults.
 */
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
    private const val KEY_ELEMENT = "element"

    /** Before elements, the glass wave was a switch: no longer read, only cleared. */
    private const val KEY_GLASS = "glass"
    private const val KEY_GLASS_BLUR = "glass_blur"
    private const val KEY_GLASS_AREA = "glass_area"
    private const val KEY_GLASS_FROST = "glass_frost"
    private const val KEY_FIRE_FLAMES = "fire_flames"
    private const val KEY_FIRE_COLOR = "fire_color"
    private const val KEY_FIRE_SPARKS = "fire_sparks"
    private const val KEY_FIRE_WAKE = "fire_wake"
    private const val KEY_AIR_GUST = "air_gust"
    private const val KEY_AIR_FLOW = "air_flow"
    private const val KEY_AIR_COLOR = "air_color"
    private const val KEY_AIR_CARRY = "air_carry"
    private const val KEY_AIR_BLUR = "air_blur"
    private const val KEY_EARTH_FORCE = "earth_force"
    private const val KEY_EARTH_FORM = "earth_form"
    private const val KEY_EARTH_COLOR = "earth_color"
    private const val KEY_EARTH_DEBRIS = "earth_debris"
    private const val KEY_EDGE_WIDTH = "edge_width"
    private const val KEY_EDGE_GLOW = "edge_glow"
    private const val KEY_EDGE_MOTION = "edge_motion"
    private const val KEY_EDGE_COLOR = "edge_color"
    private const val KEY_EDGE_MATERIAL = "edge_material"

    // Not a setting: whether setup has offered the optional shield step, so it is offered once.
    private const val KEY_SHIELD_OFFERED = "setup_shield_offered"

    private val defaults = GlowSettings()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): GlowSettings = load(prefs(context))

    /** Starts reading the file in the background, so the first message's [load] doesn't wait on disk. */
    fun warm(context: Context) {
        prefs(context)
    }

    fun shieldOffered(context: Context): Boolean = prefs(context).getBoolean(KEY_SHIELD_OFFERED, false)

    fun markShieldOffered(context: Context) = prefs(context).edit { putBoolean(KEY_SHIELD_OFFERED, true) }

    /** Writes every choice at once: a handful of keys, applied asynchronously. */
    fun save(context: Context, settings: GlowSettings) = save(prefs(context), settings)

    internal fun load(prefs: SharedPreferences): GlowSettings = with(prefs) {
        GlowSettings(
            // Camera Ring and Custom Dot, the beacon's old names, fall back to it as the default.
            // It plays where the LED was left: the LED is placed on purpose and waits for hours.
            style = getEnum(KEY_STYLE, defaults.style),
            dotX = getFloat(KEY_DOT_X, defaults.dotX).coerceIn(0f, 1f),
            dotY = getFloat(KEY_DOT_Y, defaults.dotY).coerceIn(0f, 1f),
            dotSize = getEnum(KEY_DOT_SIZE, defaults.dotSize),
            ledOnCamera = getBoolean(KEY_LED_CAMERA, defaults.ledOnCamera),
            lensOffsetDp = getFloat(KEY_LENS_OFFSET, defaults.lensOffsetDp),
            lensOffsetXDp = getFloat(KEY_LENS_OFFSET_X, defaults.lensOffsetXDp),
            lensGrowDp = getFloat(KEY_LENS_GROW, defaults.lensGrowDp),
            ledBrightness = getEnum(KEY_LED_BRIGHTNESS, defaults.ledBrightness),
            arrival = getEnum(KEY_ARRIVAL, defaults.arrival),
            spawn = getBoolean(KEY_SPAWN, defaults.spawn),
            // An element that no longer exists (the plain wave) falls back to Water.
            element = getEnum(KEY_ELEMENT, defaults.element),
            glassBlur = getEnum(KEY_GLASS_BLUR, defaults.glassBlur),
            glassArea = getEnum(KEY_GLASS_AREA, defaults.glassArea),
            glassFrost = getEnum(KEY_GLASS_FROST, defaults.glassFrost),
            fireFlames = getEnum(KEY_FIRE_FLAMES, defaults.fireFlames),
            fireColor = getEnum(KEY_FIRE_COLOR, defaults.fireColor),
            fireSparks = getEnum(KEY_FIRE_SPARKS, defaults.fireSparks),
            fireWake = getEnum(KEY_FIRE_WAKE, defaults.fireWake),
            airGust = getEnum(KEY_AIR_GUST, defaults.airGust),
            airFlow = getEnum(KEY_AIR_FLOW, defaults.airFlow),
            airColor = getEnum(KEY_AIR_COLOR, defaults.airColor),
            airCarry = getEnum(KEY_AIR_CARRY, defaults.airCarry),
            airBlur = getEnum(KEY_AIR_BLUR, defaults.airBlur),
            earthForce = getEnum(KEY_EARTH_FORCE, defaults.earthForce),
            earthForm = getEnum(KEY_EARTH_FORM, defaults.earthForm),
            earthColor = getEnum(KEY_EARTH_COLOR, defaults.earthColor),
            earthDebris = getEnum(KEY_EARTH_DEBRIS, defaults.earthDebris),
            edgeWidth = getEnum(KEY_EDGE_WIDTH, defaults.edgeWidth),
            edgeGlow = getEnum(KEY_EDGE_GLOW, defaults.edgeGlow),
            edgeMotion = getEnum(KEY_EDGE_MOTION, defaults.edgeMotion),
            edgeColor = getEnum(KEY_EDGE_COLOR, defaults.edgeColor),
            edgeMaterial = getEnum(KEY_EDGE_MATERIAL, defaults.edgeMaterial),
        )
    }

    internal fun save(prefs: SharedPreferences, settings: GlowSettings) {
        prefs.edit {
            putString(KEY_STYLE, settings.style.name)
            putFloat(KEY_DOT_X, settings.dotX.coerceIn(0f, 1f))
            putFloat(KEY_DOT_Y, settings.dotY.coerceIn(0f, 1f))
            putString(KEY_DOT_SIZE, settings.dotSize.name)
            putBoolean(KEY_LED_CAMERA, settings.ledOnCamera)
            putFloat(KEY_LENS_OFFSET, settings.lensOffsetDp)
            putFloat(KEY_LENS_OFFSET_X, settings.lensOffsetXDp)
            putFloat(KEY_LENS_GROW, settings.lensGrowDp)
            putString(KEY_LED_BRIGHTNESS, settings.ledBrightness.name)
            putString(KEY_ARRIVAL, settings.arrival.name)
            putBoolean(KEY_SPAWN, settings.spawn)
            putString(KEY_ELEMENT, settings.element.name)
            remove(KEY_GLASS)
            putString(KEY_GLASS_BLUR, settings.glassBlur.name)
            putString(KEY_GLASS_AREA, settings.glassArea.name)
            putString(KEY_GLASS_FROST, settings.glassFrost.name)
            putString(KEY_FIRE_FLAMES, settings.fireFlames.name)
            putString(KEY_FIRE_COLOR, settings.fireColor.name)
            putString(KEY_FIRE_SPARKS, settings.fireSparks.name)
            putString(KEY_FIRE_WAKE, settings.fireWake.name)
            putString(KEY_AIR_GUST, settings.airGust.name)
            putString(KEY_AIR_FLOW, settings.airFlow.name)
            putString(KEY_AIR_COLOR, settings.airColor.name)
            putString(KEY_AIR_CARRY, settings.airCarry.name)
            putString(KEY_AIR_BLUR, settings.airBlur.name)
            putString(KEY_EARTH_FORCE, settings.earthForce.name)
            putString(KEY_EARTH_FORM, settings.earthForm.name)
            putString(KEY_EARTH_COLOR, settings.earthColor.name)
            putString(KEY_EARTH_DEBRIS, settings.earthDebris.name)
            putString(KEY_EDGE_WIDTH, settings.edgeWidth.name)
            putString(KEY_EDGE_GLOW, settings.edgeGlow.name)
            putString(KEY_EDGE_MOTION, settings.edgeMotion.name)
            putString(KEY_EDGE_COLOR, settings.edgeColor.name)
            putString(KEY_EDGE_MATERIAL, settings.edgeMaterial.name)
        }
    }
}

/** The enum constant stored under [key] by name, or [default] if it is missing or no longer exists. */
private inline fun <reified E : Enum<E>> SharedPreferences.getEnum(key: String, default: E): E {
    val name = getString(key, null) ?: return default
    return enumEntries<E>().firstOrNull { it.name == name } ?: default
}
