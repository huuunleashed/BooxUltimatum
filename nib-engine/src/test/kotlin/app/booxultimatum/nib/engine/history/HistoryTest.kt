package app.booxultimatum.nib.engine.history

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.history.EditResult.Applied
import app.booxultimatum.nib.engine.history.EditResult.Rejected
import app.booxultimatum.nib.engine.line
import app.booxultimatum.nib.engine.render.DocumentRenderer
import app.booxultimatum.nib.engine.render.SoftwareRaster
import app.booxultimatum.nib.engine.scribble
import app.booxultimatum.nib.engine.stroke
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class HistoryTest {
    private val rnd = Random(21)

    private fun s(id: Long, kind: BrushKind = BrushKind.Fountain) = scribble(rnd, id, kind, 30, 0f, 0f, 400f, 400f)

    /** Three layers (ids 1, 2, 3) with four strokes each (ids 10 up). */
    private fun doc(): Document {
        var id = 10L
        val layers = (1L..3L).map { l -> Layer(l, "layer $l", strokes = (0 until 4).map { s(id++) }) }
        return Document("doc", 400, 400, layers = layers, nextId = id)
    }

    private fun assertSameContent(expected: Document, actual: Document) {
        assertEquals(expected.copy(nextId = 0), actual.copy(nextId = 0))
    }

    private fun checkIndex(h: History) {
        for (l in h.document.layers) {
            val idx = h.index.layer(l.id)!!
            assertEquals(l.strokes.size, idx.size, "index size for layer ${l.id}")
            for (st in l.strokes) assertSame(st, idx[st.id], "index holds the current stroke ${st.id}")
        }
    }

    private fun allCommands(base: Document): List<Command> {
        val l1 = base.layer(1)!!
        val l2 = base.layer(2)!!
        return listOf(
            AddStroke(1, s(100)),
            RemoveStrokes(1, listOf(l1.strokes[1].id, l1.strokes[3].id)),
            InsertStrokes(2, listOf(PlacedStroke(0, s(101)), PlacedStroke(3, s(102)), PlacedStroke(6, s(103)))),
            ReplaceStrokes(1, listOf(l1.strokes[2].copy(color = 0xFFFF0000.toInt()))),
            TransformStrokes(1, listOf(l1.strokes[0].id, l1.strokes[2].id), Affine.rotate(0.3f, 50f, 50f).then(Affine.scale(1.5f))),
            MoveStrokes(1, 2, listOf(l1.strokes[3].id, l1.strokes[0].id)),
            MoveStrokes(2, 2, listOf(l2.strokes[0].id)),
            AddLayer(Layer(200, "new", strokes = listOf(s(104))), 1),
            RemoveLayer(2),
            MoveLayer(1, 2),
            SetLayerProps(2, name = "renamed", visible = false, locked = true, opacity = 0.3f, blend = Blend.Multiply, alphaLock = true),
            MergeDown(2),
            Batch(listOf(SetLayerProps(3, opacity = 0.5f, blend = Blend.Multiply), MergeDown(3))),
            Clear(1),
            Batch(listOf(AddStroke(3, s(105)), MoveLayer(3, 0), RemoveStrokes(2, listOf(l2.strokes[2].id)))),
        )
    }

    @Test
    fun everyCommandUndoesAndRedoesExactly() {
        val base = doc()
        for (c in allCommands(base)) {
            val h = History(base)
            val r = h.execute(c)
            assertIs<Applied>(r, "$c applies")
            val after = h.document
            assertNotEquals(base, after, "$c changes the document")
            checkIndex(h)
            assertIs<Applied>(h.undo())
            assertSameContent(base, h.document)
            checkIndex(h)
            assertIs<Applied>(h.redo())
            assertEquals(after, h.document, "$c redoes to the same state")
            checkIndex(h)
            assertIs<Applied>(h.undo())
            assertSameContent(base, h.document)
            // The inverse of the inverse gets back to the edited state too.
            val undone = r.inverse.apply(after) as Applied
            assertSameContent(base, undone.document)
            assertSameContent(after, (undone.inverse.apply(undone.document) as Applied).document)
        }
    }

    @Test
    fun lockedLayersRefuseContentEdits() {
        val h = History(doc())
        assertIs<Applied>(h.execute(SetLayerProps(1, locked = true)))
        val locked = h.document
        val l1 = locked.layer(1)!!
        val refused = listOf(
            AddStroke(1, s(300)),
            RemoveStrokes(1, listOf(l1.strokes[0].id)),
            InsertStrokes(1, listOf(PlacedStroke(0, s(301)))),
            ReplaceStrokes(1, listOf(l1.strokes[0].copy(color = 0))),
            TransformStrokes(1, listOf(l1.strokes[0].id), Affine.translate(1f, 1f)),
            MoveStrokes(1, 2, listOf(l1.strokes[0].id)),
            MoveStrokes(2, 1, listOf(locked.layer(2)!!.strokes[0].id)),
            Clear(1),
            MergeDown(2),
            RemoveLayer(1),
        )
        val undo = h.undoCount
        for (c in refused) {
            val r = h.execute(c)
            assertEquals(Rejected(EditError.LayerLocked(1)), r, "$c is refused")
            assertSame(locked, h.document)
        }
        assertEquals(undo, h.undoCount, "refusals aren't recorded")
        assertIs<Applied>(h.execute(MoveLayer(1, 2)), "a locked layer can still move")
        assertIs<Applied>(h.execute(SetLayerProps(1, visible = false)), "and change its properties")
        assertIs<Applied>(h.execute(SetLayerProps(1, locked = false)))
        assertIs<Applied>(h.execute(Clear(1)))
    }

    @Test
    fun undoWaitsWhileTheLayerIsLocked() {
        val h = History(doc())
        h.execute(AddStroke(1, s(400)))
        h.execute(SetLayerProps(1, locked = true), undoable = false)
        assertEquals(Rejected(EditError.LayerLocked(1)), h.undo())
        assertTrue(h.canUndo, "the step is kept")
        h.execute(SetLayerProps(1, locked = false), undoable = false)
        assertIs<Applied>(h.undo())
        assertFalse(h.document.layer(1)!!.contains(400))
    }

    @Test
    fun stacksBehave() {
        val h = History(doc(), maxEntries = 3)
        assertFalse(h.canUndo)
        assertNull(h.undo())
        assertNull(h.redo())
        repeat(5) { h.execute(AddStroke(1, s(500L + it))) }
        assertEquals(3, h.undoCount, "only the newest steps are kept")
        h.undo()
        h.undo()
        assertEquals(2, h.redoCount)
        h.execute(AddStroke(2, s(600)))
        assertFalse(h.canRedo, "a new edit clears redo")
        val before = h.undoCount
        assertIs<Applied>(h.execute(RemoveStrokes(1, emptyList())))
        assertEquals(before, h.undoCount, "no-op edits aren't recorded")
        h.clearSteps()
        assertFalse(h.canUndo)
        val fresh = doc()
        h.reset(fresh)
        assertSame(fresh, h.document)
        checkIndex(h)
    }

    @Test
    fun refusalsAndAtomicBatches() {
        val base = doc()
        val h = History(base)
        assertEquals(Rejected(EditError.LayerNotFound(99)), h.execute(AddStroke(99, s(700))))
        assertEquals(Rejected(EditError.DuplicateId(10)), h.execute(AddStroke(1, s(10))))
        assertEquals(Rejected(EditError.StrokeNotFound(1, listOf(999L))), h.execute(RemoveStrokes(1, listOf(10, 999))))
        assertEquals(Rejected(EditError.InvalidTransform), h.execute(TransformStrokes(1, listOf(10), Affine.scale(0f))))
        assertEquals(Rejected(EditError.InvalidIndex(9)), h.execute(MoveLayer(1, 9)))
        assertEquals(Rejected(EditError.NothingBelow), h.execute(MergeDown(1)))
        assertEquals(Rejected(EditError.InvalidIndex(7)), h.execute(InsertStrokes(1, listOf(PlacedStroke(7, s(701))))))
        val batch = Batch(listOf(AddStroke(1, s(702)), RemoveStrokes(1, listOf(12345))))
        assertIs<Rejected>(h.execute(batch))
        assertSame(base, h.document, "nothing of a refused batch is applied")
        val single = History(Document.blank(100, 100))
        assertEquals(Rejected(EditError.LastLayer), single.execute(RemoveLayer(1)))
        assertEquals(1, single.document.layers.size)
    }

    @Test
    fun alphaLockedLayersPaintOnlyOverInk() {
        val h = History(Document.blank(200, 200, id = "a"))
        val square = stroke(h.allocateId(), BrushKind.Fineliner, line(50f, 100f, 150f, 100f, 20), width = 40f)
        h.execute(AddStroke(1, square))
        h.execute(SetLayerProps(1, alphaLock = true))
        val red = stroke(h.allocateId(), BrushKind.Fineliner, line(100f, 20f, 100f, 180f, 20), width = 10f, color = 0xFFFF0000.toInt())
        h.execute(AddStroke(1, red))
        val stored = h.document.layer(1)!!.strokes.last()
        assertEquals(Blend.Atop, stored.brush.blend)
        val r = SoftwareRaster(200, 200)
        DocumentRenderer.render(h.document, r, paintBackground = false)
        assertEquals(0xFFFF0000.toInt(), r.pixel(100, 100), "red over the ink")
        assertEquals(0f, r.alpha(100, 40), "nothing off the ink")
        assertEquals(BLACK, r.pixel(60, 100))
    }

    @Test
    fun listenersSeeTheEffectiveCommands() {
        val h = History(doc())
        val events = ArrayList<HistoryEvent>()
        h.addListener { events.add(it) }
        val st = s(800)
        h.execute(AddStroke(1, st))
        h.undo()
        h.redo()
        assertEquals(listOf(HistoryAction.Do, HistoryAction.Undo, HistoryAction.Redo), events.map { it.action })
        assertEquals(AddStroke(1, st), events[0].effective)
        assertEquals(RemoveStrokes(1, listOf(800L)), events[1].effective)
        assertEquals(AddStroke(1, st), events[2].effective)
        assertEquals(setOf(1L), events[0].change.layers)
        assertEquals(st.bounds, events[0].change.bounds)
        assertFalse(events[0].change.structural)
        assertSame(h.document, events[2].document)

        val moved = h.execute(MoveStrokes(1, 2, listOf(10L))) as Applied
        assertEquals(setOf(1L, 2L), moved.change.layers)
        val renamed = h.execute(SetLayerProps(2, name = "notes")) as Applied
        assertEquals(Box.EMPTY, renamed.change.bounds)
        assertFalse(renamed.change.structural)
        val hidden = h.execute(SetLayerProps(2, visible = false)) as Applied
        assertTrue(hidden.change.structural)
        assertEquals(h.document.layer(2)!!.contentBounds, hidden.change.bounds)
    }

    @Test
    fun idsAreNeverReused() {
        val h = History(doc())
        val a = h.allocateId()
        val b = h.allocateId()
        assertTrue(b > a && a >= 22)
        h.execute(AddStroke(1, s(b)))
        assertTrue(h.document.nextId > b)
        h.undo()
        assertTrue(h.allocateId() > b, "ids stay unique after undo")
        assertTrue(MergeDown.isExact(Layer(1, strokes = listOf(s(1)))))
        assertFalse(MergeDown.isExact(Layer(1, opacity = 0.5f)))
        assertFalse(MergeDown.isExact(Layer(1, strokes = listOf(s(2, BrushKind.PixelEraser)))))
    }

    @Test
    fun randomEditsKeepTheIndexInStep() {
        val h = History(doc())
        val r = Random(99)
        repeat(400) { step ->
            val d = h.document
            val layer = d.layers[r.nextInt(d.layers.size)]
            val ids = layer.strokes.map { it.id }
            when (r.nextInt(10)) {
                0, 1, 2 -> h.execute(AddStroke(layer.id, scribble(r, h.allocateId(), BrushKind.entries[r.nextInt(16)], 5 + r.nextInt(40), 0f, 0f, 400f, 400f)))
                3 -> if (ids.isNotEmpty()) h.execute(RemoveStrokes(layer.id, ids.shuffled(r).take(1 + r.nextInt(ids.size))))
                4 -> if (ids.isNotEmpty()) h.execute(TransformStrokes(layer.id, ids.take(2), Affine.translate(r.nextFloat() * 20f - 10f, 5f)))
                5 -> {
                    val other = d.layers[r.nextInt(d.layers.size)]
                    if (ids.isNotEmpty()) h.execute(MoveStrokes(layer.id, other.id, ids.take(1)))
                }
                6 -> h.undo()
                7 -> h.redo()
                8 -> if (step % 5 == 0) h.execute(if (r.nextBoolean()) Clear(layer.id) else AddLayer(Layer(h.allocateId()), r.nextInt(d.layers.size + 1)))
                9 -> if (d.layers.size > 1 && r.nextInt(4) == 0) h.execute(MergeDown(d.layers[1 + r.nextInt(d.layers.size - 1)].id))
            }
            checkIndex(h)
            val box = Box(r.nextFloat() * 300f, r.nextFloat() * 300f, 0f, 0f).let { Box(it.left, it.top, it.left + 80f, it.top + 80f) }
            for (l in h.document.layers) {
                val expected = l.strokes.filter { it.bounds.intersects(box) }.map { it.id }.toSet()
                assertEquals(expected, h.index.query(l.id, box).map { it.id }.toSet())
            }
        }
    }
}
