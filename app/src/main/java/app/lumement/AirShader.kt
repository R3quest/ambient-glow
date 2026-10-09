package app.lumement

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// The air wave as a gust (Android 13+): lines of wind sweeping out of the camera, drawn on and
// wiped off like brush strokes, curling at their heads; the air they move through, faintly
// visible; and what the gust carries, tumbling out with it. One pass of one shader per frame,
// over the ring the gust is in (and what it carries); below Android 13 the effect draws
// gradients instead.
// ---------------------------------------------------------------------------------------------

/**
 * A few sine waves round the ring, summed on 0.5 and thresholded between [from] and [to] into
 * bands with clear gaps between: the wind's strips and the gust's tongues. Whole-number waves
 * close round the ring, so nothing tears where the flow angle wraps. Each wave: amplitude, waves
 * round the ring, phase, and drift (how far its phase moves per unit of `t`). The app and the
 * shaders both work it out from these numbers ([agsl]), so what the shaders draw and what the
 * lock screen's blur columns follow can't drift apart.
 */
private class RingWaves(private val waves: FloatArray, private val from: Float, private val to: Float) {
    private val drifts = (3 until waves.size step 4).any { waves[it] != 0f }

    fun at(psi: Float, t: Float = 0f): Float {
        var w = 0.5f
        for (i in waves.indices step 4) w += waves[i] * sin(psi * waves[i + 1] + waves[i + 2] + waves[i + 3] * t)
        return smoothstep(from, to, w)
    }

    /** The same as an AGSL function `float name(float psi)`, or `(float psi, float t)` where it drifts. */
    fun agsl(name: String): String = buildString {
        append("float $name(float psi${if (drifts) ", float t" else ""}) {\n    float w = 0.5")
        for (i in waves.indices step 4) {
            append(" + ${waves[i]} * sin(psi * ${waves[i + 1]} + ${waves[i + 2]}")
            if (drifts) append(" + ${waves[i + 3]} * t")
            append(")")
        }
        append(";\n    return smoothstep($from, $to, w);\n}")
    }
}

/** The flow angle at [dx], [dy] from the camera: where on the ring the flow line through it leaves, as the shaders work it out. */
internal fun flowAngle(dx: Float, dy: Float, pitch: Float): Float = atan2(dx, dy) - pitch * ln(max(hypot(dx, dy), 1f))

/** [flowAngle] for the shaders: every one that rides the flow works it out here. */
private const val FLOW_ANGLE_AGSL = """
float flowAngle(float2 d, float lean) {
    return atan(d.x, d.y) - lean * log(max(length(d), 1.0));
}
"""

/**
 * The wind's strips: how hard the gust drags the screen at a flow angle, 0 in the still gaps to
 * 1 in a strip, in uneven widths. The veil shows them, and the screen under the gust smears in
 * them ([WindSmear], and the lock screen's blur columns in [GlowShield]).
 */
private val WIND_STRIPS = RingWaves(
    floatArrayOf(
        0.28f, 31f, 1.3f, 0f,
        0.22f, 53f, 4.1f, 0f,
        0.12f, 89f, 2.2f, 0f,
    ),
    from = 0.45f,
    to = 0.75f,
)

/** How hard the wind drags the screen at flow angle [psi], 0..1; `windStrip` in the shaders. */
internal fun windStripAt(psi: Float): Float = WIND_STRIPS.at(psi)

private val WIND_STRIP_AGSL = WIND_STRIPS.agsl("windStrip")

/**
 * The gust's tongues: a gust is not a ring, so here and there the wind surges on ahead of its
 * front, in a few broad tongues round the ring that shift as it travels. Only ever ahead: the
 * front itself still lights the frame and beacons as it reaches them, and a tongue that gets
 * there first reads as the wind striking before the glow catches. They drift with the wave.
 */
private val GUST_TONGUES = RingWaves(
    floatArrayOf(
        0.3f, 3f, 0.4f, 2.1f,
        0.22f, 5f, 2.9f, -3.4f,
        0.12f, 9f, 5.1f, 5.3f,
    ),
    from = 0.5f,
    to = 0.95f,
)

/** The farthest a tongue runs ahead of the front, as a share of its radius. */
internal const val GUST_SURGE = 0.12f

/** The front leaves the camera round, and breaks up into tongues over this stretch of the wave. */
private const val SURGE_RISE_FROM = 0.04f
private const val SURGE_RISE_TO = 0.4f

