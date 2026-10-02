package com.example.ambientglow

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Transparent wake layer. The window manager turns the panel on through window flags,
 * Compose draws one flat glow, and after [GLOW_DURATION_MS] the activity finishes and the
 * device can go back to sleep.
 */
class WakeScreenActivity : ComponentActivity() {

    private val autoClose = Handler(Looper.getMainLooper())
    private val closeNow = Runnable { finishAndRemoveTask() }

    private val request = mutableStateOf(GlowRequest.Default)
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        lightUpPanel()
        hideSystemBars()
        observeScreenGeometry()

        request.value = GlowRequest.fromIntent(intent)
        GlowLauncher.dismissBridge(this)

        setContent {
            WakeScreen(
                request = request.value,
                geometry = geometry.value,
                onDismiss = ::finishAndRemoveTask,
            )
        }
        scheduleAutoClose()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request.value = GlowRequest.fromIntent(intent)
        GlowLauncher.dismissBridge(this)
        scheduleAutoClose()
    }

    override fun onDestroy() {
        autoClose.removeCallbacks(closeNow)
        super.onDestroy()
    }

    private fun lightUpPanel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun observeScreenGeometry() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            geometry.value = ScreenGeometry.from(insets)
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    private fun scheduleAutoClose() {
        autoClose.removeCallbacks(closeNow)
        autoClose.postDelayed(closeNow, GLOW_DURATION_MS)
    }
}

private const val PULSE_MIN_ALPHA = 0.25f
private const val PULSE_HALF_PERIOD_MS = 900

@Composable
private fun WakeScreen(request: GlowRequest, geometry: ScreenGeometry, onDismiss: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "glow-pulse")
    val alpha by pulse.animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_HALF_PERIOD_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow-alpha",
    )
    val color = remember(request.color) { Color(request.color) }
    val dismiss by rememberUpdatedState(onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { dismiss() } },
    ) {
        GlowGraphic(
            style = request.style,
            color = color,
            alpha = { alpha },
            dotX = request.dotX,
            dotY = request.dotY,
            geometry = geometry,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
