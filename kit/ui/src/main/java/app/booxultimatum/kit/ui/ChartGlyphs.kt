package app.booxultimatum.kit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The day pager's chevrons: 2.5-unit round-capped strokes on the 24-unit grid, heavier than [Glyphs] so they read at 26 dp. */
internal object ChartGlyphs {
    private fun glyph(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.chevron(build: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    )

    val ChevronLeft = glyph("chart-chevron-left") { chevron { moveTo(15.5f, 5f); lineTo(8.5f, 12f); lineTo(15.5f, 19f) } }

    val ChevronRight = glyph("chart-chevron-right") { chevron { moveTo(8.5f, 5f); lineTo(15.5f, 12f); lineTo(8.5f, 19f) } }
}
