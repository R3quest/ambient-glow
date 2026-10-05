package com.example.ambientglow

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.PI

// ---------------------------------------------------------------------------------------------
// The Edge Frame's comet heads in perimeter space (Android 13+). A sweep gradient places them by
// angle from the centre, which stretches a head and its tail several times over near the corners
// of a tall screen; this measures each pixel's distance along the rounded frame instead, so a
// head keeps its length all the way round. Drawn only over the frame's band, as the mask the
// sweep was: 1 - brightness in alpha, laid on with DstOut.
// ---------------------------------------------------------------------------------------------

/**
 * `s` is a pixel's distance along the frame, clockwise from the top centre (`axis` moves the
 * start to the camera), on a rounded rectangle of `size` with corners of radius `corner`: the
 * distance is worked out in one quarter (`u`, from the nearest top or bottom centre) and unfolded.
 * `mirror` folds the right half onto the left, so one head is two, rising up both sides. A pixel
 * `d` behind the `head` (along the frame) is lit `base` plus a tail falling off over `tail`; ahead
 * of it, a short Gaussian `lead`. All in px.
 */
private val EDGE_LIGHT_AGSL = """
uniform float2 size;
uniform float corner;
uniform float head;
uniform float lead;
uniform float tail;
uniform float base;
uniform float mirror;
uniform float axis;

half4 main(float2 p) {
    float2 q = p - size * 0.5;
    float2 a = abs(q);
    float2 hx = size * 0.5 - corner;
    float quarter = hx.x + 1.5707963 * corner + hx.y;
    float per = 4.0 * quarter;
    float u;
    if (a.x <= hx.x && a.y - hx.y >= a.x - hx.x) {
        u = a.x;
    } else if (a.y <= hx.y) {
        u = quarter - a.y;
    } else {
        u = hx.x + corner * atan(a.x - hx.x, a.y - hx.y);
    }
    float s;
    if (q.x >= 0.0) {
        s = q.y < 0.0 ? u : 2.0 * quarter - u;
    } else {
        s = q.y >= 0.0 ? 2.0 * quarter + u : 4.0 * quarter - u;
    }
    s = mod(s - axis, per);
    if (mirror > 0.5 && s < 0.5 * per) s = per - s;
    float d = mod(head - s + 0.5 * per, per) - 0.5 * per;
    float k = d / lead;
    float bright = d < 0.0 ? exp(-k * k) : base + (1.0 - base) * exp(-d / tail);
    return half4(0.0, 0.0, 0.0, half(1.0 - bright));
}
"""

/** The length of the frame round a [size] canvas with corners of radius [corner], as the shader measures it. */
internal fun edgePerimeter(size: Size, corner: Float): Float =
    2f * (size.width + size.height) - (8f - 2f * PI.toFloat()) * corner

/**
 * The comet heads' mask for a frame of [size] with screen corners of [corner] px, or null below
 * Android 13 (no runtime shaders). [axis] is where the heads come home, in px along the top edge
 * from its centre (the camera); [mirror] makes the one head two, mirrored about the camera.
 */
internal fun edgeLight(
    size: Size,
    corner: Float,
    lead: Float,
    tail: Float,
    base: Float,
    mirror: Boolean,
    axis: Float,
): EdgeLight? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) EdgeLightRuntime(size, corner, lead, tail, base, mirror, axis) else null

/** Comet heads the effect moves every frame through [moveTo]; built once per size, never in the draw. */
internal abstract class EdgeLight : ShaderBrush() {
    /** Puts the head [p] of the way round the frame, clockwise from the camera. */
    abstract fun moveTo(p: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class EdgeLightRuntime(
    size: Size,
    corner: Float,
    lead: Float,
    tail: Float,
    base: Float,
    mirror: Boolean,
    axis: Float,
) : EdgeLight() {
    /** The frame's length in px; a head at `p` of the way round is at `p * perimeter`. */
    private val perimeter = edgePerimeter(size, corner)

    private val runtime = RuntimeShader(EDGE_LIGHT_AGSL).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("corner", corner)
        setFloatUniform("lead", lead)
        setFloatUniform("tail", tail)
        setFloatUniform("base", base)
        setFloatUniform("mirror", if (mirror) 1f else 0f)
        setFloatUniform("axis", axis)
        setFloatUniform("head", 0f)
    }

    override fun createShader(size: Size): Shader = runtime

    override fun moveTo(p: Float) {
        runtime.setFloatUniform("head", p * perimeter)
    }
}
