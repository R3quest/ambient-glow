package app.lumement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.ViewCompat
import app.lumement.dashboard.Dashboard
import app.lumement.ui.theme.LumementTheme

class MainActivity : ComponentActivity() {
    // Real punch-hole position, so LED presets and mock-ups line up with this phone's camera.
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

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
            LumementTheme {
                Dashboard(geometry.value)
            }
        }
    }
}