/**
 * How far the gust's front runs ahead of round at flow angle [psi] with the wave at [wave] of
 * its reach: a share of its radius, 0..[GUST_SURGE]; `gustSurge` in the shaders. The lines, the
 * air, the smear and the lock screen's blur all bend round the same tongues.
 */
internal fun gustSurgeAt(psi: Float, wave: Float): Float =
    GUST_SURGE * smoothstep(SURGE_RISE_FROM, SURGE_RISE_TO, wave) * GUST_TONGUES.at(psi, wave)

private val GUST_SURGE_AGSL = """
${GUST_TONGUES.agsl("gustTongues")}

float gustSurge(float psi, float wave) {
    return $GUST_SURGE * smoothstep($SURGE_RISE_FROM, $SURGE_RISE_TO, wave) * gustTongues(psi, wave);
}
"""

/**
 * The wind flows along log spirals out of the camera, `pitch` the tangent of their lean: a
 * pixel's flow angle `psi` is the same all along the line through it, so lines, the air's
 * texture and what it carries all ride the same flow, straight out (pitch 0) or whirling.
 *
 * Lines live in columns round the ring, `lines` of them, a power of two: the even ones are
 * the lines already there, the odd ones new lines rising (`finer`) as the ring spreads, so the
 * lines stay about `spacing` apart wherever the front is. A column is known by its angle as a
 * fraction of the turn, the same at every density, so a line keeps its timing as the columns
 * double round it.
 *
 * Every column plays its lines over and over, each a stroke along its flow line: the head draws
 * it out, the tail wipes it away after it (as motion graphics trim a path), its width swelling
 * from a hairline tail to a round head. A line ends in a curl (`curl` px across): an
 * Archimedean spiral it runs into, tightening, so a stroke drawing on whips round at its head.
 * The lines ride on the front; wisps, thinner and shorter, trail them farther back.
 *
 * Layers, bottom up: the puff, a soft ring of air thrown out of the camera as the gust is let go,
 * torn into wisps; the air, a veil in the wind's strips ([windStripAt]) trailing the front; the lines' glow, brightest round
 * their heads; the lines, cel-shaded in two bands, a white core in a coloured rim, whitening
 * towards the head; and what the gust carries. All are premultiplied and never brighter than their cover.
 */
