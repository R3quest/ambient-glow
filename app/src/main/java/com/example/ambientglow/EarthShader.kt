package com.example.ambientglow

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// The earth wave as breaking ground (Android 13+): a shock ring running out of the camera, the
// ground behind it splitting in cracks of light, and what it breaks into ([EarthForm]): only the
// cracks, slabs of stone heaving up, or spires of rock bursting out; and what it throws up
// ([EarthDebris]). One pass of one shader per frame, over the ring the quake is in; below
// Android 13 the effect draws gradients instead.
// ---------------------------------------------------------------------------------------------

/**
 * The ground is a mosaic of stones fixed on the screen, as ground is: a jittered grid of `cell`
 * px, each stone the cell nearest its site (Voronoi), its borders the cracks. The front reaches a
 * stone when its radius passes the stone's site, give or take, and the stone keeps its own clock
 * from then, in px the front has run on (`age`): it pops up over `riseLen` with an overshoot, as
 * animation snaps a thing into place, holds for `hold`, and crumbles over `sink`.
 *
 * Drawn the way animation draws stone, lit the way stone is: flat cel-shaded faces, each stone
 * tilted its own way so the mosaic catches the light unevenly, a bevel lit on the side facing the
 * light (from the top left, as on a page) and in shade on the other, and a dark inked lip along
 * every crack. The light comes from below: white-hot in the cracks where the front has just
 * passed, cooling behind it, catching the stones' rims as they break out.
 *
 * Layers, bottom up: the cracks and their light; the slabs or the spires; the smoke kicked up
 * behind the front, billowing and thinning into wisps; the shock ring; and the rubble thrown
 * up, tumbling. All are premultiplied and never brighter than their cover.
 */
