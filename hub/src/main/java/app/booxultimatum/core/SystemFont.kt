package app.booxultimatum.core

import android.content.Context
import android.content.Intent
import app.booxultimatum.core.exec.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The tablet-wide font, changed the way Boox Settings › Display › Font Style changes it: a broadcast SystemUI
 * listens for (`onyx.action.font.replace.system`, extras `font_lang` and `args_path`), which rewrites the
 * `persist.sys.font.*` properties and hot-reloads the font in every app. No root and no reboot. Verified on
 * NA6C FW 4.3, 2026-09-26: Inter applied and Manrope restored from the shell.
 *
 * The font file must sit in shared storage where SystemUI can read it. With Shizuku it is copied to `/sdcard/fonts`
 * (the folder NeoReader also reads); without it the app writes its own copy to `Documents/BooxUltimatum/` through
 * MediaStore and sends the broadcast itself, since SystemUI's receiver asks for no permission.
 */
object SystemFont {
    private const val ACTION_REPLACE = "onyx.action.font.replace.system"
    private const val ACTION_RESET = "onyx.action.font.reset.default"
    private const val JOURNAL_ID = "font.system"
    private const val SHARED = "/storage/emulated/0/fonts"
    private const val OWN_FOLDER = "Documents/BooxUltimatum/"

    fun current(): String? = UiFonts.systemFontPath()

    /** A readable name for a font path: the file name without its extension and weight-axis suffix. */
    fun nameOf(path: String?): String? = path?.substringAfterLast('/')?.substringBeforeLast('.')?.replace("-VariableFont_wght", "")?.replace('-', ' ')

    fun changed(context: Context) = Journal.original(context, JOURNAL_ID) != null

    /** The path in use before BooxUltimatum first changed the system font; empty means the Boox default. */
    fun previous(context: Context): String? = Journal.original(context, JOURNAL_ID)?.optString("path")

    /** Puts [file] where SystemUI can read it and makes it the system font. Returns the new path. */
    suspend fun apply(context: Context, file: java.io.File, label: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(Regex("^[A-Za-z0-9_\\-.]+\\.ttf$").matches(file.name)) { "Unsafe file name ${file.name}" }
            val target = if (Privileged.ready()) {
                "$SHARED/${file.name}".also { t ->
                    Privileged.sh("mkdir -p $SHARED && cp '${file.absolutePath}' '$t' && chmod 664 '$t'").also { check(it.ok) { it.message } }
                }
            } else publishOwnCopy(context, file)
            if (Journal.original(context, JOURNAL_ID) == null) Journal.rememberOriginal(context, JOURNAL_ID, JSONObject().put("path", current().orEmpty()))
            send(context, target)
            Journal.log(context, "system-font", label, "", true)
            target
        }
    }

    /**
     * Writes [file] into `Documents/BooxUltimatum/` as the app's own MediaStore row, replacing an earlier copy with the
     * same name, and returns its absolute path. No permission is needed for files the app owns.
     */
    private fun publishOwnCopy(context: Context, file: java.io.File): String {
        val resolver = context.contentResolver
        val files = android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val cols = arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DATA)
        val where = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val existing = resolver.query(files, cols, where, arrayOf(OWN_FOLDER, file.name), null)?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(files, c.getLong(0)) to c.getString(1) else null
        }
        val uri = existing?.first ?: resolver.insert(files, android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "font/ttf")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, OWN_FOLDER)
        }) ?: error("Android refused to create the font file")
        resolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return existing?.second ?: resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "/storage/emulated/0/$OWN_FOLDER${file.name}"
    }

    /** Back to the font the tablet had before BooxUltimatum changed it. */
    suspend fun restore(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val prev = previous(context)
            if (prev.isNullOrEmpty()) reset(context) else send(context, prev)
            Journal.forget(context, JOURNAL_ID)
            Journal.log(context, "system-font", nameOf(prev) ?: context.getString(app.booxultimatum.R.string.fonts_sys_default), "", true)
        }
    }

    private suspend fun reset(context: Context) {
        broadcast(context, Intent(ACTION_RESET), "am broadcast -a $ACTION_RESET")
    }

    /** Slot 0 sets every slot, Latin and CJK together (Onyx's SDK names it FONT_LANG_INDEX_ALL; 1 is CJK, 2 is Latin). */
    private suspend fun send(context: Context, path: String) {
        require(!path.contains('\'')) { "Bad path" }
        broadcast(
            context,
            Intent(ACTION_REPLACE).putExtra("font_lang", 0).putExtra("args_path", path),
            "am broadcast -a $ACTION_REPLACE --ei font_lang 0 --es args_path '$path'",
        )
        // SystemUI writes the properties, then reloads fonts; confirm before reporting success.
        repeat(10) { if (current() == path) return; delay(500) }
        check(current() == path) { "The tablet did not switch fonts" }
    }

    private suspend fun broadcast(context: Context, intent: Intent, shell: String) {
        if (Privileged.ready()) Privileged.sh(shell).also { check(it.ok) { it.message } }
        else context.sendBroadcast(intent)
    }
}
