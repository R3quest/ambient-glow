package com.example.ambientglow

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wind's colours and its curves: picked and timed once, so their shape can be pinned down here. */
class AirTest {

    private val brands = listOf(Color(DEFAULT_GLOW_COLOR), Color(0xFF25D366), Color(0xFF8B5CF6), Color(0xFFFF4B33), Color(0xFF777777))

    private fun lightness(color: Color) = toOklch(color)[0]

    /** The shortest way round the hue circle between two hues, in degrees. */
    private fun hueGap(a: Float, b: Float) = abs(((a - b) % 360f + 540f) % 360f - 180f)

    /** The closing fade starts 600 ms before the effect's 2.3 s end. */
    private val closing = 1_700f

    @Test
    fun everyWindDeepensFromItsHeadToItsTail() {
        for (mode in AirColor.entries) {
            for (brand in brands) {
                val p = airPalette(mode, brand)
                val steps = listOf(p.core, p.body, p.glow, p.shade).map(::lightness)
                steps.zipWithNext().forEach { (head, tail) -> assertTrue("$mode $brand: $steps", head > tail) }
                assertTrue("$mode $brand", lightness(p.petal) > lightness(p.petalShade))
            }
        }
    }

    @Test
    fun clearAirIsTheSameForEveryApp() {
        val first = airPalette(AirColor.CLEAR, brands.first())
        brands.forEach { assertEquals(first, airPalette(AirColor.CLEAR, it)) }
    }

    @Test
    fun appWindBlowsInTheBrandsHue() {
        for (brand in brands.dropLast(1)) {
            val hue = toOklch(brand)[2]
            assertTrue("$brand", hueGap(hue, toOklch(airPalette(AirColor.APP, brand).glow)[2]) < 6f)
        }
    }

    @Test
    fun blendIsClearWindTrailingTheBrand() {
        val clear = airPalette(AirColor.CLEAR, Color.Black)
        for (brand in brands.dropLast(1)) {
            val blend = airPalette(AirColor.BLEND, brand)
            assertEquals(clear.core, blend.core)
            assertEquals(clear.body, blend.body)
            assertEquals(clear.petal, blend.petal)
            assertTrue("$brand", hueGap(toOklch(brand)[2], toOklch(blend.glow)[2]) < 6f)
        }
    }

    @Test
    fun theGustDiesDownBeforeItsTripEnds() {
        assertEquals(0f, gustAt(SPAWN_GATHER_MS), 0f)
        assertEquals(0f, gustAt(SPAWN_GATHER_MS + SPAWN_MS), 1e-4f)
        assertTrue((0..closing.toInt()).maxOf { gustAt(it.toFloat()) } > 0.9f)
    }

    @Test
    fun whatTheGustCarriesSettlesBeforeTheFrameDrains() {
        assertEquals(0f, carryAt(SPAWN_GATHER_MS), 0f)
        assertTrue(CARRY_OUT_TO_MS <= closing)
        assertEquals(0f, carryAt(closing), 0f)
        assertTrue((0..closing.toInt()).maxOf { carryAt(it.toFloat()) } > 0.9f)
        // Held up while the gust carries it, then falling faster and faster.
        assertEquals(0f, airFallAt(SPAWN_GATHER_MS + 300f), 0f)
        val a = airFallAt(1_000f)
        val b = airFallAt(1_200f)
        val c = airFallAt(1_400f)
        assertTrue(a > 0f && b - a < c - b)
        assertEquals(0f, airDriftAt(SPAWN_GATHER_MS), 0f)
        assertTrue(airDriftAt(1_000f) > 0f)
    }

    @Test
    fun thePuffBurstsOutAsTheGustIsLetGoAndThinsAway() {
        assertEquals(0f, puffAt(SPAWN_GATHER_MS), 0f)
        assertTrue((SPAWN_GATHER_MS.toInt()..600).maxOf { puffAt(it.toFloat()) } > 0.9f)
        assertEquals(0f, puffAt(SPAWN_GATHER_MS + 0.4f * SPAWN_MS), 0f)
    }

    @Test
    fun theWhirlWindsUpThenSpinsOutAsTheGustIsReleased() {
        // Below 0 it winds up with the gather; past 1 it has gone, well before the gust is far.
        assertTrue(whirlAt(0f) < 0f)
        assertEquals(0f, whirlAt(SPAWN_GATHER_MS), 0f)
        assertTrue(whirlAt(SPAWN_GATHER_MS + 400f) > 1f)
    }
}
