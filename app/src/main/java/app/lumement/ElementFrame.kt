package app.lumement

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// The Edge Frame made of the element (Android 13+): Fire a burning fuse, Water liquid light in a
// glass tube, Air a slipstream, Earth a crack mended in gold. Each is one shader over the frame's
// band, worked out in the frame unrolled into a strip: `s` along it, as [EdgeLight] measures it,
// and `n` in from the screen's edge. The frame's reveal, comet heads and drain are the masks the
// neon frame uses, laid on after; the shader only knows where the heads are, to burn, surge, rush
// or shine hottest there.
// ---------------------------------------------------------------------------------------------

/**
 * Shared by every element. `frameAt` unrolls a pixel: `s` clockwise from the camera (`axis`),
 * folded for Twin (`mirror`) as [EdgeLight] folds it, `n` in from the rounded edge, and the frame's
 * length. Patterns along it repeat a whole number of times round (`cells`), so no seam shows
 * where `s` wraps. `heatAt` is 1 at a head, falling off over `tail` behind it and `lead` ahead,
 * and an even glow without heads (`focus` 0). `spread` is Pulse's breath, 1 elsewhere.
 */
private const val FRAME_COMMON = """
uniform float2 size;
uniform float corner;
uniform float axis;
uniform float mirror;
uniform float head;
uniform float lead;
uniform float tail;
uniform float focus;
uniform float spread;
uniform float grow;
uniform float time;
uniform float dp;
uniform float line;
uniform float reach;
uniform float peak;

const float TAU = 6.2831853;

// Hoskins' hash: no period on screen.
float hash(float2 p) {
    float3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Value noise repeating every `period` cells in x (a whole number), so it closes round the frame.
float wnoise(float x, float y, float period) {
    float i = floor(x);
    float f = fract(x);
    float j = floor(y);
    float g = fract(y);
    f = f * f * (3.0 - 2.0 * f);
    g = g * g * (3.0 - 2.0 * g);
    float i0 = mod(i, period);
    float i1 = mod(i + 1.0, period);
    return mix(mix(hash(float2(i0, j)), hash(float2(i1, j)), f),
               mix(hash(float2(i0, j + 1.0)), hash(float2(i1, j + 1.0)), f), g);
}

// How many cells about `len` long fit round a frame `per` long: a whole, even number.
float cells(float per, float len) {
    return max(2.0, 2.0 * floor(per / (2.0 * len) + 0.5));
}

float4 over(float4 top, float4 under) {
    return top + under * (1.0 - top.a);
}

float3 frameAt(float2 p) {
    float2 q = p - size * 0.5;
    float2 a = abs(q);
    float2 hx = size * 0.5 - corner;
    float2 k = a - hx;
    float n = corner - length(max(k, 0.0)) - min(max(k.x, k.y), 0.0);
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
    return float3(s, n, per);
}

// How far behind the head `s` is along the frame (below 0: ahead of it).
float behind(float s, float per) {
    return mod(head - s + 0.5 * per, per) - 0.5 * per;
}

float heatAt(float s, float per) {
    float d = behind(s, per);
    float k = d / lead;
    float near = d < 0.0 ? exp(-k * k) : exp(-d / tail);
    return mix(0.6, near, focus);
}
"""

/**
 * Fire: a burning fuse. The line burns white-hot at the head and cools behind it through the
 * fire's colours to embers that flicker; tongues of flame lick in off it, tallest and hottest at
 * the head, cel-shaded as the fire wave's are ([FireShader]); sparks float in off the line.
 * `flame` is the tallest tongue in px, `column` the space between tongues.
 */
