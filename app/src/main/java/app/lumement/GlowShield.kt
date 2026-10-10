package app.lumement

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
import app.lumement.dashboard.GrantReturn
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Optional overlay above the lock screen, for a few short moments:
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
 *   screen fades to black first ([dimIn]), so the takeover isn't a cut to black: under the
 *   effect while it plays, so its last light still lands on the LED, then as the cover. The Edge
 *   Frame's LED goes out on its first exhale in that cover, and the glow screen's own LED starts
 *   once it has ([hideWhenDone]), so the light never cuts between the two windows.
 * - The LED's blinks: between two breaths the phone sleeps ([sleepNow]), so the power key wakes
 *   it straight into the lock screen; over a lit LED a press is a sleep request, which One UI
 *   acts on only after waiting for more presses. Each breath plays in a blink window from the
 *   moment the panel is on ([showBlink]), so the wake hand-over isn't dark time. A finger on it
 *   puts the lock screen in front under it, so the fingerprint can unlock the phone.
 * - The LED on the always-on display, where it shows: the phone dozes, and the LED breathes over
 *   it in a window put up while the screen is on ([prepareAodLed], [AodBreath]).
 *
 * No app window can draw over a visible lock screen; an accessibility overlay can, because it
 * sits above the system bars and the keyguard. The service subscribes to no accessibility events
 * and reads no window content, so it costs nothing while idle. If the user hasn't enabled it,
 * every call here does nothing: the lock screen simply lights up, and the LED stays lit.
 */
class GlowShield : AccessibilityService() {

    private val windowManager by lazy(LazyThreadSafetyMode.NONE) { getSystemService(WindowManager::class.java) }
    private val timers = Handler(Looper.getMainLooper())
    private val hideNow = Runnable { removeCover() }
    private val stopArrivalNow = Runnable { removeArrival() }
    private var cover: View? = null
    private var coverParams: WindowManager.LayoutParams? = null
    private var dim: Dim? = null

    /** The dim, while it plays under the effect in the effect's own window ([addDimCover]). */
    private var backdrop: View? = null
    private var arrival: FrameLayout? = null
    private var arrivalEffect: View? = null
    private var arrivalOwner: OverlayOwner? = null

    /**
     * The effect's last light (the Edge Frame's LED going out on its first exhale), still playing
     * in the window it left as the cover ([keepAsCover]), and what keeps its composition alive.
     */
    private var tail: View? = null
    private var tailOwner: OverlayOwner? = null

