package com.example.ambientglow

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit

/** How long premium is free to try. */
const val TRIAL_DAYS = 7

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Where premium stands: Fire, Air and Earth, and an app's own colour. It is bought once, and free
 * to try for [TRIAL_DAYS] from the first message it plays for, so the week counts only once it
 * is seen on the lock screen; the dashboard's previews play it always. Every state but [Over]
 * plays it.
 */
@Immutable
sealed interface PremiumState {
    /** Bought, or unlocked at build time ([BuildConfig.PREMIUM]). */
    data object Owned : PremiumState

    /** Not played for a message yet: the trial starts when it is. */
    data object Untried : PremiumState

    /** In its trial, with [daysLeft] (1 is the last day). */
    data class Trial(val daysLeft: Int) : PremiumState

    /** The trial is over: messages arrive in the free look until it is bought. */
    data object Over : PremiumState

    val unlocked: Boolean get() = this != Over
}

/**
 * Where premium stands at [now] with the trial started at [trialStart] (0: not yet). A clock set
 * back before the start counts as the trial's first moment, not as more days.
 */
internal fun premiumState(owned: Boolean, trialStart: Long, now: Long): PremiumState {
    if (owned) return PremiumState.Owned
    if (trialStart <= 0L) return PremiumState.Untried
    val left = trialStart + TRIAL_DAYS * DAY_MS - now.coerceAtLeast(trialStart)
    if (left <= 0L) return PremiumState.Over
    return PremiumState.Trial(daysLeft = ((left + DAY_MS - 1) / DAY_MS).toInt())
}

/**
 * The stage a build was given ([BuildConfig.TRIAL], `make install TRIAL=over`), whatever the
 * clock says, or null: `untried`, `over`, or the days left.
 */
internal fun trialStage(value: String): PremiumState? = when (value) {
    "" -> null
    "untried" -> PremiumState.Untried
    "over" -> PremiumState.Over
    else -> value.toIntOrNull()?.takeIf { it in 1..TRIAL_DAYS }?.let { PremiumState.Trial(it) }
}

/**
 * The look as it plays on the phone: once the trial is over, a premium element arrives as Water,
 * the free one, while the choice itself stays saved for when premium is bought.
 */
fun GlowSettings.playable(unlocked: Boolean): GlowSettings =
    if (unlocked || !element.premium) this else copy(element = SpawnElement.WATER)

/** This look plays a premium element when a message arrives. */
val GlowSettings.playsPremium: Boolean get() = spawn && element.premium && arrival.playsEffect

/**
 * Premium's own file: whether it is owned (as Google Play last said, see the dashboard's
 * PremiumModel) and when its trial started. Read by the listener and the glow screen on each
 * message, which never talk to Play themselves.
 */
object Premium {
    private const val FILE = "ambient_glow_premium"
    private const val KEY_OWNED = "owned"
    private const val KEY_TRIAL_START = "trial_start"

    /** The dashboard has said the trial is over, so it says it once. */
    private const val KEY_OVER_SEEN = "over_seen"

    /** A staged trial ([trialStage]) writes nothing: a normal build after it starts fresh. */
    private val staged = trialStage(BuildConfig.TRIAL)

    /** A staged trial's card, dismissed until the app is next started, so it can be seen again. */
    private var stagedOverSeen = false

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun state(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): PremiumState =
        if (BuildConfig.PREMIUM) {
            PremiumState.Owned
        } else {
            staged ?: premiumState(prefs.getBoolean(KEY_OWNED, false), prefs.getLong(KEY_TRIAL_START, 0L), now)
        }

    fun unlocked(context: Context): Boolean = state(prefs(context)).unlocked

    /** A message played premium at [now]: the trial starts with the first one. */
    fun played(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (staged == null && state(prefs, now) == PremiumState.Untried) prefs.edit { putLong(KEY_TRIAL_START, now) }
    }

    fun setOwned(prefs: SharedPreferences, owned: Boolean) {
        if (prefs.getBoolean(KEY_OWNED, false) != owned) prefs.edit { putBoolean(KEY_OWNED, owned) }
    }

    fun overSeen(prefs: SharedPreferences): Boolean =
        if (staged != null) stagedOverSeen else prefs.getBoolean(KEY_OVER_SEEN, false)

    fun markOverSeen(prefs: SharedPreferences) {
        if (staged != null) stagedOverSeen = true else prefs.edit { putBoolean(KEY_OVER_SEEN, true) }
    }
}
