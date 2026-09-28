package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

class NibFileTest {
    private fun bytes(doc: Document, thumb: ByteArray? = null, token: Long = 0L): ByteArray =
        ByteArrayOutputStream().also { NibFile.write(doc, it, thumb, token) }.toByteArray()

    private fun read(b: ByteArray): NibFileContents = NibFile.read(ByteArrayInputStream(b))

    @Test
    fun roundTripKeepsEverything() {
        val doc = SampleDocs.rich(1)
        val thumb = ByteArray(3000) { (it * 7).toByte() }
        val back = read(bytes(doc, thumb, 42L))
        assertEquals(doc, back.document)
        assertTrue(thumb.contentEquals(back.thumbnailPng))
        assertEquals(42L, back.journalToken)
        assertEquals(NibFileContents(doc, thumb, 42L), back)
        val plain = read(bytes(Document.blank(100, 200, id = "x")))
        assertEquals(Document.blank(100, 200, id = "x"), plain.document)
        assertNull(plain.thumbnailPng)
    }

    @Test
    fun hundredLayersAndAFiveThousandPointStroke() {
        val long = SampleDocs.longStroke(5000, 5000)
        val layers = (1L..100L).map { Layer(it, "L$it", strokes = if (it == 50L) listOf(long) else emptyList()) }
        val doc = Document("big", 1860, 2480, layers = layers, nextId = 6000)
        val b = bytes(doc)
        val back = read(b).document
        assertEquals(doc, back)
        assertEquals(5000, back.layer(50)!!.strokes.single().points.size)
        println("NIB 100 layers + 5000-point stroke: ${b.size} bytes")
        assertTrue(b.size < 5000 * 12 + 100 * 300, "points are packed compactly (${b.size} bytes)")
    }

    @Test
    fun damagedFilesFailCleanly() {
        val doc = SampleDocs.rich(2, layers = 3, strokesPerLayer = 8)
        val good = bytes(doc, ByteArray(100) { it.toByte() })
        val rnd = Random(3)
        var refused = 0
        repeat(300) {
            val bad = good.copyOf()
            val at = rnd.nextInt(bad.size)
            bad[at] = (bad[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            try {
                val back = read(bad)
                assertEquals(doc, back.document, "a flip at $at that reads must not change the document")
            } catch (e: NibFileException) {
                refused++
            } catch (e: Exception) {
                fail("flip at $at threw ${e::class.simpleName}: ${e.message}")
            }
        }
        assertTrue(refused > 150, "most damage is caught ($refused of 300)")
    }

    @Test
    fun truncatedFilesFailCleanly() {
        val doc = SampleDocs.rich(4, layers = 3, strokesPerLayer = 6)
        val good = bytes(doc, ByteArray(50) { 1 })
        val step = maxOf(1, good.size / 400)
        var cut = 0
        while (cut < good.size) {
            try {
                val back = read(good.copyOf(cut))
                assertEquals(doc, back.document, "a cut at $cut that reads must be complete")
            } catch (_: NibFileException) {
            } catch (e: Exception) {
                fail("cut at $cut threw ${e::class.simpleName}: ${e.message}")
            }
            cut += if (cut > good.size - 200) 1 else step
        }
        assertFailsWith<NibFileException> { read(good.copyOf(good.size / 2)) }
    }

    @Test
    fun otherFilesAreNotNibFiles() {
        assertFailsWith<NibFileException.NotNibFile> { read(ByteArray(0)) }
        assertFailsWith<NibFileException.NotNibFile> { read(Random(1).nextBytes(5000)) }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("readme.txt"))
            z.write("hello".toByteArray())
            z.closeEntry()
        }
        assertIs<NibFileException.Corrupt>(runCatching { read(zip.toByteArray()) }.exceptionOrNull())
        val badMagic = zipOf("manifest.bin" to "NOPE1234".toByteArray())
        assertFailsWith<NibFileException.NotNibFile> { read(badMagic) }
    }

