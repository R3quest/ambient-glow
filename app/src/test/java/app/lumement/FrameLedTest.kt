package app.lumement

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** The frame LED: a line round the screen's edge that never leaves it, and the arrivals that end in it. */
class FrameLedTest {
    private val screen = Size(1080f, 2340f)

    @Test
    fun theFrameIsTheEdgeFrameWaitingAtAboutHalf() {
        val dp = 3f
        val light = ledFrameLight(GlowSettings(edgeWidth = EdgeWidth.THIN, edgeGlow = EdgeGlow.SOFT), screen, corner = 100f, inset = 0f, dp = dp)
        val line = EdgeWidth.THIN.ledLine.value * dp
        assertEquals(line, light.line, 1e-5f)
        // Through the line's middle: its outer edge on the screen's.
        assertEquals(Size(screen.width - line, screen.height - line), light.frame)
        assertEquals(100f - line / 2f, light.corner, 1e-5f)
        assertEquals(EdgeGlow.SOFT.reach.value * dp / 2f, light.bloom, 1e-5f)
        // Every width waits wider than the last and quieter than the Edge Frame's, never thinner than the floor.
        val lines = EdgeWidth.entries.map { it.ledLine.value }
        assertEquals(lines.sorted(), lines)
        for (width in EdgeWidth.entries) {
            assertTrue("$width", width.ledLine < width.stroke)
            assertTrue("$width", width.ledLine.value >= 0.6f)
        }
        assertEquals(1.5f, EdgeWidth.HAIRLINE.ledLine.value, 1e-4f)
        assertEquals(5.55f, EdgeWidth.HEAVY.ledLine.value, 1e-4f)
        // Every glow reaches further than the last and less far than the Edge Frame's; the glow off is none.
        val reaches = EdgeGlow.entries.map { it.ledReach.value }
        assertTrue(reaches.zipWithNext().all { (a, b) -> a < b })
        for (glow in EdgeGlow.entries - EdgeGlow.OFF) assertTrue("$glow", glow.ledReach < glow.reach)
        assertEquals(0f, EdgeGlow.OFF.ledReach.value, 0f)
        assertEquals(0f, ledFrameLight(GlowSettings(edgeGlow = EdgeGlow.OFF), screen, 100f, 0f, dp).bloom, 0f)
    }

    @Test
    fun itsBurnInStepIsInwardOnly() {
        for (cycle in 0 until 8) {
            val step = ledStepPx(LedForm.FRAME, cycle)
            assertTrue(step in 0f..2f)
            assertEquals(Offset.Zero, ledShiftAt(LedForm.FRAME, cycle))
            val light = ledFrameLight(GlowSettings(), screen, corner = 80f, inset = step, dp = 3f)
            assertEquals(screen.width - 2f * step - light.line, light.frame.width, 1e-4f)
        }
        // It does move, breath to breath and step to step on the always-on display.
        assertTrue((0 until 4).map { ledStepPx(LedForm.FRAME, it) }.toSet().size > 1)
    }

    @Test
    fun theFrameNeverGoesNegative() {
        // A square screen, or a corner tighter than the inset and half the line: a square corner.
        assertEquals(0f, ledFrameLight(GlowSettings(), screen, corner = 0f, inset = 2f, dp = 3f).corner, 0f)
        assertEquals(0f, ledFrameLight(GlowSettings(edgeWidth = EdgeWidth.HEAVY), screen, corner = 1f, inset = 2f, dp = 3f).corner, 0f)
        // Otherwise the corner comes in with the line.
        val light = ledFrameLight(GlowSettings(), screen, corner = 100f, inset = 2f, dp = 3f)
        assertEquals(100f - 2f - light.line / 2f, light.corner, 1e-4f)
        // A canvas smaller than the line (a layer not laid out yet): no frame, rather than a negative one.
        assertEquals(Size.Zero, ledFrameLight(GlowSettings(), Size(2f, 2f), corner = 10f, inset = 2f, dp = 3f).frame)
        assertEquals(Size.Zero, ledFrameLight(GlowSettings(), Size.Zero, corner = 10f, inset = 2f, dp = 3f).frame)
    }

