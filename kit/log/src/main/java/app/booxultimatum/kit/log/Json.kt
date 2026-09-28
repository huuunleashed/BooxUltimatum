package app.booxultimatum.kit.log

/** The small JSON writer the log format needs: strings escaped by hand, no intermediate objects. */
internal object Json {
    private const val HEX = "0123456789abcdef"

    /** Appends [value] as a quoted JSON string. Control characters and unpaired surrogates become \uXXXX. */
    fun appendString(sb: StringBuilder, value: CharSequence) {
        sb.append('"')
        val n = value.length
        var i = 0
        while (i < n) {
            val c = value[i]
            when {
                c == '"' -> sb.append('\\').append('"')
                c == '\\' -> sb.append('\\').append('\\')
                c < ' ' -> appendEscape(sb, c)
                Character.isHighSurrogate(c) ->
                    if (i + 1 < n && Character.isLowSurrogate(value[i + 1])) {
                        sb.append(c).append(value[i + 1])
                        i++
                    } else {
                        appendEscape(sb, c)
                    }
                Character.isLowSurrogate(c) -> appendEscape(sb, c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    /** The UTF-8 size of [s], counted without encoding it. */
    fun utf8Length(s: CharSequence): Int {
        var bytes = 0
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i].code
            bytes += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                Character.isHighSurrogate(s[i]) && i + 1 < n && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    private fun appendEscape(sb: StringBuilder, c: Char) {
        val v = c.code
        sb.append('\\').append('u')
            .append(HEX[(v shr 12) and 0xF])
            .append(HEX[(v shr 8) and 0xF])
            .append(HEX[(v shr 4) and 0xF])
            .append(HEX[v and 0xF])
    }
}
