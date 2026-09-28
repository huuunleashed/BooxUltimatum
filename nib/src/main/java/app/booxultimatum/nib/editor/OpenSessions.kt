package app.booxultimatum.nib.editor

import app.booxultimatum.nib.store.AutoSaver
import app.booxultimatum.nib.store.DrawingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The drawings open in the editor, kept while their editor is anywhere on the back stack, so a visit to Diagnostics
 * or About keeps the undo steps. Main thread only.
 */
class OpenSessions(private val store: DrawingStore) {
    private val open = HashMap<String, EditorSession>()

    operator fun get(id: String): EditorSession? = open[id]

    /** The session for [id], reading the drawing on first use; null when it can't be read. */
    suspend fun load(id: String): EditorSession? {
        open[id]?.let { return it }
        val opened = withContext(Dispatchers.IO) { store.open(id) } ?: return null
        open[id]?.let {
            opened.journal.close()
            return it
        }
        val s = EditorSession(opened.info, opened.document, AutoSaver(store, id, opened)) { paper ->
            Thread({ store.setPaper(id, paper) }, "nib-paper").start()
        }
        open[id] = s
        return s
    }

    /** Closes (and saves) every session whose id isn't in [ids]. */
    fun retain(ids: Set<String>) {
        for (id in open.keys.filter { it !in ids }) open.remove(id)?.close()
    }

    fun closeAll() = retain(emptySet())
}
