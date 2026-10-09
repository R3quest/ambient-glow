package app.lumement.dashboard

import app.lumement.R
import org.junit.Assert.assertEquals
import org.junit.Test

class LedPlaceTest {
    private val ring = DotSpot(0, R.string.led_place_ring, x = 0.5f, y = 0.02f, onCameraLine = false, camera = true, atLens = true)
    private val camLeft = DotSpot(0, R.string.led_place_cam_left, x = 0.42f, y = 0.02f, onCameraLine = true)
    private val spots = listOf(ring, camLeft)

    @Test
    fun onTheCameraItIsTheRing() {
        assertEquals(R.string.led_place_ring, ledPlace(0.5f, 0.02f, onCamera = true, spots))
    }

    @Test
    fun onASpotItSaysTheSpot() {
        assertEquals(R.string.led_place_cam_left, ledPlace(0.42f, 0.02f, onCamera = false, spots))
    }

    @Test
    fun aDotOnTheLensCentreIsNotTheRing() {
        // Without the camera flag the ring's spot doesn't count: the dot there is just top centre.
        assertEquals(R.string.led_place_top_centre, ledPlace(0.5f, 0.02f, onCamera = false, spots))
    }

    @Test
    fun offTheSpotsItSaysTheThirdOfTheScreen() {
        assertEquals(R.string.led_place_bottom_right, ledPlace(0.9f, 0.95f, onCamera = false, spots))
        assertEquals(R.string.led_place_middle, ledPlace(0.5f, 0.5f, onCamera = false, spots))
        assertEquals(R.string.led_place_top_left, ledPlace(0f, 0f, onCamera = false, spots))
        assertEquals(R.string.led_place_bottom_left, ledPlace(0f, 1f, onCamera = false, spots))
    }
}
