package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The LED keeps the element the Edge Frame was made of, and its motion stays a slow, bounded signal. */
class LedLookTest {
    private val elemental = GlowSettings(style = GlowStyle.EDGE_FRAME, edgeMaterial = EdgeMaterial.ELEMENT, spawn = true)

    @Test
    fun theLedIsTheElementsOnlyAfterAFrameMadeOfIt() {
        assertEquals(LedMaterial.COAL, ledMaterial(elemental.copy(element = SpawnElement.FIRE), shaders = true))
        assertEquals(LedMaterial.BEAD, ledMaterial(elemental.copy(element = SpawnElement.WATER), shaders = true))
        assertEquals(LedMaterial.WISP, ledMaterial(elemental.copy(element = SpawnElement.AIR), shaders = true))
        assertEquals(LedMaterial.GEM, ledMaterial(elemental.copy(element = SpawnElement.EARTH), shaders = true))
        // A neon frame, one that plays as neon, the beacon, or no effect at all: a neon LED.
        assertEquals(LedMaterial.NEON, ledMaterial(elemental.copy(edgeMaterial = EdgeMaterial.NEON), shaders = true))
        assertEquals(LedMaterial.NEON, ledMaterial(elemental.copy(spawn = false), shaders = true))
        assertEquals(LedMaterial.NEON, ledMaterial(elemental, shaders = false))
        assertEquals(LedMaterial.NEON, ledMaterial(elemental.copy(style = GlowStyle.BEACON), shaders = true))
        assertEquals(LedMaterial.NEON, ledMaterial(elemental.copy(arrival = ArrivalMode.LED_ONLY), shaders = true))
    }

    @Test
    fun theCoalsGlowWaversSlowlyAndNeverGoesOut() {
        val frame = 1000f / 60f
        // A smoulder, not a strobe: no frame moves it further than the breath itself moves.
        var breathStep = 0f
        var ms = 0f
        while (ms < LED_BREATH_MS) {
            breathStep = maxOf(breathStep, abs(ledBreathAt(ms + frame) - ledBreathAt(ms)))
            ms += frame
        }
        ms = 0f
        while (ms <= LED_BREATH_MS) {
            val glow = coalGlowAt(ms)
            assertTrue("$glow at $ms", glow in 0.7f..1f)
            assertTrue("jumps at $ms", abs(coalGlowAt(ms + frame) - glow) <= breathStep)
            ms += frame
        }
    }

    @Test
    fun theBeadRipplesOnceAsItFillsAndIsStillBeforeItGoesDark() {
        assertTrue(beadRippleAt(0f) < 0f)
        assertTrue(beadRippleAt(LED_RISE_MS) in 0f..1f)
        assertTrue(beadRippleAt(LED_BREATH_MS) >= 1f)
    }

    @Test
    fun everyMomentIsOverBeforeTheBreathGoesDark() {
        // Sparks rise once the coal is nearly full and burn out before it fades.
        for (i in 0 until COAL_SPARKS) {
            assertTrue(coalSparkAt(LED_RISE_MS, i) > -1f)
            assertTrue(coalSparkAt(LED_BREATH_MS, i) >= 1f)
        }
        // Both ripples run out, and the wobble has settled.
        assertTrue(beadRippleAt(LED_BREATH_MS, 1) >= 1f)
        assertEquals(0f, beadSwellAt(0f), 0f)
        assertTrue(abs(beadSwellAt(LED_BREATH_MS)) < 0.01f)
        // Each moonlet goes behind the stone and comes round in front of it within a breath.
        for (i in 0 until GEM_MOONLETS) {
            val sides = (0..LED_BREATH_MS.toInt() step 20).map { kotlin.math.sin(gemOrbitAt(it.toFloat(), i)) >= 0f }.toSet()
            assertEquals("moonlet $i", setOf(true, false), sides)
        }
        // The coal's flame runs once round, from the bottom, and comes to rest there.
        assertEquals(0f, coalRunAt(0f), 0f)
        assertEquals(360f, coalRunAt(LED_BREATH_MS), 1e-3f)
    }

    @Test
    fun theCurlIsAllOutByThePeak() {
        assertTrue(wispUnfurlAt(0f) in 0.2f..0.5f)
        assertEquals(1f, wispUnfurlAt(LED_RISE_MS), 1e-6f)
    }

    @Test
    fun theGemGlintsAtThePeakOnly() {
        assertEquals(1f, gemGlintAt(LED_RISE_MS), 1e-6f)
        assertEquals(0f, gemGlintAt(0f), 0f)
        assertEquals(0f, gemGlintAt(LED_BREATH_MS), 0f)
    }

    @Test
    fun theWispTurnsAnticlockwiseFromWhereItStarts() {
        assertEquals(0f, wispTurnAt(0f), 0f)
        assertTrue(wispTurnAt(LED_BREATH_MS) < 0f)
        // Less than a turn a breath: a drift you can follow, not a spin.
        assertTrue(wispTurnAt(LED_BREATH_MS) > -360f)
    }
}
