package com.example.ambientglow

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

// ---------------------------------------------------------------------------------------------
// The fire wave as a burning front (Android 13+): a ragged white-hot line with tongues of flame
// trailing it back towards the camera, and what it leaves ([FireWake]): char it burns open,
// coals cooling in cracks, or nothing. Sparks rise behind it. One pass of one shader per frame,
// over the ring the fire is in (and the char still ahead); below Android 13 the effect draws
// gradients instead.
// ---------------------------------------------------------------------------------------------

/**
 * Tongues live in polar space: `u` round the ring in columns, one tongue each, `h` back from the
 * front in flame lengths. Tied to angles, a tongue would widen with the ring; tied to the screen,
 * the front would race through them. So the ring holds `tongues` columns, a power of two, `column`
 * px wide at the front, and the next power's tongues rise (`finer`) as this one's sink: a tongue
 * keeps its place and stays a hand's width, wherever the front is. The seam where `u` wraps
 * points straight up, above the camera.
 *
 * The flames are drawn the way animation draws fire, lit the way fire is: each tongue a crisp
 * teardrop swelling above its root and tapering to a point that sways, tall dark ones behind
 * short bright ones, wisps breaking off their tips and rising away. Each is cel-shaded in nested
 * bands, deep rim, body, yellow, white core, every band a soft gradient of its own light, and
 * the whole sits in a glow, so the shapes are graphic but the light is real.
 *
 * Layers, bottom up: the char (`veil`) ahead of the front, with embers (`scorch`) smouldering in
 * it at the edge; coals behind; the flames in their glow; the burning line and its halo; sparks, rising on the
 * screen as sparks do, each a small four-point sparkle. All are premultiplied and never brighter than their cover.
 */
