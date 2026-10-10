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
    private var onReady: (() -> Unit)? = null
    private val step = Runnable { step() }

    /** The surface exists, so it can be drawn. */
    var ready = false
        private set

    init {
        view.holder.addCallback(this)
    }

    /** [then] once the surface exists: at once if it does. */
    fun whenReady(then: () -> Unit) {
        if (ready) then() else onReady = then
    }

    /**
     * The LED in [color] where the screen shows it at breath [cycle], its burn-in step included,
     * with [settings] as the screen shows them and [geometry] fitted. Dark until [breathe].
     */
    fun show(settings: GlowSettings, geometry: ScreenGeometry, color: Int, cycle: Int) {
        val density = context.resources.displayMetrics.density
        val size = Size(view.width.toFloat(), view.height.toFloat())
        val onCamera = settings.ledOnCamera
        val light = ledLight(
            core = settings.dotSize.radius.value * density,
            onCamera = onCamera,
            lens = geometry.lensRadius(density),
            ringGrowPx = if (onCamera) RING_SHIFTS[cycle % RING_SHIFTS.size] else 0f,
            ringGap = LED_RING_GAP.value * density,
        )
        val shift = if (onCamera) Offset.Zero else PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size]
        lightAt = ledCenter(onCamera, geometry, settings.dotX, settings.dotY, light, size, density) + shift
        look = LedLook(ledMaterial(settings, elementFramesSupported), Color(color), light, onCamera)
        setLevel(0f)
        // Neon: once, at its peak; the breath is the alpha. A material that moves: each step.
        if (look?.moves == false) paint(1f, LED_RISE_MS) else paint(0f, 0f)
    }

    /** One breath from now; [onOut] once it is dark again. */
    fun breathe(onOut: () -> Unit) {
        timers.removeCallbacks(step)
        this.onOut = onOut
        breathFrom = SystemClock.uptimeMillis()
        step()
    }

    /** The light goes out at once; a breath under way ends without its [breathe] callback. */
    fun stop() {
        timers.removeCallbacks(step)
        onOut = null
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
        stop()
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

    private fun setLevel(to: Float) {
        if (to == level) return
        val control = view.surfaceControl ?: return
        level = to
        alphaChange.setAlpha(control, to).apply()
    }

    /** The LED at [alpha], [ms] into its breath; none of a material's one-off moments. */
    private fun paint(alpha: Float, ms: Float) {
        val holder = view.holder
        if (!holder.surface.isValid) return
        val canvas = holder.lockHardwareCanvas()
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val drawn = look ?: return
            val size = Size(view.width.toFloat(), view.height.toFloat())
            val density = context.resources.displayMetrics.density
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(canvas), size) {
                drawn.draw(this, lightAt, alpha, ms, accents = false)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val FRAME_MS = 1_000f / LED_FADE_HZ
    }
}
