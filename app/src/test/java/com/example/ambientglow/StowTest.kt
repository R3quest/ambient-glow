package com.example.ambientglow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StowTest {

    private val faceDown = -9.7f
    private val faceUp = 9.7f

    /** A sample with gravity [z] out of the screen, the other axes level. */
    private fun StowDetector.at(now: Long, z: Float, covered: Boolean = false) = sample(now, 0f, 0f, z, covered)

    /** Lying still face down from [from] to [to], a sample every 100 ms (as the S23 delivers them). */
    private fun StowDetector.lying(from: Long, to: Long, z: Float = faceDown): Boolean {
        var stowed = false
        for (t in from..to step 100L) stowed = at(t, z)
        return stowed
    }

    @Test
    fun faceDownCountsOnceItHasLainStillAMoment() {
        val detector = StowDetector(faceDownCounts = true)
        assertFalse(detector.lying(0L, FACE_DOWN_MS - 100L))
        assertTrue(detector.at(FACE_DOWN_MS, faceDown))
    }

    @Test
    fun heldFaceDownInTheAirIsBeingLookedAt() {
        // The steadiest hand measured on the S23 still moved 0.56 m/s² between samples.
        val detector = StowDetector(faceDownCounts = true)
        var stowed = false
        for (i in 0..100) stowed = detector.sample(i * 100L, if (i % 2 == 0) 0.3f else -0.3f, 0f, faceDown, covered = false)
        assertFalse(stowed)
    }

    @Test
    fun theStillTimeCountsFromWhenItWasSetDown() {
        val detector = StowDetector(faceDownCounts = true)
        detector.at(0L, faceDown)
        detector.at(100L, faceDown - 1.5f) // the jolt of setting it down
        // Settling back from the jolt is movement too: still from the sample after it.
        assertFalse(detector.lying(200L, 200L + FACE_DOWN_MS - 100L))
        assertTrue(detector.at(200L + FACE_DOWN_MS, faceDown))
    }

    @Test
    fun turningItOverInTheHandDoesNot() {
        val detector = StowDetector(faceDownCounts = true)
        detector.lying(0L, 1_000L)
        detector.at(1_100L, faceUp) // turned back up
        assertFalse(detector.at(1_500L, faceDown))
        assertFalse(detector.lying(1_600L, 1_500L + FACE_DOWN_MS - 100L))
        assertTrue(detector.at(1_600L + FACE_DOWN_MS, faceDown))
    }

    @Test
    fun aSlightTiltWhileLyingFaceDownKeepsItsTime() {
        val detector = StowDetector(faceDownCounts = true)
        detector.lying(0L, 700L)
        assertTrue(detector.lying(800L, FACE_DOWN_MS))
        // Between the two thresholds: neither newly face down nor turned over.
        val tilted = StowDetector(faceDownCounts = true)
        tilted.lying(0L, 100L)
        tilted.at(200L, -7f) // tipped onto an edge: a jolt, so the still time starts over
        assertFalse(tilted.lying(300L, 200L + FACE_DOWN_MS - 100L, z = -7f))
        assertTrue(tilted.at(200L + FACE_DOWN_MS, -7f))
    }

    @Test
    fun withNothingToWakeItFaceDownAloneNeverCounts() {
        val detector = StowDetector(faceDownCounts = false)
        assertFalse(detector.lying(0L, 60_000L))
    }

    @Test
    fun coveredCountsAfterALongerWhileEvenOnTheMove() {
        val detector = StowDetector(faceDownCounts = false)
        // A pocket while walking: never still, always covered.
        var stowed = false
        var t = 0L
        while (t < COVERED_MS) {
            stowed = detector.sample(t, if ((t / 100L) % 2L == 0L) 1f else -1f, 0f, 2f, covered = true)
            assertFalse("at $t", stowed)
            t += 100L
        }
        assertTrue(detector.sample(COVERED_MS, 1f, 0f, 2f, covered = true))
    }

    @Test
    fun aHandPassingOverStartsTheCountAgain() {
        val detector = StowDetector(faceDownCounts = true)
        detector.at(0L, faceUp, covered = true)
        detector.at(5_000L, faceUp, covered = false)
        assertFalse(detector.at(6_000L, faceUp, covered = true))
        assertFalse(detector.at(6_000L + COVERED_MS - 1, faceUp, covered = true))
        assertTrue(detector.at(6_000L + COVERED_MS, faceUp, covered = true))
    }

    @Test
    fun takenOutMeansUncoveredAndNoLongerFaceDown() {
        assertTrue(takenOut(faceUp, near = false))
        assertTrue("held upright", takenOut(0f, near = false))
        assertFalse("still face down on glass", takenOut(faceDown, near = false))
        assertFalse("in the pocket", takenOut(0f, near = true))
        assertFalse("lifted a little, still mostly face down", takenOut(-7f, near = false))
    }
}