private val FIRE_AGSL = """
uniform float2 origin;
uniform float radius;
uniform float energy;
uniform float veil;
uniform float scorch;
uniform float coals;
uniform float sparks;
uniform float time;
uniform float dp;
uniform float height;
uniform float lobe;
uniform float warp;
uniform float rise;
uniform float tongues;
uniform float finer;
uniform float column;
uniform float sparkCell;
uniform float sparkSize;
layout(color) uniform half4 core;
layout(color) uniform half4 hot;
layout(color) uniform half4 body;
layout(color) uniform half4 flare;
layout(color) uniform half4 tip;
layout(color) uniform half4 ember;

const float TAU = 6.2831853;
// Char: not black, a burnt brown too dark to tell from it, which the embers glow out of.
const float3 CHAR = float3(0.035, 0.016, 0.008);
// How far behind the front coals stay warm, in flame lengths, plus this many dp.
const float COAL_REACH = $FIRE_COAL_REACH;
const float COAL_REACH_DP = $FIRE_COAL_REACH_DP;
const float SPARK_REACH = $FIRE_SPARK_REACH;
const float SPARK_REACH_DP = $FIRE_SPARK_REACH_DP;
const float AHEAD_DP = $FIRE_AHEAD_DP;
// How far behind the front flames and their wisps reach, in flame lengths.
const float FLAME_REACH = $FIRE_FLAME_REACH;

// Hoskins' hash: no period on screen.
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

// How tall the tongue in column i stands, in flame lengths: a few tall ones among shorter ones,
// each breathing at its own pace.
float tongueHeight(float i, float tall, float seed) {
    float r = hash(float2(i, seed));
    return tall * (0.42 + 0.58 * r * r) * (0.84 + 0.16 * sin(time * rise * (1.6 + 1.2 * r) + r * 40.0));
}

// One tongue, in column i of a set: its depth at s across the columns and h back from the front
// (in flame lengths), 1 on its centre line at the root, 0 on its outline, below 0 outside. A fat
// teardrop swelling above the root and tapering to a point that sways into an S, the tip
// hooking over as it licks.
float tongue(float i, float s, float h, float tall, float seed) {
    float H = tongueHeight(i, tall, seed);
    float q = h / max(H, 0.001);
    if (q >= 1.0) return -1.0;
    float r = hash(float2(i, seed + 0.5));
    float sway = sin(2.2 * q + time * rise * 0.9 + r * 12.0);
    float bend = warp * (0.42 * q * sqrt(q) * sway + 0.3 * q * q * q * q * (r - 0.5));
    float w = 0.62 * pow(1.0 - q, 0.75) * (1.0 + 0.4 * sin(3.14159 * min(1.0, 1.7 * q)));
    return (1.0 - abs(s - bend) / w) * (1.0 - 0.72 * q);
}

// A set of tongues round the ring at `tall` of their full length (0 sinks them all): a back row,
// and a front row of shorter, hotter ones between them; and a wisp off each back tongue's tip.
// `colH` is a flame length in columns.
float flames(float u, float h, float colH, float tall) {
    if (tall < 0.01) return -1.0;
    // The whole fire licks sideways, more the farther from the root.
    float ub = u + warp * 0.45 * h * (noise(float2(u * 0.45, h * 1.6 - time * rise * 0.6)) - 0.5);
    float uf = ub + 0.5;
    float ib = floor(ub);
    float sb = fract(ub) - 0.5;
    float jf = floor(uf);
    float sf = fract(uf) - 0.5;
    float d = -1.0;
    for (int k = -1; k <= 1; k++) {
        float o = float(k);
        d = max(d, 0.8 * tongue(ib + o, sb - o, h, tall, 1.0));
        d = max(d, tongue(jf + o, sf - o, h, 0.58 * tall, 2.0));
    }
    // The wisp: it breaks off the back tongue's tip yellow-hot and rises away, shrinking and
    // cooling through the bands, then the next one breaks off.
    float r = hash(float2(ib, 5.0));
    float life = fract(time * rise * 0.3 * (0.7 + 0.6 * r) + r);
    float size = 0.14 * (1.0 - life) * smoothstep(0.0, 0.1, life) * (0.7 + 0.6 * hash(float2(ib, 9.0)));
    if (size > 0.0) {
        float from = 0.8 * tongueHeight(ib, tall, 1.0);
        float2 p = float2(sb - 0.25 * warp * sin(r * 30.0 + life * 4.0) * life, (h - from - 0.7 * life) * colH);
        // A drop pointed away from the fire.
        p.y *= p.y > 0.0 ? 0.4 : 0.85;
        d = max(d, (1.0 - length(p) / size) * (0.95 - 0.75 * life) * min(1.0, 1.5 * tall));
    }
    return d;
}

// Depth to colour in four flat bands with anti-aliased edges (aa, in depth): the deep rim, the
// body, yellow, the white core. Each band is a soft gradient towards the next, as light is.
float3 cel(float d, float aa) {
    float3 c = mix(float3(tip.rgb), float3(flare.rgb), 0.5 * smoothstep(0.0, 0.2, d));
    c = mix(c, mix(float3(flare.rgb), float3(body.rgb), 0.55 + 0.45 * smoothstep(0.2, 0.44, d)), smoothstep(0.2 - aa, 0.2 + aa, d));
    c = mix(c, mix(float3(body.rgb), float3(hot.rgb), 0.65 + 0.35 * smoothstep(0.44, 0.68, d)), smoothstep(0.44 - aa, 0.44 + aa, d));
    return mix(c, mix(float3(hot.rgb), float3(core.rgb), 0.75 + 0.25 * smoothstep(0.68, 0.9, d)), smoothstep(0.68 - aa, 0.68 + aa, d));
}

// Plain noise round the ring at both densities, for what only needs one octave.
float ring1(float u, float s, float y) {
    return mix(noise(float2(u * s, y)), noise(float2(2.0 * u * s, y)), finer);
}

float4 over(float4 top, float4 under) {
    return top + under * (1.0 - top.a);
}

half4 main(float2 xy) {
    float2 d = xy - origin;
    float x = length(d) - radius;
    // Well ahead of the front there is only the char, if any: no noise to work out.
    if (x > lobe + AHEAD_DP * dp) return half4(half3(CHAR * veil), half(veil));
    float u = atan(d.x, d.y) / TAU * tongues;
    // The front, ragged like a burning edge: slow lobes along it.
    float xe = x + lobe * (2.0 * ring1(u, 0.3, time * 0.8) - 1.0);
    float back = -xe;
    float4 col = float4(0.0);

    if (veil > 0.0 || scorch > 0.0) {
        float va = veil * smoothstep(-1.5 * dp, 1.5 * dp, xe);
        col = float4(CHAR * va, va);
        float s = 1.0 - xe / (14.0 * dp);
        if (scorch > 0.0 && xe > 0.0 && s > 0.0) {
            float speck = smoothstep(0.4, 0.9, noise(xy / (5.0 * dp)) * 0.7 + noise(xy / (2.2 * dp) + 3.7) * 0.3);
            float ea = scorch * s * s * (0.3 + 0.7 * speck);
            col = over(float4(mix(float3(ember.rgb), float3(body.rgb), speck * s) * ea, ea), col);
        }
    }

    if (coals > 0.0 && back > 0.0) {
        float cool = exp(-back / (COAL_REACH * height + COAL_REACH_DP * dp));
        if (cool > 0.03) {
            // Glowing cracks: the ridges of a two-octave noise, fixed on the screen like the ground.
            float cn = 0.65 * noise(xy / (15.0 * dp)) + 0.35 * noise(xy / (6.5 * dp) + 7.3);
            float ridge = 1.0 - abs(2.0 * cn - 1.0);
            float r3 = ridge * ridge * ridge;
            float crack = r3 * r3;
            float pool = noise(xy / (44.0 * dp) + 4.2);
            float glow = (0.1 + 0.9 * crack) * (0.45 + 0.55 * pool) * (0.85 + 0.15 * sin(time * 6.0 + pool * 18.0));
            float ca = 0.8 * coals * cool * glow * smoothstep(0.0, 10.0 * dp, back);
            // Fresh cracks still glow yellow; they redden as they cool.
            float3 cc = mix(float3(ember.rgb), float3(body.rgb), crack);
            cc = mix(cc, float3(hot.rgb), crack * cool * cool);
            col = over(float4(cc * ca, ca), col);
        }
    }

    if (energy > 0.0 && back > -6.0 * dp && back < FLAME_REACH * height) {
        float h = max(back, 0.0) / height;
        // The light the fire gives off, under its shapes.
        float ga = energy * 0.3 * exp(-2.4 * h) * smoothstep(-3.0 * dp, 0.0, back);
        col = over(float4(float3(flare.rgb) * ga, ga), col);
        float colH = height / column;
        // Low while the ring is small, rising to full height as it spreads, so the flames never
        // reach back past the camera.
        float grow = mix(0.3, 1.0, smoothstep(0.0, 2.0 * height, radius));
        float d = max(flames(u, h, colH, grow * sqrt(1.0 - finer)), flames(2.0 * u, h, 2.0 * colH, grow * sqrt(finer)));
        // The roots: one band along the front, white-hot at the line and cooling through the bands.
        d = max(d, 0.85 * (1.0 - h / 0.22));
        d -= max(xe, 0.0) / (3.0 * dp);
        // About a pixel of depth, for edges that are crisp but never jagged.
        float aa = (1.0 + finer) / (0.45 * column);
        // Bloom: light just outside every tongue and wisp, following its outline, so the flat
        // shapes glow as fire does.
        float bloom = smoothstep(-0.5, 0.0, d);
        float ba = energy * 0.55 * bloom * bloom;
        if (ba > 0.0) col = over(float4(mix(float3(flare.rgb), float3(body.rgb), bloom) * ba, ba), col);
        float fa = energy * smoothstep(-aa, aa, d);
        if (fa > 0.0) col = over(float4(cel(d, aa) * fa, fa), col);
    }

    if (energy > 0.0) {
        float flick = 0.72 + 0.28 * ring1(u, 1.7, time * 9.0);
        float c = exp(-0.5 * xe * xe / (2.0 * dp * dp)) * flick;
        float g = (xe > 0.0 ? exp(-xe / (4.0 * dp)) : exp(xe / (9.0 * dp))) * 0.45 * flick;
        float la = energy * min(1.0, c + g);
        float3 lc = (float3(core.rgb) * c + float3(hot.rgb) * g) / max(c + g, 0.001);
        col = over(float4(lc * la, la), col);
    }

    if (sparks > 0.0 && back > 0.0) {
        float reach = SPARK_REACH * height + SPARK_REACH_DP * dp;
        float vis = smoothstep(0.0, 6.0 * dp, back) * (1.0 - smoothstep(0.3 * reach, reach, back));
        if (vis > 0.0) {
            // Columns of cells scrolling up the screen, each at its own pace; at most one spark a
            // cell, it and its glow kept clear of the cell's edges, so no neighbour needs looking at.
            float cs = sparkCell;
            float column = floor(xy.x / cs);
            float hc = hash(float2(column, 7.13));
            float yy = xy.y + time * (0.55 + 0.9 * hc) * 150.0 * dp + hc * 997.0;
            float2 cell = float2(column, floor(yy / cs));
            float hs = hash(cell + 19.7);
            if (hs < sparks) {
                float2 c = float2(0.35 + 0.3 * hash(cell + 3.3), 0.35 + 0.3 * hash(cell + 8.1));
                c.x += 0.08 * sin(time * 7.0 + hs * 40.0);
                float2 l = (float2(fract(xy.x / cs), fract(yy / cs)) - c) * cs;
                float sz = sparkSize * (0.6 + 0.8 * hash(cell + 5.9));
                // A crisp four-point sparkle, the app's own mark, drawn out along its flight, in a
                // soft glow of its own.
                float star = sqrt(abs(l.x) / (1.2 * sz)) + sqrt(abs(l.y) / (1.7 * sz));
                float gem = smoothstep(1.0, 0.7, star);
                float s = (gem + 0.3 * exp(-dot(l, l) / (2.0 * sz * sz))) * (0.6 + 0.4 * sin(time * 31.0 + hs * 60.0)) * vis;
                if (s > 0.003) {
                    float sa = min(1.0, s);
                    col = over(float4(mix(float3(hot.rgb), float3(core.rgb), gem) * sa, sa), col);
                }
            }
        }
    }

    return half4(min(col.rgb, float3(col.a)), col.a);
}
"""

