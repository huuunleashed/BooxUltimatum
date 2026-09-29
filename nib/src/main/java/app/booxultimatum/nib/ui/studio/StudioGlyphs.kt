package app.booxultimatum.nib.ui.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Nib's own glyphs: a 24-unit keyline, 2-unit strokes with round caps and joins, and on each one small inked detail
 * (a filled shape) that gives it weight on e-ink: an ink bead on a nib, a shaded graphite cone, a marker's cap band, a
 * crescent of ink on the eraser's edge. Drawn here rather than borrowed, so every glyph speaks the same hand.
 */
object StudioGlyphs {
    private fun glyph(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(build: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    )

    private fun ImageVector.Builder.ink(build: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.Black), pathBuilder = build)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    /** Back to the shelf: a chevron and a page with its corner turned down in ink. */
    val Library = glyph("library") {
        line { moveTo(8.5f, 6f); lineTo(3.5f, 12f); lineTo(8.5f, 18f) }
        line { moveTo(16f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(20f, 19f); lineTo(20f, 9f) }
        ink { moveTo(16f, 4f); lineTo(21f, 9f); lineTo(16f, 9f); close() }
    }

    /** A hooked arrow back, ending in a bead of ink. */
    val Undo = glyph("undo") {
        line { moveTo(8f, 4f); lineTo(3.5f, 8.5f); lineTo(8f, 13f) }
        line { moveTo(4f, 8.5f); lineTo(14f, 8.5f); arcTo(5.5f, 5.5f, 0f, false, true, 14f, 19.5f); lineTo(11.5f, 19.5f) }
        ink { circle(8.8f, 19.5f, 1.8f) }
    }

    val Redo = glyph("redo") {
        line { moveTo(16f, 4f); lineTo(20.5f, 8.5f); lineTo(16f, 13f) }
        line { moveTo(20f, 8.5f); lineTo(10f, 8.5f); arcTo(5.5f, 5.5f, 0f, false, false, 10f, 19.5f); lineTo(12.5f, 19.5f) }
        ink { circle(15.2f, 19.5f, 1.8f) }
    }

    /** A brush with its bristles loaded. */
    val Brushes = glyph("brushes") {
        line { moveTo(20.5f, 3.5f); lineTo(13f, 11f) }
        line { moveTo(10.8f, 9.4f); lineTo(14.6f, 13.2f) }
        ink {
            moveTo(11.2f, 12.2f)
            curveTo(8.4f, 11.4f, 5.8f, 13f, 5.3f, 15.8f)
            curveTo(4.9f, 17.8f, 4f, 19f, 2.6f, 19.5f)
            curveTo(6.4f, 21f, 10.5f, 20f, 12.1f, 17.3f)
            curveTo(13f, 15.7f, 12.7f, 13.9f, 11.2f, 12.2f)
            close()
        }
    }

    /** Three sheets, the top one inked. */
    val Layers = glyph("layers") {
        ink { moveTo(12f, 3f); lineTo(21f, 7.8f); lineTo(12f, 12.6f); lineTo(3f, 7.8f); close() }
        line { moveTo(3.5f, 12f); lineTo(12f, 16.5f); lineTo(20.5f, 12f) }
        line { moveTo(3.5f, 16f); lineTo(12f, 20.5f); lineTo(20.5f, 16f) }
    }

    /** Three rules, the short one tipped with ink. */
    val Menu = glyph("menu") {
        line { moveTo(4f, 6.5f); lineTo(20f, 6.5f); moveTo(4f, 12f); lineTo(14f, 12f); moveTo(4f, 17.5f); lineTo(20f, 17.5f) }
        ink { circle(18.5f, 12f, 2f) }
    }

    /** A tilted rubber with a crescent of ink along the edge it rubs with. */
    val Eraser = glyph("eraser") {
        line { moveTo(13.5f, 4f); lineTo(20f, 10.5f); lineTo(11f, 19.5f); lineTo(4.5f, 13f); close() }
        line { moveTo(9f, 8.5f); lineTo(15.5f, 15f) }
        ink {
            moveTo(4.5f, 13f); lineTo(11f, 19.5f)
            curveTo(8.2f, 20.6f, 5.2f, 19.8f, 4.1f, 17.5f)
            curveTo(3.4f, 16f, 3.6f, 14.3f, 4.5f, 13f)
            close()
        }
        line { moveTo(14.5f, 20.5f); lineTo(20.5f, 20.5f) }
    }

    /** A dashed loop with a knotted bead and its tail. */
    val Lasso = glyph("lasso") {
        line { moveTo(4f, 10f); arcTo(8f, 5.5f, 0f, false, true, 8f, 5.24f) }
        line { moveTo(12f, 4.5f); arcTo(8f, 5.5f, 0f, false, true, 18.93f, 7.25f) }
        line { moveTo(20f, 10f); arcTo(8f, 5.5f, 0f, false, true, 16f, 14.76f) }
        line { moveTo(12.5f, 15.5f); arcTo(8f, 5.5f, 0f, false, true, 9.6f, 15.1f) }
        ink { circle(7.4f, 15.2f, 2.1f) }
        line { moveTo(7.2f, 17.6f); curveTo(6.9f, 19.3f, 7.9f, 20.6f, 9.8f, 21f) }
    }

    /** A pipette with an inked bulb and a drop at its tip. */
    val Eyedropper = glyph("eyedropper") {
        line { moveTo(13.2f, 7.6f); lineTo(16.4f, 10.8f); lineTo(8.6f, 18.6f); lineTo(5f, 19f); lineTo(5.4f, 15.4f); close() }
        ink {
            moveTo(14f, 6.6f); lineTo(17.4f, 10f); lineTo(19.6f, 7.8f)
            curveTo(20.6f, 6.8f, 20.6f, 5.2f, 19.6f, 4.3f)
            curveTo(18.6f, 3.3f, 17f, 3.3f, 16.1f, 4.4f)
            close()
        }
        ink { circle(3.9f, 21.1f, 1.4f) }
    }

    /** A three-quarter turn round an inked, tilted sheet: turn the page. */
    val Hand = glyph("hand") {
        line { moveTo(4.6f, 12f); arcTo(7.4f, 7.4f, 0f, true, true, 12f, 19.4f) }
        line { moveTo(14.6f, 21.6f); lineTo(12f, 19.4f); lineTo(14.4f, 16.9f) }
        ink { moveTo(10f, 8.9f); lineTo(15.1f, 9.9f); lineTo(14.1f, 15f); lineTo(9f, 14f); close() }
    }
    val Close = glyph("close") { line { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }

    /** A push pin, its head open: the panel closes when the pen draws. */
    val Pin = glyph("pin") {
        line { moveTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(14f, 9.5f); lineTo(17f, 12.5f); lineTo(7f, 12.5f); lineTo(10f, 9.5f); close() }
        line { moveTo(12f, 12.5f); lineTo(12f, 20.5f) }
    }

    /** The pin pressed in, its head inked: the panel stays while the pen draws. */
    val Pinned = glyph("pinned") {
        ink { moveTo(8f, 2.5f); lineTo(16f, 2.5f); lineTo(15f, 9f); lineTo(18.3f, 12.3f); lineTo(18.3f, 13.5f); lineTo(5.7f, 13.5f); lineTo(5.7f, 12.3f); lineTo(9f, 9f); close() }
        line { moveTo(12f, 13.5f); lineTo(12f, 21f) }
    }

    /** Six dots: take hold here to move the card. */
    val Grip = glyph("grip") {
        for (y in listOf(6.5f, 12f, 17.5f)) for (x in listOf(9f, 15f)) ink { circle(x, y, 1.6f) }
    }

    val Plus = glyph("plus") { line { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }

    val Minus = glyph("minus") { line { moveTo(5f, 12f); lineTo(19f, 12f) } }

    val Eye = glyph("eye") {
        line { moveTo(2.5f, 12f); curveTo(5f, 7f, 8.5f, 5f, 12f, 5f); curveTo(15.5f, 5f, 19f, 7f, 21.5f, 12f); curveTo(19f, 17f, 15.5f, 19f, 12f, 19f); curveTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); close() }
        ink { circle(12f, 12f, 3.2f) }
    }

    val EyeClosed = glyph("eye-closed") {
        line { moveTo(2.5f, 10f); curveTo(5f, 14.5f, 8.5f, 16.5f, 12f, 16.5f); curveTo(15.5f, 16.5f, 19f, 14.5f, 21.5f, 10f) }
        line { moveTo(6f, 14.5f); lineTo(4.5f, 17f); moveTo(12f, 16.5f); lineTo(12f, 19.5f); moveTo(18f, 14.5f); lineTo(19.5f, 17f) }
    }

    /** A padlock shut, its body inked. */
    val Lock = glyph("lock") {
        ink { moveTo(5f, 10.5f); lineTo(19f, 10.5f); lineTo(19f, 20.5f); lineTo(5f, 20.5f); close() }
        line { moveTo(8.5f, 10.5f); lineTo(8.5f, 7.5f); curveTo(8.5f, 5.3f, 10f, 3.5f, 12f, 3.5f); curveTo(14f, 3.5f, 15.5f, 5.3f, 15.5f, 7.5f); lineTo(15.5f, 10.5f) }
    }

    val LockOpen = glyph("lock-open") {
        line { moveTo(5.5f, 11f); lineTo(18.5f, 11f); lineTo(18.5f, 20f); lineTo(5.5f, 20f); close() }
        line { moveTo(8.5f, 11f); lineTo(8.5f, 7.5f); curveTo(8.5f, 5.3f, 10f, 3.5f, 12f, 3.5f); curveTo(13.6f, 3.5f, 14.9f, 4.7f, 15.3f, 6.3f) }
    }

    /** A checkered square: the layer's transparency stays as it is. */
    val AlphaLock = glyph("alpha-lock") {
        line { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close() }
        ink { moveTo(4f, 4f); lineTo(12f, 4f); lineTo(12f, 12f); lineTo(4f, 12f); close() }
        ink { moveTo(12f, 12f); lineTo(20f, 12f); lineTo(20f, 20f); lineTo(12f, 20f); close() }
    }

    val Up = glyph("up") { line { moveTo(5f, 15.5f); lineTo(12f, 8.5f); lineTo(19f, 15.5f) } }

    val Down = glyph("down") { line { moveTo(5f, 8.5f); lineTo(12f, 15.5f); lineTo(19f, 8.5f) } }

    val ChevronLeft = glyph("chevron-left") { line { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) } }

    val ChevronRight = glyph("chevron-right") { line { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) } }

