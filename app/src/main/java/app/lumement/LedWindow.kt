package app.lumement

import android.view.Display
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * Hides the status and navigation bars over a black LED screen. Their icons and gesture handle
 * are set dark, so the moment the system shows them anyway (One UI does on every wake) they are
 * invisible on black. A swipe still brings them back for a moment.
 */
internal fun Window.hideBarsOnBlack() {
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Undoes [hideBarsOnBlack] for the dashboard's dark theme. */
internal fun Window.showBars() {
    WindowCompat.getInsetsController(this, decorView).apply {
        show(WindowInsetsCompat.Type.systemBars())
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

/**
 * The display modes the LED face asks for at the current resolution: [idle], the lowest rate, for
 * fewer panel scans while it sits dark, and [fade], the one nearest [fadeHz], for its breaths.
 * Looked up once after each [reset], never per frame; 0 (no preference) without a display.
 */
internal class PanelModes(private val display: () -> Display?, private val fadeHz: Float) {
    private var cached: Pair<Int, Int>? = null

    val idle: Int get() = ids().first
    val fade: Int get() = ids().second

    /** Forget the modes, e.g. after a resolution change. */
    fun reset() {
        cached = null
    }

    /** The panel's top refresh rate, or 0 (no preference) without a display. */
    fun highestRate(): Float = display()?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f

    private fun ids(): Pair<Int, Int> {
        cached?.let { return it }
        val display = display() ?: return 0 to 0
        val current = display.mode
        val modes = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val idle = modes.minByOrNull { it.refreshRate }?.modeId ?: 0
        val fade = modes.minByOrNull { abs(it.refreshRate - fadeHz) }?.modeId ?: idle
        return (idle to fade).also { cached = it }
    }
}