private val EARTH_AGSL = """
uniform float2 origin;
uniform float radius;
uniform float energy;
uniform float time;
uniform float dp;
uniform float cell;
uniform float crack;
uniform float form;
uniform float crystal;
uniform float riseLen;
uniform float hold;
uniform float sink;
uniform float heatReach;
uniform float dust;
uniform float puff;
uniform float rubble;
uniform float rubbleSize;
uniform float cols;
uniform float row;
uniform float fall;
layout(color) uniform half4 light;
layout(color) uniform half4 stone;
layout(color) uniform half4 shade;
layout(color) uniform half4 core;
layout(color) uniform half4 glow;
layout(color) uniform half4 deep;
layout(color) uniform half4 dustLit;
layout(color) uniform half4 dustShade;

const float TAU = 6.2831853;
// Towards the light: the top left, as on a page.
const float2 SUN = float2(-0.6, -0.8);
// The share of stones a spire bursts out of, and of rubble cells that hold a chunk.
const float SPIKE_SHARE = $EARTH_SPIKE_SHARE;
const float RUBBLE_SHARE = $EARTH_RUBBLE_SHARE;
// Rubble rides from this share of the front's radius out to the front.
const float DEBRIS_FROM = $EARTH_DEBRIS_FROM;
// A slab's bevel, as a share of a stone; Faults' main faults run this many stones apart.
const float BEVEL = 0.09;
const float FAULT_SCALE = 2.6;
// How dark the lip along a crack is, how bright the shock ring is and how thick the smoke at most.
const float LIP = 0.6;
const float SHOCK = 0.85;
const float SMOKE_ALPHA = 0.74;
// The smoke thins behind the front over this many puffs, and has risen this share of how far
// behind it is.
const float SMOKE_REACH = 2.2;
const float SMOKE_RISE = 0.12;

// Hoskins' hashes: no period on screen.
float hash(float2 p) {
    float3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float2 hash2(float2 p) {
    float3 p3 = fract(p.xyx * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
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

// 0 to 1 by way of 1.1: a stone snapping up out of the ground and settling.
float pop(float t) {
    float u = clamp(t, 0.0, 1.0) - 1.0;
    return 1.0 + u * u * (2.70158 * u + 1.70158);
}

// Stone id's site, in cells: jittered, but never near its cell's edge, so a spire from it never
// reaches past the next cell.
float2 site(float2 id) {
    return id + 0.2 + 0.6 * hash2(id);
}

// Fractal noise: four octaves, each half as strong and twice as fine; about 0..1.
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + 17.1;
        a *= 0.5;
    }
    return v / 0.9375;
}

// How dense the smoke is at xy: kicked up where the front passes, a rolling wall just behind it
// that lingers and thins into wisps further back. Its billows sit on the screen, as smoke stays
// where it was raised, churning (warped by slower noise) and drifting up; the longer they have
// been up, the higher they have risen.
float smokeAt(float2 xy) {
    float h = radius - length(xy - origin);
    float wall = smoothstep(-0.5 * puff, 0.6 * puff, h) * exp(-max(h - 0.6 * puff, 0.0) / (SMOKE_REACH * puff));
    if (wall < 0.08) return 0.0;
    float2 q = (xy + float2(0.0, SMOKE_RISE * max(h, 0.0))) / (1.25 * puff);
    float2 w = float2(noise(q * 0.55 + float2(0.0, time * 0.6)), noise(q * 0.55 + float2(5.2, 1.3 - time * 0.5)));
    return wall * 1.6 * fbm(q + 1.8 * w + float2(0.0, time * 1.1));
}

// The rubble at xy: one chunk in a cell at most, kept clear of the cell's edges so no neighbour
// needs looking at. The cells ride out with the front at their share of its radius, so the
// chunks spread as it does, thrown up and falling back (`fall`). A chunk is an irregular hexagon
// tumbling as it flies: a flat top face, and six facets round it, lit as they face the light.
float4 rubbleAt(float2 xy) {
    float2 p = xy - origin - float2(0.0, fall);
    float rp = length(p);
    float f = rp / max(radius, 1.0);
    if (f < DEBRIS_FROM || f >= 1.0) return float4(0.0);
    float cu = atan(p.x, p.y) / TAU * cols;
    float cv = (f - DEBRIS_FROM) / row;
    float2 rc = float2(mod(floor(cu), cols), floor(cv));
    float hs = hash(rc + 41.0);
    if (hs >= RUBBLE_SHARE) return float4(0.0);
    float2 c = float2(0.35 + 0.3 * hash(rc + 3.3), 0.35 + 0.3 * hash(rc + 8.1));
    float2 l0 = float2((fract(cu) - c.x) * TAU / cols * rp, (fract(cv) - c.y) * row * radius);
    // Round the ring and out along it, to the screen's own frame, to light it.
    float2 away = p / max(rp, 1.0);
    float2 l = l0.x * float2(away.y, -away.x) + l0.y * away;
    // Smaller as it settles, so the last of it lands rather than vanishing.
    float sz = rubbleSize * (0.6 + 0.8 * hash(rc + 5.9)) * (0.55 + 0.45 * rubble);
    float ang = hash(rc + 1.7) * TAU + time * (3.0 + 5.0 * hash(rc + 2.3)) * (hs < 0.5 * RUBBLE_SHARE ? 1.0 : -1.0);
    float ca = cos(ang);
    float sa = sin(ang);
    float2 k = float2(ca * l.x - sa * l.y, sa * l.x + ca * l.y);
    float2 n1 = float2(1.0, 0.0);
    float2 n2 = float2(0.5, 0.866);
    float2 n3 = float2(-0.5, 0.866);
    float s1 = dot(k, n1) / (sz * (0.75 + 0.5 * hash(rc + 6.1)));
    float s2 = dot(k, n2) / (sz * (0.75 + 0.5 * hash(rc + 6.7)));
    float s3 = dot(k, n3) / (sz * (0.75 + 0.5 * hash(rc + 7.3)));
    float m = max(abs(s1), max(abs(s2), abs(s3)));
    float cov = clamp(0.5 - (m - 1.0) * sz, 0.0, 1.0);
    if (cov <= 0.0) return float4(0.0);
    float2 nf = abs(s1) >= m ? n1 * sign(s1) : (abs(s2) >= m ? n2 * sign(s2) : n3 * sign(s3));
    float2 ns = float2(ca * nf.x + sa * nf.y, -sa * nf.x + ca * nf.y);
    float3 side = mix(float3(shade.rgb), float3(light.rgb), smoothstep(-0.25, 0.25, dot(ns, SUN)));
    float3 rock = mix(side, float3(stone.rgb), 1.0 - smoothstep(0.5, 0.6, m));
    // The facets turned back to the cracks catch their light, while it lasts.
    rock = mix(rock, float3(glow.rgb), 0.55 * energy * smoothstep(0.3, 0.8, -dot(ns, away)) * step(0.6, m));
    // An inked outline.
    rock *= 1.0 - 0.4 * smoothstep(1.0 - 1.3 * dp / sz, 1.0, m);
    float a = 0.95 * cov * min(1.0, 1.6 * rubble);
    return float4(rock * a, a);
}

// The stone round xy, of stones `size` px across: how far the nearest border is (px), a hash for
// the two stones either side of it (the same from both, so the crack between them agrees), which
// stone it is, and the way out of it across that border.
struct Stone {
    float edge;
    float pair;
    float2 id;
    float2 norm;
};

Stone stoneAt(float2 xy, float size) {
    float2 p = xy / size;
    // A little warped, so cracks kink as stone breaks instead of running ruler-straight.
    p += 0.14 * (float2(noise(p * 1.9 + 5.2), noise(p * 1.9 + 9.4)) - 0.5);
    float2 ip = floor(p);
    float2 fp = p - ip;
    // The nearest site...
    float best = 8.0;
    float2 mr = float2(0.0);
    float2 mg = float2(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 g = float2(float(i), float(j));
            float2 rr = site(ip + g) - ip - fp;
            float dd = dot(rr, rr);
            if (dd < best) {
                best = dd;
                mr = rr;
                mg = g;
            }
        }
    }
    // ...then the nearest border, on the bisector with a neighbour.
    float edge = 8.0;
    float2 en = float2(0.0, 1.0);
    float2 ng = mg;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 g = mg + float2(float(i), float(j));
            float2 rr = site(ip + g) - ip - fp;
            float2 dv = rr - mr;
            float l2 = dot(dv, dv);
            if (l2 > 0.0001) {
                float2 n = dv * inversesqrt(l2);
                float e = dot(0.5 * (mr + rr), n);
                if (e < edge) {
                    edge = e;
                    en = n;
                    ng = g;
                }
            }
        }
    }
    float2 id = ip + mg;
    float2 nid = ip + ng;
    return Stone(edge * size, hash(id + nid + 0.37 * abs(id - nid)), id, en);
}

// A crack `ep` px off the pixel, `w` px either side of its line, at strength `a`: a dark lip
// either side (`lip` of it), the broken edge in shadow as animation inks a crack; then its light,
// pulses running out along it, white-hot in its middle at the front and cooling behind it.
float4 crackAt(float4 col, float ep, float w, float lip, float a, float back, float heat) {
    if (a <= 0.0) return col;
    if (lip > 0.0) {
        float la = a * lip * LIP * min(1.0, 2.0 * heat) * clamp(w + 1.5 * dp - ep + 0.5, 0.0, 1.0);
        col = over(float4(float3(shade.rgb) * 0.3 * la, la), col);
    }
    float throb = 0.82 + 0.18 * sin(back / (0.45 * cell) + time * 14.0);
    float c = clamp(w - ep + 0.5, 0.0, 1.0);
    float g = 0.55 * exp(-ep / (3.0 * dp + w));
    float3 lc = mix(float3(deep.rgb), float3(glow.rgb), smoothstep(0.12, 0.55, heat));
    lc = mix(lc, float3(core.rgb), smoothstep(0.5, 0.95, heat) * c);
    float ca = a * heat * throb * min(1.0, c + g);
    return over(float4(lc * ca, ca), col);
}

half4 main(float2 xy) {
    float2 d = xy - origin;
    float r = length(d);
    float x0 = r - radius;
    // The front, ragged as breaking ground: the ground's grain lets it in early here, late there.
    float xe = x0 + 0.55 * cell * (noise(xy / (1.4 * cell)) - 0.5);
    float back = -xe;
    // How hot the light in the cracks still is: white at the front, cooling behind it.
    float heat = exp(-max(back, 0.0) / heatReach);
    float4 col = float4(0.0);

    if (energy > 0.0 && back > -2.0 * dp) {
        Stone st = stoneAt(xy, cell);
        float open = energy * smoothstep(-2.0 * dp, 1.0 * dp, back);
        if (heat > 0.02) {
            // Each crack its own width, splitting open as the front runs on from it.
            float split = 0.35 + 0.65 * smoothstep(0.0, 0.5 * cell, back);
            float w = crack * (0.6 + 0.8 * hash(float2(st.pair, 3.1))) * split;
            if (form < 0.5) {
                // Faults, as ground breaks: hairlines between the stones here and there, and main
                // faults through the ground a few stones apart, wide, bright and inked.
                col = crackAt(col, st.edge, 0.5 * w, 0.5, 0.75 * open * step(st.pair, 0.55), back, heat);
                Stone big = stoneAt(xy + 31.7 * cell, FAULT_SCALE * cell);
                float wb = crack * (1.0 + 0.7 * hash(float2(big.pair, 3.1))) * split;
                col = crackAt(col, big.edge, wb, 1.0, open, back, heat);
            } else if (form < 1.5) {
                // Under slabs the cracks are the gaps between them: no lip, their light fills them.
                col = crackAt(col, st.edge, 1.7 * w, 0.0, open, back, heat);
            } else {
                col = crackAt(col, st.edge, w, 1.0, open, back, heat);
            }
        }

        // The slabs: each stone pops up out of its cracks, holds, then crumbles in on itself.
        float2 s = site(st.id);
        float age = radius - length(s * cell - origin) + 0.4 * cell * (hash(st.id + 3.7) - 0.5);
        if (form > 0.5 && form < 1.5 && age > 0.0) {
            float down = 1.0 - smoothstep(hold, hold + sink, age);
            if (down > 0.0) {
                float up = pop(age / riseLen);
                float base = 1.4 * crack + 1.2 * dp;
                float ins = max(0.5 * base, base + 0.45 * cell * (1.0 - up)) + 0.3 * cell * (1.0 - down);
                float pd = st.edge - ins;
                float cov = clamp(pd + 0.5, 0.0, 1.0);
                if (cov > 0.0) {
                    // Each tilted its own way, so the faces catch the light unevenly.
                    float face = clamp(0.5 + 1.1 * dot(hash2(st.id + 11.0) - 0.5, SUN), 0.0, 1.0);
                    float grain = 0.92 + 0.16 * noise(xy / (2.4 * dp));
                    float3 top = mix(float3(stone.rgb), float3(light.rgb), 0.55 * face) * mix(0.85, 1.0, face) * grain;
                    // Crystal: a facet line through each, one half catching the light.
                    float split = dot(xy / cell - s, float2(-SUN.y, SUN.x));
                    top *= 1.0 + crystal * (0.22 * smoothstep(-0.02, 0.02, split) - 0.08);
                    float bev = 1.0 - clamp(pd - BEVEL * cell + 0.5, 0.0, 1.0);
                    float3 rim = mix(float3(shade.rgb), float3(light.rgb), smoothstep(-0.15, 0.15, dot(st.norm, SUN)));
                    float3 pc = mix(top, rim, bev);
                    // The cracks' light catching its rim from below, and the whole of it as it breaks out.
                    float flare = heat * exp(-age / (0.8 * cell));
                    pc = mix(pc, float3(glow.rgb), min(1.0, bev * (0.2 + 0.6 * smoothstep(0.05, 0.6, heat)) + 0.3 * flare));
                    float pa = cov * energy * min(1.0, 2.5 * down) * (1.0 - 0.15 * crystal);
                    col = over(float4(pc * pa, pa), col);
                }
            }
        }
    }

    // The spires: out of some stones a shard of rock bursts, pointing away from the camera, a
    // little askew; two faces either side of its spine, lit and in shade.
    if (form > 1.5 && energy > 0.0 && x0 < 1.3 * cell) {
        float2 q = xy / cell;
        float2 iq = floor(q);
        float bd = 100000.0;
        float bb = 0.0;
        float ba = 0.0;
        float bl = 1.0;
        float bdown = 0.0;
        float bface = 0.0;
        float bage = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float2 id = iq + float2(float(i), float(j));
                float2 h = hash2(id + 23.1);
                if (h.x < SPIKE_SHARE) {
                    float2 c = site(id) * cell;
                    float2 v = c - origin;
                    float dc = length(v);
                    float age = radius - dc + 0.4 * cell * (h.y - 0.5);
                    float down = 1.0 - smoothstep(hold, hold + sink, age);
                    if (age > 0.0 && down > 0.0) {
                        float t = (h.y - 0.5) * 0.8;
                        float2 away = v / max(dc, 1.0);
                        float2 dir = float2(away.x * cos(t) - away.y * sin(t), away.x * sin(t) + away.y * cos(t));
                        float2 perp = float2(-dir.y, dir.x);
                        float up = pop(age / riseLen);
                        // At most a stone long, so it never reaches past the next cell.
                        float L = cell * (0.6 + 0.35 * h.x / SPIKE_SHARE) * up * (0.35 + 0.65 * down);
                        float W = 0.38 * cell * min(1.0, up) * (0.55 + 0.45 * down);
                        float K = 0.55 * W;
                        float2 l = xy - c;
                        float a = dot(l, dir);
                        float b = dot(l, perp);
                        float ab = abs(b);
                        // A kite: a short back, widest at its root, a long point.
                        float sd = max((ab * L + a * W - L * W) * inversesqrt(L * L + W * W),
                                       (ab * K - a * W - K * W) * inversesqrt(K * K + W * W));
                        if (sd < bd) {
                            bd = sd;
                            bb = b;
                            ba = a;
                            bl = L;
                            bdown = down;
                            bface = dot(perp, SUN);
                            bage = age;
                        }
                    }
                }
            }
        }
        float cov = clamp(0.5 - bd, 0.0, 1.0);
        if (cov > 0.0) {
            float lit = smoothstep(-0.1, 0.1, bface * clamp(bb / 0.75, -1.0, 1.0));
            float3 fc = mix(float3(shade.rgb), mix(float3(stone.rgb), float3(light.rgb), 0.75), lit);
            // Lighter towards the tip, rising into the light.
            fc *= 0.9 + 0.2 * clamp(ba / max(bl, 1.0), 0.0, 1.0);
            // The spine: a hairline of light where the faces meet; on crystal, a white glint.
            float spine = (1.0 - smoothstep(0.4 * dp, 1.2 * dp, abs(bb))) * step(0.0, ba);
            fc = mix(fc, mix(float3(light.rgb), float3(core.rgb), crystal), (0.5 + 0.3 * crystal) * spine);
            // The cracks' light at its root, where it broke out of the ground.
            float flare = heat * exp(-bage / (0.8 * cell));
            float root = 1.0 - smoothstep(-0.2 * bl, 0.45 * bl, ba);
            fc = mix(fc, float3(glow.rgb), min(1.0, 0.6 * root * (0.3 + flare)));
            // An inked outline.
            fc *= 1.0 - 0.4 * smoothstep(-1.6 * dp, -0.5 * dp, bd);
            float sa = cov * energy * min(1.0, 2.5 * bdown) * (1.0 - 0.12 * crystal);
            col = over(float4(fc * sa, sa), col);
        }
    }

    // The smoke: soft billows with no outline, lit on the side towards the light (it is thinner
    // there than a step towards it), their undersides catching the cracks' light near the front.
    // As it settles it thins from its edges in, rather than fading as a whole.
    if (dust > 0.0 && puff > 0.0 && x0 > -5.2 * puff && x0 < 0.6 * puff) {
        float den = smokeAt(xy);
        float lo = 0.24 + 0.36 * (1.0 - dust);
        if (den > lo) {
            float lit = clamp(0.55 + 2.4 * (den - smokeAt(xy + SUN * 0.4 * puff)), 0.0, 1.0);
            float3 sc = mix(float3(dustShade.rgb), float3(dustLit.rgb), lit) * (0.88 + 0.12 * smoothstep(lo, lo + 0.5, den));
            float under = energy * exp(-max(-x0, 0.0) / (0.9 * puff)) * (1.0 - lit);
            sc = mix(sc, float3(glow.rgb), 0.5 * under);
            float sa = SMOKE_ALPHA * smoothstep(lo, lo + 0.4, den) * min(1.0, 2.0 * dust);
            col = over(float4(sc * sa, sa), col);
        }
    }

    // The shock ring: round, not ragged, a thin line of light just ahead of the breaking ground.
    if (energy > 0.0 && x0 > -16.0 * dp && x0 < 16.0 * dp) {
        float c = exp(-0.5 * x0 * x0 / (2.0 * dp * dp));
        float g = (x0 > 0.0 ? exp(-x0 / (3.0 * dp)) : exp(x0 / (10.0 * dp))) * 0.4;
        float la = energy * SHOCK * min(1.0, c + g);
        float3 lc = (float3(core.rgb) * c + float3(glow.rgb) * g) / max(c + g, 0.001);
        col = over(float4(lc * la, la), col);
    }

    if (rubble > 0.0 && rubbleSize > 0.0) col = over(rubbleAt(xy), col);

    return half4(min(col.rgb, float3(col.a)), col.a);
}
"""

