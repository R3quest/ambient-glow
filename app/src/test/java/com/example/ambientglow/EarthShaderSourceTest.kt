package com.example.ambientglow

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The earth shader only compiles on a device, where a bad name throws at the first draw; here
 * what can be checked without one: no name it declares is one AGSL reserves.
 */
class EarthShaderSourceTest {

    /** Words AGSL (SkSL) reserves: qualifiers, and names kept back from GLSL. */
    private val reserved = setOf(
        "in", "out", "inout", "uniform", "const", "flat", "noperspective", "inline", "noinline",
        "sk_FragCoord", "cast", "sample", "filter", "input", "output", "sizeof", "namespace", "using",
        "class", "union", "enum", "typedef", "template", "this", "packed", "resource", "goto",
        "volatile", "public", "static", "extern", "external", "attribute", "varying", "precision",
        "invariant", "asm", "fixed", "long", "short", "double", "unsigned", "superp", "common",
        "partition", "active", "highp", "mediump", "lowp", "lowp", "buffer", "shared", "coherent",
        "restrict", "readonly", "writeonly", "subroutine", "layout", "centroid", "patch", "smooth",
    )

    @Test
    fun noDeclaredNameIsReserved() {
        val types = "float|float2|float3|float4|half|half2|half3|half4|int|bool|Stone"
        val declared = Regex("\\b(?:$types)\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(EARTH_AGSL).map { it.groupValues[1] }.toSet()
        assertTrue("found no declarations to check", declared.size > 20)
        val clashes = declared.filter { it in reserved }
        assertTrue("reserved names declared: $clashes", clashes.isEmpty())
    }
}
