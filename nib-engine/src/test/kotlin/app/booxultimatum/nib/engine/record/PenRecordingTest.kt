package app.booxultimatum.nib.engine.record

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.Tool
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class PenRecordingTest {
    private val header = PenHeader("BOOX NoteAir6C test", 1860, 2480, 4095f, "about 420 Hz")

    /** Three pen strokes (the second cancelled), hover in between, an eraser stroke and display markers. */
    private fun session(): PenRecording {
        val rec = PenRecorder(header)
        var t = 1_000_000_000L
        fun sample(action: PenAction, x: Float, y: Float, tool: Tool = Tool.Pen) {
            t += 2_380_952L
            rec.sample(action, tool, InputSample(x, y, (x % 100f) / 100f, 0.4f, -0.7f, t))
        }
        rec.marker("setPenState", "1", t)
        sample(PenAction.HoverEnter, 10f, 10f)
        sample(PenAction.HoverMove, 12f, 10f)
        sample(PenAction.HoverExit, 14f, 10f)
        sample(PenAction.Down, 20f, 20f)
        for (i in 1..30) sample(PenAction.Move, 20f + i * 2f, 20f + i)
        sample(PenAction.Up, 85f, 52f)
        rec.marker("repaint", "full", t)
        sample(PenAction.Down, 200f, 200f)
        sample(PenAction.Move, 210f, 200f)
        sample(PenAction.Cancel, 210f, 200f)
        sample(PenAction.Down, 300f, 300f, Tool.Eraser)
        sample(PenAction.Move, 320f, 300f, Tool.Eraser)
        sample(PenAction.Up, 340f, 300f, Tool.Eraser)
        sample(PenAction.Down, 400f, 400f)
        for (i in 1..10) sample(PenAction.Move, 400f + i * 3f, 400f)
        return rec.build()
    }

    private fun encode(r: PenRecording): ByteArray = ByteArrayOutputStream().also { PenRecordingCodec.write(r, it) }.toByteArray()

    @Test
    fun roundTripsExactly() {
        val rec = session()
        val back = PenRecordingCodec.read(ByteArrayInputStream(encode(rec)))
        assertEquals(rec, back)
        assertEquals(2, back.markers.size)
        assertEquals(rec.events.size - 2, back.samples.size)
        val rnd = Random(4)
        val r2 = PenRecorder(PenHeader("", 0, 0, 0f))
        repeat(1000) {
            r2.sample(PenAction.entries[rnd.nextInt(7)], Tool.entries[rnd.nextInt(3)], InputSample(rnd.nextFloat() * 3000f, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextLong()))
        }
        val big = r2.build()
        assertEquals(big, PenRecordingCodec.read(ByteArrayInputStream(encode(big))))
    }

    @Test
    fun truncatedRecordingsAreReportedOrReadLeniently() {
        val rec = session()
        val bytes = encode(rec)
        assertFailsWith<PenRecordingException> { PenRecordingCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 5))) }
        val headerLength = encode(PenRecording(header, emptyList())).size - 1
        for (cut in headerLength until bytes.size step 7) {
            val partial = PenRecordingCodec.read(ByteArrayInputStream(bytes.copyOf(cut)), lenient = true)
            assertEquals(header, partial.header)
            assertTrue(partial.events.size <= rec.events.size)
            assertEquals(rec.events.subList(0, partial.events.size), partial.events, "cut at $cut keeps a clean prefix")
        }
        assertFailsWith<PenRecordingException> { PenRecordingCodec.read(ByteArrayInputStream("hello world".toByteArray())) }
    }

    @Test
    fun streamingWriterMatchesTheCodec() {
        val rec = session()
        val out = ByteArrayOutputStream()
        PenRecordingWriter(out, rec.header).use { w -> rec.events.forEach(w::write) }
        assertEquals(rec, PenRecordingCodec.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun replayRebuildsTheStrokes() {
        val rec = session()
        val brush = BrushSpec.defaults(BrushKind.Fountain)
        val strokes = PenReplay.strokes(rec, brush, BLACK, firstId = 10)
        assertEquals(2, strokes.size, "the cancelled stroke and the eraser are left out; the open one is finished")
        assertEquals(listOf(10L, 11L), strokes.map { it.id })
        val first = strokes[0]
        assertEquals(32, first.points.size)
        assertEquals(PackedPoints.quantize(85f), first.points.x(first.points.size - 1), "ends where the pen lifted")
        assertEquals(PackedPoints.quantize(52f), first.points.y(first.points.size - 1))
        assertEquals(2, first.points.deltaMillis(1), "timing survives")
        assertEquals(11, strokes[1].points.size)
        val erasers = PenReplay.strokes(rec, BrushSpec.defaults(BrushKind.PixelEraser), BLACK, tools = setOf(Tool.Eraser))
        assertEquals(1, erasers.size)
        assertEquals(3, erasers[0].points.size)
        val doubled = PenReplay.strokes(rec, brush, BLACK, transform = Affine.scale(2f))
        assertEquals(PackedPoints.quantize(170f), doubled[0].points.x(doubled[0].points.size - 1))
    }
}