/** Earth (shader): the share of stones a spire bursts out of. */
internal const val EARTH_SPIKE_SHARE = 0.55f

/** Earth (shader): the share of rubble cells that hold a chunk. */
internal const val EARTH_RUBBLE_SHARE = 0.32f

/** Earth (shader): rubble rides from this share of the front's radius out to it. */
internal const val EARTH_DEBRIS_FROM = 0.72f

/** Earth: a stone pops up over this many stones' width behind the front, holds for the next, and crumbles over the last. */
private const val EARTH_RISE = 0.6f
private const val EARTH_HOLD = 2.4f
private const val EARTH_SINK = 1.8f

/** Slabs cover what is under them, so theirs is a shorter band: they hold and crumble sooner. */
private const val SLAB_HOLD = 1.3f
private const val SLAB_SINK = 1.3f

/** Earth: the cracks' light cools over this many stones behind the front; faults, the whole show, glow on longer and wider. */
private const val EARTH_HEAT = 1.2f
private const val FAULT_HEAT = 2f
private const val FAULT_WIDEN = 1.3f

/** Earth: the smoke's billows are about this big, as a share of a stone. */
private const val PUFF_SHARE = 0.9f

/** Earth: a chunk of rubble's size, as a share of a stone. */
private const val RUBBLE_SIZE_SHARE = 0.26f

