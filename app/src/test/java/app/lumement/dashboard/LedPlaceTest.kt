package app.lumement.dashboard

import app.lumement.LedForm
import app.lumement.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedPlaceTest {
    private val ring = DotSpot(0, R.string.led_place_ring, x = 0.5f, y = 0.02f, onCameraLine = false, camera = true, atLens = true)
    private val camLeft = DotSpot(0, R.string.led_place_cam_left, x = 0.42f, y = 0.02f, onCameraLine = true)
    private val camRight = DotSpot(R.string.dot_spot_cam_right, R.string.led_place_cam_right, x = 0.58f, y = 0.02f, onCameraLine = true)
    private val spots = listOf(ring, camLeft)

    @Test
    fun theFrameIsRoundTheEdgeWhereverTheDotWasLeft() {
        assertEquals(R.string.led_place_frame, ledPlace(0.42f, 0.02f, LedForm.FRAME, spots))
        assertEquals(R.string.led_place_frame, ledPlace(0.5f, 0.02f, LedForm.FRAME, spots))
    }

    @Test
    fun onTheCameraItIsTheRing() {
        assertEquals(R.string.led_place_ring, ledPlace(0.5f, 0.02f, LedForm.RING, spots))
    }

    @Test
    fun onASpotItSaysTheSpot() {
        assertEquals(R.string.led_place_cam_left, ledPlace(0.42f, 0.02f, LedForm.DOT, spots))
    }

    @Test
    fun aDotOnTheLensCentreIsNotTheRing() {
        // Without the camera flag the ring's spot doesn't count: the dot there is just top centre.
        assertEquals(R.string.led_place_top_centre, ledPlace(0.5f, 0.02f, LedForm.DOT, spots))
    }

    @Test
    fun offTheSpotsItSaysTheThirdOfTheScreen() {
        assertEquals(R.string.led_place_bottom_right, ledPlace(0.9f, 0.95f, LedForm.DOT, spots))
        assertEquals(R.string.led_place_middle, ledPlace(0.5f, 0.5f, LedForm.DOT, spots))
        assertEquals(R.string.led_place_top_left, ledPlace(0f, 0f, LedForm.DOT, spots))
        assertEquals(R.string.led_place_bottom_left, ledPlace(0f, 1f, LedForm.DOT, spots))
    }

    @Test
    fun theRingIsOnlyOfferedRoundACamera() {
        assertEquals(LedForm.entries, ledForms(camera = true))
        assertEquals(listOf(LedForm.DOT, LedForm.FRAME), ledForms(camera = false))
    }

    @Test
    fun theRingTileGoesOntoTheLensOrNowhereWithoutOne() {
        assertEquals(Triple(0.5f, 0.02f, LedForm.RING), ledFormMove(LedForm.RING, 0.9f, 0.9f, spots))
        assertNull(ledFormMove(LedForm.RING, 0.9f, 0.9f, listOf(camLeft, camRight)))
    }

    @Test
    fun theDotTileLeavesTheLensForJustBesideIt() {
        val withBeside = spots + camRight
        // The lens can't light: from the ring the dot goes just right of the camera.
        assertEquals(Triple(0.58f, 0.02f, LedForm.DOT), ledFormMove(LedForm.DOT, 0.5f, 0.02f, withBeside))
        // Anywhere else, or with no spot beside it, it keeps its place.
        assertEquals(Triple(0.3f, 0.7f, LedForm.DOT), ledFormMove(LedForm.DOT, 0.3f, 0.7f, withBeside))
        assertEquals(Triple(0.5f, 0.02f, LedForm.DOT), ledFormMove(LedForm.DOT, 0.5f, 0.02f, spots))
    }

    @Test
    fun theFrameTileKeepsTheDotsPlaceForComingBack() {
        assertEquals(Triple(0.5f, 0.02f, LedForm.FRAME), ledFormMove(LedForm.FRAME, 0.5f, 0.02f, spots + camRight))
        assertEquals(Triple(0.3f, 0.7f, LedForm.FRAME), ledFormMove(LedForm.FRAME, 0.3f, 0.7f, listOf(camLeft)))
    }
}