    /** A sheet with a copy in front of it, marked with a plus. */
    val Duplicate = glyph("duplicate") {
        line { moveTo(8.5f, 7f); lineTo(8.5f, 3.5f); lineTo(20f, 3.5f); lineTo(20f, 15.5f); lineTo(16f, 15.5f) }
        line { moveTo(4f, 7.5f); lineTo(15.5f, 7.5f); lineTo(15.5f, 20.5f); lineTo(4f, 20.5f); close() }
        ink { moveTo(8.9f, 10.8f); lineTo(10.6f, 10.8f); lineTo(10.6f, 13.1f); lineTo(12.9f, 13.1f); lineTo(12.9f, 14.9f); lineTo(10.6f, 14.9f); lineTo(10.6f, 17.2f); lineTo(8.9f, 17.2f); lineTo(8.9f, 14.9f); lineTo(6.6f, 14.9f); lineTo(6.6f, 13.1f); lineTo(8.9f, 13.1f); close() }
    }

    /** Two layers folding into the inked one below. */
    val MergeDown = glyph("merge-down") {
        line { moveTo(6f, 4f); lineTo(12f, 10f); lineTo(18f, 4f) }
        line { moveTo(12f, 10f); lineTo(12f, 14f) }
        ink { moveTo(4f, 15.5f); lineTo(20f, 15.5f); lineTo(20f, 20f); lineTo(4f, 20f); close() }
    }

