package app.booxultimatum.core.sleep

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.launcher.BooxIntents
import java.io.File

/**
 * Hands a rendered face to Onyx. Verified on NA6C FW 4.3, 2026-09-26:
 *
 * - `onyx.action.SCREENSAVER` with `type` 16 and `file` switches the Boox sleep screen to the image style with that
 *   file as its only picture. A file directly in `/sdcard/Pictures/` is used in place and decoded again at every sleep,
 *   so overwriting it between sleeps updates the next sleep screen; anything elsewhere is copied once and later
 *   overwrites are ignored. Above 10 MB Onyx drops the file.
 * - The Transparent style's Lockscreen Sticker is saved by Boox as `Pictures/Sticker/sticker_<time>.png` and reloaded
 *   at every sleep. Boox owns that file, so only the shell uid (Shizuku) can overwrite it.
 */
object SleepPublisher {
    private const val ACTION = "onyx.action.SCREENSAVER"
    private const val TYPE_IMAGE = 16
    const val BOOX_DEFAULT = "/system/media/standby-1.png"
    private const val IMAGE_NAME = "booxultimatum-sleep"
    private const val PICK_NAME = "booxultimatum"
    private const val PICTURES = "Pictures/"
    private const val STICKERS = "Pictures/Sticker/"
    private val STICKER_DIR = android.os.Environment.getExternalStorageDirectory().path + "/Pictures/Sticker"
    private const val STICKER_JOURNAL = "sleep.sticker"
    /** Onyx refuses files above 10 MB; stay clear of it. */
    const val LIMIT = 9_500_000

    enum class Format(val ext: String, val mime: String) { Png("png", "image/png"), Jpeg("jpg", "image/jpeg") }

    private val collection: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    // ---------- Our own MediaStore pictures ----------