private val AIR_AGSL = """
uniform float2 origin;
uniform float radius;
uniform float wave;
uniform float energy;
uniform float puff;
uniform float time;
uniform float dp;
uniform float len;
uniform float width;
uniform float curl;
uniform float pitch;
uniform float cosPitch;
uniform float turn;
uniform float lines;
uniform float finer;
uniform float pace;
uniform float ahead;
uniform float behind;
uniform float carry;
uniform float kind;
uniform float density;
uniform float size;
uniform float cols;
uniform float row;
uniform float drift;
uniform float fall;
layout(color) uniform half4 core;
layout(color) uniform half4 body;
layout(color) uniform half4 glow;
layout(color) uniform half4 shade;
layout(color) uniform half4 petal;
layout(color) uniform half4 petalShade;
layout(color) uniform half4 leaf;
layout(color) uniform half4 leafShade;

const float TAU = 6.2831853;
// One line's life in s at pace 1, from drawing on to wiped off; the cycle rests a quarter as long again.
const float LIFE = $AIR_LIFE;
// The curl: its radius shrinks by this share a turn, and it runs this far round (radians).
const float SHRINK = 0.55;
const float PHI_MAX = 5.4;
// The air's veil falls off behind the front over this many line lengths.
const float BODY_REACH = $AIR_BODY_REACH;
const float BODY_ALPHA = 0.14;
// The lines' glow: its falloff in dp, and how strong it is.
const float BLOOM_DP = $AIR_BLOOM_DP;
const float BLOOM = 0.4;
// The puff at its brightest.
const float PUFF_ALPHA = 0.3;
// What the gust carries rides from this share of the front's radius out to the front, in rows of
// cells `row` of it deep (a whole number of rows), `cols` cells round the ring.
const float CARRY_FROM = $AIR_CARRY_FROM;

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

float4 over(float4 top, float4 under) {
    return top + under * (1.0 - top.a);
}

$FLOW_ANGLE_AGSL

$WIND_STRIP_AGSL

$GUST_SURGE_AGSL

// How far along a curl of radius rho the stroke has run at phi radians into it.
float curlArc(float rho, float phi) {
    return rho * (phi - SHRINK * phi * phi / (2.0 * TAU));
}

// A brush stroke's width along its visible part, v 0 at the tail to 1 at the head: a hairline
// tail swelling to a round head.
float taper(float v) {
    if (v <= 0.0 || v >= 1.0) return 0.0;
    return pow(v, 0.45) * sqrt(1.0 - pow(v, 6.0));
}

// One line of column k (its key), b 0 for the lines on the front or 1 for the wisps behind them.
// off is the pixel's flow angle off the column's centre, r its radius, x its distance past the
// front, span the columns there were round the ring when this column first rose. Returns its
// depth (half-width minus the distance off its centre, in px), its half-width, where along its
// visible part the pixel is (0 tail, 1 head) and its strength; depth -1000 where there is none.
float4 streak(float k, float b, float off, float r, float x, float span, float weight) {
    float4 none = float4(-1000.0, 0.0, 0.0, 0.0);
    float rate = pace * (0.75 + 0.5 * hash(float2(k, b + 1.0))) * (1.0 - 0.25 * b) / LIFE;
    float cyc = time * rate + hash(float2(k, b + 2.0));
    float n = floor(cyc);
    float tau = fract(cyc) * 1.25;
    if (tau >= 1.0) return none;
    // A new line every cycle: its own length, lag behind the front, place and curl.
    float h1 = hash(float2(k + 7.31 * n, b + 3.0));
    float h2 = hash(float2(k + 3.17 * n, b + 4.0));
    float h3 = hash(float2(k + 5.83 * n, b + 5.0));
    float occ = mix(0.85, 0.55, b);
    if (h3 > occ) return none;
    float L = len * mix(0.75 + 0.5 * h1, 0.45 + 0.3 * h1, b);
    float lag = len * mix(0.35 * h2, 0.9 + 1.1 * h2, b);
    float rho = b > 0.5 ? 0.0 : curl * (0.8 + 0.4 * h2);
    float side = h3 < turn * occ ? 1.0 : -1.0;
    float S = L + (rho > 0.0 ? curlArc(rho, PHI_MAX) : 0.0);
    // The trim: the head draws the stroke out, fast then easing; the tail follows, slow then fast,
    // and wipes it away.
    float a = min(1.0, tau / 0.55);
    float head = S * (1.0 - (1.0 - a) * (1.0 - a));
    float tail = S * smoothstep(0.3, 1.0, tau);
    float vis = head - tail;
    if (vis < 0.5) return none;
    float jit = (h1 - 0.5) * 0.36 * TAU / span;
    float across = (off - jit) * r * cosPitch;
    float along = (x + lag) / cosPitch;
    // A short stroke is a thin one: it swells as it draws out and thins as it is wiped.
    float w = 0.5 * width * mix(1.0, 0.5, b) * smoothstep(0.0, 0.45 * S, vis);
    float alpha = weight * mix(1.0, 0.6, b);
    float4 best = none;
    // The line, straight along the flow up to where it curls.
    if (along <= 0.0 && along >= -L) {
        float v = (L + along - tail) / vis;
        float hw = w * taper(v);
        if (hw > 0.0) best = float4(hw - abs(across), hw, v, alpha);
    }
    // The curl, turning off the line's end towards `side` and tightening as it goes round.
    if (rho > 0.0) {
        float2 q = float2(along, across * side - rho);
        float phi = mod(atan(q.y, q.x) + 0.25 * TAU, TAU);
        if (phi <= PHI_MAX) {
            float v = (L + curlArc(rho, phi) - tail) / vis;
            float hw = w * taper(v) * (1.0 - 0.5 * phi / PHI_MAX);
            float d = hw - abs(length(q) - rho * (1.0 - SHRINK * phi / TAU));
            if (hw > 0.0 && d > best.x) best = float4(d, hw, v, alpha);
        }
    }
    return best;
}

// What the gust carries at xy: one in a cell at most, kept clear of the cell's edges so no
// neighbour needs looking at. The cells ride the flow out at their share of the front's radius,
// so they spread as it does, then drift on and fall.
float4 carried(float2 xy) {
    float2 p = xy - origin - float2(0.0, fall);
    float rp = length(p);
    float f = (rp - drift) / max(radius, 1.0);
    if (f < CARRY_FROM || f >= 1.0) return float4(0.0);
    float cu = flowAngle(p, pitch) / TAU * cols;
    float cv = (f - CARRY_FROM) / row;
    float2 cell = float2(mod(floor(cu), cols), floor(cv));
    float hs = hash(cell + kind * 17.0);
    if (hs >= density) return float4(0.0);
    float2 c = float2(0.35 + 0.3 * hash(cell + 3.3), 0.35 + 0.3 * hash(cell + 8.1));
    c.y += 0.1 * sin(time * (2.0 + 2.0 * hash(cell + 2.7)) + hs * 50.0);
    float2 l = float2((fract(cu) - c.x) * TAU / cols * rp * cosPitch, (fract(cv) - c.y) * row * radius);
    float sz = size * (0.7 + 0.6 * hash(cell + 5.9));
    if (kind < 1.5) {
        // Dust: a glint that twinkles as it turns to the light.
        float g = exp(-dot(l, l) / (2.0 * sz * sz));
        float ma = g * (0.5 + 0.5 * cos(time * (5.0 + 6.0 * hash(cell + 4.4)) + hs * 40.0));
        return float4(mix(body.rgb, core.rgb, g) * ma, ma) * carry;
    }
    // Petals and leaves spin, and tumble: seen edge on as they flip, their back in shade.
    float a = hash(cell + 1.7) * TAU + time * (1.5 + 2.5 * hash(cell + 2.3)) * (hs < 0.5 * density ? 1.0 : -1.0);
    float ca = cos(a);
    float sa = sin(a);
    l = float2(ca * l.x - sa * l.y, sa * l.x + ca * l.y);
    float face = cos(time * (3.0 + 4.0 * hash(cell + 4.4)) + hs * 40.0);
    float thin = max(0.2, abs(face));
    float2 q = float2(l.x / thin, l.y) / sz;
    float sd;
    float3 base;
    if (kind < 2.5) {
        // A petal: an oval with a notch at its tip, deeper at its base.
        float notch = 0.32 - length(q - float2(0.0, 1.0));
        sd = max(length(q * float2(1.6, 1.0)) - 1.0, notch);
        base = mix(float3(petalShade.rgb), float3(petal.rgb), smoothstep(-1.0, 0.5, q.y));
    } else {
        // A leaf: pointed at both ends, a darker rib down its middle.
        sd = max(length(q - float2(0.62, 0.0)), length(q + float2(0.62, 0.0))) - 1.0;
        base = mix(float3(leaf.rgb), float3(leafShade.rgb), hash(cell + 6.6));
        base *= 1.0 - 0.3 * (1.0 - smoothstep(0.03, 0.09, abs(q.x))) * step(abs(q.y), 0.6);
    }
    if (face < 0.0) base *= 0.75;
    float ma = 0.92 * clamp(0.5 - sd * sz * thin, 0.0, 1.0);
    return float4(base * ma, ma) * carry;
}

half4 main(float2 xy) {
    float2 d = xy - origin;
    float r = length(d);
    // The front, surging on ahead in the gust's tongues; the lines and the air ride it there.
    float psi = flowAngle(d, pitch);
    float surge = gustSurge(psi, wave);
    float x = r - radius * (1.0 + surge);
    float4 col = float4(0.0);

    if ((energy > 0.0 || puff > 0.0) && x < ahead && x > -behind) {
        // The tongues carry the gust's full strength; the calm between them blows a little softer.
        float push = surge / $GUST_SURGE;
        float gust = energy * (0.8 + 0.2 * push);
        float h = -x;
        // The puff: sharp at its leading edge, thinning behind, torn round the ring.
        if (puff > 0.0) {
            float pr = x > 0.0 ? exp(-x / (5.0 * dp)) : exp(x / (26.0 * dp));
            float tear = 0.4 + 0.6 * noise(float2(psi * 14.0, time * 3.0));
            float pa = puff * PUFF_ALPHA * pr * tear;
            col = float4(mix(float3(glow.rgb), float3(body.rgb), pr) * pa, pa);
        }
        // The air: a veil in the wind's strips, the ones that smear the screen under it, rushing
        // out along them, thickest just behind the front.
        float prof = smoothstep(-8.0 * dp, 6.0 * dp, h) * exp(-max(h, 0.0) / (BODY_REACH * len));
        if (prof > 0.01) {
            float n1 = noise(float2(psi * 40.0, r / (0.55 * len) - time * 2.4 * pace));
            float n2 = noise(float2(psi * 87.0 + 3.1, r / (0.27 * len) - time * 3.6 * pace));
            float rush = smoothstep(0.2, 0.8, 0.65 * n1 + 0.35 * n2);
            float va = gust * BODY_ALPHA * (1.0 + 0.5 * push) * prof * (0.15 + 0.85 * windStrip(psi)) * (0.35 + 0.65 * rush);
            col = over(float4(float3(glow.rgb) * va, va), col);
        }

        // The lines: this column's and its neighbours', since curls reach across.
        float u = psi / TAU * lines;
        float c0 = floor(u + 0.5);
        float4 win = float4(-1000.0, 0.0, 0.0, 0.0);
        float bloom = 0.0;
        for (int j = -1; j <= 1; j++) {
            float i = c0 + float(j);
            float m = mod(i, lines);
            float weight = mod(m, 2.0) > 0.5 ? finer : 1.0;
            if (weight > 0.0) {
                // The columns there were when this one rose: halve until odd.
                float odd = m;
                float span = lines;
                for (int t = 0; t < 12; t++) {
                    float even = (odd > 0.5 && mod(odd, 2.0) < 0.5) ? 1.0 : 0.0;
                    odd = mix(odd, 0.5 * odd, even);
                    span = mix(span, 0.5 * span, even);
                }
                if (m < 0.5) span = 4.0;
                float key = m / lines * 4096.0;
                float off = (u - i) * TAU / lines;
                for (int b = 0; b < 2; b++) {
                    float4 s = streak(key, float(b), off, r, x, span, weight);
                    if (s.x > win.x) win = s;
                    // Glinting as it whips round at the head.
                    float glint = 0.6 + 0.7 * s.z * s.z * s.z;
                    bloom = max(bloom, s.w * glint * exp(min(s.x, 0.0) / (BLOOM_DP * dp)) * min(1.0, s.y / (0.25 * width)));
                }
            }
        }
        float ga = min(1.0, gust * BLOOM * bloom);
        if (ga > 0.0) col = over(float4(float3(glow.rgb) * ga, ga), col);
        float cov = clamp(win.x + 0.5, 0.0, 1.0) * min(1.0, 2.0 * win.y);
        if (cov > 0.0) {
            float v = win.z;
            float inner = clamp(win.x - 0.45 * win.y + 0.5, 0.0, 1.0);
            float3 rim = mix(float3(shade.rgb), float3(glow.rgb), smoothstep(0.0, 0.6, v));
            float3 hot = mix(float3(body.rgb), float3(core.rgb), smoothstep(0.3, 1.0, v));
            float la = gust * win.w * cov * (0.5 + 0.5 * v);
            col = over(float4(mix(rim, hot, inner) * la, la), col);
        }
    }

    if (carry > 0.0 && size > 0.0) col = over(carried(xy), col);

    return half4(min(col.rgb, float3(col.a)), col.a);
}
"""

