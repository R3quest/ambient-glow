package com.example.ambientglow

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The elements' frame shaders only compile on a device, where a bad name throws at the first
 * draw; here what can be checked without one: no name they declare is one AGSL reserves (`half`
 * is a type, `flat` a qualifier), and none shadows a uniform they share.
 */
class ElementFrameSourceTest {

    private val sources = mapOf(
        "fire" to FIRE_FRAME_AGSL,
        "water" to WATER_FRAME_AGSL,
        "air" to AIR_FRAME_AGSL,
        "earth" to EARTH_FRAME_AGSL,
    )

    /** Words AGSL (SkSL) reserves: types, qualifiers, and names kept back from GLSL. */
    private val reserved = setOf(
        "half", "half2", "half3", "half4", "float", "int", "bool", "in", "out", "inout", "uniform",
        "const", "flat", "noperspective", "inline", "noinline", "sk_FragCoord", "cast", "sample",
        "filter", "input", "output", "sizeof", "namespace", "using", "class", "union", "enum",
        "typedef", "template", "this", "packed", "resource", "goto", "volatile", "public", "static",
        "extern", "external", "attribute", "varying", "precision", "invariant", "asm", "fixed",
        "long", "short", "double", "unsigned", "superp", "common", "partition", "active", "highp",
        "mediump", "lowp", "buffer", "shared", "coherent", "restrict", "readonly", "writeonly",
        "subroutine", "layout", "centroid", "patch", "smooth",
    )

    private val types = "float|float2|float3|float4|half|half2|half3|half4|int|bool"

    @Test
    fun noDeclaredNameIsReserved() {
        sources.forEach { (element, source) ->
            val declared = Regex("\\b(?:$types)\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(source).map { it.groupValues[1] }.toSet()
            assertTrue("$element: found no declarations to check", declared.size > 20)
            val clashes = declared.filter { it in reserved }
            assertTrue("$element declares reserved names: $clashes", clashes.isEmpty())
        }
    }

    @Test
    fun noLocalShadowsAUniform() {
        sources.forEach { (element, source) ->
            val uniforms = Regex("uniform\\s+(?:$types)\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(source).map { it.groupValues[1] }.toSet()
            // Locals and parameters: declarations not on a uniform line.
            val locals = source.lines()
                .filterNot { it.trimStart().startsWith("uniform") || it.trimStart().startsWith("layout") }
                .flatMap { line -> Regex("\\b(?:$types)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[=;,)]").findAll(line).map { it.groupValues[1] }.toList() }
                .toSet()
            val shadows = locals.filter { it in uniforms }
            assertTrue("$element: locals shadow uniforms: $shadows", shadows.isEmpty())
        }
    }
}
