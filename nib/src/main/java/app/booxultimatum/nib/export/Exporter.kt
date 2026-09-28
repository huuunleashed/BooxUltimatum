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
import java.io.File
import java.io.OutputStream

/**
 * Flattened pictures of a drawing: saved into the gallery (`Pictures/Nib/`) or handed to another app. Everything here
 * renders the full page, so call it off the main thread.
 */
object Exporter {
    private val log = Logbook.logger("nib.doc")
    const val RELATIVE_DIR = "Pictures/Nib/"

    /** The page flattened over its paper, as PNG bytes into [out]. */
    fun writePng(doc: Document, out: OutputStream) {
        val start = SystemClock.elapsedRealtime()
        val bmp = DocumentPainter.render(doc)
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
        log.i("png rendered", "width" to doc.width, "height" to doc.height, "strokes" to doc.strokeCount, "ms" to (SystemClock.elapsedRealtime() - start))
    }

    /** Saves the page into the gallery; returns the new picture's address, or null when Android refused. */
    fun saveToGallery(context: Context, doc: Document, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { writePng(doc, it) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            log.i("png saved to gallery")
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            log.e("png export failed", error = e)
            null
        }
    }

    /** Writes the page to the share folder and returns an intent that offers it to other apps. */
    fun shareIntent(context: Context, doc: Document, name: String): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(name))
        file.outputStream().buffered().use { writePng(doc, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Offers any file in the provider's folders to other apps. */
    fun shareFile(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** A file name from a drawing's name: letters, digits, spaces and dashes only. */
    fun fileName(name: String): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }.joinToString("").trim().take(60)
        return (safe.ifBlank { "Nib" }) + ".png"
    }
}