internal val FIRE_FRAME_AGSL = FRAME_COMMON + """
uniform float flame;
uniform float column;
uniform float warp;
uniform float rise;
uniform float sparks;
layout(color) uniform half4 core;
layout(color) uniform half4 hot;
layout(color) uniform half4 body;
layout(color) uniform half4 flare;
layout(color) uniform half4 tip;
layout(color) uniform half4 ember;

// Hot to cool, 1 to 0: white-hot, yellow, the flames, reddening, deep, embers.
float3 ramp(float t) {
    float3 c = mix(float3(ember.rgb), float3(tip.rgb), smoothstep(0.0, 0.2, t));
    c = mix(c, float3(flare.rgb), smoothstep(0.2, 0.4, t));
    c = mix(c, float3(body.rgb), smoothstep(0.4, 0.6, t));
    c = mix(c, float3(hot.rgb), smoothstep(0.6, 0.8, t));
    return mix(c, float3(core.rgb), smoothstep(0.8, 1.0, t));
}

// Depth to colour in flat bands with anti-aliased edges, as the fire wave's flames.
float3 cel(float d, float aa) {
    float3 c = mix(float3(tip.rgb), float3(flare.rgb), 0.5 * smoothstep(0.0, 0.2, d));
    c = mix(c, mix(float3(flare.rgb), float3(body.rgb), 0.55 + 0.45 * smoothstep(0.2, 0.44, d)), smoothstep(0.2 - aa, 0.2 + aa, d));
    c = mix(c, mix(float3(body.rgb), float3(hot.rgb), 0.65 + 0.35 * smoothstep(0.44, 0.68, d)), smoothstep(0.44 - aa, 0.44 + aa, d));
    return mix(c, mix(float3(hot.rgb), float3(core.rgb), 0.75 + 0.25 * smoothstep(0.68, 0.9, d)), smoothstep(0.68 - aa, 0.68 + aa, d));
}

// One tongue in column i, at x across the columns and y in flame lengths off the line: 1 on its
// centre line at the root, 0 on its outline. A teardrop tapering to a point that sways.
float tongue(float i, float x, float y, float tall, float seed) {
    float r = hash(float2(i, seed));
    float H = tall * (0.42 + 0.58 * r * r) * (0.84 + 0.16 * sin(time * rise * (1.6 + 1.2 * r) + r * 40.0));
    float q = max(y, 0.0) / max(H, 0.001);
    if (q >= 1.0) return -1.0;
    float sway = sin(2.2 * q + time * rise * 0.9 + r * 12.0);
    float bend = warp * (0.42 * q * sqrt(q) * sway + 0.3 * q * q * q * q * (hash(float2(i, seed + 0.5)) - 0.5));
    float w = 0.62 * pow(1.0 - q, 0.75) * (1.0 + 0.4 * sin(3.14159 * min(1.0, 1.7 * q)));
    return (1.0 - abs(x - bend) / w) * (1.0 - 0.72 * q);
}

half4 main(float2 p) {
    float3 f = frameAt(p);
    float s = f.x;
    float n = f.y;
    float per = f.z;
    float heat = heatAt(s, per);
    float count = cells(per, column);
    float W = per / count;
    float root = 0.45 * line;

    // The fire's light on the screen round it.
    float halo = peak * spread * exp(-n / max(reach, 1.0)) * (0.3 + 0.7 * heat);
    float4 col = float4(float3(flare.rgb) * halo, halo);

    // Tongues: a back row, and a front row of shorter, hotter ones between them.
    float y = (n - root) / flame;
    float tall = spread * (0.45 + 0.55 * heat);
    if (y < 1.0 && tall > 0.02) {
        float u = s / W;
        float ub = u + warp * 0.45 * max(y, 0.0) * (wnoise(u * 0.5, y * 1.6 - time * rise * 0.6, 0.5 * count) - 0.5);
        float ib = floor(ub);
        float xb = fract(ub) - 0.5;
        float jf = floor(ub + 0.5);
        float xf = fract(ub + 0.5) - 0.5;
        float d = -1.0;
        for (int k = -1; k <= 1; k++) {
            float o = float(k);
            d = max(d, 0.8 * tongue(mod(ib + o, count), xb - o, y, tall, 1.0));
            d = max(d, tongue(mod(jf + o, count), xf - o, y, 0.58 * tall, 2.0));
        }
        if (d > 0.0) {
            float aa = 1.5 / W;
            float a = smoothstep(0.0, aa, d);
            float3 c = cel(min(1.0, d * (0.7 + 0.5 * heat)), aa);
            col = over(float4(c * a, a), col);
        }
    }

    // Sparks: one to a cell along the frame, floating in off the line and burning out.
    if (sparks > 0.0) {
        float C = per / cells(per, 11.0 * dp);
        float sc = per / C;
        float ic = floor(s / C);
        for (int k = -1; k <= 1; k++) {
            float iu = ic + float(k);
            float id = mod(iu, sc);
            float r = hash(float2(id, 61.0));
            if (r < sparks) {
                float life = fract(time * (0.55 + 0.5 * r) + hash(float2(id, 67.0)));
                float2 at = float2((iu + 0.5) * C + 6.0 * dp * sin(life * 5.0 + r * 30.0), root + life * 1.9 * flame);
                float sz = (1.4 * dp + 0.25 * line) * (1.0 - life);
                float sa = smoothstep(sz, 0.3 * sz, length(float2(s, n) - at)) * heat * (1.0 - life * life);
                col = over(float4(mix(float3(body.rgb), float3(core.rgb), 1.0 - life) * sa, sa), col);
            }
        }
    }

    // The fuse: ragged, flickering, white-hot at the head and down to embers behind it.
    float edge = line * (0.8 + 0.4 * wnoise(s / (5.0 * dp), time * 2.5, cells(per, 5.0 * dp)));
    float la = 1.0 - smoothstep(edge - 0.75, edge + 0.75, n);
    if (la > 0.0) {
        float flick = 0.78 + 0.22 * wnoise(s / (4.0 * dp), time * 9.0, cells(per, 4.0 * dp));
        float t = clamp((0.2 + 0.85 * heat) * flick * (1.0 - 0.3 * n / max(line, 1.0)), 0.0, 1.0);
        col = over(float4(ramp(t) * la, la), col);
    }
    return half4(col);
}
"""