    @Test
    fun effectsLandOnTheFrameAtTheScreensCentre() {
        with(Density(3f)) {
            val settings = GlowSettings(ledForm = LedForm.FRAME, edgeWidth = EdgeWidth.BOLD, dotX = 0.1f, dotY = 0.9f)
            val landing = ledLanding(settings, CutoutSpot(540f, 60f, 30f), screen, scale = 1f, corner = 90f)
            assertEquals(Offset(screen.width / 2f, screen.height / 2f), landing.center)
            assertEquals(EdgeWidth.BOLD.ledLine.toPx(), landing.light.line, 1e-4f)
        }
    }

    /** What an effect lands as is the frame [LedSurface] lights on its first breath, full size or in a mock-up, so it doesn't jump. */
    @Test
    fun theLandingIsTheFirstBreathsFrame() {
        val settings = GlowSettings(ledForm = LedForm.FRAME, edgeWidth = EdgeWidth.BOLD, edgeGlow = EdgeGlow.STRONG)
        assertEquals(0f, ledStepPx(LedForm.FRAME, 0), 0f)
        with(Density(3f)) {
            for (scale in listOf(1f, 0.5f)) {
                val landing = ledLanding(settings, CutoutSpot(540f, 60f, 30f), screen, scale, corner = 90f)
                val first = ledFrameLight(settings, screen, 90f, inset = ledStepPx(LedForm.FRAME, 0), dp = 3f * scale)
                assertEquals(screen.center, landing.center)
                assertEquals(first.frame, landing.light.frame)
                assertEquals(first.corner, landing.light.corner, 1e-4f)
                assertEquals(first.line, landing.light.line, 1e-4f)
                assertEquals(first.bloom, landing.light.bloom, 1e-4f)
                // The mock-up's scale reaches the line.
                assertEquals(EdgeWidth.BOLD.ledLine.value * 3f * scale, landing.light.line, 1e-4f)
            }
        }
    }

    @Test
    fun theFrameWaitsInPlainLight() {
        // Never an element: it would repaint the whole edge every step of every blink, and lag.
        val frame = GlowSettings(ledForm = LedForm.FRAME, style = GlowStyle.EDGE_FRAME, edgeMaterial = EdgeMaterial.ELEMENT, spawn = true)
        for (element in SpawnElement.entries) {
            assertEquals("$element", LedMaterial.NEON, ledMaterial(frame.copy(element = element), shaders = true))
        }
    }

    /**
     * [LedSurface] paints a look that doesn't move once a blink, and breathes it by the surface's
     * alpha. (In the app's colour: Duo's and Spectrum's sweep is an Android shader, and holds still too.)
     */
    @Test
    fun theFrameIsPaintedOncePerBlinkWhateverItIsMadeOf() {
        val light = ledFrameLight(GlowSettings(), screen, corner = 80f, inset = 0f, dp = 3f)
        for (material in LedMaterial.entries) {
            for (calm in listOf(0f, 1f)) {
                assertFalse("$material", LedLook(material, Color.Red, light, LedForm.FRAME, GlowSettings(edgeColor = EdgeColor.APP), calm).moves)
            }
        }
        // As a dot, an element does move.
        val dot = ledLight(9f, onCamera = false, lens = 0f, ringGrowPx = 0f, ringGap = 0f)
        assertTrue(LedLook(LedMaterial.COAL, Color.Red, dot, LedForm.DOT).moves)
    }

    @Test
    fun theFrameWearsTheEdgeFramesColours() {
        for (brand in listOf(Color(0xFF25D366), Color(0xFF0088CC))) {
            assertNull(edgeTintStops(EdgeColor.APP, brand))
            val hue = toOklch(brand)[2]

            val duo = edgeTintStops(EdgeColor.DUO, brand)!!
            assertSweep(duo)
            assertEquals(9, duo.size)
            // From the brand to its partner and back, twice round, and closed where it started.
            assertEquals(brand, duo[0].second)
            assertEquals(brand, duo[8].second)
            assertEquals(duo[2].second, duo[6].second)
            assertEquals("$brand", 55f, hueGap(hue, toOklch(duo[2].second)[2]), 4f)

            val spectrum = edgeTintStops(EdgeColor.SPECTRUM, brand)!!
            assertSweep(spectrum)
            assertEquals(13, spectrum.size)
            // From the brand's hue, every step as light as the next (not the brand itself: as vivid as the rest).
            assertTrue("$brand", hueGap(hue, toOklch(spectrum[0].second)[2]) < 4f)
            for ((at, color) in spectrum) assertEquals("$brand at $at", 0.72f, toOklch(color)[0], 0.01f)
            // Seamless: round to where it began, to within a step of 8-bit colour.
            val first = spectrum.first().second
            val last = spectrum.last().second
            assertEquals(first.red, last.red, 0.005f)
            assertEquals(first.green, last.green, 0.005f)
            assertEquals(first.blue, last.blue, 0.005f)
        }
    }

