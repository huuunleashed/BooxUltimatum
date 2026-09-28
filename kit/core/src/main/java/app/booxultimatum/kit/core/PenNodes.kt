package app.booxultimatum.kit.core

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * The `/dev/input` node the pen was last seen on. Apps can't look it up (SELinux closes `/sys/class/input`), so the
 * pen reader finds it by watching for a pen tool and remembers it here for the next start and for reports.
 */
object PenNodes {
    private const val PREFS = "ink"
    private const val KEY_NODE = "pen_node"

    fun remembered(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NODE, null)?.takeIf { File(it).canRead() }

    fun remember(context: Context, path: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_NODE, path) }
}
