package app.lumement

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fire's colours and its curves: picked and timed once, so their shape can be pinned down here. */
class FireTest {

    private val brands = listOf(Color(DEFAULT_GLOW_COLOR), Color(0xFF25D366), Color(0xFF8B5CF6), Color(0xFFFF4B33), Color(0xFF777777))

    private fun lightness(color: Color) = toOklch(color)[0]

    /** The shortest way round the hue circle between two hues, in degrees. */
    private fun hueGap(a: Float, b: Float) = abs(((a - b) % 360f + 540f) % 360f - 180f)

    @Test
    fun everyFireDarkensFromItsCoreToItsEmbers() {
        for (mode in FireColor.entries) {
            for (brand in brands) {
                val p = firePalette(mode, brand)
                val steps = listOf(p.core, p.hot, p.body, p.flare, p.tip, p.ember).map(::lightness)
                steps.zipWithNext().forEach { (hotter, cooler) ->
                    assertTrue("$mode $brand: $steps", hotter > cooler)
                }
            }
        }
    }

    @Test
    fun naturalFireIsTheSameForEveryApp() {
        val first = firePalette(FireColor.NATURAL, brands.first())
        brands.forEach { assertEquals(first, firePalette(FireColor.NATURAL, it)) }
    }

    @Test
    fun appFireBurnsInTheBrandsHue() {
        for (brand in brands.dropLast(1)) {
            val hue = toOklch(brand)[2]
            assertTrue("$brand", hueGap(hue, toOklch(firePalette(FireColor.APP, brand).body)[2]) < 6f)
        }
    }

    @Test
    fun blendIsNaturalFireTippedWithTheBrand() {
        val natural = firePalette(FireColor.NATURAL, Color.Black)
        for (brand in brands.dropLast(1)) {
            val blend = firePalette(FireColor.BLEND, brand)
            assertEquals(natural.core, blend.core)
            assertEquals(natural.body, blend.body)
            assertTrue("$brand", hueGap(toOklch(brand)[2], toOklch(blend.tip)[2]) < 6f)
        }
    }

    @Test
    fun theCharCoversTheScreenAtReleaseAndIsGoneByTheEnd() {
        assertEquals(0f, charAt(0f), 0f)
        assertEquals(1f, charAt(SPAWN_GATHER_MS), 1e-4f)
        assertEquals(0f, charAt(SPAWN_GATHER_MS + SPAWN_MS), 1e-4f)
    }

    @Test
    fun theFireHasDiedDownBeforeTheFrameDrains() {
        // The closing fade starts 600 ms before the effect's 2.3 s end.
        val closing = 1_700f
        assertTrue(COALS_COOL_TO_MS <= closing)
        assertEquals(0f, coalsAt(closing), 0f)
        assertTrue((0..closing.toInt()).maxOf { coalsAt(it.toFloat()) } > 0.9f)
        assertEquals(0f, sparksAt(SPAWN_GATHER_MS + SPAWN_MS), 1e-4f)
        assertTrue((0..closing.toInt()).maxOf { sparksAt(it.toFloat()) } > 0.5f)
    }

    @Test
    fun theStarburstPlaysAsTheFireIsReleased() {
        // Outside 0..1 it isn't drawn: before the gather ends, and once the ring has left the camera.
        assertTrue(starburstAt(0f) < 0f)
        assertTrue(starburstAt(SPAWN_GATHER_MS) in 0f..1f)
        assertTrue(starburstAt(SPAWN_GATHER_MS + 300f) > 1f)
    }
}