    /** Absolute path of a row we own, if it still exists in [folder]; a row renamed in place is still fine to use. */
    private fun liveRow(context: Context, key: String, folder: String): Pair<Uri, String>? {
        val uri = SleepStore.get(context, key)?.let(Uri::parse) ?: return null
        val found = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) null
                else if (c.getString(0)?.trimEnd('/') != folder.trimEnd('/')) null
                else c.getString(2) ?: ("/storage/emulated/0/$folder" + c.getString(1))
            }
        }.getOrNull()
        if (found == null) SleepStore.put(context, key, null)
        return found?.let { uri to it }
    }

    /**
     * Writes [bytes] into our picture in [folder], creating it when missing. The bytes are encoded in full before the
     * file is opened, and written in one call, so Onyx never decodes half a picture. Returns the absolute path.
     */
    private fun write(context: Context, key: String, folder: String, name: String, format: Format, bytes: ByteArray): String {
        val resolver = context.contentResolver
        liveRow(context, key, folder)?.let { (uri, path) ->
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            return path
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.${format.ext}")
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused a new picture in $folder")
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        SleepStore.put(context, key, uri.toString())
        return liveRow(context, key, folder)?.second ?: "/storage/emulated/0/$folder$name.${format.ext}"
    }

    private fun imageKey(format: Format) = "image_uri_${format.ext}"

    fun writeImage(context: Context, bytes: ByteArray, format: Format): String {
        val path = write(context, imageKey(format), PICTURES, IMAGE_NAME, format, bytes)
        // Only one of the two formats is ever in use; the other would just be a stale picture in the gallery.
        Format.entries.filter { it != format }.forEach { deleteRow(context, imageKey(it)) }
        return path
    }

    /** Whether the picture last written for the image style is still where Onyx reads it. */
    fun imageLive(context: Context): Boolean = Format.entries.any { liveRow(context, imageKey(it), PICTURES) != null }

    /** Puts the overlay into the gallery's Sticker album, where the Boox sticker picker finds it. */
    fun placeStickerPick(context: Context, bytes: ByteArray): String = write(context, "sticker_pick_uri", STICKERS, PICK_NAME, Format.Png, bytes)

    private fun deleteRow(context: Context, key: String) {
        val uri = SleepStore.get(context, key)?.let(Uri::parse) ?: return
        runCatching { context.contentResolver.delete(uri, null, null) }
        SleepStore.put(context, key, null)
    }

    /** Removes every picture the studio put in shared storage. */
    fun deleteOurPictures(context: Context) {
        Format.entries.forEach { deleteRow(context, imageKey(it)) }
        deleteRow(context, "sticker_pick_uri")
    }

    // ---------- The image style ----------

    /**
     * Sends the style switch from the app (T0). When Shizuku is up it is repeated from the shell, the sender it was
     * verified with; a second identical switch is harmless, and this runs only on Apply and when the path changes.
     */
    suspend fun broadcastImage(context: Context, path: String) {
        require(!path.contains('\'')) { "Bad path" }
        context.sendBroadcast(Intent(ACTION).putExtra("type", TYPE_IMAGE).putExtra("file", path).putExtra("show_result_hint", false))
        if (Privileged.ready()) Privileged.sh("am broadcast -a $ACTION --ei type $TYPE_IMAGE --es file '$path' --ez show_result_hint false")
    }

    // ---------- The Transparent style's sticker ----------

    /** The sticker Boox saved most recently, which is the one the Transparent style shows. Needs Shizuku. */
    suspend fun findSticker(): String? {
        val r = Privileged.sh("ls -t $STICKER_DIR/sticker_*.png 2>/dev/null | head -n 1")
        return r.out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") && it.endsWith(".png") && !it.contains('\'') }
    }

    /**
     * Copies [bytes] over the Boox sticker through the shell. The sticker Boox made is backed up once per sticker
     * file and journaled, so Restore puts it back. [redetect] looks for the newest sticker instead of the remembered one.
     */
    suspend fun writeSticker(context: Context, bytes: ByteArray, redetect: Boolean): Result<String> = runCatching {
        check(Privileged.ready()) { context.getString(app.booxultimatum.R.string.sl_err_needs_shizuku) }
        val dir = File(context.getExternalFilesDir(null), "sleep").apply { mkdirs() }
        val ours = File(dir, "overlay.png")
        val tmp = File(dir, "overlay.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(ours)) { "Could not stage the overlay" }
        var target = if (redetect) null else SleepStore.get(context, "sticker_path")
        if (target == null) target = findSticker() ?: error(context.getString(app.booxultimatum.R.string.sl_err_no_sticker))
        backupSticker(context, target).getOrThrow()
        var r = Privileged.sh("cp '${ours.absolutePath}' '$target'")
        if (!r.ok && !redetect) {
            target = findSticker() ?: error(context.getString(app.booxultimatum.R.string.sl_err_no_sticker))
            backupSticker(context, target).getOrThrow()
            r = Privileged.sh("cp '${ours.absolutePath}' '$target'")
        }
        check(r.ok) { r.message }
        SleepStore.put(context, "sticker_path", target)
        target
    }

    /** Before the first overwrite of [target], keeps Boox's own sticker in our files and journals where it came from. */
    suspend fun backupSticker(context: Context, target: String): Result<Unit> = runCatching {
        val prior = app.booxultimatum.core.Journal.original(context, STICKER_JOURNAL)
        if (prior?.optString("path") == target) return@runCatching
        val backup = File(File(context.getExternalFilesDir(null), "sleep").apply { mkdirs() }, "sticker-original.png")
        val r = Privileged.sh("cp '$target' '${backup.absolutePath}'")
        check(r.ok) { r.message }
        app.booxultimatum.core.Journal.forget(context, STICKER_JOURNAL)
        app.booxultimatum.core.Journal.rememberOriginal(context, STICKER_JOURNAL, org.json.JSONObject().put("path", target).put("backup", backup.absolutePath))
    }

    fun stickerChanged(context: Context) = app.booxultimatum.core.Journal.original(context, STICKER_JOURNAL) != null

    /** Puts Boox's own sticker back over ours. */
    suspend fun restoreSticker(context: Context): Result<Unit> = runCatching {
        val o = app.booxultimatum.core.Journal.original(context, STICKER_JOURNAL) ?: return@runCatching
        check(Privileged.ready()) { context.getString(app.booxultimatum.R.string.sl_err_needs_shizuku) }
        val path = o.getString("path")
        val backup = o.getString("backup")
        require(!path.contains('\'') && !backup.contains('\''))
        val r = Privileged.sh("cp '$backup' '$path'")
        check(r.ok) { r.message }
        app.booxultimatum.core.Journal.forget(context, STICKER_JOURNAL)
        SleepStore.put(context, "sticker_path", null)
    }

    // ---------- Boox settings ----------

    /**
     * Boox's screensaver style page. `SettingContainerActivity` is exported and maps these actions to its pages
     * (read from the decompiled `com.onyx`, resolved with `cmd package resolve-activity`), though its intent filter
     * does not list them, so the component is named explicitly. Falls back to Boox Settings.
     */
    fun openScreensaverSettings(context: Context) {
        val container = ComponentName("com.onyx", "com.onyx.common.setting.ui.SettingContainerActivity")
        val tries = listOf(
            Intent("onyx.settings.action.DREAM_STYLE_SETTING").setComponent(container),
            Intent("onyx.settings.action.LAUNCHER_SCREENSAVER_SETTING").setComponent(container),
        )
        for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        BooxIntents.openSettings(context)
    }
}
