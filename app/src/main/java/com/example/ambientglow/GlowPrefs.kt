package com.example.ambientglow

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.core.content.edit

/** How long a single glow stays on screen before the wake layer finishes itself. */
const val GLOW_DURATION_MS = 3_500L

/** Brand accent used when an app icon yields no usable colour, and for the Test Preview. */
const val DEFAULT_GLOW_COLOR = 0xFF00E5FF.toInt()

enum class GlowStyle(@param:StringRes val title: Int, @param:StringRes val caption: Int) {
    EDGE_FRAME(R.string.style_edge_title, R.string.style_edge_caption),
    CAMERA_RING(R.string.style_ring_title, R.string.style_ring_caption),
    CUSTOM_DOT(R.string.style_dot_title, R.string.style_dot_caption);

    companion object {
        fun fromName(name: String?): GlowStyle = entries.firstOrNull { it.name == name } ?: EDGE_FRAME
    }
}

/**
 * The user's saved choices. Dot coordinates are stored as 0..1 fractions of the screen so a
 * position chosen on the dashboard preview maps exactly onto any physical resolution.
 */
@Immutable
data class GlowSettings(
    val style: GlowStyle = GlowStyle.EDGE_FRAME,
    val dotX: Float = DEFAULT_DOT_X,
    val dotY: Float = DEFAULT_DOT_Y,
) {
    companion object {
        const val DEFAULT_DOT_X = 0.5f
        const val DEFAULT_DOT_Y = 0.08f
    }
}

/** Plain SharedPreferences storage, read once per notification and written on user change only. */
object GlowPrefs {
    private const val FILE = "ambient_glow"
    private const val KEY_STYLE = "style"
    private const val KEY_DOT_X = "dot_x"
    private const val KEY_DOT_Y = "dot_y"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): GlowSettings {
        val prefs = prefs(context)
        return GlowSettings(
            style = GlowStyle.fromName(prefs.getString(KEY_STYLE, null)),
            dotX = prefs.getFloat(KEY_DOT_X, GlowSettings.DEFAULT_DOT_X).coerceIn(0f, 1f),
            dotY = prefs.getFloat(KEY_DOT_Y, GlowSettings.DEFAULT_DOT_Y).coerceIn(0f, 1f),
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
}

/** Everything the wake layer needs to draw one glow, carried as Intent extras. */
@Immutable
data class GlowRequest(
    val color: Int,
    val style: GlowStyle,
    val dotX: Float,
    val dotY: Float,
) {
    fun writeTo(intent: Intent): Intent = intent
        .putExtra(EXTRA_COLOR, color)
        .putExtra(EXTRA_STYLE, style.name)
        .putExtra(EXTRA_DOT_X, dotX)
        .putExtra(EXTRA_DOT_Y, dotY)

    companion object {
        const val EXTRA_COLOR = "com.example.ambientglow.extra.COLOR"
        const val EXTRA_STYLE = "com.example.ambientglow.extra.STYLE"
        const val EXTRA_DOT_X = "com.example.ambientglow.extra.DOT_X"
        const val EXTRA_DOT_Y = "com.example.ambientglow.extra.DOT_Y"

        val Default = GlowRequest(
            color = DEFAULT_GLOW_COLOR,
            style = GlowStyle.EDGE_FRAME,
            dotX = GlowSettings.DEFAULT_DOT_X,
            dotY = GlowSettings.DEFAULT_DOT_Y,
        )

        fun of(settings: GlowSettings, color: Int) = GlowRequest(
            color = color,
            style = settings.style,
            dotX = settings.dotX,
            dotY = settings.dotY,
        )

        fun fromIntent(intent: Intent?): GlowRequest {
            if (intent == null) return Default
            return GlowRequest(
                color = intent.getIntExtra(EXTRA_COLOR, DEFAULT_GLOW_COLOR),
                style = GlowStyle.fromName(intent.getStringExtra(EXTRA_STYLE)),
                dotX = intent.getFloatExtra(EXTRA_DOT_X, GlowSettings.DEFAULT_DOT_X).coerceIn(0f, 1f),
                dotY = intent.getFloatExtra(EXTRA_DOT_Y, GlowSettings.DEFAULT_DOT_Y).coerceIn(0f, 1f),
            )
        }
    }
}
