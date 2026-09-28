package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Why a `.nib` file couldn't be read. */
sealed class NibFileException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Not a Nib document at all. */
    class NotNibFile(message: String) : NibFileException(message)

    /** Written by a newer Nib whose format this version can't read. */
    class UnsupportedVersion(val major: Int, val minor: Int) :
        NibFileException("format $major.$minor is newer than ${NibFile.FORMAT_MAJOR}.x")

    /** Damaged or cut short. */
    class Corrupt(message: String, cause: Throwable? = null) : NibFileException(message, cause)
}

/** What a `.nib` file holds. [journalToken] ties the file to the journal written after it (see [Journal]). */
class NibFileContents(val document: Document, val thumbnailPng: ByteArray?, val journalToken: Long = 0L) {
    override fun equals(other: Any?): Boolean =
        other is NibFileContents && document == other.document && journalToken == other.journalToken &&
            (thumbnailPng?.contentEquals(other.thumbnailPng) ?: (other.thumbnailPng == null))

    override fun hashCode(): Int = document.hashCode() * 31 + (thumbnailPng?.contentHashCode() ?: 0)
}

/**
 * The `.nib` document format: a zip holding `manifest.bin` (magic `NIB1`, format version, document fields and the
 * layer table), `thumb.png` when there is a thumbnail, and one `layers/<id>.strokes` per layer (a brush table, then
 * stroke records with delta-encoded varint points). Every record is length-prefixed so newer minor versions can add
 * fields that older readers skip; a newer major version is refused with [NibFileException.UnsupportedVersion].
 */
object NibFile {
    const val FORMAT_MAJOR = 1
    const val FORMAT_MINOR = 0

    private val MANIFEST_MAGIC = "NIB1".toByteArray(Charsets.US_ASCII)
    private val STROKES_MAGIC = "NIBS".toByteArray(Charsets.US_ASCII)
    private const val MANIFEST = "manifest.bin"
    private const val THUMBNAIL = "thumb.png"
    private const val MAX_ENTRY_BYTES = 1L shl 30

    /** Writes [doc] to [out] (which stays open). */
    fun write(doc: Document, out: OutputStream, thumbnailPng: ByteArray? = null, journalToken: Long = 0L) {
        val zip = ZipOutputStream(BufferedOutputStream(out, 1 shl 16))
        val layerFiles = doc.layers.map { "layers/${it.id}.strokes" }

        val m = ByteWriter(1024)
        m.raw(MANIFEST_MAGIC)
        m.varint(FORMAT_MAJOR)
        m.varint(FORMAT_MINOR)
        m.fieldString(1, doc.id)
        m.fieldVarint(2, doc.width.toLong())
        m.fieldVarint(3, doc.height.toLong())
        m.fieldInt32(4, doc.background)
        m.fieldVarint(5, doc.nextId)
        doc.layers.forEachIndexed { i, l ->
            m.field(6) {
                Codecs.writeLayerProps(this, l)
                fieldVarint(8, l.strokes.size.toLong())
                fieldString(9, layerFiles[i])
            }
        }
        m.fieldVarint(7, journalToken)
        m.fieldBool(8, thumbnailPng != null)
        putEntry(zip, MANIFEST, m.bytes(), m.size)

        if (thumbnailPng != null) putEntry(zip, THUMBNAIL, thumbnailPng, thumbnailPng.size)

        val w = ByteWriter(1 shl 16)
        doc.layers.forEachIndexed { i, l ->
            w.reset()
            writeStrokes(w, l)
            putEntry(zip, layerFiles[i], w.bytes(), w.size)
        }
        zip.finish()
        zip.flush()
    }

