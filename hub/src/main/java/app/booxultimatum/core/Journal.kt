package app.booxultimatum.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class JournalEntry(val at: Long, val action: String, val subject: String, val detail: String, val ok: Boolean)

/**
 * Every change the app makes is written here with what it replaced, so it can be undone even after the
 * app restarts. Stored in private app preferences; nothing leaves the device.
 */
object Journal {
    private const val PREFS = "journal"
    private const val LOG = "log"
    private const val MAX = 200

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves the pre-change state for [id] only if none is stored, so repeated applies never lose the original. */
    fun rememberOriginal(context: Context, id: String, state: JSONObject) {
        val p = prefs(context)
        // commit(), not apply(): some tweaks (fonts, overlays) restart the process right after, and the record must survive.
        if (!p.contains("state:$id")) p.edit().putString("state:$id", state.toString()).commit()
    }

    fun original(context: Context, id: String): JSONObject? =
        prefs(context).getString("state:$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun forget(context: Context, id: String) {
        prefs(context).edit().remove("state:$id").commit()
    }

    @Synchronized
    fun log(context: Context, action: String, subject: String, detail: String, ok: Boolean) {
        val p = prefs(context)
        val arr = runCatching { JSONArray(p.getString(LOG, "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("action", action).put("subject", subject).put("detail", detail).put("ok", ok))
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX) until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString(LOG, trimmed.toString()).commit()
    }

    fun entries(context: Context): List<JournalEntry> {
        val arr = runCatching { JSONArray(prefs(context).getString(LOG, "[]")) }.getOrDefault(JSONArray())
        return (arr.length() - 1 downTo 0).map { i ->
            val o = arr.getJSONObject(i)
            JournalEntry(o.optLong("at"), o.optString("action"), o.optString("subject"), o.optString("detail"), o.optBoolean("ok"))
        }
    }

    fun clearLog(context: Context) {
        prefs(context).edit().remove(LOG).apply()
    }
}