/**
 * Earth (shader): the cells rubble rides in, this many round the ring and this many rows deep (a
 * whole number, so no row is cut by the front); a chunk is at most [RUBBLE_CELL_SHARE] of one.
 */
private const val RUBBLE_COLS = 48f
private const val RUBBLE_ROWS = 3f
private const val RUBBLE_CELL_SHARE = 0.2f

/** Never finer than this many px, so the shrunken preview's stones and rubble don't vanish between pixels. */
private const val CELL_MIN_PX = 10f
private const val RUBBLE_MIN_PX = 2f

/**
 * The earth's colours: [light], [stone] and [shade] its lit faces, tops and faces in shade; [core]
 * white-hot in the cracks at the front, [glow] their light, [deep] where it cools; [dustLit] and
 * [dustShade] the smoke where it is lit and in shade. Built once per effect.
 */
@Immutable
internal data class EarthPalette(
    val light: Color,
    val stone: Color,
    val shade: Color,
    val core: Color,
    val glow: Color,
    val deep: Color,
    val dustLit: Color,
    val dustShade: Color,
)

/** Grey stone with golden light in its cracks, like pottery mended with gold; smoke the colour of dry earth. */
private val NaturalEarth = EarthPalette(
    light = Color(0xFFCBC4B8),
    stone = Color(0xFF8E877D),
    shade = Color(0xFF4A443E),
    core = Color(0xFFFFF8E1),
    glow = Color(0xFFFFC857),
    deep = Color(0xFFD9822B),
    dustLit = Color(0xFFDDD3C4),
    dustShade = Color(0xFF6E6457),
)

