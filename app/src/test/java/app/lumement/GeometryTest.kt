package app.lumement

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    private val screen = Size(1080f, 2340f)

    @Test
    fun dotFractionUndoesDotCenter() {
        val margin = 18f
        for ((x, y) in listOf(0f to 0f, 0.06f to 0.008f, 0.5f to 0.5f, 1f to 1f)) {
            val back = dotFraction(dotCenter(x, y, screen, margin), screen, margin)
            assertEquals(x, back.x, 1e-5f)
            assertEquals(y, back.y, 1e-5f)
        }
    }

    @Test
    fun dotFractionClampsToTheScreen() {
        val f = dotFraction(Offset(-50f, 99_999f), screen, 10f)
        assertEquals(0f, f.x, 0f)
        assertEquals(1f, f.y, 0f)
    }

    @Test
    fun lensIsTheReportedCutout() {
        val spot = CutoutSpot(540f, 60f, 30f)
        assertEquals(spot, ScreenGeometry(spot, null).lens(screen.width, density = 3f))
    }

    @Test
    fun lensFallsBackToTopCentreScaled() {
        val lens = ScreenGeometry.Unknown.lens(screen.width, density = 3f, scale = 0.5f)
        val metrics = GlowMetrics.FullScreen
        assertEquals(540f, lens.centerX, 0f)
        assertEquals(metrics.fallbackCameraCenterY.value * 3f * 0.5f, lens.centerY, 1e-4f)
        assertEquals(metrics.fallbackCameraRadius.value * 3f * 0.5f, lens.radius, 1e-4f)
    }

    @Test
    fun fittedMovesAndGrowsTheLensByTheUsersFit() {
        val geometry = ScreenGeometry(CutoutSpot(540f, 60f, 30f), null)
        val fit = GlowSettings(lensOffsetXDp = 1f, lensOffsetDp = -2f, lensGrowDp = 0.5f)
        assertEquals(CutoutSpot(543f, 54f, 31.5f), geometry.fitted(fit, density = 3f).cutout)
    }
}