/**
 * Water: liquid light in a glass tube. The liquid is the brand's colour, caustics running through
 * it; its surface rolls in waves and swells into a surge at the head, a bright meniscus with
 * glints running along it. `depth` is how deep the liquid stands at rest, in px.
 */
internal val WATER_FRAME_AGSL = FRAME_COMMON + """
uniform float depth;
layout(color) uniform half4 tint;

half4 main(float2 p) {
    float3 f = frameAt(p);
    float s = f.x;
    float n = f.y;
    float per = f.z;
    float heat = heatAt(s, per);
    float3 c = float3(tint.rgb);
    float3 light = mix(c, float3(1.0), 0.65);

    // The surface: three wave trains, each closing round the frame, and the surge riding the head.
    float k1 = TAU * cells(per, 36.0 * dp) / per;
    float k2 = TAU * cells(per, 14.0 * dp) / per;
    float k3 = TAU * cells(per, 7.0 * dp) / per;
    float amp = 1.8 * dp * (0.5 + 0.7 * heat) * spread;
    float wave = amp * (0.65 * sin(k1 * s - time * 4.0) + 0.28 * sin(k2 * s + time * 6.5) + 0.07 * sin(k3 * s - time * 9.0));
    float b = (behind(s, per) - 8.0 * dp) / (26.0 * dp);
    float surge = focus * 5.0 * dp * exp(-b * b) * spread;
    float surface = depth * (0.7 + 0.3 * spread) + wave + surge;

    // Light through the glass, beyond the surface.
    float g = peak * spread * exp(-max(n - surface, 0.0) / max(reach, 1.0)) * (0.45 + 0.55 * heat);
    float4 col = float4(c * g, g);

    // The liquid, caustics wandering through it.
    float inside = 1.0 - smoothstep(surface - 0.8, surface + 0.8, n);
    if (inside > 0.0) {
        float cn = wnoise(s / (16.0 * dp) - time * 1.8, n / (3.5 * dp) + time * 0.7, cells(per, 16.0 * dp));
        float ridge = 1.0 - abs(2.0 * cn - 1.0);
        ridge = ridge * ridge * ridge * ridge;
        float a = inside * (0.62 + 0.3 * ridge) * (0.7 + 0.3 * heat);
        col = over(float4(mix(c, light, 0.25 + 0.6 * ridge) * a, a), col);
    }

    // The meniscus, glints running along it.
    float m = (n - surface) / (0.7 * dp + 0.15 * line);
    float glint = wnoise(s / (20.0 * dp) - time * 3.0, 0.5, cells(per, 20.0 * dp));
    glint = glint * glint * glint * glint * glint * glint;
    float ma = min(1.0, exp(-m * m) * (0.55 + 0.6 * glint + 0.3 * heat));
    col = over(float4(mix(c, float3(1.0), 0.55 + 0.45 * glint) * ma, ma), col);

    // The glass: a fine bright rim at the bezel.
    float rim = (1.0 - smoothstep(0.6 * dp, 1.3 * dp, n)) * (0.35 + 0.3 * heat);
    col = over(float4(light * rim, rim), col);
    return half4(col);
}
"""

