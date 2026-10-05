package com.example.ambientglow.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

/** Dashboard chrome motion. It follows the system animator scale; the live effect uses RealTimeMotion instead. */
object GlowMotion {
    const val EXIT_MS = 90
    const val STATE_MS = 150
    const val ENTER_MS = 220
    const val ENTER_DELAY_MS = 70

    /** Space opening for new content: settles without bounce (within 5% at ~210 ms). */
    val SizeIn: SpringSpec<IntSize> = spring(1f, 500f, IntSize.VisibilityThreshold)

    /** Space closing: faster than [SizeIn]. */
    val SizeOut: SpringSpec<IntSize> = spring(1f, 700f, IntSize.VisibilityThreshold)

    /** Unfolds from the top: size settles first ([SizeIn]), then the content fades in. */
    val DisclosureEnter: EnterTransition =
        expandVertically(SizeIn, expandFrom = Alignment.Top) +
            fadeIn(tween(ENTER_MS, ENTER_DELAY_MS, LinearOutSlowInEasing))

    /** Faster than the way in: the space starts closing at once, and the quicker fade is done well before it. */
    val DisclosureExit: ExitTransition =
        fadeOut(tween(EXIT_MS, easing = FastOutLinearInEasing)) +
            shrinkVertically(SizeOut, shrinkTowards = Alignment.Top)

    /** A label or line replaced in place: the old one goes at once, the new one fades in after it. */
    val SwapIn: EnterTransition = fadeIn(tween(ENTER_MS, ENTER_DELAY_MS, LinearOutSlowInEasing))
    val SwapOut: ExitTransition = fadeOut(tween(EXIT_MS, easing = FastOutLinearInEasing))

    /** [SwapIn] / [SwapOut] for AnimatedContent, the space easing to the new content's size. */
    fun swap(): ContentTransform = ContentTransform(SwapIn, SwapOut, sizeTransform = SizeTransform(clip = false) { _, _ -> SizeIn })

    /** A state turning (selection, tint, dim): eased both ways, read in draw where it can be. */
    fun <T> stateChange(): TweenSpec<T> = tween(STATE_MS, easing = FastOutSlowInEasing)

    /** Selection that slides (chips, swatches): near-critical, so long hops barely overshoot. */
    val Slide: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 600f)
}
