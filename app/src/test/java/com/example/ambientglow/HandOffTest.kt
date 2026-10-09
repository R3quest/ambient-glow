package com.example.ambientglow

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HandOffTest {

    @Test
    fun theTokenIsBornWhereTheHeadsLand() {
        assertEquals(0f, handOffPopAt(LANDING_MS - 1f))
        assertEquals(0f, handOffPopAt(LANDING_MS))
        assertTrue(handOffPopAt(LANDING_MS + 30f) > 0f)
        assertEquals(1f, handOffPopAt(LANDING_MS + 500f), 1e-4f)
    }

    @Test
    fun itLandsAsTheLedsFirstBreathAndGoesOutOnItsExhale() {
        assertEquals(0f, handOffFlightAt(HANDOFF_FLY_FROM_MS))
        assertEquals(1f, handOffFlightAt(HANDOFF_LANDED_MS), 1e-4f)
        // It has the LED's shape as it lands, at the peak of the LED's breath.
        assertEquals(1f, handOffMorphAt(HANDOFF_LANDED_MS + 40f), 1e-4f)
        assertEquals(1f, handOffFadeAt(HANDOFF_LANDED_MS))
        assertEquals(LED_RISE_MS, handOffBreathMs(HANDOFF_LANDED_MS), 0f)
        // The rest is the LED's own exhale, and the Edge Frame ends with it.
        val mid = HANDOFF_LANDED_MS + 500f
        assertEquals(ledBreathAt(LED_RISE_MS + 500f), handOffFadeAt(mid), 1e-6f)
        assertEquals(0f, handOffFadeAt(HANDOFF_END_MS))
        assertEquals(HANDOFF_END_MS.toInt(), arrivalMsFor(GlowSettings(style = GlowStyle.EDGE_FRAME)))
        assertEquals(ARRIVAL_MS, arrivalMsFor(GlowSettings(style = GlowStyle.BEACON)))
    }

    @Test
    fun itGoesOutWithNoStepAtTheEnd() {
        // The panel steps its brightness on black: the last frames before the end must be near zero.
        val end = HANDOFF_END_MS
        assertTrue(handOffFadeAt(end - 8f) < 0.01f)
        var last = 1f
        var ms = HANDOFF_LANDED_MS
        while (ms <= end) {
            val level = handOffFadeAt(ms)
            assertTrue("rises at $ms", level <= last + 1e-6f)
            last = level
            ms += 4f
        }
    }

    @Test
    fun theLandingSpringsAndSettlesAndItsFlashIsBrief() {
        assertEquals(0f, handOffSpringAt(HANDOFF_LANDED_MS), 0f)
        // Swells first, swings back once, and has settled well inside the exhale.
        val peak = (1..120).maxOf { handOffSpringAt(HANDOFF_LANDED_MS + it) }
        val dip = (120..260).minOf { handOffSpringAt(HANDOFF_LANDED_MS + it) }
        assertTrue("peak $peak", peak in 0.5f..1f)
        assertTrue("dip $dip", dip in -0.5f..-0.1f)
        assertTrue(abs(handOffSpringAt(HANDOFF_LANDED_MS + 700f)) < 0.02f)
        assertEquals(0f, handOffFlashAt(HANDOFF_LANDED_MS), 0f)
        assertTrue(handOffFlashAt(HANDOFF_LANDED_MS + 500f) < 0.02f)
    }

    @Test
    fun everyTokensPathRunsFromTheCameraToTheLed() {
        val camera = Offset(540f, 60f)
        val led = Offset(90f, 300f)
        for (token in HandOffToken.entries) {
            val path = HandOffPath(token, camera, led, around = false, radius = 0f)
            assertEquals("$token start", camera, path.at(0f))
            assertClose("$token end", led, path.at(1f))
        }
    }

    @Test
    fun withTheCameraAndLedAtTheTopNoPathLeavesTheScreen() {
        val camera = Offset(540f, 60f)
        val led = Offset(90f, 40f)
        for (token in HandOffToken.entries) {
            val path = HandOffPath(token, camera, led, around = false, radius = 0f, top = 30f)
            for (i in 0..100) {
                val y = path.at(i / 100f).y
                assertTrue("$token leaves the top at ${i / 100f}: $y", y >= 30f - 0.5f)
            }
        }
    }

    @Test
    fun withRoomAboveAnEmberHopsUp() {
        val path = HandOffPath(HandOffToken.EMBER, Offset(540f, 600f), Offset(90f, 600f), around = false, radius = 0f, top = 30f)
        assertTrue(path.at(0.5f).y < 600f)
    }

    @Test
    fun aroundTheLensItCirclesOnceFromBelow() {
        val camera = Offset(540f, 60f)
        val path = HandOffPath(HandOffToken.GEM, camera, camera, around = true, radius = 30f)
        assertClose("below", Offset(540f, 90f), path.at(0f))
        assertClose("round", Offset(540f, 90f), path.at(1f))
        assertClose("above", Offset(540f, 30f), path.at(0.5f))
    }

    @Test
    fun theTokenIsTheElementsOnlyWhenTheFrameIsMadeOfIt() {
        val elemental = GlowSettings(edgeMaterial = EdgeMaterial.ELEMENT, spawn = true)
        assertEquals(HandOffToken.EMBER, handOffToken(elemental.copy(element = SpawnElement.FIRE), shaders = true))
        assertEquals(HandOffToken.DROP, handOffToken(elemental.copy(element = SpawnElement.WATER), shaders = true))
        assertEquals(HandOffToken.WISP, handOffToken(elemental.copy(element = SpawnElement.AIR), shaders = true))
        assertEquals(HandOffToken.GEM, handOffToken(elemental.copy(element = SpawnElement.EARTH), shaders = true))
        // Neon, or an element frame that plays as neon, hands over a plain spark.
        assertEquals(HandOffToken.SPARK, handOffToken(elemental.copy(edgeMaterial = EdgeMaterial.NEON), shaders = true))
        assertEquals(HandOffToken.SPARK, handOffToken(elemental.copy(spawn = false), shaders = true))
        assertEquals(HandOffToken.SPARK, handOffToken(elemental, shaders = false))
    }

    private fun assertClose(what: String, expected: Offset, actual: Offset) {
        assertTrue("$what: $actual, not $expected", (expected - actual).getDistance() < 0.01f)
    }
}
