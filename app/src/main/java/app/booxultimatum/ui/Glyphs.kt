package app.booxultimatum.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Rail glyphs drawn in the instrument's own grammar: circles, rims and one needle. 24-unit grid, 1.75 strokes. */
object Glyphs {
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

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        arcToRelative(rad, rad, 0f, false, true, rad, rad)
        lineTo(r, b - rad)
        arcToRelative(rad, rad, 0f, false, true, -rad, rad)
        lineTo(l + rad, b)
        arcToRelative(rad, rad, 0f, false, true, -rad, -rad)
        lineTo(l, t + rad)
        arcToRelative(rad, rad, 0f, false, true, rad, -rad)
        close()
    }

    /** A dial with its needle: the overview. */
    val Overview = glyph("overview") {
        line { circle(12f, 12f, 9f) }
        line { moveTo(12f, 12f); lineTo(16.5f, 7.5f) }
        line(fill = true) { circle(12f, 12f, 1.6f) }
    }

    /** A cell with its charge bar. */
    val Battery = glyph("battery") {
        line { roundRect(2.5f, 7f, 19f, 17f, 2f) }
        line(fill = true) { roundRect(19.8f, 10f, 21.8f, 14f, 0.8f) }
        line(fill = true) { roundRect(5f, 9.5f, 11.5f, 14.5f, 0.8f) }
    }

    /** The tablet: a slab with its side key. */
    val Device = glyph("device") {
        line { roundRect(5f, 2.5f, 19f, 21.5f, 2.2f) }
        line { moveTo(9.5f, 18f); lineTo(14.5f, 18f) }
    }

    /** Four keys of a keypad: the installed apps. */
    val Apps = glyph("apps") {
        line { circle(7.5f, 7.5f, 3.2f) }
        line { circle(16.5f, 7.5f, 3.2f) }
        line { circle(7.5f, 16.5f, 3.2f) }
        line(fill = true) { circle(16.5f, 16.5f, 3.2f) }
    }

    /** A key: what the app is allowed to open. */
    val Access = glyph("access") {
        line { circle(7.5f, 12f, 4.2f) }
        line { moveTo(11.7f, 12f); lineTo(21f, 12f); moveTo(17.5f, 12f); lineTo(17.5f, 15.2f); moveTo(20.5f, 12f); lineTo(20.5f, 14.4f) }
    }

    /** Two slide controls, one set: the tweaks. */
    val Tweaks = glyph("tweaks") {
        line { moveTo(3f, 8f); lineTo(21f, 8f); moveTo(3f, 16f); lineTo(21f, 16f) }
        line(fill = true) { circle(9f, 8f, 2.8f) }
        line { circle(15f, 16f, 2.8f) }
    }

    /** A dial of choices around a centre: the settings index. */
    val Hub = glyph("hub") {
        line { circle(12f, 12f, 3.2f) }
        line { moveTo(12f, 2.5f); lineTo(12f, 6f); moveTo(12f, 18f); lineTo(12f, 21.5f); moveTo(2.5f, 12f); lineTo(6f, 12f); moveTo(18f, 12f); lineTo(21.5f, 12f) }
        line { moveTo(5.3f, 5.3f); lineTo(7.6f, 7.6f); moveTo(16.4f, 16.4f); lineTo(18.7f, 18.7f); moveTo(18.7f, 5.3f); lineTo(16.4f, 7.6f); moveTo(7.6f, 16.4f); lineTo(5.3f, 18.7f) }
    }

    /** Search lens for text fields. */
    val Search = glyph("search") {
        line { circle(10.5f, 10.5f, 6.5f) }
        line { moveTo(15.3f, 15.3f); lineTo(20.5f, 20.5f) }
    }

    /** A swatch disc half inked: appearance. */
    val Appearance = glyph("appearance") {
        line { circle(12f, 12f, 9f) }
        line(fill = true) {
            moveTo(12f, 3f)
            arcToRelative(9f, 9f, 0f, false, true, 0f, 18f)
            close()
        }
        line { moveTo(12f, 3f); lineTo(12f, 21f) }
    }

    /** A letterform on a baseline: the fonts library. */
    val Fonts = glyph("fonts") {
        line { moveTo(4f, 19f); lineTo(10f, 4.5f); lineTo(16f, 19f); moveTo(6.3f, 13.5f); lineTo(13.7f, 13.5f) }
        line { moveTo(2.5f, 21.5f); lineTo(21.5f, 21.5f) }
        line(fill = true) { circle(19f, 16.5f, 2.4f) }
    }

    // ---------- Key glyphs (launcher keys) ----------

    /** A cog: eight flat teeth around a rim, with a hub. */
    val Gear = glyph("gear") {
        line {
            val teeth = 8
            for (i in 0 until teeth * 2) {
                val a = Math.toRadians(i * 360.0 / (teeth * 2) - 90)
                val r = if (i % 2 == 0) 9.6f else 7.4f
                val a2 = Math.toRadians((i + 1) * 360.0 / (teeth * 2) - 90)
                val x1 = 12f + (kotlin.math.cos(a) * r).toFloat(); val y1 = 12f + (kotlin.math.sin(a) * r).toFloat()
                val x2 = 12f + (kotlin.math.cos(a2) * r).toFloat(); val y2 = 12f + (kotlin.math.sin(a2) * r).toFloat()
                if (i == 0) moveTo(x1, y1) else lineTo(x1, y1)
                lineTo(x2, y2)
            }
            close()
        }
        line { circle(12f, 12f, 3f) }
    }

    /** A pencil laid diagonally, with its tip and a rule underneath: arranging. */
    val Pencil = glyph("pencil") {
        line { moveTo(15.5f, 4.5f); lineTo(19.5f, 8.5f); lineTo(9f, 19f); lineTo(4.5f, 19.5f); lineTo(5f, 15f); close() }
        line { moveTo(13.5f, 6.5f); lineTo(17.5f, 10.5f) }
    }

    val Close = glyph("close") { line { moveTo(6f, 6f); lineTo(18f, 18f) }; line { moveTo(18f, 6f); lineTo(6f, 18f) } }

    val ChevronLeft = glyph("chevron-left") { line { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }

    val ChevronRight = glyph("chevron-right") { line { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }

    /** Nine dots: every app. */
    val AllApps = glyph("all-apps") {
        for (x in listOf(6f, 12f, 18f)) for (y in listOf(6f, 12f, 18f)) line(fill = true) { circle(x, y, 1.7f) }
    }

    /** BooxUltimatum's own mark, the needle across its scale with the lamp at its tip: the hub app. */
    val Mark = glyph("mark") {
        line { moveTo(4.5f, 18.5f); lineTo(19.5f, 18.5f) }
        line { moveTo(7f, 18.5f); lineTo(7f, 16f); moveTo(17f, 18.5f); lineTo(17f, 16f) }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round) { moveTo(12f, 7.5f); lineTo(12f, 19f) }
        line(fill = true) { circle(12f, 6f, 2.6f) }
    }
}