package com.example.ambientglow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Lights the panel without launching anything: the screen comes on showing whatever is on top,
 * the system lock screen or the LED, exactly as it was arranged while the screen was dark.
 * A screen wake lock with ACQUIRE_CAUSES_WAKEUP is still honoured for apps targeting SDK 35
 * (the TURN_SCREEN_ON requirement is not enabled for any released target SDK). It auto-releases,
 * and ON_AFTER_RELEASE leaves the normal lock-screen timeout in charge afterwards.
 */
object PanelWaker {
    private const val HOLD_MS = 1_000L

    @Suppress("DEPRECATION")
    fun wake(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        GlowLog.d { "panel wake" }
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "AmbientGlow:panel",
        ).apply { setReferenceCounted(false) }.acquire(HOLD_MS)
    }
}

/**
 * Keeps the CPU up across a short dark gap (screen just went off, wake due in a few hundred ms).
 * Handler time stops while the CPU is suspended, so without this the panel could stay dark.
 */
object DarkHold {
    private const val HOLD_MS = 2_000L
    private var lock: PowerManager.WakeLock? = null
    private var heldUntil = 0L

    /** Holds the CPU for at least [holdMs]; never shortens a longer hold already running. */
    fun acquire(context: Context, holdMs: Long = HOLD_MS) {
        val until = SystemClock.elapsedRealtime() + holdMs
        val held = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AmbientGlow:dark")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        if (held.isHeld && until <= heldUntil) return
        heldUntil = until
        held.acquire(holdMs)
    }
}

/** Proximity blanks the screen during calls; never light up over a call. */
internal val AudioManager.inCall: Boolean get() = mode != AudioManager.MODE_NORMAL

/**
 * Runs [then] once, when the call in progress is over (audio back to normal). Every relight bails
 * out during a call; without this, a screen turned off before the call had quite ended (the power
 * key hanging up) would stay dark, LED and all, until something else woke it. Android 12+, where
 * the audio mode can be followed without a permission; below it, the next wake brings the LED back.
 */
internal class AfterCall(private val context: Context, private val then: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var listener: Any? = null

    fun arm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || listener != null) return
        val onMode = AudioManager.OnModeChangedListener { mode ->
            if (mode != AudioManager.MODE_NORMAL) return@OnModeChangedListener
            cancel()
            GlowLog.d { "call over" }
            DarkHold.acquire(context) // woken by the hang-up: keep the CPU up for the relight
            then()
        }
        listener = onMode
        audio.addOnModeChangedListener(context.mainExecutor, onMode)
    }

    fun cancel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        (listener as? AudioManager.OnModeChangedListener)?.let(audio::removeOnModeChangedListener)
        listener = null
    }
}

/** Registers [receiver] for the panel turning off and on (system broadcasts, not exported). */
internal fun Context.registerScreenEvents(receiver: BroadcastReceiver) {
    ContextCompat.registerReceiver(
        this,
        receiver,
        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        },
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
}