    /** Reads a document; throws a [NibFileException] for anything that isn't a complete, readable `.nib`. */
    fun read(input: InputStream): NibFileContents {
        val entries = HashMap<String, ByteArray>()
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                entries[e.name] = readEntry(zip)
            }
        } catch (e: NibFileException) {
            throw e
        } catch (e: IOException) {
            throw NibFileException.Corrupt("damaged container: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw NibFileException.Corrupt("damaged container: ${e.message}", e)
        }
        val manifest = entries[MANIFEST]
            ?: if (entries.isEmpty()) throw NibFileException.NotNibFile("not a Nib document") else throw NibFileException.Corrupt("no manifest")
        try {
            return parse(manifest, entries)
        } catch (e: NibFileException) {
            throw e
        } catch (e: IOException) {
            throw NibFileException.Corrupt("damaged document: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw NibFileException.Corrupt("damaged document: ${e.message}", e)
        }
    }

    /**
     * Writes [doc] to [file] crash-safely: to a temporary file beside it, synced to disk, then renamed over [file],
     * so the old version survives until the new one is complete.
     */
    fun writeAtomically(doc: Document, file: File, thumbnailPng: ByteArray? = null, journalToken: Long = 0L) {
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            write(doc, fos, thumbnailPng, journalToken)
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Reads a document from [file]. */
    fun read(file: File): NibFileContents = file.inputStream().buffered().use { read(it) }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, len: Int) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes, 0, len)
        zip.closeEntry()
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw NibFileException.Corrupt("entry too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeStrokes(w: ByteWriter, layer: Layer) {
        w.raw(STROKES_MAGIC)
        w.varint(1)
        val table = LinkedHashMap<BrushSpec, Int>()
        for (s in layer.strokes) {
            val index = table[s.brush] ?: table.size.also {
                table[s.brush] = it
                w.field(1) { Codecs.writeBrush(this, s.brush) }
            }
            w.field(2) { Codecs.writeStroke(this, s, index) }
        }
    }

    private fun readStrokes(bytes: ByteArray): List<Stroke> {
        val r = ByteReader(bytes)
        for (b in STROKES_MAGIC) if (r.byte() != b.toInt()) throw NibFileException.Corrupt("bad stroke file")
        r.count()
        val brushes = ArrayList<BrushSpec>()
        val strokes = ArrayList<Stroke>()
        r.fields { tag, f ->
            when (tag) {
                1 -> brushes.add(Codecs.readBrush(f))
                2 -> strokes.add(Codecs.readStroke(f, brushes))
            }
        }
        return strokes
    }

    private fun parse(manifest: ByteArray, entries: Map<String, ByteArray>): NibFileContents {
        val r = ByteReader(manifest)
        if (manifest.size < MANIFEST_MAGIC.size || MANIFEST_MAGIC.indices.any { manifest[it] != MANIFEST_MAGIC[it] }) {
            throw NibFileException.NotNibFile("bad magic")
        }
        r.raw(MANIFEST_MAGIC.size)
        val major = r.count()
        val minor = r.count()
        if (major > FORMAT_MAJOR) throw NibFileException.UnsupportedVersion(major, minor)
        if (major < 1) throw NibFileException.Corrupt("bad version $major")

        var id: String? = null
        var width = 0
        var height = 0
        var background = Document.WHITE
        var nextId = 1L
        var token = 0L
        var hasThumbnail = false
        val layers = ArrayList<Layer>()
        r.fields { tag, f ->
            when (tag) {
                1 -> id = f.string()
                2 -> width = f.count()
                3 -> height = f.count()
                4 -> background = f.int32()
                5 -> nextId = f.varint()
                6 -> {
                    var count = -1
                    var entry: String? = null
                    val props = Codecs.readLayerProps(f) { t, g ->
                        when (t) {
                            8 -> count = g.count()
                            9 -> entry = g.string()
                        }
                    }
                    val name = entry ?: throw NibFileException.Corrupt("layer ${props.id} has no stroke file")
                    val bytes = entries[name] ?: throw NibFileException.Corrupt("missing $name")
                    val strokes = readStrokes(bytes)
                    if (count >= 0 && count != strokes.size) throw NibFileException.Corrupt("$name holds ${strokes.size} strokes, expected $count")
                    layers.add(props.copy(strokes = strokes))
                }
                7 -> token = f.varint()
                8 -> hasThumbnail = f.bool()
            }
        }
        val thumbnail = entries[THUMBNAIL]
        if (hasThumbnail && thumbnail == null) throw NibFileException.Corrupt("missing thumbnail")
        val doc = Document(
            id ?: throw NibFileException.Corrupt("no document id"),
            width,
            height,
            background,
            layers,
            maxOf(nextId, 1L),
        )
        return NibFileContents(doc, if (hasThumbnail) thumbnail else null, token)
    }
}
