package app.booxultimatum.kit.update

/**
 * A semantic version: `major.minor.patch`, optionally with a pre-release such as `0.6.0-test.3`. A pre-release comes
 * before its release, and its dot-separated parts compare numerically when both are numbers, otherwise as text, with
 * numbers first (Semantic Versioning 2.0, section 11). Build metadata after `+` is ignored.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<Version> {
    val isPreRelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(value: String): Version? {
            val m = PATTERN.matchEntire(value.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return Version(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt(), pre.split('.').filter { it.isNotEmpty() })
        }
    }
}
