package app.booxultimatum.kit.log

import java.io.File

internal class FakeClock(var wall: Long = T0, var mono: Long = 1_000_000_000L) : LogClock {
    override fun wallMs(): Long = wall
    override fun monoNanos(): Long = mono
}

/** 2026-09-28 03:15:00 UTC. */
internal const val T0 = 1_790_565_300_000L

internal fun event(
    message: String = "hello",
    level: Level = Level.Info,
    category: String = "test",
    fields: Map<String, String> = emptyMap(),
    error: String? = null,
    wallMs: Long = T0,
) = LogEvent(
    wallMs = wallMs,
    monoNanos = 123_456_789L,
    level = level,
    category = category,
    message = message,
    fields = fields,
    thread = "main",
    pid = 4242,
    session = "0badc0de",
    error = error,
)

internal fun header(pid: Int = 4242) = LogHeader(
    app = "app.booxultimatum.test",
    version = "1.2.3",
    versionCode = 42,
    debuggable = false,
    device = "ONYX NoteAir6C",
    android = "16 (36)",
    session = "0badc0de",
    pid = pid,
    started = T0,
)

/** Events of every event file in [dir], oldest file first; header lines checked and skipped. */
internal fun readEvents(dir: File): List<LogEvent> =
    LogFiles.list(dir).filter { LogFiles.isEvent(it.name) }.reversed().flatMap { f ->
        val lines = f.readLines().filter { it.isNotBlank() }
        check(lines.first().startsWith("{\"schema\":1,")) { "no header in ${f.name}" }
        lines.drop(1).map { requireNotNull(EventFormat.parse(it)) { "unparsable: $it" } }
    }
