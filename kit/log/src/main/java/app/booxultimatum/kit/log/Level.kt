package app.booxultimatum.kit.log

/** Severity of a log event, lowest first. */
enum class Level { Verbose, Debug, Info, Warn, Error }

/** The one-letter code written as "l" in every event line. */
internal val Level.code: Char
    get() = when (this) {
        Level.Verbose -> 'V'
        Level.Debug -> 'D'
        Level.Info -> 'I'
        Level.Warn -> 'W'
        Level.Error -> 'E'
    }

internal fun levelOf(code: String): Level? = when (code) {
    "V" -> Level.Verbose
    "D" -> Level.Debug
    "I" -> Level.Info
    "W" -> Level.Warn
    "E" -> Level.Error
    else -> null
}
