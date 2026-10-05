package com.example.ambientglow

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

// ---------------------------------------------------------------------------------------------
// The glass wave's frost as a medium rather than a veil (Android 13+). An overlay can't blur
// what is under it, so frost drawn on top only reads as frost through its own structure: a fine
// ground-glass grain, fog that forms and clears in breath-sized patches, sparse drops that dry
// smallest-first, and the crest's light scattering through it. One pass of one shader per frame,
// only while there is frost and only over the ring it can be in (all of it for the whole-screen
// area); below Android 13 the effect draws its gradient frost instead.
// ---------------------------------------------------------------------------------------------

/**
 * `melt` 0..1 is how condensed the frost is: thick patches fog over first and dry last, and the
 * drops left between them shrink away smallest-first. In [GlassArea.REVEAL] `cover` does the
 * same behind the wave, over a band that trails the crest by a fixed time (`trail` is where the
 * wave was then), so the edge it sweeps clears in lobes instead of wiping. `light` is the
 * crest's: brightest at the clearing edge, scattering ahead into the fog, glinting only on the
 * drops under it. `ambient` is the light the screen under the frost gives it: 0 over the black
 * panel, where unlit frost is nothing at all.
 */
private val FROST_AGSL = """
uniform float2 origin;
uniform float radius;
uniform float trail;
uniform float melt;
uniform float light;
uniform float ambient;
uniform float level;
uniform float grain;
uniform float area;
layout(color) uniform half4 mist;
layout(color) uniform half4 glow;

const float EDGE = $HAZE_REVEAL_EDGE;
const float MELT = $FROST_SHADER_MELT;
const float SCATTER = $FROST_SCATTER;
const float LIGHT_BEHIND = $FROST_LIGHT_BEHIND;
const float WARP = $FROST_WARP;
// How much of a drop's squared radius drying takes away, so the smallest drops go first.
const float EVAP = 0.25;

// Hoskins' hash: no period on screen, so the drops don't line up in a repeating pattern.
float hash(float2 p) {
    float3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}

// Breath-sized patches, 0 where the frost is thickest to 1 where it is thinnest.
float patches(float2 xy) {
    return 0.65 * noise(xy / (grain * 40.0) + 3.0) + 0.35 * noise(xy / (grain * 15.0) + 11.0);
}

// Condensation: sparse round drops on a jittered grid, shrinking as the frost thins. A drop is a
// lens: a body a little brighter at its rim, and a glint on the side facing the light L. Returns (body, glint);
// aa is a pixel, in cells.
float2 drops(float2 p, float thick, float aa, float density, float2 L, float seed) {
    float2 i = floor(p);
    float2 f = fract(p);
    float body = 0.0;
    float glint = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float2 cell = i + float2(float(x), float(y)) + seed;
            if (hash(cell + float2(3.1, 9.7)) > density) continue;
            float2 c = float2(float(x), float(y)) + 0.15 + 0.7 * float2(hash(cell), hash(cell + float2(31.7, 57.1))) - f;
            float r0 = 0.14 + 0.36 * hash(cell + float2(7.3, 2.9));
            float r2 = r0 * r0 - EVAP * (1.0 - thick);
            if (r2 <= 0.0) continue;
            float r = sqrt(r2);
            float cov = 1.0 - smoothstep(r - aa, r + aa, length(c));
            if (cov <= 0.0) continue;
            float2 v = -c / r;
            float rim = smoothstep(0.55, 0.95, length(v));
            float2 g = v - 0.45 * L;
            // Drops under ~3 px are too small to show a highlight.
            float spec = exp(-dot(g, g) / 0.05) * smoothstep(1.5 * aa, 4.0 * aa, r);
            body = max(body, cov * (0.7 + 0.3 * rim));
            glint = max(glint, cov * spec);
        }
    }
    return float2(body, glint);
}

half4 main(float2 xy) {
    float d = distance(xy, origin);
    float u = d / radius;
    float cover = 1.0;
    float thin = 0.0;
    if (area < 0.5) {
        // Never thinner than 4% of the radius, or the clearing edge reads as a hard circle.
        float outer = EDGE * radius;
        float inner = min(max(EDGE * trail, (EDGE - MELT) * radius), outer - 0.04 * radius);
        float band = outer - inner;
        if (d < inner - WARP * band) return half4(0.0);
        thin = patches(xy);
        // Thick patches linger up to WARP bands behind the thin ones.
        cover = smoothstep(inner, outer, d + (1.0 - thin) * WARP * band);
    } else {
        if (area < 1.5) {
            cover = smoothstep(0.72, 0.9, u) * (1.0 - smoothstep(0.975, 1.0, u));
            if (cover <= 0.0) return half4(0.0);
        }
        thin = patches(xy);
    }
    float thick = cover * melt;
    if (thick <= 0.0) return half4(0.0);
    // The crest's light where the frost clears: a fixed width in px, however far the wave has
    // spread, so it stays a glint on the edge rather than a band.
    float x = d - EDGE * radius;
    float lit = light * smoothstep(-LIGHT_BEHIND * grain, 0.0, x) * exp(-max(x, 0.0) / (SCATTER * grain));
    if (ambient + lit <= 0.004) return half4(0.0);
    // Ground glass: a per-pixel grain, steady from frame to frame.
    float fine = hash(floor(xy));
    half3 c = mix(mist.rgb, glow.rgb, half(lit / (ambient + lit + 1e-3)));
    // Each patch fogs over once the frost is thicker than its own threshold: thick ones first.
    float t = 0.35 + 0.5 * thin;
    float film = smoothstep(t - 0.1, t + 0.1, thick);
    float filmA = clamp(level * (1.25 - 0.5 * thin) * (0.92 + 0.16 * fine) * (ambient + lit), 0.0, 1.0);
    half4 filmP = half4(c * half(filmA), half(filmA));
    if (film >= 1.0) return filmP;
    // The light comes from the crest, out from the origin.
    float2 L = (xy - origin) / max(d, 1.0);
    float bead = grain * 3.0;
    float big = bead * 2.3;
    float2 s = drops(xy / bead, thick, 0.75 / bead, 0.7, L, 0.0);
    float2 b = drops(xy / big, thick, 0.75 / big, 0.35, L, 71.0);
    // Lit drop bodies take only 0.4 of the light, so they never read as bright discs.
    float dropA = clamp(level * max(s.x, b.x) * (ambient + 0.4 * lit), 0.0, 1.0);
    float ga = min(0.45 * max(s.y, b.y) * lit, 1.0);
    half4 drop = half4(c * half(dropA), half(dropA));
    drop = half4(mix(glow.rgb, half3(1.0), 0.5) * half(ga), half(ga)) + drop * half(1.0 - ga);
    return mix(drop, filmP, half(film));
}
"""