/**
 * Air: no solid line, a slipstream. Wind lines race round the frame in lanes `lane` px deep, a
 * white head on a tail fading through the wind's colours, thickest and fastest at the frame and
 * thinning inward; denser, brighter at the head. What the gust carries tumbles along with them.
 */
internal val AIR_FRAME_AGSL = FRAME_COMMON + """
uniform float lane;
uniform float lanes;
uniform float streak;
uniform float speed;
uniform float carry;
uniform float carrySize;
uniform float leaves;
layout(color) uniform half4 core;
layout(color) uniform half4 body;
layout(color) uniform half4 glow;
layout(color) uniform half4 shade;
layout(color) uniform half4 bloom;
layout(color) uniform half4 bloomShade;

half4 main(float2 p) {
    float3 f = frameAt(p);
    float s = f.x;
    float n = f.y;
    float per = f.z;
    float heat = heatAt(s, per);

    // The air itself, faintly lit, and a whisper of a line so the frame still reads between lines.
    float g = 0.75 * peak * spread * exp(-n / max(reach, 1.0)) * (0.35 + 0.65 * heat);
    float4 col = float4(float3(glow.rgb) * g, g);
    float wl = (1.0 - smoothstep(0.4 * line, 0.4 * line + 1.0, n)) * 0.4 * (0.4 + 0.6 * heat);
    col = over(float4(float3(body.rgb) * wl, wl), col);

    // Wind lines, one lane at a time.
    float j = floor(n / lane);
    if (j < lanes) {
        float depthT = j / lanes;
        float yl = fract(n / lane) - 0.5;
        float cnt = cells(per, streak);
        float L = per / cnt;
        float pace = speed * (0.75 + 0.5 * hash(float2(j, 3.0))) * (1.0 - 0.35 * depthT);
        float u = (s - time * pace) / L;
        float i = mod(floor(u), cnt);
        float r = hash(float2(i, j));
        if (r < 0.95 - 0.5 * depthT) {
            float len = (0.35 + 0.5 * hash(float2(i, j + 7.0))) * L;
            float off = hash(float2(i, j + 13.0)) * (L - len);
            float t = (fract(u) * L - off) / len;
            if (t > 0.0 && t < 1.0) {
                float wig = 0.18 * sin(t * 3.0 + time * 5.0 + r * 20.0);
                // Never thinner than about a pixel, so a shrunken preview's lines don't flicker out between pixels.
                float thick = max(0.55, (0.3 * dp + (0.6 * dp + 0.12 * line) * t * t) * (1.0 - 0.4 * depthT) * (0.6 + 0.4 * spread));
                float dy = abs(yl - wig) * lane;
                float a = smoothstep(thick + 0.7, thick - 0.7, dy) * pow(t, 1.4) * smoothstep(1.0, 0.94, t) * (0.5 + 0.5 * heat) * (0.3 + 0.7 * spread);
                float3 c = mix(float3(shade.rgb), float3(glow.rgb), smoothstep(0.0, 0.5, t));
                c = mix(c, float3(core.rgb), smoothstep(0.7, 0.97, t));
                col = over(float4(c * a, a), col);
            }
        }
    }

    // What the gust carries: one to a cell, tumbling along a little slower than the wind.
    if (carry > 0.0) {
        float C = per / cells(per, 30.0 * dp);
        float cc = per / C;
        float x = s - time * speed * 0.55;
        float ic = floor(x / C);
        for (int k = -1; k <= 1; k++) {
            float iu = ic + float(k);
            float id = mod(iu, cc);
            float r = hash(float2(id, 71.0));
            if (r < carry) {
                float2 at = float2((iu + 0.5) * C, line + (3.0 + 15.0 * hash(float2(id, 73.0))) * dp + 3.0 * dp * sin(time * 2.2 + r * 30.0));
                float2 d = float2(x, n) - at;
                float spin = time * (2.0 + 3.0 * r) + r * 40.0;
                float cs = cos(spin);
                float sn = sin(spin);
                d = float2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
                // Petals and leaves are ovals turning to show their edge; dust is round.
                float flatness = mix(0.45, 0.2, leaves) + 0.5 * abs(sin(spin * 0.7));
                float e = length(d / float2(carrySize, carrySize * flatness));
                float ca = smoothstep(1.0, 0.75, e) * (0.35 + 0.65 * heat);
                float3 c = mix(float3(bloomShade.rgb), float3(bloom.rgb), smoothstep(0.9, 0.2, e) * (0.5 + 0.5 * cs));
                col = over(float4(c * ca, ca), col);
            }
        }
    }
    return half4(col);
}
"""