/** Fire (shader): how far behind the front coals stay warm, in flame lengths plus dp. */
internal const val FIRE_COAL_REACH = 1.1f
internal const val FIRE_COAL_REACH_DP = 40f

/** Fire (shader): how far behind the front sparks still show, in flame lengths plus dp. */
internal const val FIRE_SPARK_REACH = 1.3f
internal const val FIRE_SPARK_REACH_DP = 50f

/** Fire (shader): how far behind the front the flames and the wisps off their tips reach, in flame lengths. */
internal const val FIRE_FLAME_REACH = 1.9f

/** Fire (shader): past this many dp (and the front's lobes) ahead of the front, nothing but char. */
internal const val FIRE_AHEAD_DP = 24f

/** Fire: how ragged the front is, as a share of the flame length either way. */
internal const val FIRE_LOBE = 0.18f

/** Fire: the space between tongues, as a share of the flame length, at its narrowest. */
private const val FIRE_TONGUE = 0.7f

/** Fire (shader): sparks are this far apart at the most, and at most this big. */
private val SPARK_CELL = 12.dp
private val SPARK_SIZE = 1.6.dp

/** Never finer than this many px, so the shrunken preview's sparks don't vanish between pixels. */
private const val SPARK_CELL_MIN_PX = 6f
private const val SPARK_SIZE_MIN_PX = 1.3f

