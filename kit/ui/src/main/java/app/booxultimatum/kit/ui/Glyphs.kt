package app.booxultimatum.kit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rail and key glyphs drawn in the instrument's own grammar: circles, rims, one needle and the odd lit lamp.
 * 24-unit grid with a 20-unit live area, 1.75 strokes with round caps and joins, 2.5 corners on large rims, and at
 * least 1.5 units of paper between separate parts so every glyph still reads at 24 dp on e-ink and inverted in the rail.
 */
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

    /**
     * A crescent: the disc of radius [r] at ([cx], [cy]) less a bite of radius [biteR] centred [offset] away along
     * [angleDeg]. The horns are the two circles' true intersections, joined by the outer arc round the back and the bite's arc.
     */
    private fun PathBuilder.crescent(cx: Float, cy: Float, r: Float, biteR: Float, offset: Float, angleDeg: Float) {
        val phi = Math.toRadians(angleDeg.toDouble())
        val along = (offset * offset + r * r - biteR * biteR) / (2 * offset)
        val half = sqrt(r * r - along * along)
        val ux = cos(phi).toFloat()
        val uy = sin(phi).toFloat()
        val mx = cx + ux * along
        val my = cy + uy * along
        moveTo(mx - uy * half, my + ux * half)
        arcTo(r, r, 0f, along > 0, true, mx + uy * half, my - ux * half)
        arcTo(biteR, biteR, 0f, along > offset, false, mx - uy * half, my + ux * half)
        close()
    }

    /** A cog of [teeth] tapered teeth: flats on the [tip] circle, true arcs along the [root] circle between them. */
    private fun PathBuilder.gear(cx: Float, cy: Float, teeth: Int, tip: Float, root: Float, tipHalf: Float, rootHalf: Float) {
        val step = 360f / teeth
        fun x(deg: Float, r: Float) = cx + cos(Math.toRadians(deg - 90.0)).toFloat() * r
        fun y(deg: Float, r: Float) = cy + sin(Math.toRadians(deg - 90.0)).toFloat() * r
        moveTo(x(-rootHalf, root), y(-rootHalf, root))
        for (i in 0 until teeth) {
            val c = i * step
            lineTo(x(c - tipHalf, tip), y(c - tipHalf, tip))
            arcTo(tip, tip, 0f, false, true, x(c + tipHalf, tip), y(c + tipHalf, tip))
            lineTo(x(c + rootHalf, root), y(c + rootHalf, root))
            arcTo(root, root, 0f, false, true, x(c + step - rootHalf, root), y(c + step - rootHalf, root))
        }
        close()
    }

    /** Square cells of side [size] from ([x0], [y0]), one filled for every character in [rows] that isn't '.'. */
    private fun PathBuilder.cells(x0: Float, y0: Float, size: Float, vararg rows: String) {
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch != '.') {
                    moveTo(x0 + c * size, y0 + r * size)
                    horizontalLineToRelative(size)
                    verticalLineToRelative(size)
                    horizontalLineToRelative(-size)
                    close()
                }
            }
        }
    }

    /** A dial with its needle: the overview. */
    val Overview = glyph("overview") {
        line { circle(12f, 12f, 9f) }
        line { moveTo(12f, 12f); lineTo(16f, 8f) }
        line(fill = true) { circle(12f, 12f, 1.75f) }
    }

    /** A cell with its terminal and a charge bar. */
    val Battery = glyph("battery") {
        line { roundRect(2.5f, 6.5f, 18.25f, 17.5f, 2.5f) }
        line { moveTo(21.5f, 10.25f); lineTo(21.5f, 13.75f) }
        line(fill = true) { roundRect(4.875f, 8.875f, 11f, 15.125f, 0.75f) }
    }

    /** The tablet: a slab with its bezel bar. */
    val Device = glyph("device") {
        line { roundRect(5.25f, 3f, 18.75f, 21f, 2.5f) }
        line { moveTo(10f, 17.5f); lineTo(14f, 17.5f) }
    }

    /** Four keys of a keypad, one lit: the installed apps. */
    val Apps = glyph("apps") {
        line { circle(7f, 7f, 3.25f) }
        line { circle(17f, 7f, 3.25f) }
        line { circle(7f, 17f, 3.25f) }
        line(fill = true) { circle(17f, 17f, 3.75f) }
    }

    /** A key with a lamp in its bow: what the app is allowed to open. */
    val Access = glyph("access") {
        line { circle(7.5f, 12f, 4.5f) }
        line(fill = true) { circle(7.5f, 12f, 1.75f) }
        line { moveTo(12f, 12f); lineTo(21f, 12f); lineTo(21f, 16f); moveTo(17f, 12f); lineTo(17f, 15.25f) }
    }

    /** Two slide controls, one set: the tweaks. */
    val Tweaks = glyph("tweaks") {
        line { moveTo(3f, 7f); lineTo(21f, 7f) }
        line(fill = true) { circle(9f, 7f, 3.5f) }
        line { moveTo(3f, 17f); lineTo(12.25f, 17f); moveTo(17.75f, 17f); lineTo(21f, 17f) }
        line { circle(15f, 17f, 2.75f) }
    }

    /** A cog: the settings index. */
    val Hub = glyph("hub") {
        line { gear(12f, 12f, 6, 8.75f, 6.5f, 9.5f, 15.5f) }
        line { circle(12f, 12f, 2.5f) }
    }

    /** Search lens for text fields. */
    val Search = glyph("search") {
        line { circle(10.5f, 10.5f, 6.5f) }
        line { moveTo(15.1f, 15.1f); lineTo(20.5f, 20.5f) }
    }

    /** A rim round a half-inked disc: contrast, the appearance. */
    val Appearance = glyph("appearance") {
        line { circle(12f, 12f, 9f) }
        line(fill = true) {
            moveTo(12f, 6.5f)
            arcTo(5.5f, 5.5f, 0f, false, true, 12f, 17.5f)
            close()
        }
    }

    /** Capital and small a: the fonts library. */
    val Fonts = glyph("fonts") {
        line { moveTo(2.5f, 19.5f); lineTo(7f, 4.5f); lineTo(11.5f, 19.5f); moveTo(4f, 14.5f); lineTo(10f, 14.5f) }
        line { circle(17.75f, 16.25f, 3.25f) }
        line { moveTo(21f, 13f); lineTo(21f, 19.5f) }
    }

    /** A crescent moon with a lamp in its bite: the sleep screen, the tablet asleep with its face still showing. */
    val Sleep = glyph("sleep") {
        line { crescent(12f, 12f, 8.5f, 6.75f, 3.5f, -45f) }
        line(fill = true) { circle(16.25f, 7.75f, 1.75f) }
    }

    /** A stylus with its inked nib and the stroke it has just laid: instant ink. */
    val Pen = glyph("pen") {
        line { moveTo(16f, 3f); lineTo(19f, 6f); lineTo(10.5f, 14.5f); lineTo(6f, 16f); lineTo(7.5f, 11.5f); close() }
        line(fill = true) { moveTo(10.5f, 14.5f); lineTo(6f, 16f); lineTo(7.5f, 11.5f); close() }
        line { moveTo(4.5f, 20.25f); curveToRelative(1.75f, -1.25f, 3.75f, -1.25f, 5.5f, 0f); reflectiveCurveToRelative(3.75f, 1.25f, 5.5f, 0f) }
    }

    /** Four modules of a kit, one lit: the suite of apps and built-in modules. */
    val Suite = glyph("suite") {
        line { roundRect(3.5f, 3.5f, 10.5f, 10.5f, 1.75f) }
        line { roundRect(13.5f, 3.5f, 20.5f, 10.5f, 1.75f) }
        line { roundRect(3.5f, 13.5f, 10.5f, 20.5f, 1.75f) }
        line(fill = true) { roundRect(13f, 13f, 21f, 21f, 2f) }
    }

    /** A ruled page with its binding: the logbook. */
    val Logbook = glyph("logbook") {
        line { roundRect(5f, 3f, 19f, 21f, 2f) }
        line { moveTo(8.5f, 3f); lineTo(8.5f, 21f) }
        line { moveTo(11.5f, 8f); lineTo(16f, 8f); moveTo(11.5f, 12f); lineTo(16f, 12f); moveTo(11.5f, 16f); lineTo(14.5f, 16f) }
    }

    /** A pen nib with its slit and breather hole: Nib, the drawing app. */
    val Nib = glyph("nib") {
        line { moveTo(12f, 21f); lineTo(5.5f, 11f); lineTo(8f, 3.5f); lineTo(16f, 3.5f); lineTo(18.5f, 11f); close() }
        line { moveTo(12f, 21f); lineTo(12f, 12.5f) }
        line(fill = true) { circle(12f, 10.25f, 1.75f) }
    }

    /** An arrow turning back: undo. */
    val Undo = glyph("undo") {
        line { moveTo(8.5f, 5f); lineTo(4f, 9.5f); lineTo(8.5f, 14f) }
        line { moveTo(4f, 9.5f); lineTo(14.5f, 9.5f); curveToRelative(3.25f, 0f, 5.5f, 2.25f, 5.5f, 5.25f); reflectiveCurveToRelative(-2.25f, 5.25f, -5.5f, 5.25f); lineTo(10f, 20f) }
    }

    /** An arrow turning forward: redo. */
    val Redo = glyph("redo") {
        line { moveTo(15.5f, 5f); lineTo(20f, 9.5f); lineTo(15.5f, 14f) }
        line { moveTo(20f, 9.5f); lineTo(9.5f, 9.5f); curveToRelative(-3.25f, 0f, -5.5f, 2.25f, -5.5f, 5.25f); reflectiveCurveToRelative(2.25f, 5.25f, 5.5f, 5.25f); lineTo(14f, 20f) }
    }

    // ---------- Key glyphs (launcher keys) ----------

    /** A cog with its hub. */
    val Gear = Hub

    /** A pencil laid diagonally, with its ferrule and sharpened cone: arranging. */
    val Pencil = glyph("pencil") {
        line { moveTo(17f, 4f); lineTo(20f, 7f); lineTo(8.5f, 18.5f); lineTo(4f, 20f); lineTo(5.5f, 15.5f); close() }
        line { moveTo(15f, 6f); lineTo(18f, 9f); moveTo(5.5f, 15.5f); lineTo(8.5f, 18.5f) }
    }

    val Close = glyph("close") { line { moveTo(6f, 6f); lineTo(18f, 18f) }; line { moveTo(18f, 6f); lineTo(6f, 18f) } }

    val ChevronLeft = glyph("chevron-left") { line { moveTo(15.5f, 5f); lineTo(8.5f, 12f); lineTo(15.5f, 19f) } }

    val ChevronRight = glyph("chevron-right") { line { moveTo(8.5f, 5f); lineTo(15.5f, 12f); lineTo(8.5f, 19f) } }

    /** Nine dots: every app. */
    val AllApps = glyph("all-apps") {
        for (x in listOf(6f, 12f, 18f)) for (y in listOf(6f, 12f, 18f)) line(fill = true) { circle(x, y, 1.75f) }
    }

    /**
     * BooxUltimatum's own mark, the ordered-dither ramp of `ic_mark`: black thickening from the top right to solid along
     * the bottom and left edges, with the 2×2 lamp ('o', green in the logo) lit in the top-right corner. The full 8×8
     * grid on 2.25 cells, an 18-unit square like the family's other square keylines: the hub app.
     */
    val Mark = glyph("mark") {
        line(fill = true) {
            cells(
                3f, 3f, 2.25f,
                ".#....oo",
                "#.....oo",
                ".#.#....",
                "###.....",
                "##.#.#..",
                "#####...",
                "####.#.#",
                "#######.",
            )
        }
    }
}
