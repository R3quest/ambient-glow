package com.example.ambientglow

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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

    /** Before elements, the glass wave was a switch; on, it is Water now. */
    private const val KEY_GLASS = "glass"
    private const val KEY_GLASS_BLUR = "glass_blur"
    private const val KEY_GLASS_AREA = "glass_area"
    private const val KEY_GLASS_FROST = "glass_frost"
    private const val KEY_EDGE_WIDTH = "edge_width"
    private const val KEY_EDGE_GLOW = "edge_glow"
    private const val KEY_EDGE_MOTION = "edge_motion"
    private const val KEY_EDGE_COLOR = "edge_color"

    private val defaults = GlowSettings()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): GlowSettings = with(prefs(context)) {
        GlowSettings(
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
            element = getEnum(KEY_ELEMENT, if (getBoolean(KEY_GLASS, false)) SpawnElement.WATER else defaults.element),
            glassBlur = getEnum(KEY_GLASS_BLUR, defaults.glassBlur),
            glassArea = getEnum(KEY_GLASS_AREA, defaults.glassArea),
            glassFrost = getEnum(KEY_GLASS_FROST, defaults.glassFrost),
            edgeWidth = getEnum(KEY_EDGE_WIDTH, defaults.edgeWidth),
            edgeGlow = getEnum(KEY_EDGE_GLOW, defaults.edgeGlow),
            edgeMotion = getEnum(KEY_EDGE_MOTION, defaults.edgeMotion),
            edgeColor = getEnum(KEY_EDGE_COLOR, defaults.edgeColor),
        )
    }

    /** Writes every choice at once: a handful of keys, applied asynchronously. */
    fun save(context: Context, settings: GlowSettings) {
        prefs(context).edit {
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
            putString(KEY_EDGE_WIDTH, settings.edgeWidth.name)
            putString(KEY_EDGE_GLOW, settings.edgeGlow.name)
            putString(KEY_EDGE_MOTION, settings.edgeMotion.name)
            putString(KEY_EDGE_COLOR, settings.edgeColor.name)
        }
    }
}

/** The enum constant stored under [key] by name, or [default] if it is missing or no longer exists. */
private inline fun <reified E : Enum<E>> SharedPreferences.getEnum(key: String, default: E): E {
    val name = getString(key, null) ?: return default
    return enumValues<E>().firstOrNull { it.name == name } ?: default
}