/**
 * The gust's smear of what is under it, as an effect on that content (Android 13+): in the
 * wind's strips each pixel is drawn from along the flow line upwind of it, as if the wind had
 * dragged the screen out in streaks; between them it is left sharp. `len` is the longest drag.
 */
private val WIND_SMEAR_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float pitch;
uniform float len;

$FLOW_ANGLE_AGSL

$WIND_STRIP_AGSL

half4 main(float2 xy) {
    float2 d = xy - origin;
    float r = max(length(d), 1.0);
    float drag = len * windStrip(flowAngle(d, pitch));
    if (drag < 0.75) return content.eval(xy);
    // The wind's way here: straight out, leaning with the flow.
    float2 away = d / r;
    float2 flow = normalize(away + pitch * float2(away.y, -away.x));
    half4 acc = half4(0.0);
    float sum = 0.0;
    for (int i = 0; i < $SMEAR_TAPS; i++) {
        // Mostly from upwind, the nearest strongest: the screen pushed out, trailing.
        float t = float(i) / ${SMEAR_TAPS - 1}.0;
        float w = 1.0 - 0.6 * t;
        acc += content.eval(xy - flow * (drag * (t - 0.2))) * half(w);
        sum += w;
    }
    return acc / half(sum);
}
"""

private const val SMEAR_TAPS = 12

/** The smear drags up to this many times a blur's radius along the wind, softened by a blur of this share of it. */
private const val SMEAR_DRAG = 3f
private const val SMEAR_SOFT = 0.3f

/**
 * The gust's smear for the dashboard and its preview, which blur what they draw themselves
 * ([glassHaze]): built once per size, then one effect per blur step, so a frame only picks one.
 */
internal fun interface WindSmear {
    /**
     * The smear in place of a blur of [radius] px: dragged [SMEAR_DRAG] times as far along the
     * wind in its strips, after a light blur. Each effect keeps the drag it was made with.
     */
    fun effect(radius: Float): RenderEffect
}

/** The gust's smear from [origin] for lines leaning [pitch], or null below Android 13 (no runtime shaders). */
internal fun windSmear(origin: Offset, pitch: Float): WindSmear? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return WindSmearRuntime(origin, pitch)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class WindSmearRuntime(origin: Offset, pitch: Float) : WindSmear {
    private val shader = RuntimeShader(WIND_SMEAR_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("pitch", pitch)
    }

    override fun effect(radius: Float): RenderEffect {
        shader.setFloatUniform("len", SMEAR_DRAG * radius)
        val soft = SMEAR_SOFT * radius
        return android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
            android.graphics.RenderEffect.createBlurEffect(soft, soft, android.graphics.Shader.TileMode.CLAMP),
        ).asComposeRenderEffect()
    }
}

/**
 * The band the gust smears what is under it in, as a mask (white where it smears): the glass
 * wave's band ([hazeMask]), bent round the gust's tongues ([gustSurgeAt]) so the smear rides
 * ahead with them instead of ending on a circle.
 */
private val WIND_BAND_AGSL = """
uniform float2 origin;
uniform float radius;
uniform float wave;
uniform float pitch;