    val Trash = glyph("trash") {
        line { moveTo(4f, 6.5f); lineTo(20f, 6.5f) }
        line { moveTo(9.5f, 6.5f); lineTo(9.5f, 3.8f); lineTo(14.5f, 3.8f); lineTo(14.5f, 6.5f) }
        line { moveTo(6f, 6.5f); lineTo(7f, 20.5f); lineTo(17f, 20.5f); lineTo(18f, 6.5f) }
        ink { moveTo(9.2f, 10f); lineTo(10.8f, 10f); lineTo(10.8f, 17f); lineTo(9.2f, 17f); close(); moveTo(13.2f, 10f); lineTo(14.8f, 10f); lineTo(14.8f, 17f); lineTo(13.2f, 17f); close() }
    }

    /** An inked arrow rising out of a tray. */
    val Export = glyph("export") {
        line { moveTo(4.5f, 13.5f); lineTo(4.5f, 20f); lineTo(19.5f, 20f); lineTo(19.5f, 13.5f) }
        line { moveTo(12f, 8f); lineTo(12f, 15.5f) }
        ink { moveTo(7.5f, 8.8f); lineTo(12f, 3f); lineTo(16.5f, 8.8f); close() }
    }

    val Share = glyph("share") {
        line { moveTo(7.6f, 11f); lineTo(15.4f, 6.8f); moveTo(7.6f, 13f); lineTo(15.4f, 17.2f) }
        ink { circle(6f, 12f, 2.6f) }
        line { circle(17.5f, 5.8f, 2.4f) }
        line { circle(17.5f, 18.2f, 2.4f) }
    }