/**
 * Earth: a crack mended in gold. A jagged seam runs round the frame, lit from inside, with
 * cracks branching in off it as the frame lights (`grow`), each tapering to nothing; a lip of
 * lit stone along every crack, and a sheen of light running along the seam. `jag` is how far the
 * seam zig-zags, `branch` how far in a branch reaches, `crack` the seam's width, all in px.
 */
internal val EARTH_FRAME_AGSL = FRAME_COMMON + """
uniform float jag;
uniform float branch;
uniform float crack;
layout(color) uniform half4 light;
layout(color) uniform half4 stone;
layout(color) uniform half4 core;
layout(color) uniform half4 glow;
layout(color) uniform half4 deep;

// Distance to the segment from a to b, and how far along it the nearest point is.
float2 segment(float2 p, float2 a, float2 b) {
    float2 pa = p - a;
    float2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return float2(length(pa - ba * h), h);
}

half4 main(float2 p) {
    float3 f = frameAt(p);
    float s = f.x;
    float n = f.y;
    float per = f.z;
    float heat = heatAt(s, per);
    float2 at = float2(s, n);

    // The seam: a zig-zag through knots along the frame, its width uneven.
    float kc = cells(per, 7.0 * dp);
    float K = per / kc;
    float ki = floor(s / K);
    float kf = fract(s / K);
    float k0 = mod(ki, kc);
    float k1 = mod(ki + 1.0, kc);
    float rest = 0.5 * crack + 0.8 * dp;
    float centre = rest + jag * mix(hash(float2(k0, 31.0)), hash(float2(k1, 31.0)), kf);
    float w = crack * (0.7 + 0.6 * mix(hash(float2(k0, 37.0)), hash(float2(k1, 37.0)), kf)) * (0.75 + 0.25 * spread);
    float dist = abs(n - centre) - 0.5 * w;

    // Branches: at most one to a cell, three jagged steps in off the seam, tapering.
    float bc = cells(per, 26.0 * dp);
    float B = per / bc;
    float bi = floor(s / B);
    for (int k = -1; k <= 1; k++) {
        float iu = bi + float(k);
        float id = mod(iu, bc);
        if (hash(float2(id, 41.0)) < 0.65) {
            float len = branch * (0.35 + 0.65 * hash(float2(id, 53.0))) * grow;
            if (len > 0.5) {
                float side = hash(float2(id, 47.0)) < 0.5 ? -1.0 : 1.0;
                float th = 0.55 + 0.7 * hash(float2(id, 59.0));
                float2 dir = float2(side * cos(th), sin(th));
                float2 nrm = float2(-dir.y, dir.x);
                float2 a = float2((iu + 0.15 + 0.7 * hash(float2(id, 43.0))) * B, rest + 0.5 * jag);
                float2 b = a + dir * (len / 3.0) + nrm * (hash(float2(id, 79.0)) - 0.5) * 0.5 * len;
                float2 c = b + dir * (len / 3.0) + nrm * (hash(float2(id, 83.0)) - 0.5) * 0.5 * len;
                float2 e = c + dir * (len / 3.0) + nrm * (hash(float2(id, 89.0)) - 0.5) * 0.4 * len;
                float2 d1 = segment(at, a, b);
                float2 d2 = segment(at, b, c);
                float2 d3 = segment(at, c, e);
                float root = 0.75 * w;
                dist = min(dist, d1.x - 0.5 * root * (1.0 - 0.3 * d1.y));
                dist = min(dist, d2.x - 0.5 * root * (0.7 - 0.3 * d2.y));
                dist = min(dist, d3.x - 0.5 * root * (0.4 - 0.35 * d3.y));
            }
        }
    }

    // The sheen: light running along the seam, once round in a few places.
    float sc = cells(per, 120.0 * dp);
    float sheen = pow(0.5 + 0.5 * cos(TAU * (s * sc / per - time * 0.9)), 28.0);

    // The light the cracks throw on the stone round them.
    float halo = peak * spread * exp(-max(dist, 0.0) / max(0.5 * reach, 1.0)) * (0.4 + 0.6 * heat + 0.4 * sheen);
    float4 col = float4(float3(glow.rgb) * halo, halo);

    // A lip of lit stone either side of the crack, catching its light.
    float lip = smoothstep(1.8 * dp, 0.3 * dp, dist) * step(0.0, dist) * 0.55;
    float grain = wnoise(s / (2.0 * dp), n / (2.0 * dp), cells(per, 2.0 * dp));
    col = over(float4(mix(float3(stone.rgb), float3(light.rgb), grain) * lip, lip), col);

    // The crack: white-hot at its heart, gold, deepening at its lips.
    float ca = smoothstep(0.6, -0.6, dist);
    if (ca > 0.0) {
        float inner = clamp(-dist / max(0.5 * w, 0.5), 0.0, 1.0);
        float hotness = clamp(0.35 + 0.5 * heat + 0.7 * sheen, 0.0, 1.0);
        float3 c = mix(float3(deep.rgb), float3(glow.rgb), smoothstep(0.0, 0.6, inner * hotness + 0.3 * hotness));
        c = mix(c, float3(core.rgb), smoothstep(0.55, 1.0, inner * hotness));
        col = over(float4(c * ca, ca), col);
    }
    return half4(col);
}
"""

