package com.example.ambientglow

/** Hermite ease from 0 at [e0] to 1 at [e1]. */
internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Where the rising [f] reaches [target] between [lo] and [hi], by [steps] halvings. For lookups
 * made once per layout or gesture, never per frame.
 */
internal inline fun solveRising(target: Float, lo: Float, hi: Float, steps: Int, f: (Float) -> Float): Float {
    var below = lo
    var above = hi
    repeat(steps) {
        val mid = (below + above) / 2f
        if (f(mid) < target) below = mid else above = mid
    }
    return above
}
