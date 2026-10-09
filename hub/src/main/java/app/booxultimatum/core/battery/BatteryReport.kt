package app.booxultimatum.core.battery

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.booxultimatum.BuildConfig
import app.booxultimatum.R
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.ui.battery.BatteryText
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * The battery report, shared from the Battery section: the last 72 hours of the log, the findings with their evidence
 * and a plain summary, in one zip. Usage is behaviour, so app names stay out unless the owner includes them.
 */
object BatteryReport {
    private const val WINDOW_MS = 72L * 3_600_000
    private const val PREFIX = "battery-report-"
    private const val HEADER_START = "epoch,"
    private val APP_KEYS = listOf("alarmWakeups", "wakelockAcquisitions", "uidDelta", "wakelocks", "foregroundSec")

    /**
     * Writes the report into the folder the Logs export shares from, replacing earlier reports, and returns it. Blocking:
     * call off the main thread.
     */
    fun build(context: Context, includeApps: Boolean, nowMs: Long = System.currentTimeMillis()): File {
        val app = context.applicationContext
        val since = nowMs - WINDOW_MS
        val logs = BatteryLog.dir(app)
        val rows = logs.listFiles { f -> f.name.startsWith("battery-") && f.name.endsWith(".csv") }.orEmpty()
            .sortedBy { it.name }
            .flatMap { f -> f.useLines { filterRows(it, since, includeApps) } }
        val snapshots = deepFilesFor(logs.list().orEmpty().toList(), since, nowMs, ZoneId.systemDefault())
            .flatMap { name -> File(logs, name).useLines { filterDeep(it, since, includeApps) } }
        val view = BatteryModel.load(app, WINDOW_MS, fresh = true, nowMs = nowMs)
        val dir = File(app.getExternalFilesDir(null), "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { it.delete() }
        val zip = File(dir, PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowMs)) + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.add("summary.txt", summary(app, view, includeApps, nowMs))
            z.add("battery-rows.csv", rows.joinToString("") { "$it\n" })
            z.add("deep.jsonl", snapshots.joinToString("") { "$it\n" })
            z.add("README.txt", readme(includeApps))
        }
        return zip
    }

    /** The share sheet for a zip in the Logs export's folder, sent the way the Logs screen sends its own. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The log's lines the report keeps: every header line, and the rows at or after [sinceMs]. Without app names the
     * `top` column is blank, and a note is kept only for display rows and, with app names, for marks. A row that doesn't
     * parse, or comes before any header, is dropped.
     */
    internal fun filterRows(lines: Sequence<String>, sinceMs: Long, includeApps: Boolean): List<String> {
        var columns: ReportColumns? = null
        val out = ArrayList<String>()
        for (line in lines) {
            if (line.startsWith(HEADER_START)) {
                out += line
                columns = ReportColumns.of(line)
                continue
            }
            val cols = columns ?: continue
            val values = line.split(',').toMutableList()
            if (values.size < cols.width) continue
            val epoch = values.getOrNull(cols.epoch)?.trim()?.toLongOrNull() ?: continue
            if (epoch < sinceMs) continue
            if (!includeApps) values.blank(cols.top)
            if (!keepsNote(values.getOrNull(cols.reason)?.trim().orEmpty(), includeApps)) values.blank(cols.note)
            out += values.joinToString(",")
        }
        return out
    }

    /** The deep snapshot lines at or after [sinceMs], each through [redactDeep]. */
    internal fun filterDeep(lines: Sequence<String>, sinceMs: Long, includeApps: Boolean): List<String> = lines.mapNotNull { line ->
        val epoch = runCatching { JSONObject(line).getLong("epoch") }.getOrNull() ?: return@mapNotNull null
        if (epoch < sinceMs) null else redactDeep(line, includeApps)
    }.toList()

    /**
     * One deep snapshot for the report: without app names the per-app tables are removed, and the line is kept as it is
     * with them. Null when the line isn't a JSON object.
     */
    internal fun redactDeep(line: String, includeApps: Boolean): String? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (includeApps) return line
        APP_KEYS.forEach { json.remove(it) }
        return json.toString()
    }

    private fun keepsNote(reason: String, includeApps: Boolean): Boolean = when (reason) {
        "display_on", "display_stuck" -> true
        "mark" -> includeApps
        else -> false
    }

    private fun summary(context: Context, view: BatteryView, includeApps: Boolean, nowMs: Long): String {
        val res = context.resources
        val baseline = view.baseline
        val estimate = view.estimate
        return buildString {
            appendLine("BooxUltimatum battery report")
            appendLine("Made ${stamp(nowMs)}, covering the last ${BatteryText.duration(context, WINDOW_MS)}.")
            appendLine("App names: ${if (includeApps) "included" else "left out"}.")
            appendLine()
            appendLine("Tablet: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Firmware: ${Build.DISPLAY}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("BooxUltimatum: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("Verdict: ${BatteryText.verdictTitle(context, view.verdict.state)}")
            appendLine("Recorded ${BatteryText.duration(context, hoursMs(view.verdict.recordedHours))} of ${BatteryText.duration(context, WINDOW_MS)}.")
            if (view.verdict.findings.isEmpty()) appendLine("No findings in this window.")
            for (f in view.verdict.findings) {
                appendLine()
                appendLine("${BatteryText.severityLabel(context, f.severity)}: ${BatteryText.findingTitle(context, f)}")
                appendLine("  ${BatteryText.findingEvidence(context, f.withoutNames(includeApps))}")
            }
            appendLine()
            appendLine("What is normal on this tablet")
            val capacity = view.timeline.capacityMah
            appendLine(
                baseline.quietAsleepMa?.let { "  Asleep with the screen off: ${BatteryText.ma(context, it)}, ${BatteryText.pctPerHour(context, it, capacity)}" }
                    ?: "  Asleep with the screen off: not enough data yet",
            )
            baseline.dayToDaySpreadMa?.let { appendLine("  Day to day it varies by about ${BatteryText.ma(context, it)}") }
            baseline.inUseMa?.let { appendLine("  In use, on average: ${BatteryText.ma(context, it)}") }
            baseline.asleepShare?.let { appendLine("  Asleep for ${context.getString(R.string.bt_percent, (it * 100).roundToInt())} of unplugged time") }
            val days = baseline.basisDays.roundToInt()
            appendLine("  Based on ${res.getQuantityString(R.plurals.bd_days, days, days)} of unplugged log")
            appendLine()
            appendLine("Time left")
            if (estimate == null) {
                appendLine("  ${context.getString(R.string.bd_needs_day)}")
            } else {
                val basis = estimate.basisDays.roundToInt().coerceAtLeast(1)
                val range = res.getQuantityString(
                    R.plurals.bd_estimate_range, basis,
                    BatteryText.duration(context, hoursMs(estimate.lowHours)), BatteryText.duration(context, hoursMs(estimate.highHours)), basis,
                )
                appendLine("  ${context.getString(R.string.bd_estimate_about, BatteryText.duration(context, hoursMs(estimate.hours)))} ($range)")
            }
        }
    }

    private fun readme(includeApps: Boolean): String {
        val names = if (includeApps) {
            "App names are included, because Include app names was ticked."
        } else {
            "App names are left out: the top column and the labels of marks are blank, the per-app tables are removed from the snapshots, and the findings name no app."
        }
        return "This report was made by BooxUltimatum from the battery log of the last 72 hours. summary.txt says in plain words " +
            "what the log shows: the verdict, the findings with their evidence, what is normal on this tablet and the time left. " +
            "battery-rows.csv holds the log’s rows, each read under the column names of its own header line (a full log export’s " +
            "README explains the columns), and deep.jsonl holds the deep snapshots of the same time. $names\n"
    }

    private fun Finding.withoutNames(includeApps: Boolean): Finding =
        if (includeApps || (kind != FindingKind.AppHeavy && kind != FindingKind.WakeLock)) this else copy(subject = null)

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.US).format(Date(ms))

    private fun hoursMs(hours: Double): Long = (hours * 3_600_000).toLong()

    private fun ZipOutputStream.add(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }
}

/** The positions of the columns the report reads, from one header line. -1 when a layout doesn't have the column. */
private class ReportColumns(val width: Int, val epoch: Int, val reason: Int, val top: Int, val note: Int) {
    companion object {
        fun of(header: String): ReportColumns {
            val names = header.split(',').map { it.trim() }
            return ReportColumns(names.size, names.indexOf("epoch"), names.indexOf("reason"), names.indexOf("top"), names.indexOf("note"))
        }
    }
}

private fun MutableList<String>.blank(at: Int) {
    if (at in indices) this[at] = ""
}
