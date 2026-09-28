package app.booxultimatum.nib.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.log.Redact
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.io.Journal
import app.booxultimatum.nib.engine.io.NibFile
import app.booxultimatum.nib.engine.io.ReplayStop
import app.booxultimatum.nib.render.DocumentPainter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.random.Random

/** One drawing in the library. */
data class DrawingInfo(val id: String, val name: String, val modified: Long, val width: Int, val height: Int)

/** A drawing read back for editing: the document with its journal replayed, and the journal open for appending. */
class OpenedDrawing(
    val info: DrawingInfo,
    val document: Document,
    val journal: Journal,
    val token: Long,
    val replayed: Int,
    val journalBytes: Long,
)

/**
 * The drawings on this tablet: `filesDir/drawings/<id>.nib` (the snapshot, with a thumbnail), `<id>.journal` (every
 * edit since, see [Journal]) and `<id>.meta` (the name). Every call does file work, so call it off the main thread.
 */
class DrawingStore(context: Context) {
    private val log = Logbook.logger("nib.doc")
    val dir: File = File(context.filesDir, "drawings").apply { mkdirs() }
    private val thumbs = LruCache<String, Bitmap>(24)
    private val _changes = MutableStateFlow(0)

    /** Counts snapshots written, renames and deletions, so the library can read itself again. */
    val changes: StateFlow<Int> = _changes

    fun nibFile(id: String) = File(dir, "$id.nib")
    fun journalFile(id: String) = File(dir, "$id.journal")
    fun metaFile(id: String) = File(dir, "$id.meta")

    /** Every drawing, most recently changed first. */
    fun list(): List<DrawingInfo> = dir.listFiles { f -> f.name.endsWith(".nib") }.orEmpty()
        .mapNotNull { info(it.name.removeSuffix(".nib")) }
        .sortedByDescending { it.modified }

    fun info(id: String): DrawingInfo? {
        val nib = nibFile(id)
        if (!nib.isFile) return null
        val meta = readMeta(id)
        val modified = maxOf(nib.lastModified(), journalFile(id).takeIf { it.isFile }?.lastModified() ?: 0L)
        return DrawingInfo(
            id,
            meta.getProperty(KEY_NAME).orEmpty(),
            modified,
            meta.getProperty(KEY_WIDTH)?.toIntOrNull() ?: 0,
            meta.getProperty(KEY_HEIGHT)?.toIntOrNull() ?: 0,
        )
    }

    /** A new, empty drawing with one layer named [layerName]: its snapshot and an empty journal are written at once. */
    fun create(name: String, width: Int, height: Int, layerName: String): DrawingInfo {
        val id = UUID.randomUUID().toString()
        val doc = Document.blank(width, height, id = id, layerName = layerName)
        writeSnapshot(id, doc, freshToken()).close()
        writeMeta(id, name, width, height)
        log.i("drawing created", "id" to Redact.hash(id), "width" to width, "height" to height)
        return info(id)!!
    }

    fun rename(id: String, name: String) {
        val m = readMeta(id)
        writeMeta(id, name, m.getProperty(KEY_WIDTH)?.toIntOrNull() ?: 0, m.getProperty(KEY_HEIGHT)?.toIntOrNull() ?: 0)
        _changes.value++
        log.i("drawing renamed", "id" to Redact.hash(id))
    }

    /** A copy of the drawing as it stands, journal included, under a new id. */
    fun duplicate(id: String, name: String): DrawingInfo? {
        val source = open(id) ?: return null
        source.journal.close()
        val newId = UUID.randomUUID().toString()
        val doc = source.document.copy(id = newId)
        writeSnapshot(newId, doc, freshToken()).close()
        writeMeta(newId, name, doc.width, doc.height)
        log.i("drawing duplicated", "id" to Redact.hash(id), "copy" to Redact.hash(newId), "strokes" to doc.strokeCount)
        return info(newId)
    }

    fun delete(id: String) {
        listOf(nibFile(id), journalFile(id), metaFile(id), File(dir, "$id.nib.tmp")).forEach { it.delete() }
        thumbs.remove(id)
        _changes.value++
        log.i("drawing deleted", "id" to Redact.hash(id))
    }