$FLOW_ANGLE_AGSL

$GUST_SURGE_AGSL

half4 main(float2 xy) {
    float2 d = xy - origin;
    float f = length(d) / max(radius * (1.0 + gustSurge(flowAngle(d, pitch), wave)), 1.0);
    // The glass band's stops, as its gradient lays them ([hazeMask]): in to half, to full, then
    // out at its edge.
    float a = $HAZE_BAND_HALF_ALPHA * clamp((f - $HAZE_BAND_FADE) / ${HAZE_BAND_HALF - HAZE_BAND_FADE}, 0.0, 1.0)
            + ${1f - HAZE_BAND_HALF_ALPHA} * clamp((f - $HAZE_BAND_HALF) / ${HAZE_BAND_FULL - HAZE_BAND_HALF}, 0.0, 1.0);
    a *= 1.0 - clamp((f - $HAZE_BAND_OUTER) / ${1f - HAZE_BAND_OUTER}, 0.0, 1.0);
    return half4(a);
}
"""

/** The gust's smear band for content the app draws itself, moved every frame through [draw]. */
internal fun interface WindBand {
    /** Cuts what is drawn so far to the band, the wave at [wave] of its reach. */
    fun draw(scope: DrawScope, wave: Float)
}

/** The gust's smear band from [origin] over [reach], lines leaning [pitch]; null below Android 13. */
internal fun windBand(origin: Offset, reach: Float, pitch: Float): WindBand? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return WindBandRuntime(origin, reach, pitch)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class WindBandRuntime(origin: Offset, private val reach: Float, pitch: Float) : WindBand {
    private val shader = RuntimeShader(WIND_BAND_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("pitch", pitch)
    }
    private val paint = android.graphics.Paint().apply {
        this.shader = this@WindBandRuntime.shader
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    override fun draw(scope: DrawScope, wave: Float) {
        shader.setFloatUniform("radius", wave * reach)
        shader.setFloatUniform("wave", wave)
        scope.drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint) }
    }
}

/** Air (shader): one line's life at pace 1, in s. */
internal const val AIR_LIFE = 0.5f

/** Air (shader): the air's veil falls off behind the front over this many line lengths. */
internal const val AIR_BODY_REACH = 0.7f

/** Air (shader): the lines' glow falls off over this many dp. */
internal const val AIR_BLOOM_DP = 3f

/** Air (shader): the farthest behind the front a line or wisp reaches, in line lengths. */
private const val AIR_STREAK_REACH = 2.8f

/** Air (shader): what the gust carries rides from this share of the front's radius out to it. */
internal const val AIR_CARRY_FROM = 0.6f

/**
 * Air (shader): the cells what the gust carries rides in, this many round the ring and this many
 * rows deep (a whole number, so no row is cut by the front): bigger things, fewer and bigger cells.
 */
private fun AirCarry.cells(): Pair<Float, Float> = when (this) {
    AirCarry.NONE, AirCarry.DUST -> 72f to 7f
    AirCarry.PETALS -> 60f to 6f
    AirCarry.LEAVES -> 44f to 5f
}

/** Air: the curl's radius at most, as a share of the spacing between lines, so it stays within its neighbours. */
private const val CURL_SHARE = 0.17f

/**
 * Air: what the gust carries is at most this share of a cell, kept clear of the cell's edges as
 * it spins and sways. Cells grow with the front, so things carried grow in out of the camera.
 */
private const val CARRY_SIZE_SHARE = 0.2f

/**
 * The wind's colours from brightest to deepest: [core] the white at a line's head, [body] its
 * core towards the tail, [glow] its rim and the light round it, [shade] its far tail; and what it
 * carries, [petal] and [petalShade] at its base, [leaf] and [leafShade] (each leaf picks between
 * them). Built once per effect.
 */
@Immutable
internal data class AirPalette(
    val core: Color,
    val body: Color,
    val glow: Color,
    val shade: Color,
    val petal: Color,
    val petalShade: Color,
    val leaf: Color,
    val leafShade: Color,
)

private val ClearAir = AirPalette(
    core = Color(0xFFFFFFFF),
    body = Color(0xFFE6F5FF),
    glow = Color(0xFF9CD6F5),
    shade = Color(0xFF5B98C7),
    // Cherry blossom, and the leaves of early autumn.
    petal = Color(0xFFFFD6E3),
    petalShade = Color(0xFFE88AAB),
    leaf = Color(0xFFF2B54C),
    leafShade = Color(0xFFC9572E),
)

/** Brand colours with less chroma than this have no hue to speak of: their wind stays as pale as they are. */
private const val GREY_CHROMA = 0.04f

/** The least chroma an app's wind blows with, so a muted brand still shows its colour. */
private const val AIR_CHROMA = 0.12f

/**
 * The wind for [mode] in a message from [brand]. App and Blend pick their colours in OKLCh, so
 * every app's wind steps down in lightness the same way. Blend keeps clear air's white lines and
 * what it carries, and trails the brand in their rims and glow.
 */
internal fun airPalette(mode: AirColor, brand: Color): AirPalette {
    val (_, c, h) = toOklch(brand)
    val chroma = if (c < GREY_CHROMA) c else max(c, AIR_CHROMA)
    return when (mode) {
        AirColor.CLEAR -> ClearAir
        AirColor.APP -> AirPalette(
            core = oklch(0.985f, 0.015f, h),
            body = oklch(0.93f, 0.35f * chroma, h),
            glow = oklch(0.8f, chroma, h),
            shade = oklch(0.62f, chroma, h),
            petal = oklch(0.88f, 0.6f * chroma, h),
            petalShade = oklch(0.7f, chroma, h),
            leaf = oklch(0.78f, chroma, h),
            leafShade = oklch(0.56f, chroma, h),
        )
        AirColor.BLEND -> ClearAir.copy(
            glow = oklch(0.8f, chroma, h),
            shade = oklch(0.62f, chroma, h),
        )
    }
}

/**
 * The gust's material for this effect, or null below Android 13 (no runtime shaders). [carry]
 * says what it carries, if anything.
 */
internal fun airShader(
    origin: Offset,
    palette: AirPalette,
    gust: AirGust,
    flow: AirFlow,
    carry: AirCarry,
    density: Float,
    scale: Float,
): AirShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return AirRuntime(origin, palette, gust, flow, carry, density * scale)
}

/** A gust the effect moves every frame through [update]; built once per size, never in the draw. */
internal abstract class AirShader {
    /** The shader itself, for a platform paint that draws it as a ring band. */
    abstract val runtime: android.graphics.Shader

    /** How far behind and ahead of the front the lines and the air can show, in px. */
    abstract val behindPx: Float
    abstract val aheadPx: Float

    /** How big what the gust carries is now, in px, and how far it has drifted out and fallen. */
    abstract val carrySizePx: Float
    abstract val driftPx: Float
    abstract val fallPx: Float

    /**
     * [radius] the front's in px where it is round, [wave] how far out it is (0..1 of its reach,
     * for the tongues that surge ahead of it, [gustSurgeAt]), [energy] the lines' light, [puff] the
     * puff's and [carry] the share of what the gust carries still in the air, all 0..1; [ms] the
     * effect's clock.
     */
    abstract fun update(radius: Float, wave: Float, energy: Float, puff: Float, carry: Float, ms: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AirRuntime(
    origin: Offset,
    palette: AirPalette,
    gust: AirGust,
    flow: AirFlow,
    carry: AirCarry,
    private val dp: Float,
) : AirShader() {
    private val len = gust.length.value * dp
    private val width = gust.width.value * dp
    private val spacing = gust.spacing.value * dp
    private val curl = min(flow.curl.value, CURL_SHARE * gust.spacing.value) * dp
    private val size = carry.size.value * dp

    private val cells = carry.cells()
    private val cols = cells.first
    private val rows = cells.second

    /** A cell's smaller side, as a share of the front's radius: across, at the back of the band, or deep. */
    private val cell = min((1f - AIR_CARRY_FROM) / rows, 2f * PI.toFloat() / cols * AIR_CARRY_FROM)

    override val runtime = RuntimeShader(AIR_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("dp", dp)
        setFloatUniform("pitch", flow.pitch)
        setFloatUniform("cosPitch", 1f / sqrt(1f + flow.pitch * flow.pitch))
        setFloatUniform("turn", flow.turn)
        setFloatUniform("pace", gust.pace)
        setFloatUniform("kind", carry.ordinal.toFloat())
        setFloatUniform("density", carry.density)
        setFloatUniform("cols", cols)
        setFloatUniform("row", (1f - AIR_CARRY_FROM) / rows)
        setColorUniform("core", palette.core.toArgb())
        setColorUniform("body", palette.body.toArgb())
        setColorUniform("glow", palette.glow.toArgb())
        setColorUniform("shade", palette.shade.toArgb())
        setColorUniform("petal", palette.petal.toArgb())
        setColorUniform("petalShade", palette.petalShade.toArgb())
        setColorUniform("leaf", palette.leaf.toArgb())
        setColorUniform("leafShade", palette.leafShade.toArgb())
    }

    override val behindPx: Float = AIR_STREAK_REACH * len + 4f * dp + 2f

    override val aheadPx: Float = 1.3f * curl + width + 4f * AIR_BLOOM_DP * dp + 2f

    override var carrySizePx = 0f
        private set
    override var driftPx = 0f
        private set
    override var fallPx = 0f
        private set

    override fun update(radius: Float, wave: Float, energy: Float, puff: Float, carry: Float, ms: Float) {
        // Short lines while the ring is small, so they never reach back past the camera.
        val grow = 0.3f + 0.7f * smoothstep(0f, 3f * len, radius)
        // Columns round the ring at their widest, as a power of two, and how far to the next.
        val level = log2(max(4f, 2f * PI.toFloat() * radius / spacing))
        val whole = floor(level)
        carrySizePx = min(size, CARRY_SIZE_SHARE * cell * radius)
        driftPx = airDriftAt(ms) * dp
        fallPx = airFallAt(ms) * dp
        runtime.setFloatUniform("radius", radius)
        runtime.setFloatUniform("wave", wave)
        runtime.setFloatUniform("energy", energy)
        runtime.setFloatUniform("puff", puff)
        runtime.setFloatUniform("time", ms / 1000f)
        runtime.setFloatUniform("len", len * grow)
        runtime.setFloatUniform("curl", curl * grow)
        runtime.setFloatUniform("width", width * (0.6f + 0.4f * grow))
        runtime.setFloatUniform("lines", 2f * 2f.pow(whole))
        runtime.setFloatUniform("finer", level - whole)
        runtime.setFloatUniform("ahead", aheadPx)
        runtime.setFloatUniform("behind", behindPx)
        // Things carried show once the ring is wide enough to hold them.
        runtime.setFloatUniform("carry", carry * smoothstep(60f * dp, 160f * dp, radius))
        runtime.setFloatUniform("size", carrySizePx)
        runtime.setFloatUniform("drift", driftPx)
        runtime.setFloatUniform("fall", fallPx)
    }
}
