package com.example.ambientglow.dashboard

import android.app.Activity
import com.example.ambientglow.GlowApp
import com.example.ambientglow.GlowLog
import com.example.ambientglow.PremiumState
import com.example.ambientglow.TRIAL_MS
import com.google.android.play.core.review.ReviewManagerFactory

/** How long Ambient Glow has worked before it asks for a review. */
internal const val REVIEW_AFTER_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * Whether to ask for a review now: once, and never in premium's way, since a purchase matters
 * more than a rating. Not during the trial, while premium is being decided. Bought, or never
 * tried: once Ambient Glow has lit up for messages for [REVIEW_AFTER_MS] (the apps' first
 * messages are already stored, so nothing is counted per message); after buying, that is usually
 * the next open. Tried and not bought: [REVIEW_AFTER_MS] after the trial ended, not while losing
 * it stings.
 */
internal fun reviewDue(apps: List<GlowApp>, asked: Boolean, premium: PremiumState, trialStart: Long, now: Long): Boolean {
    if (asked || apps.isEmpty()) return false
    val since = when (premium) {
        is PremiumState.Trial -> return false
        PremiumState.Over -> if (trialStart > 0L) trialStart + TRIAL_MS else return false
        PremiumState.Owned, PremiumState.Untried -> apps.minOf { it.firstSeen }
    }
    return now - since >= REVIEW_AFTER_MS
}

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
