package app.lumement

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale

// ---------------------------------------------------------------------------------------------
// Gradient brushes the effects build once per size and then move, scale or reuse every frame
// without allocating.
// ---------------------------------------------------------------------------------------------

/**
 * A gradient shader built once and reused at every size. Compose rebuilds a gradient brush
 * whenever the drawn size changes; the wave masks change size every frame.
 */
internal class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

internal fun fixedRadial(center: Offset, radius: Float, vararg stops: Pair<Float, Color>): Brush =
    FixedShaderBrush(radialShader(center, radius, stops))

/**
 * A gradient that moves through its local matrix while the shape it paints stays put, so the
 * Edge Frame's masks only ever touch the frame band instead of the whole screen. Every draw of
 * it sits between solid-colour draws, so the paint re-reads the moved shader each time.
 */
internal class MovingShaderBrush(private val shader: Shader) : ShaderBrush() {
    private val matrix = android.graphics.Matrix()

    override fun createShader(size: Size): Shader = shader

    fun rotateTo(degrees: Float, pivot: Offset) {
        matrix.setRotate(degrees, pivot.x, pivot.y)
        shader.setLocalMatrix(matrix)
    }

    /** As [rotateTo], then mirrored left to right about [pivot]: the twin of a head running the other way. */
    fun rotateMirroredTo(degrees: Float, pivot: Offset) {
        matrix.setRotate(degrees, pivot.x, pivot.y)
        matrix.postScale(-1f, 1f, pivot.x, pivot.y)
        shader.setLocalMatrix(matrix)
    }

    fun scaleTo(scale: Float, pivot: Offset) {
        matrix.setScale(scale, scale, pivot.x, pivot.y)
        shader.setLocalMatrix(matrix)
    }
}

internal fun movingRadial(center: Offset, radius: Float, vararg stops: Pair<Float, Color>): MovingShaderBrush =
    MovingShaderBrush(radialShader(center, radius, stops))

internal fun movingSweep(center: Offset, vararg stops: Pair<Float, Color>): MovingShaderBrush =
    MovingShaderBrush(SweepGradientShader(center, stops.map { it.second }, stops.map { it.first }))

private fun radialShader(center: Offset, radius: Float, stops: Array<out Pair<Float, Color>>): Shader =
    RadialGradientShader(center, radius, stops.map { it.second }, stops.map { it.first })

/**
 * Fills exactly the canvas with [brush] scaled by [s] around [pivot]: the wave masks grow by
 * scaling one fixed gradient. The rect is the canvas's pre-image, so nothing off screen is drawn.
 */
internal fun DrawScope.fillScaled(
    brush: Brush,
    s: Float,
    pivot: Offset,
    alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    val canvas = size
    scale(s, pivot) {
        drawRect(
            brush = brush,
            topLeft = Offset(pivot.x - pivot.x / s, pivot.y - pivot.y / s),
            size = Size(canvas.width / s, canvas.height / s),
            alpha = alpha,
            blendMode = blendMode,
        )
    }
}
