package com.example.ambientglow

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
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
    fun theWindSmearsInStripsWithStillGapsBetween() {
        val samples = (0 until 3_600).map { windStripAt(it / 3_600f * 2f * PI.toFloat() - PI.toFloat()) }
        val dragged = samples.count { it > 0.5f } / samples.size.toFloat()
        val still = samples.count { it == 0f } / samples.size.toFloat()
        assertTrue("dragged $dragged", dragged in 0.25f..0.6f)
        assertTrue("still $still", still in 0.2f..0.6f)
        // Strips, not noise: across a few hundred of the samples (a hand's width at mid-screen) it
        // turns on and off only a few times.
        val turns = samples.take(300).zipWithNext().count { (a, b) -> (a > 0.5f) != (b > 0.5f) }
        assertTrue("turns $turns", turns in 1..12)
    }

    @Test
    fun theFlowAngleIsTheSameAllAlongAFlowLine() {
        val pitch = AirFlow.VORTEX.pitch
        val psi = 0.4f
        for (r in listOf(50f, 400f, 2_000f)) {
            val theta = psi + pitch * kotlin.math.ln(r)
            // The same angle, a whole number of turns apart (the flow line winds round as it goes out).
            val turns = (flowAngle(r * kotlin.math.sin(theta), r * kotlin.math.cos(theta), pitch) - psi) / (2f * PI.toFloat())
            assertEquals(kotlin.math.round(turns), turns, 1e-4f)
        }
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

    @Test
    fun theGustLeavesRoundThenBreaksIntoTonguesThatOnlyEverRunAhead() {
        val ring = (0 until 3_600).map { it / 3_600f * 2f * PI.toFloat() - PI.toFloat() }
        // Round as it leaves the camera, so the whirl and the puff stay round.
        assertTrue(ring.all { gustSurgeAt(it, 0.03f) == 0f })
        for (wave in listOf(0.5f, 0.75f, 1f)) {
            val surge = ring.map { gustSurgeAt(it, wave) }
            // Never behind the front, which is what lights the frame, and never far ahead of it.
            assertTrue(surge.all { it in 0f..GUST_SURGE })
            // A few broad tongues with calm between, not a ragged fringe.
            val ahead = surge.count { it > 0.5f * GUST_SURGE } / surge.size.toFloat()
            val calm = surge.count { it == 0f } / surge.size.toFloat()
            assertTrue("wave $wave ahead $ahead", ahead in 0.1f..0.45f)
            assertTrue("wave $wave calm $calm", calm in 0.2f..0.75f)
            val tongues = surge.zipWithNext().count { (a, b) -> a == 0f && b > 0f }
            assertTrue("wave $wave tongues $tongues", tongues in 2..7)
        }
    }

    @Test
    fun theTonguesCloseRoundTheRing() {
        // The flow angle jumps a whole turn where atan2 wraps; the tongues mustn't tear there.
        for (wave in listOf(0.3f, 0.7f, 1f)) {
            assertEquals(gustSurgeAt(-PI.toFloat(), wave), gustSurgeAt(PI.toFloat(), wave), 1e-5f)
        }
    }
}