/** Brand colours with less chroma than this have no hue to speak of: their light stays as pale as they are. */
private const val GREY_CHROMA = 0.04f

/** The least chroma an app's light or crystal shows, so a muted brand still shows its colour. */
private const val EARTH_CHROMA = 0.12f

/**
 * The earth for [mode] in a message from [brand]. App keeps the natural stone and lights its
 * cracks in the brand; Crystal turns the stone itself into the brand, a geode broken open. Both
 * pick their colours in OKLCh, so every app's steps down in lightness the same way.
 */
internal fun earthPalette(mode: EarthColor, brand: Color): EarthPalette {
    val (_, c, h) = toOklch(brand)
    val chroma = if (c < GREY_CHROMA) c else max(c, EARTH_CHROMA)
    return when (mode) {
        EarthColor.STONE -> NaturalEarth
        EarthColor.APP -> NaturalEarth.copy(
            core = oklch(0.98f, 0.03f, h),
            glow = oklch(0.82f, chroma, h),
            deep = oklch(0.62f, chroma, h),
        )
        EarthColor.CRYSTAL -> EarthPalette(
            light = oklch(0.9f, 0.45f * chroma, h),
            stone = oklch(0.72f, chroma, h),
            shade = oklch(0.48f, 0.85f * chroma, h),
            core = oklch(0.985f, 0.02f, h),
            glow = oklch(0.8f, chroma, h),
            deep = oklch(0.6f, chroma, h),
            dustLit = oklch(0.88f, 0.2f * chroma, h),
            dustShade = oklch(0.6f, 0.35f * chroma, h),
        )
    }
}