/** The fuse's tallest tongue, as a share of the fire wave's ([FireFlames.height]). */
private const val FRAME_FLAME = 0.42f

/** Fire: the space between the fuse's tongues, as a share of their length. */
private const val FRAME_COLUMN = 0.8f

/** Water: how deep the liquid stands, past the line's own width. */
private const val WATER_DEPTH_DP = 3f

/** Air: the depth of one wind lane, and how many lanes there are, past the line's own. */
private const val AIR_LANE_DP = 3.2f
private const val AIR_LANES = 6f

/** Air: never shallower lanes than this, so a shrunken preview's lines stay apart. */
private const val AIR_LANE_MIN_PX = 3.5f

/** Air: what the gust carries is this size along the frame, as a share of the wave's. */
private const val AIR_CARRY_SIZE = 0.75f

/** Earth: how far the seam zig-zags, and how far in a branch reaches, at Quake. */
private const val EARTH_JAG_DP = 2.2f
private const val EARTH_BRANCH_DP = 26f

/** The frame's glow is drawn out to this many reaches in, where it has fallen to ~5%. */
private const val GLOW_REACHES = 3f

/** Elements' frames need runtime shaders; below Android 13 the frame plays as Neon. */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
internal val elementFramesSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The element's frame for this effect, or null below Android 13 (no runtime shaders), where the
 * neon frame plays. [size] and [corner] as the neon frame's; [axis] where the heads come home, in
 * px along the top edge from its centre; [mirror] for Twin. [line], [reach], [peak], [lead] and
 * [tail] are the frame's, in px at this [scale]; [density] the screen's.
 */