    @Test
    fun aScreenWithNoCameraKeepsTheFrame() {
        val noCamera = ScreenGeometry(cutout = null, cornerRadiusPx = null)
        assertEquals(LedForm.FRAME, GlowSettings(ledForm = LedForm.FRAME).forScreen(noCamera).ledForm)
        assertEquals(LedForm.DOT, GlowSettings(ledForm = LedForm.RING).forScreen(noCamera).ledForm)
    }

    @Test
    fun theEdgeFramesHeadsTurnRoundAtTheCameraAndRunBackOutAsTheLed() {
        val frame = GlowSettings(style = GlowStyle.EDGE_FRAME, ledForm = LedForm.FRAME)
        assertTrue(frameRunsFromCamera(frame.copy(edgeMotion = EdgeMotion.COMET)))
        assertTrue(frameRunsFromCamera(frame.copy(edgeMotion = EdgeMotion.TWIN)))
        // Pulse has no heads to gather: it settles where it is. Neither the beacon nor a dot runs.
        assertFalse(frameRunsFromCamera(frame.copy(edgeMotion = EdgeMotion.PULSE)))
        assertFalse(frameRunsFromCamera(frame.copy(style = GlowStyle.BEACON)))
        assertFalse(frameRunsFromCamera(frame.copy(ledForm = LedForm.DOT)))
        // It leaves as the heads land on the camera and lays the frame as the beacon's does.
        assertEquals(LANDING_MS, EDGE_RUN_FROM_MS, 0f)
        assertEquals(0f, frameRunAt(EDGE_RUN_FROM_MS, EDGE_RUN_FROM_MS), 0f)
        assertEquals(1f, frameRunAt(EDGE_RUN_FROM_MS + FRAME_RUN_MS, EDGE_RUN_FROM_MS), 1e-5f)
        assertEquals(1f, frameRunFadeAt(EDGE_RUN_FROM_MS + FRAME_RUN_MS, EDGE_RUN_FROM_MS), 0f)
        val end = frameRunEndMs(EDGE_RUN_FROM_MS)
        assertEquals(0f, frameRunFadeAt(end, EDGE_RUN_FROM_MS), 0f)
        assertTrue(frameRunFadeAt(end - 8f, EDGE_RUN_FROM_MS) < 0.01f)
        // The beacon's run keeps its own timing.
        assertEquals(FRAME_RUN_END_MS, frameRunEndMs(FRAME_RUN_FROM_MS), 0f)
    }

    @Test
    fun theEdgeFrameSettlesIntoItAsTheTokenWouldHaveLanded() {
        assertEquals(0f, frameSettleAt(LANDING_MS - 1f), 0f)
        assertEquals(0f, frameSettleAt(LANDING_MS), 0f)
        assertEquals(1f, frameSettleAt(HANDOFF_LANDED_MS), 0f)
        assertNeverFalls(0f, HANDOFF_END_MS, ::frameSettleAt)
        assertEquals(HANDOFF_END_MS.toInt(), arrivalMsFor(GlowSettings(style = GlowStyle.EDGE_FRAME, ledForm = LedForm.FRAME, edgeMotion = EdgeMotion.PULSE)))
        // The LED rises as the frame settles, peaks where the token would have landed, and goes out with no step.
        val level = { ms: Float -> frameSettleAt(ms) * handOffFadeAt(ms) }
        assertNeverFalls(0f, HANDOFF_LANDED_MS, level)
        assertEquals(1f, level(HANDOFF_LANDED_MS), 0f)
        assertNeverRises(HANDOFF_LANDED_MS, HANDOFF_END_MS, level)
        assertTrue(level(HANDOFF_END_MS - 8f) < 0.01f)
    }

