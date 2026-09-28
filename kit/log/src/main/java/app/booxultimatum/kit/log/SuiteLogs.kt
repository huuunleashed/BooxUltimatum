package app.booxultimatum.kit.log

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.InputStream

/** One log file another suite app exposes through its [LogProvider]. */
data class RemoteLogFile(val name: String, val size: Long, val modified: Long)

/**
 * Client for other suite apps' [LogProvider]s. Every call returns empty, null or false when the app isn't installed,
 * doesn't expose logs, or refuses the caller.
 */
object SuiteLogs {
    fun authority(packageName: String) = "$packageName.logs"

    fun listFiles(context: Context, packageName: String): List<RemoteLogFile> = try {
        context.contentResolver.query(filesUri(packageName), null, null, null, null)?.use { c ->
            val name = c.getColumnIndexOrThrow(LogProvider.COLUMN_NAME)
            val size = c.getColumnIndexOrThrow(LogProvider.COLUMN_SIZE)
            val modified = c.getColumnIndexOrThrow(LogProvider.COLUMN_MODIFIED)
            val out = ArrayList<RemoteLogFile>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out.add(RemoteLogFile(c.getString(name), c.getLong(size), c.getLong(modified)))
            out
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun open(context: Context, packageName: String, name: String): InputStream? {
        if (!LogFiles.isSafeName(name)) return null
        return try {
            context.contentResolver.openInputStream(filesUri(packageName).buildUpon().appendPath(name).build())
        } catch (_: Exception) {
            null
        }
    }

    fun setDetailedUntil(context: Context, packageName: String, wallMs: Long): Boolean =
        call(context, packageName, LogProvider.METHOD_SET_DETAILED_UNTIL, wallMs.toString())
            ?.getBoolean(LogProvider.EXTRA_OK) == true

    /** The app's detailed-mode end time, or null when it can't be asked. */
    fun detailedUntil(context: Context, packageName: String): Long? =
        call(context, packageName, LogProvider.METHOD_DETAILED_UNTIL, null)
            ?.takeIf { it.getBoolean(LogProvider.EXTRA_OK) }
            ?.getLong(LogProvider.EXTRA_DETAILED_UNTIL)

    /** Asks the app to write its queued events to disk, e.g. before [listFiles] for an export. */
    fun flush(context: Context, packageName: String): Boolean =
        call(context, packageName, LogProvider.METHOD_FLUSH, null)?.getBoolean(LogProvider.EXTRA_OK) == true

    private fun filesUri(packageName: String): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority(packageName))
        .appendPath(LogProvider.PATH_FILES)
        .build()

    private fun call(context: Context, packageName: String, method: String, arg: String?) = try {
        context.contentResolver.call(authority(packageName), method, arg, null)
    } catch (_: Exception) {
        null
    }
}
