package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.NullSink
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.scribble
import kotlin.random.Random
import kotlin.test.assertTrue
import org.junit.Test

/** Prints timings; never fails on time, so it is safe on slow CI machines. */
class RenderBenchmarkTest {
    private fun time(label: String, iterations: Int, block: () -> Unit): Double {
        repeat(maxOf(5, iterations / 4)) { block() }
        val start = System.nanoTime()
        repeat(iterations) { block() }
        val micros = (System.nanoTime() - start) / 1000.0 / iterations
        println("BENCH %-44s %9.1f us".format(label, micros))
        return micros
    }

    @Test
    fun fountainStrokeOfTwoThousandPoints() {
        val stroke = scribble(Random(1), 1, BrushKind.Fountain, 2000, 100f, 100f, 1700f, 2300f, step = 2.5f)
        val sink = NullSink()
        val outline = time("Fountain 2000 pts -> outline (NullSink)", 400) { StrokeRenderer.render(stroke, sink) }
        time("Fountain 2000 pts -> bounds", 400) { StrokeRenderer.bounds(stroke) }
        val pencil = Stroke(2, stroke.brush.copy(kind = BrushKind.GrainPencil), stroke.color, stroke.points)
        time("Pencil 2000 pts -> dabs (NullSink)", 200) { StrokeRenderer.render(pencil, sink) }
        val poly = StrokeRenderer.outline(stroke)
        println("BENCH outline vertices: ${poly.size / 2}")
        val raster = SoftwareRaster(1860, 2480)
        time("Fountain 2000 pts -> SoftwareRaster 1860x2480", 5) { StrokeRenderer.render(stroke, raster) }
        assertTrue(outline > 0.0 && sink.points > 0)
    }
}