    @Test
    fun theFrameWearsDuoAndSpectrumWhereTheEdgeFramesTurnEnds() {
        for (mode in listOf(EdgeColor.DUO, EdgeColor.SPECTRUM)) {
            for (spawn in listOf(true, false)) {
                assertEquals("$mode $spawn", edgeTintRestDegrees(mode), tintTurnAt(10_000f, mode, spawn), 1e-4f)
            }
            // Without the spawn wave it starts turning at once, from the top.
            assertEquals("$mode", -90f, tintTurnAt(0f, mode, spawn = false), 1e-3f)
        }
    }

    @Test
    fun theGlowsRingsStepInsideEachOtherWithoutOverlapping() {
        val topLeft = Offset(10f, 20f)
        val size = Size(400f, 800f)
        val rings = EdgeGlowRings(topLeft, size, corner = 30f, from = 2f, step = 4f, count = 8)
        for (i in 0 until rings.count) {
            val d = 2f + 4f * (i + 0.5f)
            assertEquals(topLeft + Offset(d, d), rings.topLeft[i])
            assertEquals(Size(size.width - 2f * d, size.height - 2f * d), rings.size[i])
            assertEquals((30f - d).coerceAtLeast(0f), rings.corner[i].x, 1e-5f)
        }
        // Each ring a stroke's width inside the last: they touch, never overlap.
        assertEquals(4f, rings.stroke.width, 0f)
        for (i in 1 until rings.count) assertEquals(4f, rings.topLeft[i].x - rings.topLeft[i - 1].x, 1e-4f)
        // The corner closes to square rather than going negative.
        assertEquals(0f, rings.corner[rings.count - 1].x, 0f)
        // Its light falls off inward, already below the peak at the first ring, and a narrower spread draws it in.
        assertTrue(rings.falloff(0) < 1f)
        for (i in 1 until rings.count) assertTrue("$i", rings.falloff(i) < rings.falloff(i - 1))
        for (i in 0 until rings.count) assertTrue("$i", rings.falloff(i, spread = 0.55f) < rings.falloff(i))
        // No glow, no rings.
        val none = EdgeGlowRings(topLeft, size, corner = 30f, from = 2f, step = 1f, count = 0)
        assertEquals(0, none.topLeft.size)
        assertEquals(0, none.size.size)
        assertEquals(0, none.corner.size)
    }

    @Test
    fun theBeaconPlaysRoundTheCameraForTheRingAndTheFrame() {
        val camera = ScreenGeometry(CutoutSpot(540f, 60f, 30f), cornerRadiusPx = null)
        val noCamera = ScreenGeometry(cutout = null, cornerRadiusPx = null)
        assertTrue(beaconRings(GlowSettings(ledForm = LedForm.FRAME), camera))
        assertFalse(beaconRings(GlowSettings(ledForm = LedForm.FRAME), noCamera))
        assertTrue(beaconRings(GlowSettings(ledForm = LedForm.RING), camera))
        assertFalse(beaconRings(GlowSettings(ledForm = LedForm.DOT), camera))
    }

    @Test
    fun theBeaconsLightsMeetAsItsEmberIsGoneThenTheFrameExhales() {
        assertTrue(FRAME_RUN_FROM_MS >= 0f)
        assertEquals(FRAME_LAID_MS - FRAME_RUN_MS, FRAME_RUN_FROM_MS, 0f)
        assertEquals(0f, frameRunAt(0f), 0f)
        assertEquals(0f, frameRunAt(FRAME_RUN_FROM_MS), 0f)
        assertEquals(1f, frameRunAt(FRAME_LAID_MS), 1e-5f)
        assertEquals(1f, frameRunAt(FRAME_LAID_MS + 500f), 1e-5f)
        assertNeverFalls(FRAME_RUN_FROM_MS, FRAME_LAID_MS, ::frameRunAt)
        assertEquals(ARRIVAL_MS.toFloat(), FRAME_LAID_MS, 0f)
        // The laid frame is full until the lights meet.
        var ms = 0f
        while (ms <= FRAME_LAID_MS) {
            assertEquals("at $ms", 1f, frameRunFadeAt(ms), 0f)
            ms += 4f
        }
        assertEquals(1f, frameRunFadeAt(FRAME_LAID_MS), 0f)
        assertEquals(0f, frameRunFadeAt(FRAME_RUN_END_MS), 0f)
        assertEquals(FRAME_RUN_END_MS.toInt(), arrivalMsFor(GlowSettings(style = GlowStyle.BEACON, ledForm = LedForm.FRAME)))
        // Out with no step, as the LED's own exhale ends: the panel steps its brightness on black.
        assertTrue(frameRunFadeAt(FRAME_RUN_END_MS - 8f) < 0.01f)
        assertNeverRises(FRAME_LAID_MS, FRAME_RUN_END_MS, ::frameRunFadeAt)
    }

