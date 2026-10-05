package com.example.ambientglow

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// OKLab / OKLCh, for colours picked and blended at an even lightness (the Edge Frame's Duo and
// Spectrum). Built once per effect, never per frame.
// ---------------------------------------------------------------------------------------------

/** sRGB [color] as OKLCh: lightness, chroma, hue in degrees. */
internal fun toOklch(color: Color): FloatArray {
    val lab = toOklab(color)
    val c = hypot(lab[1], lab[2])
    val h = atan2(lab[2], lab[1]) * (180f / PI.toFloat())
    return floatArrayOf(lab[0], c, h)
}

private fun toOklab(color: Color): FloatArray {
    fun linear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    val r = linear(color.red)
    val g = linear(color.green)
    val b = linear(color.blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

/** OKLCh to sRGB, its chroma pulled in until it fits the gamut (hue and lightness kept). */
internal fun oklch(l: Float, c: Float, hDegrees: Float): Color {
    val h = hDegrees * (PI.toFloat() / 180f)
    var chroma = c
    repeat(40) {
        val rgb = oklabToLinear(l, chroma * cos(h), chroma * sin(h))
        if (rgb.all { it in -0.0005f..1.0005f }) return linearToColor(rgb)
        chroma *= 0.95f
    }
    return linearToColor(oklabToLinear(l, 0f, 0f))
}

/** The colour halfway between [a] and [b] in OKLab. */
internal fun oklabMix(a: Color, b: Color): Color {
    val x = toOklab(a)
    val y = toOklab(b)
    return linearToColor(oklabToLinear((x[0] + y[0]) / 2f, (x[1] + y[1]) / 2f, (x[2] + y[2]) / 2f))
}

private fun oklabToLinear(l: Float, a: Float, b: Float): FloatArray {
    val l3 = (l + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
    val m3 = (l - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
    val s3 = (l - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
    return floatArrayOf(
        4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
        -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
        -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
    )
}

private fun linearToColor(rgb: FloatArray): Color {
    fun encode(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }
    return Color(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]))
}