    /** Asked for once the cover is gone, the tail played out first ([hideWhenDone]). */
    private var onCoverGone: (() -> Unit)? = null
    private var arrivalParams: WindowManager.LayoutParams? = null
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    override fun onServiceConnected() {
        instance = this
        GlowLog.d { "shield connected" }
        GrantReturn.granted(this, GrantReturn.Grant.SHIELD)
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

    /** The blink window ([addBlink]), what keeps its composition alive, and its breath's go-ahead. */
    private var blink: View? = null
    private var blinkOwner: OverlayOwner? = null
    private val blinkRunning = mutableStateOf(false)
    private var blinkTouch: (() -> Unit)? = null
    private var blinkTap: (() -> Unit)? = null
    private var blinkDone: (() -> Unit)? = null
    private var blinkDowns = 0

    /** A finger is on the blink window. */
    private var blinkHeld = false

    /** The breath went out under a finger: its tap waits for the finger to lift. */
    private var blinkOutHeld = false
    private val blinkTapNow = Runnable { tapBlink() }
    private val blinkTimeout = Runnable { finishBlink() }

    /**
     * Black over everything, bars included, with one LED breath in [color] that starts once the
     * panel is on ([blinkRunning]). It stands in for the glow screen while the phone wakes for a
     * breath, so touches land here. The first finger down calls [onTouch] at once: it may be on
     * the fingerprint sensor, to unlock. As on the LED, a tap opens the lock screen ([onTap]), at
     * the second press of a double tap, or once the finger has lifted and the double-tap time is
     * up; a finger held down is no tap, so an unlock doesn't show the lock screen first. Neither
     * press reaches the lock screen. [onDone] once the breath is out, untouched.
     */
    private fun addBlink(color: Int, round: Int, onTouch: () -> Unit, onTap: () -> Unit, onDone: () -> Unit): Boolean {
        removeBlink()
        val settings = GlowPrefs.loadPlaying(this)
        val owner = OverlayOwner()
        blinkRunning.value = false
        blinkDowns = 0
        blinkHeld = false
        blinkOutHeld = false
        val view = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setBackgroundColor(Color.BLACK)
        }
        view.addView(
            ComposeView(this).apply {
                setContent {
                    val lens = geometry.value.fitted(settings, resources.displayMetrics.density)
                    BlinkBreath(settings.forScreen(geometry.value), color, round, lens, blinkRunning.value, onDone = ::blinkOut)
                }
            },
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            geometry.value = ScreenGeometry.from(insets)
            insets
        }
        @SuppressLint("ClickableViewAccessibility")
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    blinkHeld = true
                    blinkDowns++
                    timers.removeCallbacks(blinkTapNow)
                    if (blinkDowns >= 2) {
                        tapBlink()
                    } else {
                        blinkTouch?.let {
                            blinkTouch = null
                            it()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    blinkHeld = false
                    // Its breath out already, the tap is now; else once no second press is due.
                    if (blinkOutHeld) tapBlink() else timers.postDelayed(blinkTapNow, ViewConfiguration.getDoubleTapTimeout().toLong())
                }
            }
            true
        }
        if (addOverlay(view, PixelFormat.OPAQUE, "Lumement:blink", touchable = true) == null) {
            owner.destroy()
            return false
        }
        blink = view
        blinkOwner = owner
        blinkTouch = onTouch
        blinkTap = onTap
        blinkDone = onDone
        // Never left up by accident: a breath that never ends still ends, and the window goes.
        timers.postDelayed(blinkTimeout, MAX_BLINK_MS)
        GlowLog.d { "blink up" }
        return true
    }

    private fun tapBlink() {
        timers.removeCallbacks(blinkTapNow)
        val tap = blinkTap ?: return
        blinkTap = null
        blinkDone = null
        GlowLog.d { "blink tapped" }
        tap()
    }

    /**
     * The breath is out. Touched, a tap still waiting for its second press opens the lock screen
     * now, or once the finger on the window lifts; else done.
     */
    private fun blinkOut() {
        val done = blinkDone ?: return
        when {
            blinkDowns == 0 -> {
                timers.removeCallbacks(blinkTimeout)
                blinkDone = null
                done()
            }
            blinkHeld -> blinkOutHeld = true
            else -> tapBlink()
        }
    }

    /** The blink window goes now: touched, as a tap ends it; untouched, as its breath does. */
    private fun finishBlink() {
        if (blinkDowns > 0) tapBlink() else blinkOut()
        removeBlink()
    }

    private fun removeBlink() {
        timers.removeCallbacks(blinkTimeout)
        timers.removeCallbacks(blinkTapNow)
        blinkTouch = null
        blinkTap = null
        blinkDone = null
        val view = blink ?: return
        blink = null
        runCatching { windowManager.removeViewImmediate(view) }
        blinkOwner?.destroy()
        blinkOwner = null
        GlowLog.d { "blink down" }
    }

    /**
     * The LED over the always-on display ([AodBreath]): its window, put up while the screen is on
     * ([addAodWindow]), since a window only shows once it has drawn and One UI draws none while
     * dozing; [aodDrawn] once its first frame is on screen.
     */
    private var aodWindow: View? = null
    private var aodBreath: AodBreath? = null
    private var aodDrawn = false

    /** Transparent and untouchable: the always-on display shows as ever around the light, and takes its taps. */
    private fun addAodWindow() {
        if (aodWindow != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val surface = SurfaceView(this).apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        val breath = AodBreath(this, surface)
        surface.holder.addCallback(breath)
        val view = FrameLayout(this)
        view.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            geometry.value = ScreenGeometry.from(insets)
            insets
        }
        aodDrawn = false
        view.viewTreeObserver.registerFrameCommitCallback { if (aodWindow === view) aodDrawn = true }
        if (addOverlay(view, PixelFormat.TRANSLUCENT, "Lumement:aod") == null) return
        aodWindow = view
        aodBreath = breath
        GlowLog.d { "aod window up" }
    }

    /** The round over the always-on display, in [colors] from breath [round]; false when the window isn't ready. */
    private fun startAodBreath(colors: () -> List<Int>, round: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val breath = aodBreath ?: return false
        if (!aodDrawn || !breath.ready) return false
        val settings = GlowPrefs.loadPlaying(this)
        breath.start(settings.forScreen(geometry.value), geometry.value.fitted(settings, resources.displayMetrics.density), colors, round)
        return breath.running
    }

    /** The light goes out; the window stays for the next doze. */
    private fun stopAodBreath() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) aodBreath?.stop()
    }

    private fun removeAodWindow() {
        stopAodBreath()
        aodBreath = null
        aodDrawn = false
        val view = aodWindow ?: return
        aodWindow = null
        runCatching { windowManager.removeViewImmediate(view) }
        GlowLog.d { "aod window down" }
    }

    private fun release() {
        // Gone mid-breath (turned off, or unbound by the system): the glow screen takes its LED back.
        if (instance === this) instance = null
        finishBlink()
        removeAodWindow()
        removeArrival()
        removeCover()
    }

    /**
     * [format]: OPAQUE lets the compositor skip what is under it. A [blackout] needs TRANSLUCENT:
     * the screen timeout comes from the top window that isn't hidden by an opaque one, so an opaque
     * cover would hide the lock screen's short timeout and leave the screen to its long one.
     * [onShown]: once the cover's first frame is on screen (API 29+), at once if it was up already.
     */
    private fun addCover(maxMs: Long, format: Int, onShown: (() -> Unit)? = null) {
        removeArrival() // the LED is taking over; the effect has done its job
        timers.removeCallbacks(hideNow)
        // Never left up by accident: a missed hide() still clears it.
        timers.postDelayed(hideNow, maxMs)
        cover?.let { view ->
            // Already up (a wake hand-over): switch its format in place, with no frame uncovered.
            val params = coverParams
            if (params != null && params.format != format) {
                params.format = format
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            onShown?.invoke()
            return
        }
        val view = View(this).apply { setBackgroundColor(Color.BLACK) }
        if (onShown != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.viewTreeObserver.registerFrameCommitCallback { onShown() }
        }
        val params = addOverlay(view, format, "Lumement:shield") ?: return
        cover = view
        coverParams = params
        GlowLog.d { "shield up" }
    }

    /**
     * Black, faded in over [durationMs]. With the effect still up, under it in its own window: the
     * lock screen goes dark while the effect's tail (its last light landing on the LED) plays on
     * over the black. When the effect's window would go, it stays as the cover instead ([keepAsCover]).
     */
    private fun addDimCover(durationMs: Long) {
        timers.removeCallbacks(hideNow)
        timers.postDelayed(hideNow, MAX_COVER_MS)
        if (cover != null || backdrop != null) return
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        val frame = arrival
        if (frame != null) {
            // Above the blur views, below the effect, which is the frame's last child.
            frame.addView(view, frame.childCount - 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            backdrop = view
        } else {
            coverParams = addOverlay(view, PixelFormat.TRANSLUCENT, "Lumement:shield") ?: return
            cover = view
        }
        GlowLog.d { "shield dimming${if (frame != null) " under the effect" else ""}" }
        dim = Dim(view, durationMs).also { Choreographer.getInstance().postFrameCallback(it) }
    }

    /**
     * The effect's window was to go while the screen dims under the effect: it stays, as the
     * cover, its blur taken out and the dim going on in it. A new cover would draw nothing for its
     * first frame, and the lit lock screen would show through it. An [effect] still playing plays
     * out in it, over the black, as the [tail]; [owner] keeps it alive until then.
     */
    private fun keepAsCover(frame: FrameLayout, params: WindowManager.LayoutParams?, dimming: View, effect: View?, owner: OverlayOwner?) {
        for (i in frame.childCount - 1 downTo 0) {
            val child = frame.getChildAt(i)
            if (child !== dimming && child !== effect) frame.removeViewAt(i)
        }
        if (effect != null) {
            tail = effect
            tailOwner = owner
        } else {
            owner?.destroy()
        }
        val blurred = params != null && params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
        if (blurred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.blurBehindRadius = 0
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            runCatching { windowManager.updateViewLayout(frame, params) }
        }
        cover = frame
        coverParams = params
        GlowLog.d { "shield dim kept as the cover at ${dimming.alpha}${if (effect != null) ", its tail playing" else ""}" }
    }

    /** The effect has played out: its window goes, or, kept as the cover, its tail does. */
    private fun effectDone(frame: View) {
        if (arrival === frame) {
            arrivalEffect = null // nothing left of it to play
            removeArrival()
        } else if (tail?.parent === frame) {
            endTail()
        }
    }

    private fun endTail() {
        val view = tail ?: return
        tail = null
        (view.parent as? ViewGroup)?.removeView(view)
        tailOwner?.destroy()
        tailOwner = null
        GlowLog.d { "shield tail done" }
        // Asked to go once it had played out.
        if (onCoverGone != null) removeCover()
    }

    /** Down now, or once the tail has played out; [onGone] either way, as the cover goes. */
    private fun removeCoverWhenDone(onGone: () -> Unit) {
        onCoverGone = onGone
        if (tail == null) removeCover() else GlowLog.d { "shield down after the tail" }
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
            if (cover !== view && backdrop !== view) return
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
        // A cancelled dim never reaches its end, so it can never remove a newer arrival.
        dim?.let { Choreographer.getInstance().removeFrameCallback(it) }
        dim = null
        // Still dimming under the effect: the lit lock screen comes back from under it. (In the
        // window the effect left, it goes with the cover.)
        backdrop?.let { view ->
            backdrop = null
            if (cover == null) {
                (view.parent as? ViewGroup)?.removeView(view)
                GlowLog.d { "shield dim dropped" }
            }
        }
        // A tail still playing is cut: it goes with the window it plays in.
        tail = null
        tailOwner?.destroy()
        tailOwner = null
        val view = cover
        if (view != null) {
            cover = null
            coverParams = null
            runCatching { windowManager.removeViewImmediate(view) }
            GlowLog.d { "shield down" }
        }
        onCoverGone?.let {
            onCoverGone = null
            it()
        }
    }

    private fun addArrival(color: Int) {
        removeArrival() // a newer message restarts the effect in its colour
        val settings = GlowPrefs.loadPlaying(this)
        val owner = OverlayOwner()
        // A frame, so the blur views ([hazeTarget]) can sit beside the effect.
        // Owners on the window's root: Compose looks for them there.
        val view = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val haze = hazeTarget(settings, view)
        val effect = ComposeView(this).apply {
            setContent {
                val lens = geometry.value.fitted(settings, resources.displayMetrics.density)
                ArrivalEffect(settings.forScreen(lens), color, lens, onDone = { effectDone(view) }, onBlurBehind = haze)
            }
        }
        view.addView(effect)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            geometry.value = ScreenGeometry.from(insets)
            insets
        }
        val params = addOverlay(view, PixelFormat.TRANSLUCENT, "Lumement:arrival")
        if (params == null) {
            owner.destroy()
            return
        }
        arrival = view
        arrivalEffect = effect
        arrivalOwner = owner
        arrivalParams = params
        timers.postDelayed(stopArrivalNow, MAX_ARRIVAL_MS)
        GlowLog.d { "arrival up style=${settings.style}" }
    }

    private fun removeArrival() {
        timers.removeCallbacks(stopArrivalNow)
        val view = arrival ?: return
        arrival = null
        val dimming = backdrop
        if (dimming != null) {
            keepAsCover(view, arrivalParams, dimming, arrivalEffect, arrivalOwner)
        } else {
            runCatching { windowManager.removeViewImmediate(view) }
            arrivalOwner?.destroy()
        }
        arrivalEffect = null
        arrivalOwner = null
        arrivalParams = null
        GlowLog.d { "arrival down" }
    }

    /**
     * The spawn wave's blur of the lock screen (Water's glass, Air's gust), or null where there is none to be had:
     * - One UI ([SemBlur]): a row of [BLUR_STRIPS] narrow blur views in [root], each moved every
     *   frame to where the wave crosses its column (One UI won't let apps cut a blur to a shape,
     *   but it blurs exactly a view's bounds). One full-window view for [GlassArea.SCREEN]. Under
     *   Air's gust the columns are narrower and reach further back, and each blurs as hard as the
     *   wind's strip crossing it ([windStripAt]), so the screen goes soft in streaks.
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
                // Air's gust blurs in streaks along its wind: narrower columns in a deeper band, each
                // as hard as the wind's strip crossing it.
                val wind = settings.air
                val pitch = settings.airFlow.pitch
                val count = if (wind) AIR_BLUR_STRIPS else BLUR_STRIPS
                val strips = Array(count) { blurView(root, 0) }
                val last = IntArray(count)
                val reveal = area == GlassArea.REVEAL
                GlassHazeTarget { level, wave ->
                    if (arrival !== root) return@GlassHazeTarget
                    val width = root.width
                    val height = root.height
                    val origin = waveOrigin(geometry.value.fitted(settings, density), width.toFloat(), density, 1f)
                    val radius = wave * waveReach(origin, width.toFloat(), height.toFloat())
                    val blur = (stepped(level) * peak * SemBlur.SCALE).roundToInt()
                    for (i in strips.indices) {
                        val left = width * i / count
                        val right = width * (i + 1) / count
                        val across = (left + right) / 2f - origin.x
                        val dx = abs(across)
                        // How hard the wind drags this column, where the front crosses it, and how
                        // far the gust's tongue there carries the band on ahead.
                        val front = halfChord(HAZE_BAND_OUTER * radius, dx)
                        val psi = if (wind && !front.isNaN()) flowAngle(across, front, pitch) else 0f
                        val drag = if (!wind) 1f else if (front.isNaN()) 0f else AIR_STREAK_FLOOR + (1f - AIR_STREAK_FLOOR) * windStripAt(psi)
                        val surge = if (wind) 1f + gustSurgeAt(psi, wave) else 1f
                        val trail = if (wind) AIR_STREAK_INNER else HAZE_BAND_INNER
                        val top: Float
                        val bottom: Float
                        if (reveal) {
                            // Everything the wave hasn't reached yet.
                            val edge = halfChord(HAZE_REVEAL_EDGE * radius, dx)
                            top = if (edge.isNaN()) 0f else origin.y + edge
                            bottom = height.toFloat()
                        } else {
                            // The band under the crest, where it crosses this column.
                            val outer = halfChord(HAZE_BAND_OUTER * radius * surge, dx)
                            val inner = halfChord(trail * radius * surge, dx)
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
                        val strip = when {
                            b <= t -> 0
                            !wind -> blur
                            else -> (stepped(level * drag) * peak * SemBlur.SCALE).roundToInt()
                        }
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

    /** Full display, bars and cutout included; unless [touchable], taps reach whatever is underneath. */
    private fun addOverlay(view: View, format: Int, name: String, touchable: Boolean = false, alpha: Float = 1f): WindowManager.LayoutParams? {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
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
            this.alpha = alpha
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

        /**
         * Air's gust: its columns, narrower, so each is a thin streak along the wind, in a band
         * reaching back to this share of the wave's radius. All end on the band's arcs: rect blurs
         * have hard ends, and ends of uneven lengths read as bars, not wind. Even the still gaps
         * soften a little ([AIR_STREAK_FLOOR]), so no streak stands out against its neighbours.
         */
        private const val AIR_BLUR_STRIPS = 48
        private const val AIR_STREAK_INNER = 0.74f
        private const val AIR_STREAK_FLOOR = 0.35f

        /** Upper bound for one cover; a wake hand-over takes well under a second. */
        private const val MAX_COVER_MS = 2_000L

        /** Upper bound for one blink window: its wake takes well under a second, then one breath. */
        private const val MAX_BLINK_MS = 4_000L

        /** Upper bound for a [blackout]: the lock screen's own timeout and dim run out well within it. */
        private const val MAX_BLACKOUT_MS = 20_000L

        /** Upper bound for the arrival overlay, in case the effect never reports done. */
        private const val MAX_ARRIVAL_MS = 5_000L

        // Alpha eases out so the remaining light falls about evenly to the eye (perceived lightness
        // is roughly the cube root of luminance); a linear or accelerating alpha holds the screen
        // bright and then snaps to black.
        private val DIM_EASE = PathInterpolator(0.33f, 0f, 0.2f, 1f)

        // Set only while the system has the service bound; cleared in onUnbind/onDestroy.
        @SuppressLint("StaticFieldLeak")
        private var instance: GlowShield? = null

        /** Covers the whole display, bars included. No-op when the service is off. */
        fun show() {
            instance?.addCover(MAX_COVER_MS, PixelFormat.OPAQUE)
        }

        /**
         * [show], then [onShown] once the cover is on screen. False, and nothing happens, when the
         * service is off or Android can't tell when the cover is on screen (below 10).
         */
        fun showThen(onShown: () -> Unit): Boolean {
            val shield = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            shield.addCover(MAX_COVER_MS, PixelFormat.OPAQUE, onShown)
            return shield.cover != null
        }

        /**
         * Holds the display black while the lock screen, handed back so the panel can sleep, runs
         * out its own timeout; ends with [hide]. No-op when the service is off.
         */
        fun blackout() {
            instance?.addCover(MAX_BLACKOUT_MS, PixelFormat.TRANSLUCENT)
        }

        /** Fades the cover in over the lit lock screen, ending as the LED takes over. No-op when the service is off. */
        fun dimIn(durationMs: Long) {
            instance?.addDimCover(durationMs)
        }

        fun hide() {
            instance?.removeCover()
        }

        /**
         * The blink window, up in the dark before a wake for one breath: black over everything,
         * the breath waiting for [startBlink]. [round] steps the LED's burn-in guard. [onTouch] as
         * the first finger comes down, a tap opens the lock screen ([onTap]); [onDone] once the
         * breath is out, untouched. False when the service is off.
         */
        fun showBlink(color: Int, round: Int, onTouch: () -> Unit, onTap: () -> Unit, onDone: () -> Unit): Boolean =
            instance?.addBlink(color, round, onTouch, onTap, onDone) ?: false

        /** The panel is on: the blink's breath starts. */
        fun startBlink() {
            instance?.blinkRunning?.value = true
        }

        fun hideBlink() {
            instance?.removeBlink()
        }

        /**
         * Puts up the window the LED breathes in over the always-on display, invisible, while the
         * screen is on (Android 10+). No-op when it is up already or the service is off.
         */
        fun prepareAodLed() {
            instance?.addAodWindow()
        }

        /**
         * The LED's round over the always-on display, in [colors] (read before each breath) from
         * breath [round]. False, and nothing happens, when the service is off or its window isn't
         * up and drawn yet ([prepareAodLed]).
         */
        fun startAodLed(colors: () -> List<Int>, round: Int): Boolean = instance?.startAodBreath(colors, round) ?: false

        /** The light goes out; the window stays for the next doze. */
        fun stopAodLed() {
            instance?.stopAodBreath()
        }

        fun removeAodLed() {
            instance?.removeAodWindow()
        }

        /**
         * Takes the cover down once the effect's tail, if one plays in it, has played out (the
         * Edge Frame's LED going out on its first exhale over the black), then [onGone]; at once
         * when nothing plays, or when the service is off.
         */
        fun hideWhenDone(onGone: () -> Unit) {
            val shield = instance
            if (shield == null) onGone() else shield.removeCoverWhenDone(onGone)
        }

        /** Plays the chosen effect once over whatever is on screen. No-op when the service is off. */
        fun playArrival(color: Int) {
            instance?.addArrival(color)
        }

        fun stopArrival() {
            instance?.removeArrival()
        }

        /** [sleepNow] can work: the service is on, on Android 9+. */
        val canSleep: Boolean get() = instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /**
         * Puts the phone to sleep as the power key does, through the lock-screen action the system
         * offers accessibility services: the fingerprint stays allowed on the lock screen (a device
         * admin's lockNow would ask for the PIN). False, and nothing happens, if it [canSleep] not.
         */
        fun sleepNow(): Boolean {
            val shield = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return shield.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }

        /** Enabled and bound: also what lets the app start the glow screen from the background. */
        val isOn: Boolean get() = instance != null
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