/** The earth's material for this effect, or null below Android 13 (no runtime shaders). */
internal fun earthShader(
    origin: Offset,
    palette: EarthPalette,
    force: EarthForce,
    form: EarthForm,
    debris: EarthDebris,
    crystal: Boolean,
    density: Float,
    scale: Float,
): EarthShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return EarthRuntime(origin, palette, force, form, debris, crystal, density * scale)
}

/** A quake the effect moves every frame through [update]; built once per size, never in the draw. */
internal abstract class EarthShader {
    /** The shader itself, for a platform paint that draws it as a ring band. */
    abstract val runtime: android.graphics.Shader

    /** How far behind and ahead of the front the ground, the dust and the shock can show, in px. */
    abstract val behindPx: Float
    abstract val aheadPx: Float

    /** How big the rubble is now, in px, and how far it has been thrown up (below 0) or fallen. */
    abstract val rubbleSizePx: Float
    abstract val liftPx: Float

    /**
     * [radius] the front's in px, [energy] the light and the stone, [debris] the share of what is
     * thrown up still in the air, both 0..1; [ms] the effect's clock.
     */
    abstract fun update(radius: Float, energy: Float, debris: Float, ms: Float)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class EarthRuntime(
    origin: Offset,
    palette: EarthPalette,
    force: EarthForce,
    form: EarthForm,
    debris: EarthDebris,
    crystal: Boolean,
    private val dp: Float,
) : EarthShader() {
    private val cell = max(force.cell.value * dp, CELL_MIN_PX)
    private val faults = form == EarthForm.FAULTS
    private val slabs = form == EarthForm.SLABS
    private val hold = (if (slabs) SLAB_HOLD else EARTH_HOLD) * cell
    private val sink = (if (slabs) SLAB_SINK else EARTH_SINK) * cell
    private val heatReach = (if (faults) FAULT_HEAT else EARTH_HEAT) * cell
    private val puff = if (debris == EarthDebris.DUST) PUFF_SHARE * cell else 0f
    private val chunk = if (debris == EarthDebris.RUBBLE) max(RUBBLE_SIZE_SHARE * cell, RUBBLE_MIN_PX) else 0f

    /** A rubble cell's smaller side, as a share of the front's radius: across, at the back of the band, or deep. */
    private val rubbleCell = min((1f - EARTH_DEBRIS_FROM) / RUBBLE_ROWS, 2f * PI.toFloat() / RUBBLE_COLS * EARTH_DEBRIS_FROM)

    override val runtime = RuntimeShader(EARTH_AGSL).apply {
        setFloatUniform("origin", origin.x, origin.y)
        setFloatUniform("dp", dp)
        setFloatUniform("cell", cell)
        setFloatUniform("crack", force.crack.value * dp * (if (faults) FAULT_WIDEN else 1f))
        setFloatUniform("form", form.ordinal.toFloat())
        setFloatUniform("crystal", if (crystal) 1f else 0f)
        setFloatUniform("riseLen", EARTH_RISE * cell)
        setFloatUniform("hold", hold)
        setFloatUniform("sink", sink)
        setFloatUniform("heatReach", heatReach)
        setFloatUniform("puff", puff)
        setFloatUniform("cols", RUBBLE_COLS)
        setFloatUniform("row", (1f - EARTH_DEBRIS_FROM) / RUBBLE_ROWS)
        setColorUniform("light", palette.light.toArgb())
        setColorUniform("stone", palette.stone.toArgb())
        setColorUniform("shade", palette.shade.toArgb())
        setColorUniform("core", palette.core.toArgb())
        setColorUniform("glow", palette.glow.toArgb())
        setColorUniform("deep", palette.deep.toArgb())
        setColorUniform("dustLit", palette.dustLit.toArgb())
        setColorUniform("dustShade", palette.dustShade.toArgb())
    }

    // Stones keep up to a stone of their own behind their sites, and a site lags the front by up
    // to its jitter and the front's grain; the cracks' light is cut off where it has cooled to 3% (e^-3.5).
    override val behindPx: Float = maxOf(hold + sink + 1.6f * cell, 3.5f * heatReach + 0.3f * cell, 5.3f * puff) + 2f

    // A spire reaches up to a stone ahead of its site, which the front's grain can reach early.
    override val aheadPx: Float = 1.3f * cell + 0.8f * puff + 16f * dp + 2f

    override var rubbleSizePx = 0f
        private set
    override var liftPx = 0f
        private set

    override fun update(radius: Float, energy: Float, debris: Float, ms: Float) {
        runtime.setFloatUniform("radius", radius)
        runtime.setFloatUniform("energy", energy)
        runtime.setFloatUniform("time", ms / 1000f)
        // The smoke rises once the front is out of the flash, which would hide it anyway.
        if (puff > 0f) runtime.setFloatUniform("dust", debris * smoothstep(30f * dp, 110f * dp, radius))
        if (chunk > 0f) {
            // Chunks show once the ring is wide enough to hold them, growing in out of the camera.
            rubbleSizePx = min(chunk, RUBBLE_CELL_SHARE * rubbleCell * radius)
            liftPx = rubbleLiftAt(ms) * dp
            runtime.setFloatUniform("rubble", debris * smoothstep(50f * dp, 140f * dp, radius))
            runtime.setFloatUniform("rubbleSize", rubbleSizePx)
            runtime.setFloatUniform("fall", liftPx)
        }
    }
}