    /** Reads the snapshot, replays the journal onto it and opens the journal for appending; null when unreadable. */
    fun open(id: String): OpenedDrawing? {
        val start = SystemClock.elapsedRealtime()
        val nib = nibFile(id)
        val contents = try {
            NibFile.read(nib)
        } catch (e: Exception) {
            log.e("drawing unreadable", "id" to Redact.hash(id), "bytes" to nib.length(), error = e)
            return null
        }
        val jf = journalFile(id)
        val replay = Journal.replay(contents.document, jf, contents.journalToken)
        val journal = when (replay.stop) {
            ReplayStop.End, ReplayStop.Truncated, ReplayStop.Corrupt, ReplayStop.Rejected ->
                if (replay.validLength >= Journal.HEADER_SIZE) Journal.openForAppend(jf, replay.validLength) else Journal.create(jf, contents.journalToken)
            ReplayStop.BadHeader, ReplayStop.TokenMismatch -> Journal.create(jf, contents.journalToken)
        }
        log.i(
            "drawing opened",
            "id" to Redact.hash(id), "bytes" to nib.length(), "journal bytes" to jf.length(),
            "replayed" to replay.applied, "stop" to replay.stop.name, "truncated" to (replay.stop == ReplayStop.Truncated),
            "layers" to replay.document.layers.size, "strokes" to replay.document.strokeCount,
            "ms" to (SystemClock.elapsedRealtime() - start),
        )
        val info = info(id) ?: DrawingInfo(id, "", nib.lastModified(), contents.document.width, contents.document.height)
        return OpenedDrawing(info, replay.document, journal, contents.journalToken, replay.applied, jf.length())
    }

    /**
     * Writes a fresh snapshot of [doc] with a thumbnail and the new [token], then starts an empty journal for it,
     * which it returns. A crash in between leaves the old journal, which replay then skips by its token.
     */
    fun writeSnapshot(id: String, doc: Document, token: Long): Journal {
        val start = SystemClock.elapsedRealtime()
        val thumb = runCatching { DocumentPainter.thumbnailPng(doc) }.getOrNull()
        NibFile.writeAtomically(doc, nibFile(id), thumb, token)
        val journal = Journal.create(journalFile(id), token)
        thumbs.remove(id)
        _changes.value++
        log.i(
            "snapshot written",
            "id" to Redact.hash(id), "bytes" to nibFile(id).length(), "thumb bytes" to (thumb?.size ?: 0),
            "layers" to doc.layers.size, "strokes" to doc.strokeCount, "ms" to (SystemClock.elapsedRealtime() - start),
        )
        return journal
    }

    /** The thumbnail kept in the snapshot, read straight from its zip entry. */
    fun thumbnail(id: String): Bitmap? {
        thumbs.get(id)?.let { return it }
        val bmp = runCatching {
            ZipFile(nibFile(id)).use { zip ->
                val entry = zip.getEntry("thumb.png") ?: return null
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull() ?: return null
        thumbs.put(id, bmp)
        return bmp
    }

    fun freshToken(): Long = Random.nextLong(1L, Long.MAX_VALUE)

    private fun readMeta(id: String): Properties = Properties().apply {
        runCatching { metaFile(id).inputStream().use { load(it) } }
    }

    private fun writeMeta(id: String, name: String, width: Int, height: Int) {
        val p = Properties()
        p.setProperty(KEY_NAME, name)
        p.setProperty(KEY_WIDTH, width.toString())
        p.setProperty(KEY_HEIGHT, height.toString())
        val tmp = File(dir, "$id.meta.tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(metaFile(id))) {
            metaFile(id).delete()
            tmp.renameTo(metaFile(id))
        }
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"

        @Volatile private var instance: DrawingStore? = null

        fun get(context: Context): DrawingStore =
            instance ?: synchronized(this) { instance ?: DrawingStore(context.applicationContext).also { instance = it } }
    }
}
