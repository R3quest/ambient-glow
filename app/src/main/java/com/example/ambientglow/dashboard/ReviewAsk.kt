package com.example.ambientglow.dashboard

import android.app.Activity
import com.example.ambientglow.GlowApp
import com.example.ambientglow.GlowLog
import com.google.android.play.core.review.ReviewManagerFactory

/** How long Ambient Glow has worked before it asks for a review: a week after the first message. */
internal const val REVIEW_AFTER_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * Whether to ask for a review now, given the apps heard from: once, after Ambient Glow has
 * lit up for messages for [REVIEW_AFTER_MS]. The apps' first messages are already stored, so
 * nothing is counted per message.
 */
internal fun reviewDue(apps: List<GlowApp>, asked: Boolean, now: Long): Boolean =
    !asked && apps.isNotEmpty() && now - apps.minOf { it.firstSeen } >= REVIEW_AFTER_MS

/**
 * Google Play's own review card over [activity]. Play decides whether it shows (it limits how
 * often), and never says whether it did, so the dashboard asks once and lets it go.
 */
internal fun askForReview(activity: Activity) {
    val reviews = ReviewManagerFactory.create(activity)
    reviews.requestReviewFlow().addOnCompleteListener { request ->
        GlowLog.d { "review request ok=${request.isSuccessful}" }
        if (request.isSuccessful && !activity.isFinishing) reviews.launchReviewFlow(activity, request.result)
    }
}
