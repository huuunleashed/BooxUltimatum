package app.booxultimatum.kit.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.theme.Ink
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val SQRT2 = 1.4142135f

/**
 * Diagonal hatching for "something is wrong here" fills: shape and pattern, never colour alone. The lines run at 45
 * degrees from lower left to upper right and are clipped to the rectangle. [spacing] is the distance between
 * neighbouring lines, measured across them; at or below [lineWidth] the lines touch, so the rectangle is filled solid.
 */
fun DrawScope.drawHatch(
    topLeft: Offset,
    size: Size,
    color: Color = Ink.Black,
    lineWidth: Float = 2.dp.toPx(),
    spacing: Float = 6.dp.toPx(),
) {
    if (!(size.width > 0f && size.height > 0f && lineWidth > 0f && spacing > 0f)) return
    if (spacing <= lineWidth) {
        drawRect(color, topLeft, size)
        return
    }
    val step = spacing * SQRT2
    val path = Path()
    val count = ceil((size.width + size.height) / step).toInt()
    for (k in 0..count) {
        // The line x + y = c, relative to topLeft: it enters on the left or bottom edge and leaves on the top or right.
        val c = k * step
        val start = max(0f, c - size.height)
        val end = min(size.width, c)
        if (end > start) {
            path.moveTo(topLeft.x + start, topLeft.y + c - start)
            path.lineTo(topLeft.x + end, topLeft.y + c - end)
        }
    }
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        drawPath(path, color, style = Stroke(width = lineWidth))
    }
}

/** The same hatching drawn behind the content, over the composable's bounds. */
fun Modifier.hatch(color: Color = Ink.Black, lineWidth: Dp = 2.dp, spacing: Dp = 6.dp): Modifier = drawBehind {
    drawHatch(Offset.Zero, size, color, lineWidth.toPx(), spacing.toPx())
}