/**
 * A fire's colours from hottest to coolest: [core] white-hot at the front, [hot] yellow, [body]
 * the flames, [flare] where they redden, [tip] their deep ends, and [ember] the glow of what has
 * burned. Built once per effect.
 */
@Immutable
internal data class FirePalette(
    val core: Color,
    val hot: Color,
    val body: Color,
    val flare: Color,
    val tip: Color,
    val ember: Color,
)

private val NaturalFire = FirePalette(
    core = Color(0xFFFFF7E6),
    hot = Color(0xFFFFD05A),
    body = Color(0xFFFF7F1E),
    flare = Color(0xFFF2441D),
    // Cooling into crimson rather than brown: the tips stay vivid.
    tip = Color(0xFFC8143C),
    ember = Color(0xFF7A0A2A),
)

/** Brand colours with less chroma than this have no hue to speak of: their fire stays as pale as they are. */
private const val GREY_CHROMA = 0.04f

/** The least chroma an app's fire burns with, so a muted brand still burns in colour. */
private const val FIRE_CHROMA = 0.13f

/**
 * The fire for [mode] in a message from [brand]. App and Blend pick their colours in OKLCh, so
 * every app's fire steps down in lightness the same way; Blend's flare turns from the natural
 * body to the brand tip the short way round the hue circle, so the two never mix to grey.
 */
