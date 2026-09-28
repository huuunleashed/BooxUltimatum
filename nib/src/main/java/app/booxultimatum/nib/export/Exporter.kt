package app.booxultimatum.nib.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.render.DocumentPainter
import app.booxultimatum.nib.store.Paper
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What an export holds. */
enum class ExportKind(val key: String, val mime: String, val extension: String) {
    /** The page flattened over its paper. */
    WithPaper("paper", "image/png", "png"),

    /** The ink alone, on transparency. */
    Transparent("transparent", "image/png", "png"),

    /** Each layer as its own transparent PNG, in one zip. */
    Layers("layers", "application/zip", "zip"),
}

/**
 * Pictures of a drawing: saved into the gallery (`Pictures/Nib/`; layer zips into `Download/Nib/`) or handed to
 * another app. Everything here renders the full page, so call it off the main thread.
 */
object Exporter {
    private val log = Logbook.logger("nib.doc")
    const val RELATIVE_DIR = "Pictures/Nib/"
    const val ZIP_DIR = "Download/Nib/"

    /** The page flattened over its paper, as PNG bytes into [out]. */
    fun writePng(doc: Document, out: OutputStream, paper: Paper? = null, kind: ExportKind = ExportKind.WithPaper) {
        val start = SystemClock.elapsedRealtime()
        val withPaper = kind != ExportKind.Transparent
        val bmp = DocumentPainter.render(doc, 1f, paper, withPaper = withPaper, withGuides = withPaper && paper?.guidesInExport == true)
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
        log.i("png rendered", "kind" to kind.key, "width" to doc.width, "height" to doc.height, "strokes" to doc.strokeCount, "ms" to (SystemClock.elapsedRealtime() - start))
    }

    /** Every layer, bottom first, as its own transparent PNG in one zip. Hidden layers are included. */
    fun writeLayersZip(doc: Document, out: OutputStream, layerName: (index: Int, name: String) -> String) {
        val start = SystemClock.elapsedRealtime()
        val zip = ZipOutputStream(out.buffered())
        val used = HashSet<String>()
        doc.layers.forEachIndexed { i, layer ->
            var name = fileBase(layerName(i, layer.name))
            var n = 2
            while (!used.add(name)) name = fileBase(layerName(i, layer.name)) + " " + n++
            zip.putNextEntry(ZipEntry("$name.png"))
            val bmp = DocumentPainter.renderLayer(doc, layer)
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
            } finally {
                bmp.recycle()
            }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        log.i("layers zipped", "layers" to doc.layers.size, "ms" to (SystemClock.elapsedRealtime() - start))
    }

    private fun write(doc: Document, out: OutputStream, paper: Paper?, kind: ExportKind, layerName: (Int, String) -> String) {
        if (kind == ExportKind.Layers) writeLayersZip(doc, out, layerName) else writePng(doc, out, paper, kind)
    }

    /** Saves the export into the gallery or Downloads; returns its address, or null when Android refused. */
    fun saveToGallery(
        context: Context,
        doc: Document,
        name: String,
        paper: Paper? = null,
        kind: ExportKind = ExportKind.WithPaper,
        layerName: (Int, String) -> String = { i, n -> "${i + 1} $n" },
    ): Uri? {
        val resolver = context.contentResolver
        val zip = kind == ExportKind.Layers
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(name, kind))
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (zip) ZIP_DIR else RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (zip) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { write(doc, it, paper, kind, layerName) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            log.i("export saved", "kind" to kind.key)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            log.e("export failed", "kind" to kind.key, error = e)
            null
        }
    }

    /** Writes the export to the share folder and returns an intent that offers it to other apps. */
    fun shareIntent(
        context: Context,
        doc: Document,
        name: String,
        paper: Paper? = null,
        kind: ExportKind = ExportKind.WithPaper,
        layerName: (Int, String) -> String = { i, n -> "${i + 1} $n" },
    ): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(name, kind))
        file.outputStream().buffered().use { write(doc, it, paper, kind, layerName) }
        return shareFile(context, file, kind.mime)
    }

    /** Offers any file in the provider's folders to other apps. */
    fun shareFile(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** A file name from a drawing's name: letters, digits, spaces and dashes only. */
    fun fileName(name: String, kind: ExportKind = ExportKind.WithPaper): String {
        val suffix = if (kind == ExportKind.Transparent) " ink" else ""
        return fileBase(name) + suffix + "." + kind.extension
    }

    private fun fileBase(name: String): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }.joinToString("").trim().take(60)
        return safe.ifBlank { "Nib" }
    }
}