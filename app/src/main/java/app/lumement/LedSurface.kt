package app.lumement

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * The LED in a SurfaceView of its own ([view]): drawn straight into its surface, not by the
 * window, and breathed on the LED's own grid ([LED_FADE_HZ]). Neon, whose frames differ only in
 * light, is drawn once per breath at its peak and breathed by the surface's alpha on the
 * compositor; a material that moves is drawn every step. So a breath costs no window redraws: the
 * app's main and render threads stay all but idle (measured on the S23: Compose kept the lit LED's
 * at ~14 % of a core, this the always-on display's at ~1.6 %), and it draws even where One UI
 * skips app windows' redraws (dozing). Android 10+, for the surface's alpha.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class LedSurface(private val context: Context) : SurfaceHolder.Callback {
    val view = SurfaceView(context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    private val timers = Handler(Looper.getMainLooper())
    private val alphaChange = SurfaceControl.Transaction()
    private var level = -1f
    private var look: LedLook? = null
    private var lightAt = Offset.Zero
    private var breathFrom = 0L
    private var onOut: (() -> Unit)? = null
    private var onLit: (() -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private val step = Runnable { step() }
    private val riseStep = Runnable { rise() }

    // What paint draws with, kept: a material that moves paints every step of every breath.
    private val drawScope = CanvasDrawScope()
    private var drawDensity = Density(context.resources.displayMetrics.density)
    private var native: android.graphics.Canvas? = null
    private var wrapped: Canvas? = null

    /** The surface exists, so it can be drawn. */
    var ready = false
        private set

    /** Each time the surface goes, after a breath under way has ended ([breathe]'s callback): for its owner ([LedRound]). */
    var onLost: (() -> Unit)? = null

    init {
        view.holder.addCallback(this)
    }

    /** [then] once the surface exists: at once if it does. */
    fun whenReady(then: () -> Unit) {
        if (ready) then() else onReady = then
    }

    /**
     * The LED in [color] where the screen shows it at breath [cycle], its burn-in step included,
     * with [settings] as the screen shows them and [geometry] fitted. Dark until [breathe]. The
     * frame quiets down as [calm] goes from 0 to 1 ([BrightStreak.settledAt]). [still]: on the
     * always-on display, every waiting app's colour at once, the dot and ring larger, the frame as
     * wide as the effect's ([LedLook]).
     */
    fun show(settings: GlowSettings, geometry: ScreenGeometry, color: Int, cycle: Int, calm: Float, still: List<Int>? = null) {
        val density = context.resources.displayMetrics.density
        val size = Size(view.width.toFloat(), view.height.toFloat())
        val form = settings.ledForm
        val core = (settings.dotSize.radius.value + if (still != null) AOD_CORE_EXTRA.value else 0f) * density
        fun lightFor(step: Float) = if (form == LedForm.FRAME) {
            ledFrameLight(settings, size, geometry.frameCornerPx(density), inset = step, dp = density, full = still != null)
        } else {
            ledLight(core, form == LedForm.RING, geometry.lensRadius(density), ringGrowPx = step, ringGap = LED_RING_GAP.value * density)
        }
        // Still on the always-on display, the frame and ring step by a share of the line they draw ([STILL_SHIFTS]).
        val step = when {
            still == null -> ledStepPx(form, cycle)
            form == LedForm.DOT -> 0f
            else -> stillStepPx(cycle, lightFor(0f).line)
        }
        val light = lightFor(step)
        val shift = if (still != null && form == LedForm.DOT) stillShiftAt(cycle, core) else ledShiftAt(form, cycle)
        lightAt = ledCenter(form, geometry, settings.dotX, settings.dotY, light, size, density) + shift
        look = LedLook(ledMaterial(settings, elementFramesSupported), Color(color), light, form, settings, calm, still?.map(::Color))
        setLevel(0f)
        // Neon: once, at its peak; the breath is the alpha. A material that moves: each step.
        if (look?.moves == false) paint(1f, LED_RISE_MS) else paint(0f, 0f)
    }

    /** One breath from now; [onOut] once it is dark again. */
    fun breathe(onOut: () -> Unit) {
        timers.removeCallbacks(step)
        timers.removeCallbacks(riseStep)
        onLit = null
        if (!ready) {
            onOut() // the surface went while it prerolled: no breath to show
            return
        }
        this.onOut = onOut
        breathFrom = SystemClock.uptimeMillis()
        step()
    }

    /**
     * The breath's rise once, from now, and then the light stays: its last frame is the peak, which
     * a panel that holds its image ([LedRound] on the always-on display) keeps showing with nothing
     * drawn. [onLit] once it is there.
     */
    fun glow(onLit: () -> Unit) {
        timers.removeCallbacks(step)
        timers.removeCallbacks(riseStep)
        onOut = null
        this.onLit = onLit
        breathFrom = SystemClock.uptimeMillis()
        rise()
    }

    /** The light goes out at once; a breath or rise under way ends without its callback. */
    fun stop() {
        timers.removeCallbacks(step)
        timers.removeCallbacks(riseStep)
        onOut = null
        onLit = null
        setLevel(0f)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        level = -1f
        setLevel(0f)
        onReady?.let {
            onReady = null
            it()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        // A breath under way ends with its callback, so the round goes on and waits for the
        // surface again ([whenReady]) rather than for a breath that never ends. A rise held on
        // the always-on display has no callback left: its owner hears it ([onLost]).
        val out = onOut
        stop()
        out?.invoke()
        onLost?.invoke()
    }

    private fun step() {
        val at = (SystemClock.uptimeMillis() - breathFrom).toFloat()
        if (at < LED_BREATH_MS) {
            val ms = (at / FRAME_MS).roundToInt() * FRAME_MS
            if (look?.moves == true) {
                setLevel(1f)
                paint(ledBreathAt(ms), ms)
            } else {
                setLevel(ledBreathAt(ms))
            }
            timers.postDelayed(step, FRAME_MS.toLong())
            return
        }
        setLevel(0f)
        onOut?.let {
            onOut = null
            it()
        }
    }

    private fun rise() {
        val at = (SystemClock.uptimeMillis() - breathFrom).toFloat()
        val ms = minOf((at / FRAME_MS).roundToInt() * FRAME_MS, LED_RISE_MS)
        if (look?.moves == true) {
            setLevel(1f)
            paint(ledBreathAt(ms), ms)
        } else {
            setLevel(ledBreathAt(ms))
        }
        if (ms < LED_RISE_MS) {
            timers.postDelayed(riseStep, FRAME_MS.toLong())
            return
        }
        onLit?.let {
            onLit = null
            it()
        }
    }

    private fun setLevel(to: Float) {
        if (to == level) return
        val control = view.surfaceControl ?: return
        level = to
        alphaChange.setAlpha(control, to).apply()
    }

    /**
     * The LED at [alpha], [ms] into its breath, a material's moments included (the coal's sparks,
     * the bead's ripples, the gem's glint): a material that moves is painted every step anyway.
     */
    private fun paint(alpha: Float, ms: Float) {
        val holder = view.holder
        if (!holder.surface.isValid) return
        val canvas = holder.lockHardwareCanvas()
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val drawn = look ?: return
            val size = Size(view.width.toFloat(), view.height.toFloat())
            val density = context.resources.displayMetrics.density
            if (drawDensity.density != density) drawDensity = Density(density)
            // The surface hands back the same canvas step after step, so its wrapper is kept too.
            val target = wrapped?.takeIf { canvas === native } ?: Canvas(canvas).also {
                native = canvas
                wrapped = it
            }
            drawScope.draw(drawDensity, LayoutDirection.Ltr, target, size) {
                drawn.draw(this, lightAt, alpha, ms)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val FRAME_MS = 1_000f / LED_FADE_HZ
    }
}
