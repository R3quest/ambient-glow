package com.example.ambientglow.dashboard

import com.example.ambientglow.GlowApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAskTest {
    private val start = 1_000_000_000L

    private fun app(firstSeen: Long) = GlowApp(pkg = "p$firstSeen", label = "p", firstSeen = firstSeen, lastSeen = firstSeen, autoColor = 0)

    @Test
    fun aWeekAfterTheFirstMessageItAsksOnce() {
        val apps = listOf(app(start + 5_000), app(start))
        assertFalse(reviewDue(apps, asked = false, now = start + REVIEW_AFTER_MS - 1))
        assertTrue(reviewDue(apps, asked = false, now = start + REVIEW_AFTER_MS))
        assertFalse(reviewDue(apps, asked = true, now = start + REVIEW_AFTER_MS))
    }

    @Test
    fun withNoMessageYetItNeverAsks() {
        assertFalse(reviewDue(emptyList(), asked = false, now = start + 100 * REVIEW_AFTER_MS))
    }
}