    /** A magnifier with an inked glint. */
    val Search = glyph("search") {
        line { circle(10.5f, 10.5f, 6.5f) }
        line { moveTo(15.3f, 15.3f); lineTo(20.5f, 20.5f) }
        ink { circle(8.2f, 8.2f, 1.5f) }
    }

    /** A box with a tick: choose several. */
    val Select = glyph("select") {
        line { moveTo(4.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 19.5f); lineTo(4.5f, 19.5f); close() }
        line { moveTo(8f, 12.5f); lineTo(11f, 15.5f); lineTo(16.5f, 9f) }
    }

    val Check = glyph("check") { line { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }

    val Fullscreen = glyph("fullscreen") {
        line { moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f); moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f) }
        line { moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f); moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f) }
        ink { circle(12f, 12f, 1.8f) }
    }

    val ExitFullscreen = glyph("exit-fullscreen") {
        line { moveTo(9f, 3.5f); lineTo(9f, 9f); lineTo(3.5f, 9f); moveTo(20.5f, 9f); lineTo(15f, 9f); lineTo(15f, 3.5f) }
        line { moveTo(15f, 20.5f); lineTo(15f, 15f); lineTo(20.5f, 15f); moveTo(3.5f, 15f); lineTo(9f, 15f); lineTo(9f, 20.5f) }
    }

    /** The page's frame with an upright inked mark in the middle: fit the page, the right way up. */
    val ResetView = glyph("reset-view") {
        line { moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f); moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f) }
        line { moveTo(20f, 15.5f); lineTo(20f, 20f); lineTo(15.5f, 20f); moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f) }
        ink { moveTo(12f, 7.5f); lineTo(15f, 12f); lineTo(12f, 16.5f); lineTo(9f, 12f); close() }
    }

    /** A sheet with ruled lines and its corner folded in ink. */
    val Paper = glyph("paper") {
        line { moveTo(6f, 3.5f); lineTo(14.5f, 3.5f); lineTo(18.5f, 7.5f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close() }
        ink { moveTo(14f, 3f); lineTo(19f, 8f); lineTo(14f, 8f); close() }
        line { moveTo(9f, 12f); lineTo(15.5f, 12f); moveTo(9f, 15.8f); lineTo(15.5f, 15.8f) }
    }

    /** Two sliders with inked knobs. */
    val Settings = glyph("settings") {
        line { moveTo(3.5f, 7f); lineTo(20.5f, 7f); moveTo(3.5f, 17f); lineTo(20.5f, 17f) }
        ink { circle(9f, 7f, 2.6f) }
        ink { circle(15f, 17f, 2.6f) }
    }

    /** A probe's trace with an inked sample point. */
    val Diagnostics = glyph("diagnostics") {
        line { moveTo(3f, 14f); curveTo(5f, 14f, 5.5f, 8.5f, 7.5f, 8.5f); curveTo(9.5f, 8.5f, 10f, 16f, 12f, 16f); curveTo(14f, 16f, 14.5f, 11f, 16.5f, 11f); curveTo(18.5f, 11f, 19f, 14f, 21f, 14f) }
        ink { circle(7.5f, 8.5f, 2f) }
    }

    val About = glyph("about") {
        line { circle(12f, 12f, 8.5f) }
        line { moveTo(12f, 11f); lineTo(12f, 16.5f) }
        ink { circle(12f, 7.7f, 1.4f) }
    }

    /** Strokes dropped onto another layer: an inked arrow into a sheet. */
    val MoveToLayer = glyph("move-to-layer") {
        line { moveTo(12f, 9.5f); lineTo(20.5f, 13.8f); lineTo(12f, 18f); lineTo(3.5f, 13.8f); close() }
        line { moveTo(12f, 2.5f); lineTo(12f, 8f) }
        ink { moveTo(8.8f, 6f); lineTo(12f, 10f); lineTo(15.2f, 6f); close() }
        line { moveTo(3.5f, 17.8f); lineTo(12f, 22f); lineTo(20.5f, 17.8f) }
    }

    /** A drop half full of ink: give the strokes the chosen colour. */
    val Recolour = glyph("recolour") {
        line { moveTo(12f, 3.2f); curveTo(12f, 3.2f, 5.8f, 10f, 5.8f, 14.2f); arcTo(6.2f, 6.2f, 0f, false, false, 18.2f, 14.2f); curveTo(18.2f, 10f, 12f, 3.2f, 12f, 3.2f); close() }
        ink { moveTo(6.1f, 15.2f); arcTo(6.2f, 6.2f, 0f, false, false, 17.9f, 15.2f); close() }
    }

    /** A turning arrow round an inked centre: clear the screen of stuck ink. */
    val Recover = glyph("recover") {
        line { moveTo(19.2f, 12.5f); arcTo(7.2f, 7.2f, 0f, true, true, 16.8f, 6.6f) }
        line { moveTo(17.2f, 3f); lineTo(17.2f, 7f); lineTo(13.2f, 7f) }
        ink { circle(12f, 12.5f, 1.8f) }
    }

    /** A screen with a wipe across it and its last streak of ink: refresh the whole screen. */
    val CleanScreen = glyph("clean-screen") {
        line { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close() }
        line { moveTo(7.5f, 15.5f); lineTo(13.5f, 9.5f) }
        ink { moveTo(13.5f, 15.8f); lineTo(17f, 12.3f); lineTo(17f, 15.8f); close() }
    }

    /** A pen nib with a bead of ink at its tip: the pens. */
    val PenNib = glyph("pen-nib") {
        line { moveTo(12f, 3f); lineTo(17.5f, 10f); lineTo(12f, 19.5f); lineTo(6.5f, 10f); close() }
        line { moveTo(12f, 12f); lineTo(12f, 19f) }
        line { circle(12f, 10.2f, 1.4f) }
        ink { circle(12f, 21.2f, 1.7f) }
    }

    /** A pencil with its graphite cone shaded in: the pencils. */
    val Pencil = glyph("pencil") {
        line { moveTo(17f, 3.5f); lineTo(20.5f, 7f); lineTo(9.5f, 18f); lineTo(6f, 14.5f); close() }
        line { moveTo(14.5f, 6f); lineTo(18f, 9.5f) }
        ink { moveTo(6f, 14.5f); lineTo(9.5f, 18f); lineTo(3.4f, 20.6f); close() }
    }

    /** A marker with its cap band inked: the markers. */
    val Marker = glyph("marker") {
        line { moveTo(15f, 3.5f); lineTo(20.5f, 9f); lineTo(11f, 18.5f); lineTo(5.5f, 13f); close() }
        line { moveTo(5.5f, 13f); lineTo(3f, 18.2f); lineTo(5.8f, 21f); lineTo(11f, 18.5f) }
        ink { moveTo(12.6f, 5.9f); lineTo(18.1f, 11.4f); lineTo(16.3f, 13.2f); lineTo(10.8f, 7.7f); close() }
    }

    /** A charcoal stick shedding inked grains: the textured brushes. */
    val Charcoal = glyph("charcoal") {
        line { moveTo(16f, 3.5f); lineTo(20.5f, 8f); lineTo(10f, 18.5f); lineTo(5.5f, 14f); close() }
        ink { circle(4.4f, 19.6f, 1.5f) }
        ink { circle(7.6f, 21.3f, 1.1f) }
        ink { circle(3f, 16.6f, 0.9f) }
    }

    /** A drawn line with an inked cross on it: remove whole strokes. */
    val StrokeErase = glyph("stroke-erase") {
        line { moveTo(3f, 16f); curveTo(6f, 10f, 9f, 10f, 12f, 13f); curveTo(15f, 16f, 18f, 16f, 21f, 10f) }
        ink { moveTo(14.6f, 3.6f); lineTo(20.4f, 9.4f); lineTo(19.4f, 10.4f); lineTo(13.6f, 4.6f); close(); moveTo(13.6f, 9.4f); lineTo(19.4f, 3.6f); lineTo(20.4f, 4.6f); lineTo(14.6f, 10.4f); close() }
    }
}
