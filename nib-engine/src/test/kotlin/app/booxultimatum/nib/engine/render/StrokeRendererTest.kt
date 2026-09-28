package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.RecordingSink
import app.booxultimatum.nib.engine.RecordingSink.Call
import app.booxultimatum.nib.engine.YELLOW
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.line
import app.booxultimatum.nib.engine.scribble
import app.booxultimatum.nib.engine.stroke
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StrokeRendererTest {
    private fun raster(s: Stroke, w: Int = 200, h: Int = 200): SoftwareRaster =
        SoftwareRaster(w, h).also { StrokeRenderer.render(s, it) }

    @Test
    fun renderingIsDeterministic() {
        val rnd = Random(5)
        for (kind in BrushKind.entries) {
            val s = scribble(rnd, 42, kind, 300, 30f, 30f, 170f, 170f)
            val a = raster(s)
            val b = raster(s)
            assertTrue(a.contentEquals(b), "$kind renders the same twice")
            val ca = RecordingSink().also { StrokeRenderer.render(s, it) }.calls
            val cb = RecordingSink().also { StrokeRenderer.render(s, it) }.calls
            assertEquals(ca.size, cb.size)
            // An equal copy of the stroke (a new object) also renders identically.
            val copy = Stroke(s.id, s.brush.copy(), s.color, s.points)
            assertTrue(a.contentEquals(raster(copy)), "$kind copy renders the same")
        }
    }

    @Test
    fun finelinerOfWidthFourDarkensAFourPixelBand() {
        val s = stroke(1, BrushKind.Fineliner, line(20f, 50f, 180f, 50f, 81), width = 4f)
        val r = raster(s, 200, 100)
        for (x in listOf(40, 100, 160)) {
            var column = 0f
            for (y in 0 until 100) column += r.alpha(x, y)
            assertEquals(4f, column, 0.05f, "column $x holds 4 px of ink")
            for (y in 48..51) assertEquals(1f, r.darkness(x, y), 0.01f)
            assertEquals(0f, r.alpha(x, 47))
            assertEquals(0f, r.alpha(x, 52))
        }
        assertEquals(0xFF000000.toInt(), r.pixel(100, 50))
    }

    @Test
    fun markerCrossingItselfStaysAtHalfOpacity() {
        val path = floatArrayOf(30f, 100f, 170f, 100f, 170f, 60f, 100f, 60f, 100f, 150f)
        val s = stroke(1, BrushKind.Marker, path, width = 20f)
        val r = raster(s)
        assertEquals(0.5f, r.alpha(50, 100), 0.01f)
        assertEquals(0.5f, r.alpha(100, 100), 0.01f, "the crossing is no darker")
        assertEquals(0.5f, r.alpha(170, 80), 0.01f)
        // Two separate marker strokes do build up, like real markers.
        val two = SoftwareRaster(200, 200)
        StrokeRenderer.render(stroke(1, BrushKind.Marker, line(30f, 100f, 170f, 100f, 10), width = 20f), two)
        StrokeRenderer.render(stroke(2, BrushKind.Marker, line(100f, 30f, 100f, 170f, 10), width = 20f), two)
        assertEquals(0.75f, two.alpha(100, 100), 0.01f)
    }

    @Test
    fun pixelEraserRemovesEarlierInkInItsOwnLayerOnly() {
        val red = 0xFFFF0000.toInt()
        val c = stroke(10, BrushKind.Fineliner, line(20f, 100f, 180f, 100f, 20), width = 10f, color = red)
        val a = stroke(11, BrushKind.Fineliner, line(100f, 20f, 100f, 180f, 20), width = 10f)
        val e = stroke(12, BrushKind.PixelEraser, line(20f, 100f, 180f, 100f, 20), width = 20f)
        val b = stroke(13, BrushKind.Fineliner, line(140f, 20f, 140f, 180f, 20), width = 10f)
        val doc = Document("d", 200, 200, layers = listOf(Layer(1, strokes = listOf(c)), Layer(2, strokes = listOf(a, e, b))), nextId = 20)
        val r = SoftwareRaster(200, 200)
        DocumentRenderer.render(doc, r, paintBackground = false)
        assertEquals(BLACK, r.pixel(100, 80), "ink away from the eraser stays")
        assertEquals(red, r.pixel(100, 100), "the layer below shows through the erased ink")
        assertEquals(0f, r.alpha(100, 107), "erased, with nothing beneath")
        assertEquals(BLACK, r.pixel(140, 100), "ink drawn after the eraser is untouched")
        assertEquals(red, r.pixel(60, 100), "the eraser never reaches another layer")
    }

    @Test
    fun multiplyDarkensButDoesNotCover() {
        val grey = 0xFF808080.toInt()
        val r = SoftwareRaster(200, 200)
        StrokeRenderer.render(stroke(1, BrushKind.Fineliner, line(100f, 20f, 100f, 180f, 20), width = 6f), r)
        StrokeRenderer.render(stroke(2, BrushKind.Fineliner, line(60f, 20f, 60f, 180f, 20), width = 6f, color = grey), r)
        val greyDarkness = r.darkness(60, 50)
        StrokeRenderer.render(stroke(3, BrushKind.Highlighter, line(20f, 100f, 180f, 100f, 40), width = 20f, color = YELLOW), r)
        assertEquals(1f, r.darkness(100, 100), 0.01f, "black ink stays black under the highlighter")
        val paper = r.pixel(140, 100)
        assertEquals(0xFF, (paper shr 16) and 0xFF)
        assertEquals(0xFF, (paper shr 8) and 0xFF)
        assertEquals(0, paper and 0xFF, "the highlighter is yellow on paper")
        assertTrue(r.darkness(60, 100) > greyDarkness + 0.02f, "grey gets darker")
        val tinted = r.pixel(60, 100)
        assertEquals(0x80, (tinted shr 16) and 0xFF, "multiply keeps the grey's red")
        assertEquals(0, tinted and 0xFF, "and takes the yellow's missing blue")
    }

    @Test
    fun everyKindPaintsInsideItsBounds() {
        val rnd = Random(9)
        for (kind in BrushKind.entries) {
            val s = scribble(rnd, 7, kind, 150, 50f, 50f, 150f, 150f)
            val bounds = StrokeRenderer.bounds(s)
            assertEquals(bounds, s.bounds)
            val r = SoftwareRaster(200, 200)
            if (kind == BrushKind.PixelEraser) {
                r.fillPath(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), 4, BLACK, Blend.Normal)
            }
            val sink = RecordingSink()
            StrokeRenderer.render(s, r)
            StrokeRenderer.render(s, sink)
            if (!kind.isRendered) {
                assertTrue(sink.calls.isEmpty(), "$kind draws nothing")
                continue
            }
            var changed = 0
            for (y in 0 until 200) for (x in 0 until 200) {
                val a = r.alpha(x, y)
                val touched = if (kind == BrushKind.PixelEraser) a < 1f else a > 0f
                if (touched) {
                    changed++
                    assertTrue(bounds.contains(x + 0.5f, y + 0.5f), "$kind paints ($x, $y) outside $bounds")
                }
            }
            assertTrue(changed > 50, "$kind paints something ($changed px)")
        }
    }

    @Test
    fun callsMatchEachBrushsRecipe() {
        val path = line(20f, 20f, 120f, 60f, 30)
        fun calls(kind: BrushKind, color: Int = BLACK) =
            RecordingSink().also { StrokeRenderer.render(stroke(1, kind, path, color = color), it) }.calls

        val marker = calls(BrushKind.Marker)
        assertEquals(Call.Begin(0.5f, Blend.Normal), marker[0])
        assertEquals(Blend.Normal, (marker[1] as Call.Path).blend)
        assertEquals(BLACK, (marker[1] as Call.Path).color)
        assertEquals(Call.End, marker[2])
        assertEquals(Blend.Multiply, (calls(BrushKind.Highlighter, YELLOW).single() as Call.Path).blend)
        val eraser = calls(BrushKind.PixelEraser, 0x10123456).single() as Call.Path
        assertEquals(Blend.Erase, eraser.blend)
        assertEquals(BLACK, eraser.color, "erasers erase fully whatever their colour")
        assertEquals(0.88f, (calls(BrushKind.Ballpoint).first() as Call.Begin).alpha, 1e-6f)
        val translucent = calls(BrushKind.Fineliner, 0x80000000.toInt())
        assertEquals(128 / 255f, (translucent[0] as Call.Begin).alpha, 1e-6f)
        assertEquals(BLACK, (translucent[1] as Call.Path).color, "colour alpha moves to the group")
        for ((kind, texture) in listOf(
            BrushKind.Pencil to Texture.Grain, BrushKind.Graphite to Texture.Grain, BrushKind.Charcoal to Texture.Charcoal,
            BrushKind.CharcoalV2 to Texture.Charcoal, BrushKind.Airbrush to Texture.Soft,
        )) {
            val dabs = calls(kind).filterIsInstance<Call.Dab>()
            assertTrue(dabs.size > 10, "$kind stamps dabs")
            assertTrue(dabs.all { it.texture == texture })
            assertTrue(dabs.map { it.angle }.toSet().size > dabs.size / 2, "$kind dabs are turned by the hash")
        }
        val dash = calls(BrushKind.Dash).single() as Call.Polyline
        val w = BrushSpec.defaults(BrushKind.Dash).width
        assertEquals(w, dash.width, 1e-5f)
        assertEquals(3f * w, dash.dash!![0], 1e-5f)
        assertEquals(2.2f * w, dash.dash!![1], 1e-5f)
        assertEquals(Cap.Butt, dash.cap)
        assertTrue(calls(BrushKind.StrokeEraser).isEmpty())
        assertTrue(calls(BrushKind.LassoEraser).isEmpty())
    }

    @Test
    fun dashesLeaveGaps() {
        val s = stroke(1, BrushKind.Dash, line(20f, 50f, 180f, 50f, 40), width = 2f)
        val r = raster(s, 200, 100)
        var runs = 0
        var inked = false
        for (x in 0 until 200) {
            val now = r.alpha(x, 50) > 0.5f
            if (now && !inked) runs++
            inked = now
        }
        assertTrue(runs in 14..18, "about 160 / 10.4 dashes, got $runs")
    }

    @Test
    fun calligraphyFollowsTheNib() {
        val rising = stroke(1, BrushKind.Calligraphy, line(50f, 150f, 150f, 50f, 40), width = 16f)
        val falling = stroke(2, BrushKind.Calligraphy, line(50f, 50f, 150f, 150f, 40), width = 16f)
        val thin = raster(rising).inkedPixels()
        val thick = raster(falling).inkedPixels()
        assertTrue(thick > 3 * thin, "a 45 degree nib is thin along its edge: $thin vs $thick")

        val oriented = BrushSpec.defaults(BrushKind.Calligraphy).copy(width = 16f, nibFromOrientation = true)
        fun withOrientation(xy: FloatArray): Stroke {
            val b = PackedPoints.Builder()
            for (i in 0 until xy.size / 2) b.add(xy[2 * i], xy[2 * i + 1], 1f, 0f, 0f)
            return Stroke(3, oriented, BLACK, b.build())
        }
        val horizontal = raster(withOrientation(line(40f, 100f, 160f, 100f, 40))).inkedPixels()
        val vertical = raster(withOrientation(line(100f, 40f, 100f, 160f, 40))).inkedPixels()
        assertTrue(vertical > 3 * horizontal, "a pen pointing up holds the nib edge level: $horizontal vs $vertical")
    }

    @Test
    fun brushPenThinsWithSpeedAndTapers() {
        val path = line(40f, 100f, 160f, 100f, 61)
        val brush = BrushSpec.defaults(BrushKind.BrushPen).copy(width = 12f, taper = 0f)
        val fast = raster(stroke(1, BrushKind.BrushPen, path, deltaMillis = 1L, brush = brush)).inkedPixels()
        val slow = raster(stroke(1, BrushKind.BrushPen, path, deltaMillis = 20L, brush = brush)).inkedPixels()
        assertTrue(fast < slow * 0.85f, "fast $fast vs slow $slow")

        val neo = StrokeRenderer.centreline(stroke(2, BrushKind.NeoBrush, line(20f, 100f, 180f, 100f, 81), deltaMillis = 20L))
        val n = neo.size / 3
        val middle = neo[3 * (n / 2) + 2]
        assertTrue(neo[2] < 0.3f * middle && neo[3 * (n - 1) + 2] < 0.3f * middle, "neo brush tapers at both ends")
    }

    @Test
    fun translatedStrokesRenderTheSameShifted() {
        val rnd = Random(3)
        // Grain textures are fixed to the page, so only brushes without them are compared pixel for pixel.
        for (kind in listOf(BrushKind.Fountain, BrushKind.Airbrush, BrushKind.Calligraphy, BrushKind.Dash)) {
            val s = scribble(rnd, 5, kind, 120, 30f, 30f, 120f, 170f)
            val moved = s.transformed(Affine.translate(40f, 0f))
            val a = raster(s)
            val b = raster(moved)
            var diff = 0f
            var ink = 0f
            for (y in 0 until 200) for (x in 0 until 160) {
                diff += abs(a.alpha(x, y) - b.alpha(x + 40, y))
                ink += a.alpha(x, y)
            }
            assertTrue(ink > 0f && diff < ink * 0.02f, "$kind: diff $diff of $ink")
            assertEquals(s.bounds.left + 40f, moved.bounds.left, 0.01f)
        }
    }

    @Test
    fun degenerateStrokes() {
        val empty = Stroke(1, BrushSpec.defaults(BrushKind.Fountain), BLACK, PackedPoints.EMPTY)
        assertEquals(Box.EMPTY, StrokeRenderer.bounds(empty))
        assertTrue(RecordingSink().also { StrokeRenderer.render(empty, it) }.calls.isEmpty())
        assertEquals(0, StrokeRenderer.outline(empty).size)
        val same = stroke(2, BrushKind.Fountain, floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f), width = 8f)
        val call = RecordingSink().also { StrokeRenderer.render(same, it) }.calls.single()
        assertTrue(call is Call.Circle, "a stroke that never moves is a dot")
        val square = RecordingSink().also { StrokeRenderer.render(stroke(3, BrushKind.SquarePen, floatArrayOf(5f, 5f)), it) }
        assertEquals(4, (square.calls.single() as Call.Path).xy.size / 2, "a square pen dot is a square")
        val invisible = stroke(4, BrushKind.Fineliner, line(0f, 0f, 10f, 0f, 3), color = 0x00FFFFFF)
        assertTrue(RecordingSink().also { StrokeRenderer.render(invisible, it) }.calls.isEmpty())
    }

    @Test
    fun documentRendererHonoursLayers() {
        val s = stroke(5, BrushKind.Fineliner, line(20f, 50f, 180f, 50f, 10), width = 10f)
        val doc = Document("d", 200, 100, background = -1, layers = listOf(Layer(1, strokes = listOf(s))), nextId = 6)
        val r = SoftwareRaster(200, 100)
        DocumentRenderer.render(doc, r)
        assertEquals(-1, r.pixel(10, 10), "paper")
        assertEquals(BLACK, r.pixel(100, 50))
        val faded = doc.copy(layers = listOf(Layer(1, opacity = 0.5f, strokes = listOf(s))))
        val r2 = SoftwareRaster(200, 100)
        DocumentRenderer.render(faded, r2, paintBackground = false)
        assertEquals(0.5f, r2.alpha(100, 50), 0.01f)
        val hidden = doc.copy(layers = listOf(Layer(1, visible = false, strokes = listOf(s))))
        val r3 = SoftwareRaster(200, 100)
        DocumentRenderer.render(hidden, r3, paintBackground = false)
        assertEquals(0f, r3.coverage())
        val r4 = SoftwareRaster(200, 100)
        DocumentRenderer.render(doc, r4, clip = Box(0f, 0f, 10f, 10f), paintBackground = false)
        assertEquals(0f, r4.coverage(), "strokes outside the clip are skipped")
    }
}
