package app.booxultimatum.core.sleep

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import app.booxultimatum.core.Journal
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.launcher.BooxIntents
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hands a rendered face to Onyx. Verified on NA6C FW 4.3, 2026-09-26:
 *
 * - `onyx.action.SCREENSAVER` with `type` 16 and `file` switches the Boox sleep screen to the image style with that
 *   file as its only picture. A file directly in `/sdcard/Pictures/` is used in place and decoded again at every sleep,
 *   so overwriting it between sleeps updates the next sleep screen; anything elsewhere is copied once and later
 *   overwrites are ignored. Above 10 MB Onyx drops the file. The broadcast carries only three types — 1 wallpaper,
 *   16 image, 17 power-off — so the Transparent style can't be chosen from here (decompiled `ScreensaverReceiver`).
 * - The Transparent style's Lockscreen Sticker is saved by Boox as `Pictures/Sticker/sticker_<time>.png` and read at
 *   every sleep (from the one stored path, never during a sleep). Boox owns that file, so it takes the shell uid, or
 *   the owner's consent for the app to write that picture itself.
 */
object SleepPublisher {
    private val log = Logbook.logger("sleep.publish")
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
     * Our own picture in [folder] under [fileName], when the remembered row is gone but the row itself still exists —
     * a lost preference, a cleared cache, a restore. Android lets an app write a picture it owns, so adopting it keeps
     * the owner's gallery free of `name (1).png` copies. Never touches a row that isn't ours (those come from a copy
     * of the app that was uninstalled, and only the owner can remove them).
     */
    private fun ownRowNamed(context: Context, folder: String, fileName: String): Pair<Uri, String>? = runCatching {
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            images,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(folder, fileName, context.packageName),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(images, c.getLong(0).toString()) to (c.getString(1) ?: "") else null }
    }.getOrNull()

    /**
     * Writes [bytes] into our picture in [folder], creating it when missing. The bytes are encoded in full before the
     * file is opened, and written in one call, so Onyx never decodes half a picture. Returns the absolute path.
     */
    private fun write(context: Context, key: String, folder: String, name: String, format: Format, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val fileName = "$name.${format.ext}"
        liveRow(context, key, folder)?.let { (uri, path) ->
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            return path
        }
        ownRowNamed(context, folder, fileName)?.let { (uri, path) ->
            runCatching {
                resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                SleepStore.put(context, key, uri.toString())
            }.onSuccess {
                log.i("adopted our picture again", "file" to fileName)
                return path.ifEmpty { "/storage/emulated/0/$folder$fileName" }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused a new picture in $folder")
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        SleepStore.put(context, key, uri.toString())
        val row = liveRow(context, key, folder)
        // A different name means the plain one was taken by a picture this app can no longer write: the old copy is
        // left in the gallery (Android won't let us remove it) and the newest one is written beside it.
        if (row != null && !row.second.endsWith(fileName)) log.w("picture name was taken", "wanted" to fileName, "wrote" to row.second)
        return row?.second ?: "/storage/emulated/0/$folder$fileName"
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

    // ---------- The power-off image ----------

    /** The same broadcast with type 17 sets the picture Boox shows when the tablet is switched off (Onyx's SDK `ScreenSaverUtils`). */
    private const val TYPE_POWER_OFF = 17
    const val BOOX_POWER_OFF_DEFAULT = "/system/media/shutdown-default.png"
    private const val POWER_OFF_NAME = "booxultimatum-poweroff"

    /** Writes the power-off picture as our own file in `Pictures/` and returns its path. */
    fun writePowerOff(context: Context, bytes: ByteArray): String = write(context, "poweroff_uri", PICTURES, POWER_OFF_NAME, Format.Png, bytes)

    /** Asks Boox to use [path] as the power-off image. Boox keeps its own copy, so the picture only changes when this is sent again. */
    fun broadcastPowerOff(context: Context, path: String) {
        require(!path.contains('\'')) { "Bad path" }
        context.sendBroadcast(Intent(ACTION).putExtra("type", TYPE_POWER_OFF).putExtra("file", path).putExtra("show_result_hint", false))
    }

    fun deletePowerOff(context: Context) = deleteRow(context, "poweroff_uri")

    // ---------- The Transparent style's sticker ----------

    /**
     * One sticker file the app has written, with Boox's own bytes kept aside. Boox can be made to take a new sticker
     * at any time (the picker makes a fresh `sticker_<time>.png`), so the journal holds a list, not one file: every
     * picture the app has touched can be put back the way Boox made it.
     */
    private class Sticker(val id: String, val backup: String) {
        fun json() = JSONObject().put("id", id).put("backup", backup)

        companion object {
            fun of(o: JSONObject) = Sticker(o.getString("id"), o.getString("backup"))
        }
    }

    private fun sleepDir(context: Context) = File(context.getExternalFilesDir(null), "sleep").apply { mkdirs() }

    private fun stickers(context: Context): List<Sticker> =
        Journal.original(context, STICKER_JOURNAL)?.optJSONArray("files")?.let { arr ->
            (0 until arr.length()).mapNotNull { runCatching { Sticker.of(arr.getJSONObject(it)) }.getOrNull() }
        }.orEmpty()

    private fun remember(context: Context, list: List<Sticker>) {
        Journal.forget(context, STICKER_JOURNAL)
        if (list.isEmpty()) return
        val arr = JSONArray().apply { list.forEach { put(it.json()) } }
        Journal.rememberOriginal(context, STICKER_JOURNAL, JSONObject().put("files", arr))
    }

    private fun backupFile(context: Context, id: String) =
        File(sleepDir(context), "sticker-${sha(id).take(8)}.png")

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Whether an id names a MediaStore row (the app writes it itself) or a plain file (only the shell may write it). */
    private fun isMedia(id: String) = id.startsWith("content://")

    /**
     * Finds the sticker Boox shows. Which file that is can't be read back — it lives in `com.onyx`'s private store —
     * so this takes the newest `sticker_*.png`, through the shell when Shizuku runs and through MediaStore otherwise.
     * A sticker the picker made is always newer than the one the app last wrote, which is what makes that a safe rule.
     */
    suspend fun findSticker(context: Context): String? {
        if (Privileged.ready()) {
            val r = Privileged.sh("ls -t $STICKER_DIR/sticker_*.png 2>/dev/null | head -n 1")
            r.out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") && it.endsWith(".png") && !it.contains('\'') }
                ?.let { return it }
        }
        return newestRow(context)?.uri?.toString()
    }

    /**
     * The newest `sticker_*.png` in `Pictures/Sticker`, from MediaStore: the row to write, and the file it stands for.
     * Needs read access to pictures, and only the pictures the owner let Android share are visible.
     */
    private class StickerRow(val uri: Uri, val path: String?)

    private fun newestRow(context: Context): StickerRow? = runCatching {
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            images,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("%Sticker%", "sticker_%.png"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) StickerRow(Uri.withAppendedPath(images, c.getLong(0).toString()), c.getString(1)) else null }
    }.getOrNull()

    /** The sticker row Boox's album holds, for the page to offer a write consent for. */
    fun newestStickerRow(context: Context): Uri? = newestRow(context)?.uri

    /**
     * Whether two ids are the same picture. Shizuku names it by its path and MediaStore by its row, and the route can
     * change between refreshes (Shizuku stops at every reboot), so the ids are compared by the file they stand for
     * rather than as strings — otherwise a swap would look like a new pick on every refresh.
     */
    private fun sameSticker(context: Context, a: String, b: String): Boolean {
        if (a == b) return true
        fun pathOf(id: String): String? = if (isMedia(id)) {
            runCatching {
                context.contentResolver.query(Uri.parse(id), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        } else id
        val pb = pathOf(b)
        return pb != null && pathOf(a) == pb
    }

    /** Whether the app may write Boox's sticker at all: through the shell, or through its own granted access. */
    suspend fun canWriteSticker(context: Context): Boolean = access(context).writable

    /**
     * What the page needs to know about reaching the sticker, gathered in one pass: whether the shell is up, whether
     * pictures may be read, which sticker row Boox's album holds, and whether that row can be written yet.
     */
    data class StickerAccess(val shell: Boolean, val pictures: Boolean, val row: Uri?, val writable: Boolean)

    suspend fun access(context: Context): StickerAccess {
        if (Privileged.ready()) return StickerAccess(shell = true, pictures = canReadPictures(context), row = null, writable = true)
        val pictures = canReadPictures(context)
        val row = if (pictures) newestRow(context)?.uri else null
        return StickerAccess(shell = false, pictures = pictures, row = row, writable = row != null && canWriteRow(context, row))
    }

    private fun canWriteRow(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { } != null
    }.getOrDefault(false)

    /**
     * Whether the page should offer the system's write consent for the sticker Boox is showing. Android 11+ lets an
     * app ask to write a picture it doesn't own; the answer lasts until it is revoked, so this is asked once.
     */
    fun writeRequest(context: Context): android.content.IntentSender? = runCatching {
        val uri = newestRow(context)?.uri ?: return null
        MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
    }.getOrNull()

    /** True when pictures may be read at all, which is what finding Boox's sticker without Shizuku needs. */
    fun canReadPictures(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Copies Boox's own bytes aside before the first overwrite, once per picture however it is named. */
    private suspend fun backUp(context: Context, id: String): Result<Unit> = runCatching {
        if (stickers(context).any { sameSticker(context, it.id, id) }) return@runCatching
        val backup = backupFile(context, id)
        check(copyTo(context, id, backup)) { context.getString(app.booxultimatum.R.string.sl_err_backup) }
        remember(context, stickers(context) + Sticker(id, backup.absolutePath))
    }

    /** Puts a file the app no longer writes back the way Boox made it, and drops it from the journal. */
    private suspend fun retire(context: Context, id: String): Result<Unit> = runCatching {
        val list = stickers(context)
        val s = list.firstOrNull { sameSticker(context, it.id, id) } ?: return@runCatching
        check(copyFrom(context, s.backup, s.id)) { context.getString(app.booxultimatum.R.string.sl_err_backup) }
        remember(context, list - s)
    }

    /** Reads [id] into [into]: through the shell, or through the app's own access to the picture. */
    private suspend fun copyTo(context: Context, id: String, into: File): Boolean = if (isMedia(id)) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(id))?.use { input ->
                into.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
    } else {
        Privileged.sh("cp '$id' '${into.absolutePath}'").ok
    }

    /** Writes [from] over [id]. Through the shell it is a copy beside the target and a rename, so Boox never reads half a picture. */
    private suspend fun copyFrom(context: Context, from: String, id: String): Boolean = if (isMedia(id)) {
        runCatching {
            val bytes = File(from).readBytes()
            context.contentResolver.openOutputStream(Uri.parse(id), "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)
    } else {
        val tmp = "$id.tmp"
        Privileged.sh("cp '$from' '$tmp' && mv -f '$tmp' '$id' && stat -c %s '$id'").ok
    }

    /** How big [id] is now, or null when it can't be read; the check that a write really landed. */
    private suspend fun sizeOf(context: Context, id: String): Long? = if (isMedia(id)) {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(id), "r")?.use { it.length }.takeIf { it != null && it >= 0 }
        }.getOrNull()
    } else {
        Privileged.sh("stat -c %s '$id'").out.trim().toLongOrNull()
    }

    /**
     * Writes [bytes] over the sticker Boox shows and returns its id. Boox's own file is backed up first and journaled,
     * and a sticker the picker made since is followed instead: the old picture is put back and the new one adopted.
     */
    suspend fun writeSticker(context: Context, bytes: ByteArray, redetect: Boolean): Result<String> = runCatching {
        val ours = File(sleepDir(context), "overlay.png")
        val tmp = File(sleepDir(context), "overlay.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(ours)) { context.getString(app.booxultimatum.R.string.sl_err_stage) }
        val known = if (redetect) null else SleepStore.get(context, "sticker_path")
        var target = known ?: findSticker(context) ?: error(context.getString(app.booxultimatum.R.string.sl_err_no_sticker))
        // A newer sticker means the owner picked again in Boox: follow it, and put the file we were writing back.
        if (known != null) {
            val newest = findSticker(context)
            if (newest != null && !sameSticker(context, target, newest)) {
                retire(context, target).onFailure { log.w("could not put Boox's sticker back", "id" to target, error = it) }
                target = newest
            }
        }
        // The remembered id may name the file while only the app's own access is left (Shizuku stops at every reboot):
        // the same picture through MediaStore is then the one the app can write.
        if (!isMedia(target) && !Privileged.ready()) {
            val row = newestRow(context)
            if (row?.path != null && sameSticker(context, target, row.uri.toString())) target = row.uri.toString()
        }
        backUp(context, target).getOrThrow()
        check(copyFrom(context, ours.absolutePath, target)) { context.getString(app.booxultimatum.R.string.sl_err_write) }
        // Read the size back where it can be read: a copy that landed short is the one failure worth catching. A
        // provider that won't report a size is taken at its word rather than failing a write that worked.
        val written = sizeOf(context, target)
        check(written == null || written == bytes.size.toLong()) { context.getString(app.booxultimatum.R.string.sl_err_write) }
        SleepStore.put(context, "sticker_path", target)
        target
    }

    fun stickerChanged(context: Context) = stickers(context).isNotEmpty()

    /** Puts every sticker the app has written back the way Boox made it. */
    suspend fun restoreSticker(context: Context): Result<Unit> = runCatching {
        val list = stickers(context)
        if (list.isEmpty()) return@runCatching
        var failed: String? = null
        for (s in list) {
            val ok = copyFrom(context, s.backup, s.id)
            if (ok) remember(context, stickers(context) - s) else failed = failed ?: s.id
        }
        SleepStore.put(context, "sticker_path", null)
        check(failed == null) { context.getString(app.booxultimatum.R.string.sl_err_restore, failed) }
    }

    /** Ids of every sticker the app writes, for the page to name. */
    fun stickerPaths(context: Context): List<String> = stickers(context).map { it.id }

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