    @Test
    fun newerMajorVersionsAreRefused() {
        val m = ByteWriter()
        m.raw("NIB1".toByteArray())
        m.varint(2)
        m.varint(3)
        m.fieldString(1, "future")
        val e = assertFailsWith<NibFileException.UnsupportedVersion> { read(zipOf("manifest.bin" to m.toByteArray())) }
        assertEquals(2, e.major)
        assertEquals(3, e.minor)
    }

    @Test
    fun unknownFieldsAreSkipped() {
        val doc = SampleDocs.rich(5, layers = 2, strokesPerLayer = 3)
        val m = ByteWriter()
        m.raw("NIB1".toByteArray())
        m.varint(1)
        m.varint(7)
        m.fieldString(1, doc.id)
        m.fieldString(42, "a field from the future")
        m.fieldVarint(2, doc.width.toLong())
        m.fieldVarint(3, doc.height.toLong())
        m.fieldInt32(4, doc.background)
        m.fieldVarint(5, doc.nextId)
        val entries = mutableListOf<Pair<String, ByteArray>>()
        for (l in doc.layers) {
            m.field(6) {
                Codecs.writeLayerProps(this, l)
                fieldBytes(77, byteArrayOf(1, 2, 3))
                fieldString(9, "layers/${l.id}.strokes")
            }
            val w = ByteWriter()
            w.raw("NIBS".toByteArray())
            w.varint(3)
            w.fieldString(9, "future table")
            l.strokes.forEachIndexed { i, s ->
                w.field(1) {
                    Codecs.writeBrush(this, s.brush)
                    fieldFloat(90, 1.5f)
                }
                w.field(2) {
                    fieldString(60, "future stroke field")
                    Codecs.writeStroke(this, s, i)
                }
            }
            entries.add("layers/${l.id}.strokes" to w.toByteArray())
        }
        entries.add(0, "manifest.bin" to m.toByteArray())
        entries.add("future/extra.bin" to byteArrayOf(9, 9, 9))
        assertEquals(doc, read(zipOf(*entries.toTypedArray())).document)
    }

    @Test
    fun missingPartsAreCorrupt() {
        val doc = SampleDocs.rich(6, layers = 2, strokesPerLayer = 2)
        val m = ByteWriter()
        m.raw("NIB1".toByteArray())
        m.varint(1)
        m.varint(0)
        m.fieldString(1, doc.id)
        m.field(6) {
            Codecs.writeLayerProps(this, doc.layers[0])
            fieldString(9, "layers/1.strokes")
        }
        assertIs<NibFileException.Corrupt>(runCatching { read(zipOf("manifest.bin" to m.toByteArray())) }.exceptionOrNull())
        m.fieldBool(8, true)
        val strokes = ByteWriter().apply { raw("NIBS".toByteArray()); varint(1) }.toByteArray()
        assertIs<NibFileException.Corrupt>(
            runCatching { read(zipOf("manifest.bin" to m.toByteArray(), "layers/1.strokes" to strokes)) }.exceptionOrNull(),
            "a promised thumbnail must be there",
        )
    }

    @Test
    fun atomicWritesReplaceTheOldFile() {
        val dir = File("build/test-work/nibfile").apply { deleteRecursively(); mkdirs() }
        try {
            val file = File(dir, "note.nib")
            val first = SampleDocs.rich(7, layers = 2, strokesPerLayer = 2)
            NibFile.writeAtomically(first, file, journalToken = 1L)
            assertEquals(first, NibFile.read(file).document)
            val second = first.copy(layers = first.layers.take(1), nextId = first.nextId + 1)
            NibFile.writeAtomically(second, file, byteArrayOf(1, 2), journalToken = 2L)
            val back = NibFile.read(file)
            assertEquals(second, back.document)
            assertEquals(2L, back.journalToken)
            assertFalse(File(dir, "note.nib.tmp").exists(), "no temporary file is left behind")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownBrushKindsStillShowTheirInk() {
        val w = ByteWriter()
        w.fieldString(1, "laser_pen")
        w.fieldFloat(2, 7f)
        val brush = Codecs.readBrush(ByteReader(w.toByteArray()))
        assertEquals(BrushKind.Fineliner, brush.kind)
        assertEquals(7f, brush.width)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
