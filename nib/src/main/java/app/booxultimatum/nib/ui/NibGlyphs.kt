package app.booxultimatum.nib.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Nib's own glyphs, drawn in the kit's grammar (24-unit grid, 1.75 strokes, round caps and joins) so they sit beside
 * `kit:ui`'s Glyphs without a seam.
 */
object NibGlyphs {
    private fun glyph(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(fill: Boolean = false, build: PathBuilder.() -> Unit) = path(
        fill = if (fill) SolidColor(Color.Black) else null,
        stroke = if (fill) null else SolidColor(Color.Black),
        strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    )

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    /** A rubber block tilted on the page, its worn end resting on a stroke it has half removed. */
    val Eraser = glyph("eraser") {
        line { moveTo(14f, 4f); lineTo(20f, 10f); lineTo(11f, 19f); lineTo(5f, 13f); close() }
        line { moveTo(9f, 9f); lineTo(15f, 15f) }
        line { moveTo(13f, 20f); lineTo(20f, 20f) }
    }

    /** Three sheets stacked: layers. */
    val Layers = glyph("layers") {
        line { moveTo(12f, 4f); lineTo(20f, 8.5f); lineTo(12f, 13f); lineTo(4f, 8.5f); close() }
        line { moveTo(4f, 12.5f); lineTo(12f, 17f); lineTo(20f, 12.5f) }
        line { moveTo(4f, 16.5f); lineTo(12f, 21f); lineTo(20f, 16.5f) }
    }

    /** Three dots: more commands. */
    val More = glyph("more") {
        for (x in listOf(6f, 12f, 18f)) line(fill = true) { circle(x, 12f, 1.75f) }
    }

    val Plus = glyph("plus") { line { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }

    /** An open eye: shown. */
    val Eye = glyph("eye") {
        line { moveTo(2.5f, 12f); curveTo(5f, 7f, 8.5f, 5f, 12f, 5f); curveTo(15.5f, 5f, 19f, 7f, 21.5f, 12f); curveTo(19f, 17f, 15.5f, 19f, 12f, 19f); curveTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); close() }
        line(fill = true) { circle(12f, 12f, 3f) }
    }

    /** A closed eye: hidden. */
    val EyeClosed = glyph("eye-closed") {
        line { moveTo(2.5f, 10f); curveTo(5f, 14.5f, 8.5f, 16.5f, 12f, 16.5f); curveTo(15.5f, 16.5f, 19f, 14.5f, 21.5f, 10f) }
        line { moveTo(6f, 14.5f); lineTo(4.5f, 17f); moveTo(12f, 16.5f); lineTo(12f, 19.5f); moveTo(18f, 14.5f); lineTo(19.5f, 17f) }
    }

    /** A closed padlock. */
    val Lock = glyph("lock") {
        line { moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 20f); lineTo(6f, 20f); close() }
        line { moveTo(8.5f, 11f); lineTo(8.5f, 8f); curveTo(8.5f, 5.8f, 10f, 4f, 12f, 4f); curveTo(14f, 4f, 15.5f, 5.8f, 15.5f, 8f); lineTo(15.5f, 11f) }
    }

    /** An open padlock. */
    val LockOpen = glyph("lock-open") {
        line { moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 20f); lineTo(6f, 20f); close() }
        line { moveTo(8.5f, 11f); lineTo(8.5f, 8f); curveTo(8.5f, 5.8f, 10f, 4f, 12f, 4f); curveTo(13.6f, 4f, 14.9f, 5.2f, 15.3f, 6.8f) }
    }

    val Up = glyph("up") { line { moveTo(5f, 15.5f); lineTo(12f, 8.5f); lineTo(19f, 15.5f) } }

    val Down = glyph("down") { line { moveTo(5f, 8.5f); lineTo(12f, 15.5f); lineTo(19f, 8.5f) } }

    /** A magnifier: zoom. */
    val Zoom = glyph("zoom") {
        line { circle(10.5f, 10.5f, 6f) }
        line { moveTo(15f, 15f); lineTo(20f, 20f) }
        line { moveTo(8f, 10.5f); lineTo(13f, 10.5f); moveTo(10.5f, 8f); lineTo(10.5f, 13f) }
    }
}
