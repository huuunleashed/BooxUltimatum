package app.booxultimatum.kit.log

import java.security.MessageDigest

/** Privacy helpers for values that must not appear in logs as they are. */
object Redact {
    private const val HEX = "0123456789abcdef"
    private val KNOWN_PREFIXES = arrayOf("app.booxultimatum", "com.onyx", "com.android", "android")

    /** Short stable hash (first 10 hex chars of SHA-256) for names that must not appear in logs, e.g. file names. */
    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(10)
        for (i in 0 until 5) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
        return sb.toString()
    }

    /**
     * The package name if it's in [allowed], or is or sits under "app.booxultimatum", "com.onyx", "com.android" or
     * "android"; else "other".
     */
    fun pkg(packageName: String?, allowed: Set<String> = emptySet()): String {
        if (packageName.isNullOrBlank()) return OTHER
        if (packageName in allowed) return packageName
        for (prefix in KNOWN_PREFIXES) {
            if (packageName == prefix || (packageName.startsWith(prefix) && packageName[prefix.length] == '.')) {
                return packageName
            }
        }
        return OTHER
    }

    private const val OTHER = "other"
}
