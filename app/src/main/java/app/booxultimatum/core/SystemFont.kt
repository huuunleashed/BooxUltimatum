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
 * The font file must sit in shared storage where SystemUI can read it, so it is copied to `/sdcard/fonts`
 * (the folder NeoReader also reads) through Shizuku first.
 */
object SystemFont {
    private const val ACTION_REPLACE = "onyx.action.font.replace.system"
    private const val ACTION_RESET = "onyx.action.font.reset.default"
    private const val JOURNAL_ID = "font.system"
    private const val SHARED = "/storage/emulated/0/fonts"

    fun current(): String? = UiFonts.systemFontPath()

    /** A readable name for a font path: the file name without its extension and weight-axis suffix. */
    fun nameOf(path: String?): String? = path?.substringAfterLast('/')?.substringBeforeLast('.')?.replace("-VariableFont_wght", "")?.replace('-', ' ')

    fun changed(context: Context) = Journal.original(context, JOURNAL_ID) != null

    /** The path in use before BooxUltimatum first changed the system font; empty means the Boox default. */
    fun previous(context: Context): String? = Journal.original(context, JOURNAL_ID)?.optString("path")

    /** Copies [file] into shared storage and makes it the system font. Returns the new path. */
    suspend fun apply(context: Context, file: java.io.File, label: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(Privileged.ready()) { context.getString(app.booxultimatum.R.string.fonts_needs_shizuku) }
            require(Regex("^[A-Za-z0-9_\\-.]+\\.ttf$").matches(file.name)) { "Unsafe file name ${file.name}" }
            val target = "$SHARED/${file.name}"
            Privileged.sh("mkdir -p $SHARED && cp '${file.absolutePath}' '$target' && chmod 664 '$target'").also { check(it.ok) { it.message } }
            if (Journal.original(context, JOURNAL_ID) == null) Journal.rememberOriginal(context, JOURNAL_ID, JSONObject().put("path", current().orEmpty()))
            send(context, target)
            Journal.log(context, "system-font", label, "", true)
            target
        }
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

    /** Latin (0) is the slot Boox Settings writes; SystemUI applies the same file to the CJK slot. */
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
