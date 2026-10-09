package app.lumement

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The earth's colours and its curves: picked and timed once, so their shape can be pinned down here. */
class EarthTest {

    private val brands = listOf(Color(DEFAULT_GLOW_COLOR), Color(0xFF25D366), Color(0xFF8B5CF6), Color(0xFFFF4B33), Color(0xFF777777))

    private fun lightness(color: Color) = toOklch(color)[0]

    /** The shortest way round the hue circle between two hues, in degrees. */
    private fun hueGap(a: Float, b: Float) = abs(((a - b) % 360f + 540f) % 360f - 180f)

    /** The closing fade starts 600 ms before the effect's 2.3 s end. */
    private val closing = 1_700f

    @Test
    fun everyEarthStepsDownFromItsLightToItsShade() {
        for (mode in EarthColor.entries) {
            for (brand in brands) {
                val p = earthPalette(mode, brand)
                for (steps in listOf(listOf(p.light, p.stone, p.shade), listOf(p.core, p.glow, p.deep), listOf(p.dustLit, p.dustShade))) {
                    val l = steps.map(::lightness)
                    l.zipWithNext().forEach { (lit, dark) -> assertTrue("$mode $brand: $l", lit > dark) }
                }
            }
        }
    }

    @Test
    fun stoneIsTheSameForEveryApp() {
        val first = earthPalette(EarthColor.STONE, brands.first())
        brands.forEach { assertEquals(first, earthPalette(EarthColor.STONE, it)) }
    }

    @Test
    fun appLightsTheCracksInTheBrandAndTintsTheStoneTowardsIt() {
        for (brand in brands.dropLast(1)) {
            val app = earthPalette(EarthColor.APP, brand)
            val hue = toOklch(brand)[2]
            assertTrue("$brand", hueGap(hue, toOklch(app.glow)[2]) < 6f)
            // Stone and smoke lean the brand's way, so it shows where they cover the cracks...
            assertTrue("$brand", hueGap(hue, toOklch(app.stone)[2]) < 6f)
            assertTrue("$brand", hueGap(hue, toOklch(app.dustShade)[2]) < 6f)
            // ...but stay stone: far less coloured than the brand itself.
            assertTrue("$brand", toOklch(app.stone)[1] < 0.5f * toOklch(brand)[1])
        }
    }

    @Test
    fun crystalIsTheStoneItselfInTheBrandsHue() {
        for (brand in brands.dropLast(1)) {
            val crystal = earthPalette(EarthColor.CRYSTAL, brand)
            assertTrue("$brand", hueGap(toOklch(brand)[2], toOklch(crystal.stone)[2]) < 6f)
            assertTrue("$brand", hueGap(toOklch(brand)[2], toOklch(crystal.glow)[2]) < 6f)
        }
    }

    @Test
    fun theQuakeDiesDownBeforeItsTripEnds() {
        assertEquals(0f, quakeAt(SPAWN_GATHER_MS), 0f)
        assertEquals(0f, quakeAt(SPAWN_GATHER_MS + SPAWN_MS), 1e-4f)
        assertTrue((0..closing.toInt()).maxOf { quakeAt(it.toFloat()) } > 0.9f)
    }

    @Test
    fun whatTheQuakeThrowsUpSettlesBeforeTheFrameDrains() {
        assertEquals(0f, debrisAt(SPAWN_GATHER_MS), 0f)
        assertTrue(DEBRIS_OUT_TO_MS <= closing)
        assertEquals(0f, debrisAt(closing), 0f)
        assertTrue((0..closing.toInt()).maxOf { debrisAt(it.toFloat()) } > 0.9f)
    }

    @Test
    fun rubbleIsThrownUpThenFallsBack() {
        assertEquals(0f, rubbleLiftAt(SPAWN_GATHER_MS), 0f)
        // Up the screen first (below 0), slowing as it climbs...
        val a = rubbleLiftAt(SPAWN_GATHER_MS + 100f)
        val b = rubbleLiftAt(SPAWN_GATHER_MS + 200f)
        assertTrue(a < 0f && b < a && b - a > a)
        // ...then back down past where it was thrown from before it settles.
        assertTrue(rubbleLiftAt(DEBRIS_OUT_FROM_MS) > 0f)
    }

    @Test
    fun theImpactChargesThenSnapsOpenAsTheGroundIsStruck() {
        // Below 0 it charges with the gather; past 1 it has gone, well before the shock is far.
        assertTrue(impactAt(0f) < 0f)
        assertEquals(0f, impactAt(SPAWN_GATHER_MS), 0f)
        assertTrue(impactAt(SPAWN_GATHER_MS + 400f) > 1f)
    }

    @Test
    fun theSmokeBurstsFromTheCameraAsTheGroundIsStruckAndIsGoneBeforeTheTripEnds() {
        // Outside 0..1 it isn't there: before the strike, and once it has thinned away.
        assertTrue(smokeBurstAt(SPAWN_GATHER_MS - 1f) < 0f)
        assertEquals(0f, smokeBurstAt(SPAWN_GATHER_MS), 0f)
        assertTrue(smokeBurstAt(SPAWN_GATHER_MS + SPAWN_MS) > 1f)
    }

    @Test
    fun theGroundTremblesJoltsAsItIsStruckAndSettles() {
        assertEquals(0f, rumbleAt(0f), 0f)
        // A tremble through the gather, never as hard as the jolt.
        val gather = rumbleAt(SPAWN_GATHER_MS - 1f)
        assertTrue(gather > 0f && gather < 0.5f)
        assertEquals(1f, rumbleAt(SPAWN_GATHER_MS), 0f)
        // Dying away, and still well before the shock reaches the bottom.
        assertTrue(rumbleAt(SPAWN_GATHER_MS + 200f) < rumbleAt(SPAWN_GATHER_MS + 100f))
        assertEquals(0f, rumbleAt(SPAWN_GATHER_MS + 500f), 0f)
    }
}
