package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.history.AddLayer
import app.booxultimatum.nib.engine.history.AddStroke
import app.booxultimatum.nib.engine.history.Batch
import app.booxultimatum.nib.engine.history.Clear
import app.booxultimatum.nib.engine.history.History
import app.booxultimatum.nib.engine.history.MergeDown
import app.booxultimatum.nib.engine.history.MoveLayer
import app.booxultimatum.nib.engine.history.MoveStrokes
import app.booxultimatum.nib.engine.history.RemoveStrokes
import app.booxultimatum.nib.engine.history.SetLayerProps
import app.booxultimatum.nib.engine.history.TransformStrokes
import app.booxultimatum.nib.engine.scribble
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class JournalTest {
    private class Session(val base: Document, token: Long = 7L) {
        val out = ByteArrayOutputStream()
        val journal = Journal.over(out, token)
        val history = History(base)

        /** Byte offset after each record, and the document after it. */
        val ends = ArrayList<Int>()
        val states = ArrayList<Document>()

        init {
            history.addListener {
                journal.append(it.effective)
                journal.sync()
                ends.add(out.size())
                states.add(it.document)
            }
        }

        val bytes: ByteArray get() = out.toByteArray()
    }

    private fun edited(): Session {
        val rnd = Random(12)
        val base = SampleDocs.rich(8, layers = 3, strokesPerLayer = 5).let { d ->
            d.copy(layers = d.layers.map { it.copy(locked = false) })
        }
        val s = Session(base)
        val h = s.history
        fun stroke(kind: BrushKind = BrushKind.Fountain) = scribble(rnd, h.allocateId(), kind, 40, 0f, 0f, 1800f, 2400f)
        val l = base.layers.map { it.id }
        h.execute(AddStroke(l[0], stroke()))
        h.execute(AddStroke(l[1], stroke(BrushKind.GrainPencil)))
        h.execute(RemoveStrokes(l[2], listOf(h.document.layers[2].strokes[1].id)))
        h.execute(TransformStrokes(l[0], h.document.layers[0].strokes.take(3).map { it.id }, Affine.rotate(0.2f, 100f, 100f)))
        h.undo()
        h.undo()
        h.redo()
        h.execute(MoveStrokes(l[0], l[1], listOf(h.document.layers[0].strokes[0].id)))
        h.execute(SetLayerProps(l[1], name = "moved", opacity = 0.4f, blend = Blend.Multiply))
        h.execute(AddLayer(Layer(h.allocateId(), "fresh", strokes = listOf(stroke(BrushKind.Marker))), 1))
        h.execute(MergeDown(h.document.layers[2].id))
        h.execute(Batch(listOf(AddStroke(l[0], stroke()), MoveLayer(l[0], 2))))
        h.execute(Clear(l[2]))
        h.undo()
        h.execute(AddStroke(l[2], stroke(BrushKind.PixelEraser)))
        return s
    }

    @Test
    fun replayReproducesTheFinalState() {
        val s = edited()
        val r = Journal.replay(s.base, ByteArrayInputStream(s.bytes), expectedToken = 7L)
        assertEquals(ReplayStop.End, r.stop)
        assertEquals(s.states.size, r.applied)
        assertEquals(s.history.document, r.document)
        assertEquals(s.bytes.size.toLong(), r.validLength)
        assertEquals(7L, r.token)
        assertNotEquals(s.base, r.document)
    }

    @Test
    fun cuttingTheLastRecordAnywhereKeepsEveryEarlierOne() {
        val s = edited()
        val bytes = s.bytes
        val n = s.ends.size
        val lastStart = s.ends[n - 2]
        for (cut in lastStart until bytes.size) {
            val r = Journal.replay(s.base, ByteArrayInputStream(bytes.copyOf(cut)), expectedToken = 7L)
            assertEquals(n - 1, r.applied, "cut at $cut")
            assertEquals(s.states[n - 2], r.document)
            assertEquals(lastStart.toLong(), r.validLength)
            assertEquals(if (cut == lastStart) ReplayStop.End else ReplayStop.Truncated, r.stop, "cut at $cut")
        }
    }

    @Test
    fun cuttingAnywhereNeverThrows() {
        val s = edited()
        val bytes = s.bytes
        for (cut in 0..bytes.size) {
            val r = Journal.replay(s.base, ByteArrayInputStream(bytes.copyOf(cut)))
            val complete = s.ends.count { it <= cut }
            if (cut < Journal.HEADER_SIZE) {
                assertEquals(ReplayStop.BadHeader, r.stop)
                assertEquals(0, r.applied)
            } else {
                assertEquals(complete, r.applied, "cut at $cut")
                assertEquals(if (complete == 0) s.base else s.states[complete - 1], r.document)
            }
        }
    }

    @Test
    fun damageStopsReplayAtTheDamagedRecord() {
        val s = edited()
        val bytes = s.bytes
        val rnd = Random(5)
        repeat(200) {
            val bad = bytes.copyOf()
            val at = Journal.HEADER_SIZE + rnd.nextInt(bad.size - Journal.HEADER_SIZE)
            bad[at] = (bad[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            val record = s.ends.indexOfFirst { it > at }
            val r = Journal.replay(s.base, ByteArrayInputStream(bad))
            assertEquals(record, r.applied, "a flip at $at damages record $record")
            assertTrue(r.stop != ReplayStop.End)
            assertEquals(if (record == 0) s.base else s.states[record - 1], r.document)
        }
    }

    @Test
    fun staleOrForeignJournalsAreSkipped() {
        val s = edited()
        val stale = Journal.replay(s.base, ByteArrayInputStream(s.bytes), expectedToken = 8L)
        assertEquals(ReplayStop.TokenMismatch, stale.stop)
        assertEquals(0, stale.applied)
        assertEquals(s.base, stale.document)
        val foreign = Journal.replay(Document("other", 10, 10, layers = listOf(Layer(999)), nextId = 1000), ByteArrayInputStream(s.bytes))
        assertEquals(ReplayStop.Rejected, foreign.stop)
        assertEquals(0, foreign.applied)
        for (bad in listOf(ByteArray(0), s.bytes.copyOf(10), "NIBX".toByteArray() + s.bytes.copyOfRange(4, s.bytes.size))) {
            assertEquals(ReplayStop.BadHeader, Journal.replay(s.base, ByteArrayInputStream(bad)).stop)
        }
        val headerFlip = s.bytes.copyOf().also { it[7] = (it[7] + 1).toByte() }
        assertEquals(ReplayStop.BadHeader, Journal.replay(s.base, ByteArrayInputStream(headerFlip)).stop)
    }

    @Test
    fun fileJournalsSurviveACrashAndCompaction() {
        val dir = File("build/test-work/journal").apply { deleteRecursively(); mkdirs() }
        try {
            val base = Document.blank(1000, 1000, id = "j")
            val nib = File(dir, "note.nib")
            val log = File(dir, "note.journal")
            NibFile.writeAtomically(base, nib, journalToken = 1L)
            val h = History(NibFile.read(nib).document)
            var journal = Journal.create(log, 1L)
            h.addListener {
                journal.append(it.effective)
                journal.sync()
            }
            val rnd = Random(2)
            repeat(5) { h.execute(AddStroke(1, scribble(rnd, h.allocateId(), BrushKind.Fineliner, 20, 0f, 0f, 900f, 900f))) }
            journal.close()
            // The app dies halfway through writing a record.
            log.appendBytes(byteArrayOf(0x40, 3, 1, 2))
            val snapshot = NibFile.read(nib)
            val r1 = Journal.replay(snapshot.document, log, snapshot.journalToken)
            assertEquals(5, r1.applied)
            assertEquals(ReplayStop.Truncated, r1.stop)
            assertEquals(h.document, r1.document)
            // Reopen after the good part and keep writing.
            journal = Journal.openForAppend(log, r1.validLength)
            h.execute(AddStroke(1, scribble(rnd, h.allocateId(), BrushKind.Fineliner, 20, 0f, 0f, 900f, 900f)))
            journal.close()
            val r2 = Journal.replay(snapshot.document, log, snapshot.journalToken)
            assertEquals(ReplayStop.End, r2.stop)
            assertEquals(6, r2.applied)
            assertEquals(h.document, r2.document)
            // Compaction: a new snapshot with a new token, then a fresh journal. Dying in between leaves a stale journal.
            NibFile.writeAtomically(h.document, nib, journalToken = 2L)
            val compacted = NibFile.read(nib)
            val stale = Journal.replay(compacted.document, log, compacted.journalToken)
            assertEquals(ReplayStop.TokenMismatch, stale.stop)
            assertEquals(h.document, stale.document)
            Journal.create(log, 2L).close()
            val fresh = Journal.replay(compacted.document, log, compacted.journalToken)
            assertEquals(ReplayStop.End, fresh.stop)
            assertEquals(0, fresh.applied)
            assertEquals(ReplayStop.BadHeader, Journal.replay(base, File(dir, "missing.journal")).stop)
        } finally {
            dir.deleteRecursively()
        }
    }
}
