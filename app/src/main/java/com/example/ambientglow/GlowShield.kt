package com.example.ambientglow

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Optional overlay above the lock screen, for two short moments:
 *
 * - The arrival effect: when a new message lights the lock screen, the user's chosen style
 *   (edge frame, camera ring or dot) pulses over it, see-through and untouchable, so the lock
 *   screen and all its notifications stay visible and usable underneath. With the glass wave or
 *   the gust (Android 12+), the window also briefly blurs the lock screen behind it as the wave
 *   rolls over ([hazeTarget]): One UI's own blur ([SemBlur]), which can follow the wave, or Android's
 *   window blur, which can only blur the whole screen.
 * - A black cover for the LED hand-over: on every wake into the LED, One UI's lock-screen window
 *   stays on top of the glow screen for 100-450 ms and shows its battery icon and nav handle at
 *   the system brightness. The glow screen raises the cover just before such a wake and drops it
 *   once its own window owns the (hidden) bars. When the LED takes over a lit lock screen, the
 *   cover fades in over it first ([dimIn]), so the takeover isn't a cut to black.
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
    private var dim: Dim? = null
    private var arrival: FrameLayout? = null
    private var arrivalOwner: OverlayOwner? = null
    private var arrivalParams: WindowManager.LayoutParams? = null
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    override fun onServiceConnected() {
        instance = this
        GlowLog.d { "shield connected" }
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
        if (addOverlay(view, PixelFormat.OPAQUE, "AmbientGlow:shield") != null) {
            cover = view
            GlowLog.d { "shield up" }
        }
    }

    /**
     * The cover, faded in over [durationMs] above the arrival window. The effect's tail fades out
     * under it and is removed once the screen is black.
     */
    private fun addDimCover(durationMs: Long) {
        timers.removeCallbacks(hideNow)
        timers.postDelayed(hideNow, MAX_COVER_MS)
        if (cover != null) return
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        addOverlay(view, PixelFormat.TRANSLUCENT, "AmbientGlow:shield") ?: return
        cover = view
        GlowLog.d { "shield dimming" }
        dim = Dim(view, durationMs).also { Choreographer.getInstance().postFrameCallback(it) }
    }

    /**
     * The takeover dim, frame by frame on real time: a view animator follows the system animator
     * scale, so at 0.5x the dim would be half as long and at 0 a cut to black. An S-curve, since
     * alpha blends gamma-encoded values: an eased-in start and a long settle read as even dimming,
     * where a decelerate drops most of the light in the first frames. Bounded: it ends by itself.
     */
    private inner class Dim(private val view: View, private val durationMs: Long) : Choreographer.FrameCallback {
        private var startNanos = -1L

        override fun doFrame(frameTimeNanos: Long) {
            if (cover !== view) return
            if (startNanos < 0L) startNanos = frameTimeNanos
            val f = ((frameTimeNanos - startNanos) / 1_000_000f / durationMs).coerceIn(0f, 1f)
            view.alpha = DIM_EASE.getInterpolation(f)
            if (f < 1f) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                dim = null
                removeArrival()
            }
        }
    }

    private fun removeCover() {
        timers.removeCallbacks(hideNow)
        val view = cover ?: return
        cover = null
        // A cancelled dim never reaches its end, so it can never remove a newer arrival.
        dim?.let { Choreographer.getInstance().removeFrameCallback(it) }
        dim = null
        runCatching { windowManager.removeViewImmediate(view) }
        GlowLog.d { "shield down" }
    }

    private fun addArrival(color: Int) {
        removeArrival() // a newer message restarts the effect in its colour
        val settings = GlowPrefs.load(this)
        val owner = OverlayOwner()
        // A frame, so the blur views ([hazeTarget]) can sit beside the effect.
        // Owners on the window's root: Compose looks for them there.
        val view = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val haze = hazeTarget(settings, view)
        view.addView(
            ComposeView(this).apply {
                setContent {
                    val lens = geometry.value.fitted(settings, resources.displayMetrics.density)
                    ArrivalEffect(settings, color, lens, onDone = { removeArrival() }, onBlurBehind = haze)
                }
            },
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            geometry.value = ScreenGeometry.from(insets)
            insets
        }
        val params = addOverlay(view, PixelFormat.TRANSLUCENT, "AmbientGlow:arrival")
        if (params == null) {
            owner.destroy()
            return
        }
        arrival = view
        arrivalOwner = owner
        arrivalParams = params
        timers.postDelayed(stopArrivalNow, MAX_ARRIVAL_MS)
        GlowLog.d { "arrival up style=${settings.style}" }
    }

    private fun removeArrival() {
        timers.removeCallbacks(stopArrivalNow)
        val view = arrival ?: return
        arrival = null
        runCatching { windowManager.removeViewImmediate(view) }
        arrivalOwner?.destroy()
        arrivalOwner = null
        arrivalParams = null
        GlowLog.d { "arrival down" }
    }

    /**
     * The spawn wave's blur of the lock screen (Water's glass, Air's gust), or null where there is none to be had:
     * - One UI ([SemBlur]): a row of [BLUR_STRIPS] narrow blur views in [root], each moved every
     *   frame to where the wave crosses its column (One UI won't let apps cut a blur to a shape,
     *   but it blurs exactly a view's bounds). One full-window view for [GlassArea.SCREEN].
     * - Android's window blur (where the system allows it): the whole window, in steps, since
     *   every change is a relayout. It can't follow the wave, so every area blurs the screen.
     */
    private fun hazeTarget(settings: GlowSettings, root: FrameLayout): GlassHazeTarget? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !settings.hazes) return null
        val density = resources.displayMetrics.density
        val peak = settings.hazeBlur.radius.value * density
        fun stepped(level: Float) = (level * GLASS_BLUR_STEPS + 0.5f).toInt() / GLASS_BLUR_STEPS.toFloat()
        val area = if (SemBlur.available) settings.hazeArea else GlassArea.SCREEN
        return when {
            SemBlur.available && area == GlassArea.SCREEN -> {
                val view = blurView(root, FrameLayout.LayoutParams.MATCH_PARENT)
                var last = 0
                GlassHazeTarget { level, _ ->
                    val radius = (stepped(level) * peak * SemBlur.SCALE).roundToInt()
                    if (radius != last && arrival === root) {
                        last = radius
                        SemBlur.set(view, radius)
                    }
                }
            }
            SemBlur.available -> {
                val strips = Array(BLUR_STRIPS) { blurView(root, 0) }
                val last = IntArray(BLUR_STRIPS)
                val reveal = area == GlassArea.REVEAL
                GlassHazeTarget { level, wave ->
                    if (arrival !== root) return@GlassHazeTarget
                    val width = root.width
                    val height = root.height
                    val origin = waveOrigin(geometry.value.fitted(settings, density), width.toFloat(), density, 1f)
                    val radius = wave * waveReach(origin, width.toFloat(), height.toFloat())
                    val blur = (stepped(level) * peak * SemBlur.SCALE).roundToInt()
                    for (i in strips.indices) {
                        val left = width * i / BLUR_STRIPS
                        val right = width * (i + 1) / BLUR_STRIPS
                        val dx = abs((left + right) / 2f - origin.x)
                        val top: Float
                        val bottom: Float
                        if (reveal) {
                            // Everything the wave hasn't reached yet.
                            val edge = halfChord(HAZE_REVEAL_EDGE * radius, dx)
                            top = if (edge.isNaN()) 0f else origin.y + edge
                            bottom = height.toFloat()
                        } else {
                            // The band under the crest, where it crosses this column.
                            val outer = halfChord(HAZE_BAND_OUTER * radius, dx)
                            val inner = halfChord(HAZE_BAND_INNER * radius, dx)
                            top = if (outer.isNaN()) 0f else if (inner.isNaN()) origin.y - outer else origin.y + inner
                            bottom = if (outer.isNaN()) 0f else origin.y + outer
                        }
                        val view = strips[i]
                        val t = top.roundToInt().coerceIn(0, height)
                        val b = bottom.roundToInt().coerceIn(t, height)
                        // Placed by hand, without a layout pass; the params keep any later one in step.
                        (view.layoutParams as FrameLayout.LayoutParams).apply {
                            this.width = right - left
                            this.height = b - t
                            leftMargin = left
                            topMargin = t
                        }
                        view.layout(left, t, right, b)
                        val strip = if (b > t) blur else 0
                        if (strip != last[i]) {
                            last[i] = strip
                            SemBlur.set(view, strip)
                        }
                    }
                }
            }
            windowManager.isCrossWindowBlurEnabled -> GlassHazeTarget { level, _ ->
                setArrivalBlur((stepped(level) * peak).roundToInt())
            }
            else -> null
        }.also { GlowLog.d { "haze ${if (it == null) "none" else if (SemBlur.available) "one-ui $area" else "android screen"}" } }
    }

    /** An empty view behind the effect, for One UI to turn into a blur. */
    private fun blurView(root: FrameLayout, size: Int): View =
        View(this).also { root.addView(it, 0, FrameLayout.LayoutParams(size, size)) }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setArrivalBlur(radius: Int) {
        val view = arrival ?: return
        val params = arrivalParams ?: return
        if (params.blurBehindRadius == radius) return
        params.blurBehindRadius = radius
        params.flags = if (radius > 0) {
            params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** Full display, bars and cutout included; untouchable, so taps reach whatever is underneath. */
    private fun addOverlay(view: View, format: Int, name: String): WindowManager.LayoutParams? {
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
            params
        } catch (e: RuntimeException) {
            GlowLog.d { "shield refused $name: $e" }
            null
        }
    }

    companion object {
        /** Columns the One UI blur follows the wave in; more look smoother and cost more per frame. */
        private const val BLUR_STRIPS = 24

        /** Upper bound for one cover; a wake hand-over takes well under a second. */
        private const val MAX_COVER_MS = 2_000L

        /** Upper bound for the arrival overlay, in case the effect never reports done. */
        private const val MAX_ARRIVAL_MS = 4_000L

        // Alpha eases out so the remaining light falls about evenly to the eye (perceived lightness
        // is roughly the cube root of luminance); a linear or accelerating alpha holds the screen
        // bright and then snaps to black.
        private val DIM_EASE = PathInterpolator(0.33f, 0f, 0.2f, 1f)

        // Set only while the system has the service bound; cleared in onUnbind/onDestroy.
        @SuppressLint("StaticFieldLeak")
        private var instance: GlowShield? = null

        /** Covers the whole display, bars included. No-op when the service is off. */
        fun show() {
            instance?.addCover()
        }

        /** Fades the cover in over the lit lock screen, ending as the LED takes over. No-op when the service is off. */
        fun dimIn(durationMs: Long) {
            instance?.addDimCover(durationMs)
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

/** Half the height of a circle of [radius] at [dx] from its centre, or NaN where it doesn't reach. */
private fun halfChord(radius: Float, dx: Float): Float = sqrt(radius * radius - dx * dx)

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

/**
 * One UI's own window blur, for Samsung phones, which switch Android's cross-window blur off
 * ([WindowManager.isCrossWindowBlurEnabled] is false) and blur through `View.semSetBlurInfo`
 * instead. That is Samsung SDK API, cleared for apps but not in the Android SDK, so it is found
 * by reflection once; anywhere it is missing, or the model has no window blur, nothing happens.
 * It turns the view's background into a blur of whatever is behind its window, within the view's
 * bounds, applied by the render thread: neither a new radius nor moving the view costs a window
 * relayout, so it can follow the wave.
 */
private object SemBlur {
    private class Api(
        val builder: java.lang.reflect.Constructor<*>,
        val setRadius: java.lang.reflect.Method,
        val build: java.lang.reflect.Method,
        val apply: java.lang.reflect.Method,
        val windowMode: Int,
    )

    private val api: Api? by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            val info = Class.forName("android.view.SemBlurInfo")
            val builder = Class.forName("android.view.SemBlurInfo\$Builder")
            Api(
                builder = builder.getConstructor(Int::class.javaPrimitiveType),
                setRadius = builder.getMethod("setRadius", Int::class.javaPrimitiveType),
                build = builder.getMethod("build"),
                apply = View::class.java.getMethod("semSetBlurInfo", info),
                windowMode = info.getField("BLUR_MODE_WINDOW").getInt(null),
            )
        }.onFailure { GlowLog.d { "no One UI blur: $it" } }.getOrNull()
    }

    val available: Boolean get() = api != null

    /**
     * One UI's radius runs on its own, much larger scale (System UI asks 300 for its frosted
     * notification cards and 128 for the PIN pad); this brings [GLASS_BLUR] to the same look.
     */
    const val SCALE = 3f

    /** Blurs what is behind [view]'s window, within its bounds, by [radius] (One UI's scale); 0 clears it. */
    fun set(view: View, radius: Int) {
        val api = api ?: return
        runCatching {
            if (radius <= 0) {
                api.apply.invoke(view, null)
            } else {
                val builder = api.builder.newInstance(api.windowMode)
                api.setRadius.invoke(builder, radius)
                api.apply.invoke(view, api.build.invoke(builder))
            }
        }.onFailure { GlowLog.d { "One UI blur failed: $it" } }
    }
}