/**
 * Frost (shader): the widest the band behind the wave gets while it clears, as a fraction of the
 * wave's radius; once the wave slows, the band is the last [FROST_SHADER_MELT_MS] of its travel.
 */
internal const val FROST_SHADER_MELT = 0.12f

/** Frost (shader): how far behind the band, in bands, its thickest patches still linger. */
internal const val FROST_WARP = 0.9f

/**
 * Frost (shader): the band behind the wave is where it was this long ago, so it clears in the
 * same time wherever the wave is, not a fixed share of the radius.
 */
internal const val FROST_SHADER_MELT_MS = 100f

/**
 * Frost (shader): how far ahead of the clearing edge the crest's light scatters into it, and how
 * far behind it still catches the melting drops, in grains ([FROST_GRAIN_DP], ~22 dp and ~13 dp).
 */
internal const val FROST_SCATTER = 14f
internal const val FROST_LIGHT_BEHIND = 8f

/** Frost's scale: drops sit 3x this apart, breath patches are 15x and 40x. */
private const val FROST_GRAIN_DP = 1.6f

/** Frost's grain never finer than this many px, so the shrunken preview doesn't turn it to pixel speckle. */
private const val FROST_PREVIEW_MIN_PX = 2.6f

/**
 * The frost material for this effect, or null below Android 13 (no runtime shaders). [ambient]
 * is 1 over a lit screen and 0 over the black panel, where frost shows only in the crest's light.
 */
internal fun frostShader(
    area: GlassArea,
    origin: Offset,
    level: Float,
    mist: Color,
    glow: Color,
    ambient: Float,
    density: Float,
    scale: Float,
): FrostShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val px = (density * scale).coerceAtLeast(FROST_PREVIEW_MIN_PX / FROST_GRAIN_DP)
    return FrostRuntime(area, origin, level, mist, glow, ambient, px)
}

/** A frost the effect moves every frame through [update]; built once per size, never in the draw. */
internal abstract class FrostShader : ShaderBrush() {
    /** The shader itself, for a platform paint that draws it as a ring band. */
    abstract val runtime: android.graphics.Shader

    /** How far ahead of the clearing edge the crest's light reaches into the frost, in px. */
    abstract val scatterPx: Float

    /**
     * [radius] the wave's in px, [trail] its radius [FROST_SHADER_MELT_MS] ago, [melt] how
     * condensed 0..1, [light] the crest's light in it.
     */
    abstract fun update(radius: Float, trail: Float, melt: Float, light: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class FrostRuntime(
    area: GlassArea,
    origin: Offset,
    level: Float,
    mist: Color,
    glow: Color,
    ambient: Float,
    px: Float,
) : FrostShader() {
    override val runtime = RuntimeShader(FROST_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("level", level)
        setFloatUniform("ambient", ambient)
        setFloatUniform("grain", FROST_GRAIN_DP * px)
        setFloatUniform("area", area.ordinalForShader())
        setColorUniform("mist", mist.toArgb())
        setColorUniform("glow", glow.toArgb())
    }

    override val scatterPx = FROST_SCATTER * FROST_GRAIN_DP * px

    override fun createShader(size: Size): Shader = runtime

    override fun update(radius: Float, trail: Float, melt: Float, light: Float) {
        runtime.setFloatUniform("radius", radius.coerceAtLeast(1f))
        runtime.setFloatUniform("trail", trail.coerceAtLeast(1f))
        runtime.setFloatUniform("melt", melt)
        runtime.setFloatUniform("light", light)
    }
}

private fun GlassArea.ordinalForShader(): Float = when (this) {
    GlassArea.REVEAL -> 0f
    GlassArea.WAVE -> 1f
    GlassArea.SCREEN -> 2f
}