internal fun elementFrame(
    settings: GlowSettings,
    brand: Color,
    size: Size,
    corner: Float,
    axis: Float,
    mirror: Boolean,
    line: Float,
    reach: Float,
    peak: Float,
    lead: Float,
    tail: Float,
    density: Float,
    scale: Float,
): ElementFrame? {
    if (!elementFramesSupported) return null
    return ElementFrameRuntime(settings, brand, size, corner, axis, mirror, line, reach, peak, lead, tail, density * scale)
}

/** An element's frame the effect moves every frame through [update]; built once per size, never in the draw. */
internal abstract class ElementFrame : ShaderBrush() {
    /** How far in from the screen's edge it can draw anything, in px. */
    abstract val inwardPx: Float

    /** The colour of the glint the heads land in at the camera. */
    abstract val landing: Color

    /**
     * [ms] the effect's clock; [head] where the heads are, 0..1 round the frame from the camera;
     * [focus] how far they have taken over from the evenly lit frame, [spread] Pulse's breath and
     * [grow] how far Earth's cracks have spread, all 0..1.
     */
    abstract fun update(ms: Float, head: Float, focus: Float, spread: Float, grow: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ElementFrameRuntime(
    settings: GlowSettings,
    brand: Color,
    size: Size,
    corner: Float,
    axis: Float,
    mirror: Boolean,
    line: Float,
    reach: Float,
    peak: Float,
    lead: Float,
    tail: Float,
    dp: Float,
) : ElementFrame() {
    private val perimeter = edgePerimeter(size, corner)
    private val runtime: RuntimeShader
    override val inwardPx: Float
    override val landing: Color

    init {
        val glow = reach * GLOW_REACHES
        when (settings.element) {
            SpawnElement.FIRE -> {
                val palette = firePalette(settings.fireColor, brand)
                val flame = settings.fireFlames.height.value * dp * FRAME_FLAME
                runtime = RuntimeShader(FIRE_FRAME_AGSL).apply {
                    setFloatUniform("flame", flame)
                    setFloatUniform("column", FRAME_COLUMN * flame)
                    setFloatUniform("warp", settings.fireFlames.warp)
                    setFloatUniform("rise", settings.fireFlames.rise)
                    // Sparks thin enough to read as sparks along a line; none stays none.
                    setFloatUniform("sparks", settings.fireSparks.density * 1.4f)
                    setColorUniform("core", palette.core.toArgb())
                    setColorUniform("hot", palette.hot.toArgb())
                    setColorUniform("body", palette.body.toArgb())
                    setColorUniform("flare", palette.flare.toArgb())
                    setColorUniform("tip", palette.tip.toArgb())
                    setColorUniform("ember", palette.ember.toArgb())
                }
                inwardPx = max(line + 2f * flame, glow)
                landing = palette.hot
            }
            SpawnElement.WATER -> {
                val depth = line + WATER_DEPTH_DP * dp
                runtime = RuntimeShader(WATER_FRAME_AGSL).apply {
                    setFloatUniform("depth", depth)
                    setColorUniform("tint", brand.toArgb())
                }
                // At rest, plus its waves and the surge at the head.
                inwardPx = depth + 9f * dp + glow
                landing = brand
            }
            SpawnElement.AIR -> {
                val palette = airPalette(settings.airColor, brand)
                val lane = max(AIR_LANE_DP * dp + 0.3f * line, AIR_LANE_MIN_PX)
                val carry = settings.airCarry
                runtime = RuntimeShader(AIR_FRAME_AGSL).apply {
                    setFloatUniform("lane", lane)
                    setFloatUniform("lanes", AIR_LANES)
                    setFloatUniform("streak", settings.airGust.length.value * dp)
                    setFloatUniform("speed", 260f * dp * settings.airGust.pace)
                    setFloatUniform("carry", carry.density)
                    setFloatUniform("carrySize", max(1f, carry.size.value * dp * AIR_CARRY_SIZE))
                    setFloatUniform("leaves", if (carry == AirCarry.LEAVES) 1f else 0f)
                    setColorUniform("core", palette.core.toArgb())
                    setColorUniform("body", palette.body.toArgb())
                    setColorUniform("glow", palette.glow.toArgb())
                    setColorUniform("shade", palette.shade.toArgb())
                    val (bloom, bloomShade) = if (carry == AirCarry.LEAVES) {
                        palette.leaf to palette.leafShade
                    } else {
                        palette.petal to palette.petalShade
                    }
                    setColorUniform("bloom", bloom.toArgb())
                    setColorUniform("bloomShade", bloomShade.toArgb())
                }
                val carried = if (carry.density > 0f) line + 22f * dp + carry.size.value * dp else 0f
                inwardPx = maxOf(lane * AIR_LANES, carried, glow)
                landing = palette.glow
            }
            SpawnElement.EARTH -> {
                val palette = earthPalette(settings.earthColor, brand)
                // The seam is as wide as the line; Force sets how far it zig-zags and branches.
                val force = settings.earthForce.crack.value / EarthForce.QUAKE.crack.value
                val jag = EARTH_JAG_DP * dp * force
                val branch = EARTH_BRANCH_DP * dp * force
                runtime = RuntimeShader(EARTH_FRAME_AGSL).apply {
                    setFloatUniform("jag", jag)
                    setFloatUniform("branch", branch)
                    setFloatUniform("crack", max(line, 1.2f * dp))
                    setColorUniform("light", palette.light.toArgb())
                    setColorUniform("stone", palette.stone.toArgb())
                    setColorUniform("core", palette.core.toArgb())
                    setColorUniform("glow", palette.glow.toArgb())
                    setColorUniform("deep", palette.deep.toArgb())
                }
                inwardPx = line + jag + branch + 2f * dp + 0.5f * glow
                landing = palette.glow
            }
        }
        runtime.setFloatUniform("size", size.width, size.height)
        runtime.setFloatUniform("corner", corner)
        runtime.setFloatUniform("axis", axis)
        runtime.setFloatUniform("mirror", if (mirror) 1f else 0f)
        runtime.setFloatUniform("lead", lead)
        runtime.setFloatUniform("tail", tail)
        runtime.setFloatUniform("dp", dp)
        runtime.setFloatUniform("line", line)
        runtime.setFloatUniform("reach", reach)
        runtime.setFloatUniform("peak", peak)
        update(0f, 0f, 0f, 1f, 0f)
    }

    override fun createShader(size: Size): Shader = runtime

    override fun update(ms: Float, head: Float, focus: Float, spread: Float, grow: Float) {
        runtime.setFloatUniform("time", ms / 1000f)
        runtime.setFloatUniform("head", head * perimeter)
        runtime.setFloatUniform("focus", focus)
        runtime.setFloatUniform("spread", spread)
        runtime.setFloatUniform("grow", grow)
    }
}
