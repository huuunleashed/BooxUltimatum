package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.render.StrokeRenderer
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class NibSummaryTest {
    private fun temp(): File = File.createTempFile("nib-summary", ".nib").apply { deleteOnExit() }

    @Test
    fun theSummaryCountsLayersAndStrokesWithoutReadingThem() {
        val doc = SampleDocs.rich(11, layers = 4, strokesPerLayer = 7)
        val file = temp()
        val thumb = byteArrayOf(1, 2, 3, 4)
        NibFile.writeAtomically(doc, file, thumb, journalToken = 9L)
        val s = NibFile.readSummary(file)
        assertEquals(NibSummary(doc.width, doc.height, 4, 28, true), s)
        assertContentEquals(thumb, NibFile.readThumbnail(file))
    }

    @Test
    fun aDrawingWithoutAThumbnailSaysSo() {
        val file = temp()
        NibFile.writeAtomically(Document.blank(1860, 2480, id = "a"), file)
        assertEquals(NibSummary(1860, 2480, 1, 0, false), NibFile.readSummary(file))
        assertNull(NibFile.readThumbnail(file))
    }

    @Test
    fun otherFilesFailCleanly() {
        val file = temp()
        file.writeText("not a zip at all")
        assertTrue(runCatching { NibFile.readSummary(file) }.exceptionOrNull() is NibFileException)
        assertFailsWith<NibFileException> { NibFile.readThumbnail(file) }
    }

    @Test
    fun dabBrushesAreExactlyTheOnesTheRendererStamps() {
        for (k in BrushKind.entries) {
            assertEquals(StrokeRenderer.mode(k) == StrokeRenderer.Mode.Dabs, k.rendersAsDabs, k.id)
        }
        assertEquals(setOf(BrushKind.Calligraphy, BrushKind.SquarePen, BrushKind.Highlighter), BrushKind.entries.filter { it.usesNib }.toSet())
    }
}