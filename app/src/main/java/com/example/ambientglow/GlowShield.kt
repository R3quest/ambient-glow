package com.example.ambientglow

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Optional overlay above the lock screen, for two short moments:
 *
 * - The arrival effect: when a new message lights the lock screen, the user's chosen style
 *   (edge frame, camera ring or dot) pulses over it, see-through and untouchable, so the lock
 *   screen and all its notifications stay visible and usable underneath.
 * - A black cover for the LED hand-over: on every wake into the LED, One UI's lock-screen window
 *   stays on top of the glow screen for 100-450 ms and shows its battery icon and nav handle at
 *   the system brightness. The glow screen raises the cover just before such a wake and drops it
 *   once its own window owns the (hidden) bars.
 *
 * No app window can draw over a visible lock screen; an accessibility overlay can, because it
 * sits above the system bars and the keyguard. The service subscribes to no accessibility events
 * and reads no window content, so it costs nothing while idle. If the user hasn't enabled it,
 * every call here does nothing and the lock screen simply lights up.
 */
class GlowShield : AccessibilityService() {

    private val windowManager by lazy(LazyThreadSafetyMode.NONE) { getSystemService(WindowManager::class.java) }
    private val timers = Handler(Looper.getMainLooper())
    private val hideNow = Runnable { removeCover() }
    private val stopArrivalNow = Runnable { removeArrival() }
    private var cover: View? = null
    private var arrival: ComposeView? = null
    private var arrivalOwner: OverlayOwner? = null
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    override fun onServiceConnected() {
        instance = this
        GlowLog.d("shield connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun release() {
        removeArrival()
        removeCover()
        if (instance === this) instance = null
    }

    private fun addCover() {
        removeArrival() // the LED is taking over; the effect has done its job
        timers.removeCallbacks(hideNow)
        // Never left up by accident: a missed hide() still clears it.
        timers.postDelayed(hideNow, MAX_COVER_MS)
        if (cover != null) return
        val view = View(this).apply { setBackgroundColor(Color.BLACK) }
        if (addOverlay(view, PixelFormat.OPAQUE, "AmbientGlow:shield")) {
            cover = view
            GlowLog.d("shield up")
        }
    }

    private fun removeCover() {
        timers.removeCallbacks(hideNow)
        val view = cover ?: return
        cover = null
        runCatching { windowManager.removeViewImmediate(view) }
        GlowLog.d("shield down")
    }

    private fun addArrival(color: Int) {
        removeArrival() // a newer message restarts the effect in its colour
        val settings = GlowPrefs.load(this)
        val owner = OverlayOwner()
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val lens = geometry.value.fitted(settings, resources.displayMetrics.density)
                ArrivalEffect(settings, color, lens, onDone = { removeArrival() })
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            geometry.value = ScreenGeometry.from(insets)
            insets
        }
        if (!addOverlay(view, PixelFormat.TRANSLUCENT, "AmbientGlow:arrival")) {
            owner.destroy()
            return
        }
        arrival = view
        arrivalOwner = owner
        timers.postDelayed(stopArrivalNow, MAX_ARRIVAL_MS)
        GlowLog.d("arrival up style=${settings.style}")
    }

    private fun removeArrival() {
        timers.removeCallbacks(stopArrivalNow)
        val view = arrival ?: return
        arrival = null
        runCatching { windowManager.removeViewImmediate(view) }
        arrivalOwner?.destroy()
        arrivalOwner = null
        GlowLog.d("arrival down")
    }

    /** Full display, bars and cutout included; untouchable, so taps reach whatever is underneath. */
    private fun addOverlay(view: View, format: Int, name: String): Boolean {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            format,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            windowAnimations = 0
            title = name
        }
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: RuntimeException) {
            GlowLog.d("shield refused $name: $e")
            false
        }
    }

    companion object {
        /** Upper bound for one cover; a wake hand-over takes well under a second. */
        private const val MAX_COVER_MS = 2_000L

        /** Upper bound for the arrival overlay, in case the effect never reports done. */
        private const val MAX_ARRIVAL_MS = 4_000L

        // Set only while the system has the service bound; cleared in onUnbind/onDestroy.
        @SuppressLint("StaticFieldLeak")
        private var instance: GlowShield? = null

        /** Covers the whole display, bars included. No-op when the service is off. */
        fun show() {
            instance?.addCover()
        }

        fun hide() {
            instance?.removeCover()
        }

        /** Plays the chosen effect once over whatever is on screen. No-op when the service is off. */
        fun playArrival(color: Int) {
            instance?.addArrival(color)
        }

        fun stopArrival() {
            instance?.removeArrival()
        }
    }
}

/** Minimal lifecycle for a ComposeView in a service window: resumed while attached, then destroyed. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
