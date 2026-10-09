package app.lumement.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AMOLED palette: true black base, cold graphite surfaces, electric accents. */
object GlowPalette {
    val Void = Color(0xFF000000)
    val Surface = Color(0xFF0B0D12)
    val SurfaceRaised = Color(0xFF141821)
    val SurfaceHigh = Color(0xFF1C2230)
    val SurfaceHighest = Color(0xFF242B3B)
    val Outline = Color(0xFF2A3245)
    val OutlineSoft = Color(0xFF1A1F2B)

    val Cyan = Color(0xFF00E5FF)
    val Magenta = Color(0xFFFF2E93)
    val Lime = Color(0xFFB6FF3B)
    val Amber = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5470)

    val TextPrimary = Color(0xFFF2F5FA)
    val TextMuted = Color(0xFF8A93A6)
    val TextFaint = Color(0xFF4F586B)
}

object GlowBrushes {
    val Signature = Brush.linearGradient(listOf(GlowPalette.Cyan, GlowPalette.Magenta))
    val SignatureHorizontal = Brush.horizontalGradient(listOf(GlowPalette.Cyan, GlowPalette.Magenta))
    val Warning = Brush.linearGradient(listOf(GlowPalette.Amber, GlowPalette.Magenta))
}

/** Asymmetric "blade" corners: soft on one diagonal, tight on the other. */
object GlowShapes {
    val Card = RoundedCornerShape(topStart = 28.dp, topEnd = 6.dp, bottomEnd = 28.dp, bottomStart = 6.dp)
    val Tile = RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 4.dp)
    val Pill = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp)
    val Button = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp)
    val GhostButton = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    val Phone = RoundedCornerShape(14.dp)
}

private val GlowColorScheme = darkColorScheme(
    primary = GlowPalette.Cyan,
    onPrimary = GlowPalette.Void,
    primaryContainer = Color(0xFF00363D),
    onPrimaryContainer = GlowPalette.Cyan,
    inversePrimary = Color(0xFF006874),
    secondary = GlowPalette.Magenta,
    onSecondary = GlowPalette.Void,
    secondaryContainer = Color(0xFF3D0A22),
    onSecondaryContainer = GlowPalette.Magenta,
    tertiary = GlowPalette.Lime,
    onTertiary = GlowPalette.Void,
    tertiaryContainer = Color(0xFF233300),
    onTertiaryContainer = GlowPalette.Lime,
    background = GlowPalette.Void,
    onBackground = GlowPalette.TextPrimary,
    surface = GlowPalette.Surface,
    onSurface = GlowPalette.TextPrimary,
    surfaceVariant = GlowPalette.SurfaceRaised,
    onSurfaceVariant = GlowPalette.TextMuted,
    surfaceTint = GlowPalette.Cyan,
    inverseSurface = GlowPalette.TextPrimary,
    inverseOnSurface = GlowPalette.Void,
    error = GlowPalette.Danger,
    onError = GlowPalette.Void,
    errorContainer = Color(0xFF3D0010),
    onErrorContainer = GlowPalette.Danger,
    outline = GlowPalette.Outline,
    outlineVariant = GlowPalette.OutlineSoft,
    scrim = GlowPalette.Void,
    surfaceBright = GlowPalette.SurfaceHigh,
    surfaceDim = GlowPalette.Void,
    surfaceContainerLowest = GlowPalette.Void,
    surfaceContainerLow = GlowPalette.Surface,
    surfaceContainer = GlowPalette.SurfaceRaised,
    surfaceContainerHigh = GlowPalette.SurfaceHigh,
    surfaceContainerHighest = GlowPalette.SurfaceHighest,
)

private val GlowTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp,
        letterSpacing = 2.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.8.sp,
    ),
)

private val GlowMaterialShapes = Shapes(
    extraSmall = GlowShapes.Pill,
    small = GlowShapes.GhostButton,
    medium = GlowShapes.Tile,
    large = GlowShapes.Card,
    extraLarge = GlowShapes.Card,
)

@Composable
fun LumementTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlowColorScheme,
        typography = GlowTypography,
        shapes = GlowMaterialShapes,
        content = content,
    )
}
