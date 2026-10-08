package com.example.ambientglow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Lights the panel without launching anything: the screen comes on showing whatever is on top,
 * the system lock screen or the LED, exactly as it was arranged while the screen was dark.
 * A screen wake lock with ACQUIRE_CAUSES_WAKEUP is still honoured for apps targeting SDK 36: the
 * TURN_SCREEN_ON requirement (REQUIRE_TURN_SCREEN_ON_PERMISSION) is enabled for no released target
 * SDK (10000 in `dumpsys platform_compat` on Android 16 QPR2). It auto-releases,
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
 * key hanging up) would stay dark, LED and all, until something else woke it.
 *
 * Event-driven on every version. Android 12+ reports the audio mode itself (no permission). Below
 * it nothing does, so the events that come with a call ending are heard instead, neither needing a
 * permission there: the carrier call going idle, and the audio playing changing (a VoIP call's
 * voice stream stopping). Each looks at the mode then, and once more [SETTLE_MS] later in case
 * the mode trails the event. Nothing runs between events.
 */
internal class AfterCall(private val context: Context, private val then: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var armed = false

    // Android 12+.
    private var modeListener: Any? = null

    // Below Android 12, where PhoneStateListener (deprecated since) is the call-state event.
    @Suppress("DEPRECATION")
    private var callListener: PhoneStateListener? = null
    private var playbackListener: AudioManager.AudioPlaybackCallback? = null
    private val lookAgain = Runnable { look() }

    fun arm() {
        if (armed) return
        armed = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val onMode = AudioManager.OnModeChangedListener { mode -> if (mode == AudioManager.MODE_NORMAL) look() }
            modeListener = onMode
            audio.addOnModeChangedListener(context.mainExecutor, onMode)
            return
        }
        @Suppress("DEPRECATION")
        val onCall = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_IDLE) heard()
            }
        }
        callListener = onCall
        @Suppress("DEPRECATION")
        context.getSystemService(TelephonyManager::class.java)?.listen(onCall, PhoneStateListener.LISTEN_CALL_STATE)
        val onPlayback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = heard()
        }
        playbackListener = onPlayback
        audio.registerAudioPlaybackCallback(onPlayback, main)
    }

    /** Something that comes with a call ending happened: look now, and once more a moment later. */
    private fun heard() {
        look()
        if (!armed) return
        DarkHold.acquire(context, SETTLE_MS + 1_000L)
        main.removeCallbacks(lookAgain)
        main.postDelayed(lookAgain, SETTLE_MS)
    }

    private fun look() {
        if (!armed || audio.inCall) return
        cancel()
        GlowLog.d { "call over" }
        DarkHold.acquire(context) // woken by the hang-up: keep the CPU up for the relight
        then()
    }

    fun cancel() {
        if (!armed) return
        armed = false
        main.removeCallbacks(lookAgain)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let(audio::removeOnModeChangedListener)
            modeListener = null
            return
        }
        callListener?.let {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        callListener = null
        playbackListener?.let(audio::unregisterAudioPlaybackCallback)
        playbackListener = null
    }

    private companion object {
        /** How long the audio mode may trail the event that ends a call. */
        const val SETTLE_MS = 1_500L
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
