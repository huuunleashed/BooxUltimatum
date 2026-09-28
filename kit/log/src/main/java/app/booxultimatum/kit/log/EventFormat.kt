package app.booxultimatum.kit.log

import org.json.JSONObject

/** What the first line of every event file says about the run. */
internal data class LogHeader(
    val app: String,
    val version: String,
    val versionCode: Long,
    val debuggable: Boolean,
    val device: String,
    val android: String,
    val session: String,
    val pid: Int,
    val started: Long,
)

/** The JSON Lines format: header and event lines, value stringification, logcat text and parsing. */
internal object EventFormat {
    const val SCHEMA = 1
    const val MAX_MESSAGE = 2000
    const val MAX_VALUE = 500
    const val MAX_ERROR = 16_000

    fun appendHeader(sb: StringBuilder, h: LogHeader) {
        sb.append("{\"schema\":").append(SCHEMA)
        sb.append(",\"app\":")
        Json.appendString(sb, h.app)
        sb.append(",\"version\":")
        Json.appendString(sb, h.version)
        sb.append(",\"versionCode\":").append(h.versionCode)
        sb.append(",\"debuggable\":").append(h.debuggable)
        sb.append(",\"device\":")
        Json.appendString(sb, h.device)
        sb.append(",\"android\":")
        Json.appendString(sb, h.android)
        sb.append(",\"session\":")
        Json.appendString(sb, h.session)
        sb.append(",\"pid\":").append(h.pid)
        sb.append(",\"started\":").append(h.started)
        sb.append('}')
    }

    fun header(h: LogHeader): String = StringBuilder(256).also { appendHeader(it, h) }.toString()

    fun appendEvent(sb: StringBuilder, e: LogEvent) {
        sb.append("{\"t\":").append(e.wallMs)
        sb.append(",\"m\":").append(e.monoNanos)
        sb.append(",\"l\":\"").append(e.level.code).append('"')
        sb.append(",\"c\":")
        Json.appendString(sb, e.category)
        sb.append(",\"msg\":")
        Json.appendString(sb, e.message)
        sb.append(",\"f\":{")
        var first = true
        for ((k, v) in e.fields) {
            if (!first) sb.append(',')
            first = false
            Json.appendString(sb, k)
            sb.append(':')
            Json.appendString(sb, v)
        }
        sb.append('}')
        sb.append(",\"th\":")
        Json.appendString(sb, e.thread)
        sb.append(",\"p\":").append(e.pid)
        sb.append(",\"s\":")
        Json.appendString(sb, e.session)
        val err = e.error
        if (err != null) {
            sb.append(",\"err\":")
            Json.appendString(sb, err)
        }
        sb.append('}')
    }

    fun event(e: LogEvent): String = StringBuilder(256).also { appendEvent(it, e) }.toString()

    fun fields(pairs: Array<out Pair<String, Any?>>): Map<String, String> {
        if (pairs.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>(pairs.size * 2)
        for (p in pairs) map[p.first] = value(p.second)
        return map
    }

    fun value(v: Any?): String {
        val s = try {
            v?.toString() ?: "null"
        } catch (_: Throwable) {
            "<${v?.javaClass?.name}>"
        }
        return truncate(s, MAX_VALUE)
    }

    /** Cuts [s] to at most [max] chars, the last being an ellipsis, without splitting a surrogate pair. */
    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) + '\u2026'
    }

    fun stackTrace(t: Throwable): String = try {
        truncate(t.stackTraceToString(), MAX_ERROR)
    } catch (_: Throwable) {
        t.javaClass.name
    }

    /** A duration in ms with two decimals, e.g. "12.34". */
    fun millis(nanos: Long): String {
        val hundredths = nanos.coerceAtLeast(0) / 10_000
        val frac = hundredths % 100
        return "${hundredths / 100}.${if (frac < 10) "0" else ""}$frac"
    }

    fun logcatMessage(message: String, fields: Map<String, String>): String {
        if (fields.isEmpty()) return message
        val sb = StringBuilder(message.length + 16 * fields.size).append(message)
        for ((k, v) in fields) sb.append(' ').append(k).append('=').append(v)
        return sb.toString()
    }

    fun parse(line: String): LogEvent? {
        val s = line.trim()
        if (!s.startsWith("{")) return null
        return try {
            val o = JSONObject(s)
            if (o.has("schema")) return null
            val level = levelOf(o.getString("l")) ?: return null
            val f = o.optJSONObject("f")
            val fields = if (f == null || f.length() == 0) {
                emptyMap()
            } else {
                val map = LinkedHashMap<String, String>()
                val keys = f.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = f.optString(k)
                }
                map
            }
            LogEvent(
                wallMs = o.getLong("t"),
                monoNanos = o.optLong("m", 0L),
                level = level,
                category = o.getString("c"),
                message = o.getString("msg"),
                fields = fields,
                thread = o.optString("th", ""),
                pid = o.optInt("p", 0),
                session = o.optString("s", ""),
                error = if (o.has("err") && !o.isNull("err")) o.getString("err") else null,
            )
        } catch (_: Exception) {
            null
        }
    }
}
