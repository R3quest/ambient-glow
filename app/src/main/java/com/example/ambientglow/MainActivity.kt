package com.example.ambientglow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.ViewCompat
import com.example.ambientglow.dashboard.Dashboard
import com.example.ambientglow.ui.theme.AmbientGlowTheme

class MainActivity : ComponentActivity() {
    // Real punch-hole position, so LED presets and mock-ups line up with this phone's camera;
    // null until the first insets say what the screen is.
    private val geometry = mutableStateOf<ScreenGeometry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        GlowLauncher.ensureChannel(this)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            geometry.value = ScreenGeometry.from(insets)
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        setContent {
            AmbientGlowTheme {
                Dashboard(geometry.value)
            }
        }
    }
}
