package com.example.ambientglow.dashboard

import com.example.ambientglow.GlowApp
import com.example.ambientglow.PremiumState
import com.example.ambientglow.TRIAL_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAskTest {
    private val start = 1_000_000_000L
    private val week = REVIEW_AFTER_MS
    private val apps = listOf(app(start + 5_000), app(start))

    private fun app(firstSeen: Long) = GlowApp(pkg = "p$firstSeen", label = "p", firstSeen = firstSeen, lastSeen = firstSeen, autoColor = 0)

    private fun due(premium: PremiumState, now: Long, trialStart: Long = 0L, asked: Boolean = false) =
        reviewDue(apps, asked, premium, trialStart, now)

    @Test
    fun neverTriedAWeekAfterTheFirstMessageItAsksOnce() {
        assertFalse(due(PremiumState.Untried, now = start + week - 1))
        assertTrue(due(PremiumState.Untried, now = start + week))
        assertFalse(due(PremiumState.Untried, now = start + week, asked = true))
    }

    @Test
    fun withNoMessageYetItNeverAsks() {
        assertFalse(reviewDue(emptyList(), false, PremiumState.Untried, 0L, start + 100 * week))
    }

    @Test
    fun theTrialIsPremiumsWeekSoItNeverAsks() {
        assertFalse(due(PremiumState.Trial(1), now = start + 10 * week, trialStart = start + 9 * week))
    }

    @Test
    fun boughtItAsksOnceAWeekHasPassed() {
        assertTrue(due(PremiumState.Owned, now = start + week))
        assertFalse(due(PremiumState.Owned, now = start + week - 1))
    }

    @Test
    fun notBoughtItWaitsAWeekAfterTheTrialEnded() {
        val trialStart = start + 1_000
        val ended = trialStart + TRIAL_MS
        assertFalse(due(PremiumState.Over, now = ended, trialStart = trialStart))
        assertFalse(due(PremiumState.Over, now = ended + week - 1, trialStart = trialStart))
        assertTrue(due(PremiumState.Over, now = ended + week, trialStart = trialStart))
    }

    @Test
    fun aStagedTrialHasNoClockSoItNeverAsks() {
        assertFalse(due(PremiumState.Over, now = start + 100 * week, trialStart = 0L))
    }
}
