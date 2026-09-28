package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.history.AddLayer
import app.booxultimatum.nib.engine.history.AddStroke
import app.booxultimatum.nib.engine.history.Batch
import app.booxultimatum.nib.engine.history.Clear
import app.booxultimatum.nib.engine.history.Command
import app.booxultimatum.nib.engine.history.InsertStrokes
import app.booxultimatum.nib.engine.history.MergeDown
import app.booxultimatum.nib.engine.history.MoveLayer
import app.booxultimatum.nib.engine.history.MoveStrokes
import app.booxultimatum.nib.engine.history.PlacedStroke
import app.booxultimatum.nib.engine.history.RemoveLayer
import app.booxultimatum.nib.engine.history.RemoveStrokes
import app.booxultimatum.nib.engine.history.ReplaceStrokes
import app.booxultimatum.nib.engine.history.SetLayerProps
import app.booxultimatum.nib.engine.history.TransformStrokes
import app.booxultimatum.nib.engine.scribble
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class CodecsTest {
    private fun roundTrip(c: Command): Command {
        val w = ByteWriter()
        Codecs.writeCommand(w, c)
        val r = ByteReader(w.toByteArray())
        return Codecs.readCommand(r).also { assertEquals(0, r.remaining) }
    }

    @Test
    fun everyCommandRoundTrips() {
        val rnd = Random(1)
        fun s(id: Long) = scribble(rnd, id, BrushKind.entries[rnd.nextInt(16)], 1 + rnd.nextInt(30), -10f, -10f, 500f, 500f)
            .let { it.copy(brush = SampleDocs.randomBrush(rnd, it.brush.kind)) }
        val commands = listOf(
            AddStroke(3, s(1)),
            InsertStrokes(3, listOf(PlacedStroke(0, s(2)), PlacedStroke(5, s(3)))),
            RemoveStrokes(4, listOf(9L, 2L, 1L shl 40)),
            ReplaceStrokes(4, listOf(s(4), s(5))),
            TransformStrokes(4, listOf(1L, 2L), Affine(1.5f, -0.25f, 10f, 0.3f, 0.75f, -3.5f)),
            MoveStrokes(1, 2, listOf(5L)),
            AddLayer(Layer(8, "name \u270e", visible = false, locked = true, opacity = 0.25f, blend = Blend.Atop, alphaLock = true, strokes = listOf(s(6), s(7))), 2),
            RemoveLayer(8),
            RemoveLayer(8, evenIfLocked = true),
            MoveLayer(8, 0),
            SetLayerProps(8),
            SetLayerProps(8, "n", true, false, 0.5f, Blend.Multiply, false),
            SetLayerProps(8, visible = false, blend = Blend.Erase),
            MergeDown(8),
            Clear(9),
            Batch(emptyList()),
            Batch(listOf(Clear(1), Batch(listOf(MoveLayer(2, 1), AddStroke(1, s(8)))))),
        )
        for (c in commands) assertEquals(c, roundTrip(c))
    }

    @Test
    fun varintsAndFields() {
        val w = ByteWriter()
        val values = listOf(0L, 1L, 127L, 128L, 300L, Long.MAX_VALUE, -1L, Long.MIN_VALUE)
        for (v in values) {
            w.varint(v)
            w.svarint(v)
        }
        w.int64(0x0123456789ABCDEFL)
        w.float(-0.0f)
        w.string("h\u00e9llo")
        val r = ByteReader(w.toByteArray())
        for (v in values) {
            assertEquals(v, r.varint())
            assertEquals(v, r.svarint())
        }
        assertEquals(0x0123456789ABCDEFL, r.int64())
        assertEquals((-0.0f).toRawBits(), r.float().toRawBits())
        assertEquals("h\u00e9llo", r.string())
        assertEquals(0, r.remaining)
        assertFailsWith<FormatException> { r.byte() }
        assertFailsWith<FormatException> { ByteReader(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 1)).varint() }
        assertFailsWith<FormatException> { ByteReader(byteArrayOf(5, 1)).blob() }
        assertFailsWith<FormatException> { Codecs.readCommand(ByteReader(byteArrayOf(99))) }
        assertFailsWith<FormatException> { Codecs.readCommand(ByteReader(byteArrayOf(1))) }
    }
}
