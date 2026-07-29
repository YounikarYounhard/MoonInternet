package cc.moon.internet.ui

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/**
 * Draws a platform Drawable (an app icon) inside Compose. Accompanist has a library for this,
 * but it is a dozen lines and one less dependency to keep current.
 */
@Composable
fun rememberDrawablePainter(drawable: Drawable): Painter =
    remember(drawable) { DrawablePainter(drawable.mutate()) }

private class DrawablePainter(private val drawable: Drawable) : Painter() {

    override val intrinsicSize: Size
        get() = Size(
            drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: Size.Unspecified.width,
            drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: Size.Unspecified.height,
        )

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
