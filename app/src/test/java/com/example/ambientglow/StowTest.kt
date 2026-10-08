package com.example.ambientglow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StowTest {

    private val faceDown = -9.7f
    private val faceUp = 9.7f

    /** A sample with gravity [z] out of the screen, the other axes level. */
    private fun StowDetector.at(now: Long, z: Float) = sample(now, 0f, 0f, z)

    /** Lying still face down from [from] to [to], a sample every 100 ms (as the S23 delivers them). */
    private fun StowDetector.lying(from: Long, to: Long, z: Float = faceDown): Boolean {
        var stowed = false
        for (t in from..to step 100L) stowed = at(t, z)
        return stowed
    }

    @Test
    fun faceDownCountsOnceItHasLainStillAMoment() {
        val detector = StowDetector()
        assertFalse(detector.lying(0L, FACE_DOWN_MS - 100L))
        assertTrue(detector.at(FACE_DOWN_MS, faceDown))
    }

    @Test
    fun heldFaceDownInTheAirIsBeingLookedAt() {
        // The steadiest hand measured on the S23 still moved 0.56 m/s² between samples.
        val detector = StowDetector()
        var stowed = false
        for (i in 0..100) stowed = detector.sample(i * 100L, if (i % 2 == 0) 0.3f else -0.3f, 0f, faceDown)
        assertFalse(stowed)
    }

    @Test
    fun theStillTimeCountsFromWhenItWasSetDown() {
        val detector = StowDetector()
        detector.at(0L, faceDown)
        detector.at(100L, faceDown - 1.5f) // the jolt of setting it down
        // Settling back from the jolt is movement too: still from the sample after it.
        assertFalse(detector.lying(200L, 200L + FACE_DOWN_MS - 100L))
        assertTrue(detector.at(200L + FACE_DOWN_MS, faceDown))
    }

    @Test
    fun turningItOverInTheHandDoesNot() {
        val detector = StowDetector()
        detector.lying(0L, 1_000L)
        detector.at(1_100L, faceUp) // turned back up
        assertFalse(detector.at(1_500L, faceDown))
        assertFalse(detector.lying(1_600L, 1_500L + FACE_DOWN_MS - 100L))
        assertTrue(detector.at(1_600L + FACE_DOWN_MS, faceDown))
    }

    @Test
    fun aSlightTiltWhileLyingFaceDownKeepsItsTime() {
        val detector = StowDetector()
        detector.lying(0L, 700L)
        assertTrue(detector.lying(800L, FACE_DOWN_MS))
        // Between the two thresholds: neither newly face down nor turned over.
        val tilted = StowDetector()
        tilted.lying(0L, 100L)
        tilted.at(200L, -7f) // tipped onto an edge: a jolt, so the still time starts over
        assertFalse(tilted.lying(300L, 200L + FACE_DOWN_MS - 100L, z = -7f))
        assertTrue(tilted.at(200L + FACE_DOWN_MS, -7f))
    }

    /** Samples every 100 ms from [from] to [to] at gravity [z]: true once it lies face down. */
    private fun FaceDownLook.lying(from: Long, to: Long, z: Float = faceDown): Boolean {
        var down = false
        for (t in from..to step 100L) down = sample(t, 0f, 0f, z) || down
        return down
    }

    /** Held in a hand: never still, face up. */
    private fun FaceDownLook.held(from: Long, to: Long) {
        for (t in from..to step 100L) sample(t, if ((t / 100L) % 2L == 0L) 0.4f else -0.4f, 0f, faceUp)
    }

    @Test
    fun aLookSeesAPhoneLaidFaceDownAndCloses() {
        val look = FaceDownLook()
        assertTrue("the first turn opens it", look.turned(0L))
        look.held(0L, 1_000L)
        assertFalse(look.lying(1_100L, 1_100L + FACE_DOWN_MS - 100L))
        assertTrue(look.lying(1_100L + FACE_DOWN_MS, 1_100L + FACE_DOWN_MS))
        assertFalse("done: no more samples wanted", look.open)
    }

    @Test
    fun aLookInAHandClosesAfterItsTime() {
        val look = FaceDownLook()
        look.turned(0L)
        look.held(0L, LOOK_MS - 100L)
        assertTrue(look.open)
        look.held(LOOK_MS, LOOK_MS)
        assertFalse(look.open)
    }

    @Test
    fun aTurnDuringALookHoldsItOpen() {
        // The LED lights in a hand (a look opens), and late in it the phone is laid face down.
        val look = FaceDownLook()
        look.turned(0L)
        look.held(0L, 4_000L)
        assertFalse("already open: no second sampler", look.turned(4_000L))
        look.held(4_100L, 4_900L)
        // Still from 5 s: face down for FACE_DOWN_MS only past the first window's end.
        assertTrue(look.lying(5_000L, 5_000L + FACE_DOWN_MS))
        assertTrue(5_000L + FACE_DOWN_MS > LOOK_MS)
    }

    @Test
    fun aNewLookStartsAfresh() {
        val look = FaceDownLook()
        look.turned(0L)
        look.lying(0L, 700L) // face down, but turned back up before it counted
        look.held(800L, LOOK_MS)
        assertFalse(look.open)
        assertFalse("closed: samples are ignored", look.sample(LOOK_MS + 100L, 0f, 0f, faceDown))
        assertTrue(look.turned(10_000L))
        assertFalse("the old still time is gone", look.lying(10_000L, 10_000L + FACE_DOWN_MS - 100L))
        assertTrue(look.lying(10_000L + FACE_DOWN_MS, 10_000L + FACE_DOWN_MS))
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
