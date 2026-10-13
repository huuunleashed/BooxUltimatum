package app.booxultimatum.core.battery

import android.content.Context
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.Journal
import app.booxultimatum.core.SystemState
import app.booxultimatum.core.exec.Diagnostics
import app.booxultimatum.kit.log.LogEvent
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Watches the processor while the tablet sleeps (docs/11-not-resting.md). The keep-alive's alarm already wakes the tablet every
 * 30 minutes; at each one it measures how much of the stretch since the last the processor was awake, and hands the
 * verdict here. Only two verdicts do anything: [ProcessorPolicy.Verdict.Awake] (a row, a note, and for the first of a boot a
 * snapshot of why) and [ProcessorPolicy.Verdict.Rested] (the first of a boot writes a reference snapshot to compare with).
 * Reading `dumpsys` happens on a thread of its own, under a wake lock, and never on the alarm's.
 */
object ProcessorWatch {
    private const val PREFS = "processor_watch"
    private const val NOTED_SLEEP = "noted_sleep"
    private const val SNAP_BOOT = "snapshot_boot"
    private const val REF_BOOT = "reference_boot"
    private const val HOLD_MS = 25_000L
    private val log = Logbook.logger("battery")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "processor-watch").apply { isDaemon = true } }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Android's boot count. It also advances when Android's system process restarts, which is when the state this watches
     * for begins, so each restart gets its own note and its own reference.
     */
    internal fun bootKey(c: Context): Int = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** [sleptAtMs] is when the sleep began, so a sleep is noted once however many stretches of it were awake. Returns at once. */
    fun onSample(context: Context, verdict: ProcessorPolicy.Verdict, elapsedMs: Long, uptimeMs: Long, sleptAtMs: Long) {
        if (verdict != ProcessorPolicy.Verdict.Awake && verdict != ProcessorPolicy.Verdict.Rested) return
        val app = context.applicationContext
        val boot = bootKey(app)
        if (verdict == ProcessorPolicy.Verdict.Rested && prefs(app).getInt(REF_BOOT, Int.MIN_VALUE) == boot) return
        val lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BooxUltimatum:processor-watch")
        runCatching { lock.acquire(HOLD_MS) }
        worker.execute {
            try {
                runCatching { handle(app, verdict, elapsedMs, uptimeMs, sleptAtMs, boot) }.onFailure { log.w("processor watch failed", error = it) }
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    private fun handle(app: Context, verdict: ProcessorPolicy.Verdict, elapsedMs: Long, uptimeMs: Long, sleptAtMs: Long, boot: Int) {
        val now = System.currentTimeMillis()
        val power = runBlocking { SystemState.power(now, preferApp = true) }
        val display = PowerFactsParser.display(runBlocking { Diagnostics.dumpsys("display", preferApp = true) }.out)
        val idle = runBlocking { SystemState.doze(preferApp = true) }?.let { "${it.deep}/${it.light}" }
        val facts = power?.facts ?: PowerFacts()
        val held = power?.held.orEmpty()
        val pm = app.packageManager
        val packageOf: (Int) -> String? = { uid -> runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() }
        val p = prefs(app)
        if (verdict == ProcessorPolicy.Verdict.Rested) {
            BatteryLog.appendDeep(app, ProcessorSnapshot.json(now, "processor_ref", boot, null, null, null, facts, display, idle, held, packageOf))
            p.edit { putInt(REF_BOOT, boot) }
            return
        }
        val cause = if (ProcessorPolicy.blamedApp(held, Process.myUid()) != null) "app" else "system"
        val percent = ProcessorPolicy.awakePercent(elapsedMs, uptimeMs)
        val minutes = elapsedMs / 60_000L
        if (p.getInt(SNAP_BOOT, Int.MIN_VALUE) != boot) {
            BatteryLog.appendDeep(app, ProcessorSnapshot.json(now, "processor_awake", boot, percent, minutes, cause, facts, display, idle, held, packageOf))
            p.edit { putInt(SNAP_BOOT, boot) }
        }
        if (p.getLong(NOTED_SLEEP, 0L) == sleptAtMs) return
        p.edit { putLong(NOTED_SLEEP, sleptAtMs) }
        BatteryLog.record(app, "processor_awake", note = "awake=$percent% min=$minutes by=$cause")
        log.w("processor stayed awake while asleep", "percent" to percent, "min" to minutes, "by" to cause)
        if (cause == "system") RestartAdvice.noteProcessorAwake(app, elapsedMs, boot) else RestartAdvice.noteAppAwake(app, elapsedMs)
    }
}

/**
 * What the crash that restarted Android was, and what the hub itself did in the five minutes before it. Two restarts on a
 * tablet that runs the hub are not a control group, so this record is the only way to tell whether anything we do (a tweak
 * applied, a frozen app opened or refrozen, a Shizuku command, the sleep screen's service) precedes one.
 */
object RestartContext {
    private val EXCEPTION = Regex("""^[A-Za-z_][\w.$]*(Exception|Error|Throwable)\b""")
    private const val WINDOW_MS = 5 * 60_000L
    private const val AFTER_MS = 30_000L
    private const val MAX_ACTIONS = 40
    private const val MAX_LINE = 140

    /** The crash text's process and package lines and its exception with the first frames: system code, no app data. */
    internal fun crashHead(text: String, maxStack: Int = 14): List<String> {
        val lines = text.lines().map { it.trimEnd() }
        val head = lines.filter { it.startsWith("Process:") || it.startsWith("Package:") }.take(2)
        val at = lines.indexOfFirst { EXCEPTION.containsMatchIn(it) }
        val stack = if (at < 0) emptyList() else lines.drop(at).filter { it.isNotBlank() }.take(maxStack)
        return head + stack.map { it.take(MAX_LINE) }
    }

    /**
     * What the hub logged within five minutes before [restartAtMs] (and 30 seconds after), oldest first, as `-12s category
     * message fields`. The newest [MAX_ACTIONS] of them. Several sources may overlap, so equal lines are kept once.
     */
    internal fun hubActions(events: List<LogEvent>, journal: List<Pair<Long, String>>, restartAtMs: Long): List<String> {
        val from = restartAtMs - WINDOW_MS
        val to = restartAtMs + AFTER_MS
        val lines = events.filter { it.wallMs in from..to }.map { e ->
            val fields = e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            e.wallMs to "${e.category} ${e.message} $fields".trim()
        } + journal.filter { it.first in from..to }.map { it.first to "journal ${it.second}" }
        return lines.sortedBy { it.first }.map { (at, text) -> "${(at - restartAtMs) / 1000}s ${text.take(MAX_LINE)}" }.distinct().takeLast(MAX_ACTIONS)
    }

    /** Blocking: call off the main thread. Writes one `restart_context` record to the deep log. */
    fun capture(context: Context, restartAtMs: Long) {
        val app = context.applicationContext
        val entry = SignalReader.restartEntry(app, restartAtMs)
        val events = buildList {
            addAll(Logbook.recent(500))
            Logbook.files(app).filter { it.lastModified() >= restartAtMs - WINDOW_MS }.take(3).forEach { file ->
                runCatching { file.useLines { lines -> lines.mapNotNull { Logbook.parse(it) }.filter { it.wallMs in (restartAtMs - WINDOW_MS)..(restartAtMs + AFTER_MS) }.toList() } }
                    .getOrNull()?.let { addAll(it) }
            }
        }
        val journal = runCatching { Journal.entries(app).map { it.at to "${it.action} ${it.subject}" } }.getOrDefault(emptyList())
        val o = JSONObject()
            .put("epoch", System.currentTimeMillis())
            .put("reason", "restart_context")
            .put("restartAt", restartAtMs)
            .put("tag", entry?.first ?: "")
            .put("restartCrash", JSONArray(crashHead(entry?.second.orEmpty())))
            .put("restartHub", JSONArray(hubActions(events, journal, restartAtMs)))
        BatteryLog.appendDeep(app, o)
    }
}