    /** Sweep stops: from 0 to 1, each further round than the last. */
    private fun assertSweep(stops: Array<Pair<Float, Color>>) {
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        assertTrue(stops.toList().zipWithNext().all { (a, b) -> a.first < b.first })
    }

    /** The shortest way round the hue circle between two hues, in degrees. */
    private fun hueGap(a: Float, b: Float) = abs(((a - b) % 360f + 540f) % 360f - 180f)

    /** [curve] stepped every 4 ms from [from] to [to] never goes down. */
    private fun assertNeverFalls(from: Float, to: Float, curve: (Float) -> Float) {
        var last = curve(from)
        var ms = from
        while (ms <= to) {
            val level = curve(ms)
            assertTrue("falls at $ms", level >= last - 1e-6f)
            last = level
            ms += 4f
        }
    }

    /** [curve] stepped every 4 ms from [from] to [to] never goes up. */
    private fun assertNeverRises(from: Float, to: Float, curve: (Float) -> Float) {
        var last = curve(from)
        var ms = from
        while (ms <= to) {
            val level = curve(ms)
            assertTrue("rises at $ms", level <= last + 1e-6f)
            last = level
            ms += 4f
        }
    }

    /**
     * Still on the always-on display for hours, the LED steps by a share of its line: no pixel
     * under the frame's 12 px line lit at every step, and its hot core on other pixels each step.
     */
    @Test
    fun theStillLedsStepsSpreadItsLightOverMorePixels() {
        val line = 12f
        val steps = STILL_SHIFTS.indices.map { stillStepPx(it, line) }
        for (x in 0 until (2 * line).toInt()) {
            val lit = steps.count { x >= it && x < it + line }
            assertTrue("pixel $x lit $lit of ${steps.size}", lit < steps.size)
        }
        val core = line * 0.55f
        for (i in steps.indices) {
            val a = steps[i] + (line - core) / 2f
            val b = steps[(i + 1) % steps.size] + (line - core) / 2f
            assertTrue("step $i: the core moved off its pixels", abs(a - b) >= core * 0.9f)
        }
    }

    /**
     * On the always-on display the frame shows every waiting app at once, each on an equal length
     * of the edge: by equal angles a tall screen gave the top's short edge a sliver and one app
     * most of a long side.
     */
    @Test
    fun theStillFramesAppsShareItsEdgeInEqualLengths() {
        val frame = Size(1080f, 2340f)
        assertEquals(0f, edgeTurnAt(0f, frame), 1e-6f)
        assertEquals(1f, edgeTurnAt(1f, frame), 1e-6f)
        // A quarter of the way round is the right side's middle, half way the bottom's.
        assertEquals(0.25f, edgeTurnAt(0.25f, frame), 1e-5f)
        assertEquals(0.5f, edgeTurnAt(0.5f, frame), 1e-5f)
        // Half the top's length round is its right corner, seen from the centre where the corner is.
        val corner = frame.width / 2f / (2f * (frame.width + frame.height))
        assertEquals(atan2(frame.width, frame.height) / (2f * PI.toFloat()), edgeTurnAt(corner, frame), 1e-5f)
        // Three apps: a third of the 6840 px round is 540 along the top and 1740 down the right
        // side, 570 below its middle; by equal angles it was 312 below, the bottom's part 38 % longer.
        assertEquals(0.25f + atan2(570f, 540f) / (2f * PI.toFloat()), edgeTurnAt(1f / 3f, frame), 1e-5f)
        var last = 0f
        for (i in 1..100) {
            val turn = edgeTurnAt(i / 100f, frame)
            assertTrue("clockwise at $i%", turn > last)
            last = turn
        }
    }
}
