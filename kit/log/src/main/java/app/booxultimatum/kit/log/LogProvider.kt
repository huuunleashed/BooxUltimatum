package app.booxultimatum.kit.log

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.os.bundleOf
import java.io.FileNotFoundException

/**
 * Exposes this app's log files read-only to the other suite apps, so the hub can gather every app's logs into one
 * export and switch detailed mode on for an app. Guarded by the suite's signature permission [PERMISSION].
 *
 * - query `content://<package>.logs/files`: rows of [COLUMN_NAME], [COLUMN_SIZE], [COLUMN_MODIFIED].
 * - openFile `content://<package>.logs/files/<name>` with mode "r": one file [Logbook.files] lists.
 * - call [METHOD_SET_DETAILED_UNTIL] (arg: wall-clock ms), [METHOD_DETAILED_UNTIL] and [METHOD_FLUSH].
 * - call [METHOD_USAGE]: [Housekeeping.usage] as parallel arrays; [METHOD_CLEAN] (arg: comma-separated area ids)
 *   clears those areas and returns the bytes freed.
 */
class LogProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        enforceCaller(ctx)
        if (uri.pathSegments != listOf(PATH_FILES)) return null
        val cursor = MatrixCursor(arrayOf(COLUMN_NAME, COLUMN_SIZE, COLUMN_MODIFIED))
        for (f in Logbook.files(ctx)) cursor.addRow(arrayOf<Any>(f.name, f.length(), f.lastModified()))
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val name = LogFiles.nameFromSegments(uri.pathSegments) ?: return null
        return when {
            name.endsWith(".jsonl") -> "application/x-ndjson"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        enforceCaller(ctx)
        if (mode != "r") throw SecurityException("Logs are read-only")
        val name = LogFiles.nameFromSegments(uri.pathSegments) ?: throw FileNotFoundException(uri.toString())
        val file = LogFiles.resolve(Logbook.logsDir(ctx), name) ?: throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        enforceCaller(ctx)
        return when (method) {
            METHOD_SET_DETAILED_UNTIL -> {
                val until = arg?.toLongOrNull() ?: return bundleOf(EXTRA_OK to false)
                Logbook.setDetailedUntil(ctx, until)
                bundleOf(EXTRA_OK to true, EXTRA_DETAILED_UNTIL to Logbook.detailedUntil(ctx))
            }
            METHOD_DETAILED_UNTIL -> bundleOf(EXTRA_OK to true, EXTRA_DETAILED_UNTIL to Logbook.detailedUntil(ctx))
            METHOD_FLUSH -> {
                Logbook.flush()
                bundleOf(EXTRA_OK to true)
            }
            METHOD_USAGE -> {
                val usage = Housekeeping.usage(ctx)
                bundleOf(
                    EXTRA_OK to true,
                    EXTRA_IDS to usage.map { it.id }.toTypedArray(),
                    EXTRA_BYTES to usage.map { it.bytes }.toLongArray(),
                    EXTRA_FILES to usage.map { it.files }.toIntArray(),
                    EXTRA_CLEANABLE to usage.map { it.cleanable }.toBooleanArray(),
                )
            }
            METHOD_CLEAN -> {
                val ids = arg.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                bundleOf(EXTRA_OK to true, EXTRA_BYTES to Housekeeping.clean(ctx, ids))
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    // The manifest's permission covers query and openFile but not call(), so every entry point checks it here too.
    private fun enforceCaller(ctx: Context) {
        val granted = ctx.checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!callerAllowed(Binder.getCallingUid(), Process.myUid(), granted)) {
            throw SecurityException("Requires $PERMISSION")
        }
    }

    companion object {
        /** The suite's signature permission, declared by the hub's manifest. */
        const val PERMISSION = "app.booxultimatum.permission.SUITE"
        const val PATH_FILES = "files"
        const val COLUMN_NAME = "name"
        const val COLUMN_SIZE = "size"
        const val COLUMN_MODIFIED = "modified"
        const val METHOD_SET_DETAILED_UNTIL = "setDetailedUntil"
        const val METHOD_DETAILED_UNTIL = "detailedUntil"
        const val METHOD_FLUSH = "flush"
        const val METHOD_USAGE = "storageUsage"
        const val METHOD_CLEAN = "storageClean"
        const val EXTRA_IDS = "ids"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_FILES = "files"
        const val EXTRA_CLEANABLE = "cleanable"
        const val EXTRA_OK = "ok"
        const val EXTRA_DETAILED_UNTIL = "detailed_until"

        internal fun callerAllowed(callingUid: Int, myUid: Int, permissionGranted: Boolean): Boolean =
            callingUid == myUid || permissionGranted
    }
}
