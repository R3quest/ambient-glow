package app.lumement

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The glass wave's crest as a line of light (Android 13+): a white-hot core about 3 dp wide inside
// a coloured halo, the same width in pixels wherever the wave is, where a gradient scaled with
// the wave thickens into a fog band. One pass of one shader per frame over a band a fixed number
// of dp wide around the crest; below Android 13 the effect draws its gradient crest instead.
// ---------------------------------------------------------------------------------------------

/**
 * `x` is a pixel's distance past the crest's `radius` (in px, positive ahead). The core is a
 * Gaussian, stretched back by `smear` (how far the crest moved since the last frame) so a fast
 * crest reads as motion rather than a strobed line; the halo falls off quickly ahead and slowly
 * behind; a faint `tint` wake trails it, gone before the band's inner edge. The `echo` is a
 * fainter halo following at its own radius. `spec` is the light round the ring: a slight
 * left-right sheen and one glint at angle `glint` (radians, clockwise from 3 o'clock).
 * Distances in `dp` px; `wake` is the tint's falloff in px.
 */
private val CREST_AGSL = """
uniform float2 origin;
uniform float radius;
uniform float echo;
uniform float energy;
uniform float echoEnergy;
uniform float smear;
uniform float glint;
uniform float dp;
uniform float wake;
layout(color) uniform half4 core;
layout(color) uniform half4 glow;
layout(color) uniform half4 tint;

// Falls off over f ahead of the line (x > 0) and over b behind it.
float ring(float x, float f, float b) {
    return x > 0.0 ? exp(-x / f) : exp(x / b);
}

half4 main(float2 xy) {
    float2 d = xy - origin;
    float r = length(d);
    float th = atan(d.y, d.x);
    float gd = mod(th - glint + 3.14159, 6.28318) - 3.14159;
    float spec = min(1.3, 0.85 + 0.15 * cos(2.0 * th) + 0.35 * exp(-gd * gd / 0.048));
    float x = r - radius;
    float xc = x > 0.0 ? x : min(0.0, x + smear);
    // Sigma 1.5 dp: the white-hot line.
    float c = exp(-0.5 * xc * xc / (2.25 * dp * dp));
    float g = ring(x, 3.0 * dp, 22.0 * dp);
    float w = x < 0.0 ? exp(x / wake) * (1.0 - exp(x / (4.0 * dp))) * smoothstep(-136.0 * dp, -96.0 * dp, x) : 0.0;
    float ge = echo > 0.0 ? ring(r - echo, 6.0 * dp, 14.0 * dp) : 0.0;
    float kc = 0.85 * c * spec * energy;
    float kg = (0.38 * g * energy + 0.16 * ge * echoEnergy) * spec;
    float kw = 0.10 * w * energy;
    float a = min(1.0, kc + kg + kw);
    half3 rgb = core.rgb * half(kc) + glow.rgb * half(kg) + tint.rgb * half(kw);
    return half4(min(rgb, half3(a)), half(a));
}
"""

/** Crest (shader): how far ahead of the line its halo still shows, and how far behind its wake does. */
internal val CREST_AHEAD = 12.dp
internal val CREST_BEHIND = 138.dp

/** Crest (shader): the wake's falloff behind the line grows with the wave up to this. */
private const val CREST_WAKE_MAX_DP = 120f

/** The crest material for the wave in [color], or null below Android 13 (no runtime shaders). */
internal fun crestShader(origin: Offset, color: Color, density: Float, scale: Float): CrestShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return CrestRuntime(origin, color, density * scale)
}

/** A crest the effect moves every frame through [update]; built once per size, never in the draw. */
internal abstract class CrestShader : ShaderBrush() {
    /**
     * [radius] the crest line's in px and [echo] the echo's, [energy] and [echoEnergy] their
     * light 0..1, [smear] how far the crest moved since the last frame in px, [glint] the glint's
     * angle in radians.
     */
    abstract fun update(radius: Float, echo: Float, energy: Float, echoEnergy: Float, smear: Float, glint: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class CrestRuntime(origin: Offset, color: Color, private val dp: Float) : CrestShader() {
    private val runtime = RuntimeShader(CREST_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("dp", dp)
        setColorUniform("core", lerp(color, Color.White, 0.85f).toArgb())
        setColorUniform("glow", lerp(color, Color.White, 0.2f).toArgb())
        setColorUniform("tint", color.toArgb())
    }

    override fun createShader(size: Size): Shader = runtime

    override fun update(radius: Float, echo: Float, energy: Float, echoEnergy: Float, smear: Float, glint: Float) {
        runtime.setFloatUniform("radius", radius)
        runtime.setFloatUniform("echo", echo)
        runtime.setFloatUniform("energy", energy)
        runtime.setFloatUniform("echoEnergy", echoEnergy)
        runtime.setFloatUniform("smear", smear)
        runtime.setFloatUniform("glint", glint)
        runtime.setFloatUniform("wake", (0.1f * radius + 16f * dp).coerceAtMost(CREST_WAKE_MAX_DP * dp))
    }
}