internal fun firePalette(mode: FireColor, brand: Color): FirePalette {
    val (_, c, h) = toOklch(brand)
    val chroma = if (c < GREY_CHROMA) c else max(c, FIRE_CHROMA)
    return when (mode) {
        FireColor.NATURAL -> NaturalFire
        FireColor.APP -> FirePalette(
            core = oklch(0.97f, 0.03f, h),
            hot = oklch(0.88f, 0.6f * chroma, h),
            body = oklch(0.75f, chroma, h),
            flare = oklch(0.64f, chroma, h),
            tip = oklch(0.52f, chroma, h),
            ember = oklch(0.38f, 0.8f * chroma, h),
        )
        FireColor.BLEND -> {
            val from = toOklch(NaturalFire.body)[2]
            val turn = ((h - from) % 360f + 540f) % 360f - 180f
            NaturalFire.copy(
                flare = oklch(0.66f, max(chroma, 0.12f), from + turn / 2f),
                tip = oklch(0.58f, chroma, h),
                ember = oklch(0.4f, 0.8f * chroma, h),
            )
        }
    }
}

/**
 * The fire material for this effect, or null below Android 13 (no runtime shaders). [coals]: the
 * front leaves them, so the shader is drawn far enough behind it to show them.
 */
internal fun fireShader(
    origin: Offset,
    palette: FirePalette,
    flames: FireFlames,
    coals: Boolean,
    density: Float,
    scale: Float,
): FireShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return FireRuntime(origin, palette, flames, coals, density * scale)
}

/** A fire the effect moves every frame through [update]; built once per size, never in the draw. */
internal abstract class FireShader {
    /** The shader itself, for a platform paint that draws it as a ring band. */
    abstract val runtime: android.graphics.Shader

    /** How far behind and ahead of the front the shader can draw anything but char, in px. */
    abstract val behindPx: Float
    abstract val aheadPx: Float

    /**
     * [radius] the front's in px, [energy] the flames' light, [veil] the char's cover ahead and
     * [scorch] the embers' at its edge, [coals] the glow left behind, [sparks] the share of spark
     * cells lit, all 0..1; [ms] the effect's clock.
     */
    abstract fun update(radius: Float, energy: Float, veil: Float, scorch: Float, coals: Float, sparks: Float, ms: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class FireRuntime(
    origin: Offset,
    palette: FirePalette,
    flames: FireFlames,
    coals: Boolean,
    private val dp: Float,
) : FireShader() {
    private val height = flames.height.value * dp
    private val lobe = FIRE_LOBE * height
    private val tongue = FIRE_TONGUE * height

    override val runtime = RuntimeShader(FIRE_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("dp", dp)
        setFloatUniform("height", height)
        setFloatUniform("lobe", lobe)
        setFloatUniform("warp", flames.warp)
        setFloatUniform("rise", flames.rise)
        setFloatUniform("sparkCell", max(SPARK_CELL.value * dp, SPARK_CELL_MIN_PX))
        setFloatUniform("sparkSize", max(SPARK_SIZE.value * dp, SPARK_SIZE_MIN_PX))
        setColorUniform("core", palette.core.toArgb())
        setColorUniform("hot", palette.hot.toArgb())
        setColorUniform("body", palette.body.toArgb())
        setColorUniform("flare", palette.flare.toArgb())
        setColorUniform("tip", palette.tip.toArgb())
        setColorUniform("ember", palette.ember.toArgb())
    }

    override val behindPx: Float = lobe + 2f + maxOf(
        FIRE_FLAME_REACH * height,
        FIRE_SPARK_REACH * height + FIRE_SPARK_REACH_DP * dp,
        // The coals are cut off where they have cooled to 3% (e^-3.5).
        if (coals) 3.5f * (FIRE_COAL_REACH * height + FIRE_COAL_REACH_DP * dp) else 0f,
    )

    override val aheadPx: Float = lobe + FIRE_AHEAD_DP * dp + 2f

    override fun update(radius: Float, energy: Float, veil: Float, scorch: Float, coals: Float, sparks: Float, ms: Float) {
        // Tongues round the ring at their narrowest, as a power of two, and how far to the next.
        val count = max(4f, 2f * PI.toFloat() * radius / tongue)
        val level = log2(count)
        val whole = floor(level)
        runtime.setFloatUniform("radius", radius)
        runtime.setFloatUniform("energy", energy)
        runtime.setFloatUniform("veil", veil)
        runtime.setFloatUniform("scorch", scorch)
        runtime.setFloatUniform("coals", coals)
        runtime.setFloatUniform("sparks", sparks)
        runtime.setFloatUniform("time", ms / 1000f)
        val tongues = 2f.pow(whole)
        runtime.setFloatUniform("tongues", tongues)
        runtime.setFloatUniform("column", max(1f, 2f * PI.toFloat() * radius / tongues))
        runtime.setFloatUniform("finer", level - whole)
    }
}
