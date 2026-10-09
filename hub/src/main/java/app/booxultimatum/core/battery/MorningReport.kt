package app.booxultimatum.core.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import app.booxultimatum.MainActivity
import app.booxultimatum.R
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.ui.battery.BatteryText
import java.util.Date
import java.util.concurrent.Executors

/** One night, as numbers and the findings that overlap it. The composition is pure: the tests call [MorningReport.compose]. */
data class ReportInput(
    val sleepMs: Long,
    val level0: Int,
    val level1: Int,
    /** The death when the battery ran out in the span, else the worst finding that overlaps it. */
    val finding: Finding?,
    /** The display-on finding before the death, when [finding] is the death. */
    val before: Finding?,
    /** The guard's record, when it acted within the span. */
    val guard: GuardRecord?,
)

sealed class ReportContent {
    data class Normal(val sleepMs: Long, val level0: Int, val level1: Int, val guard: GuardRecord?) : ReportContent()
    data class WithFinding(val finding: Finding, val guard: GuardRecord?) : ReportContent()
    data class Died(val finding: Finding, val before: Finding?) : ReportContent()
}

/**
 * The morning report (docs §4.7): one quiet notification after a night on battery, and one after a boot that follows the
 * battery running out. The notification has a fixed id, so a newer report replaces the older one.
 */
object MorningReport {
    /** Android 13's permission by name, as InkScreen has it: the constant would inline into the older levels. */
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val CHANNEL = "battery_report"
    private const val NOTIFICATION_ID = 61
    private const val OPEN_REQUEST = 62
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val MIN_SLEEP_MS = 4 * HOUR_MS
    private const val MIN_WINDOW_MS = 6 * HOUR_MS
    private const val MERGE_GAP_MS = MINUTE_MS
    private const val DIED_WITHIN_MS = 12 * HOUR_MS
    private const val BOOT_WINDOW_MS = 24 * HOUR_MS
    private const val PREVIEW_WINDOW_MS = 48 * HOUR_MS
    private val log = Logbook.logger("battery")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "battery-report").apply { isDaemon = true } }

    /** A night qualifies at four hours or more, on battery for most of it: charging for less than half. */
    fun qualifies(sleepMs: Long, chargingMs: Long): Boolean = sleepMs >= MIN_SLEEP_MS && chargingMs * 2 < sleepMs

    /** Time on the charger inside [fromMs, toMs]. */
    fun chargingMs(episodes: List<Episode>, fromMs: Long, toMs: Long): Long = episodes
        .filter { it.kind == EpisodeKind.Charging }
        .sumOf { (minOf(it.endMs, toMs) - maxOf(it.startMs, fromMs)).coerceAtLeast(0L) }

    /** The spans of sleep: Asleep and DisplayOn episodes, joined while they follow each other. */
    fun sleepSpans(episodes: List<Episode>): List<Pair<Long, Long>> {
        val spans = mutableListOf<Pair<Long, Long>>()
        for (e in episodes.sortedBy { it.startMs }) {
            if (e.kind != EpisodeKind.Asleep && e.kind != EpisodeKind.DisplayOn) continue
            val last = spans.lastOrNull()
            if (last != null && e.startMs - last.second <= MERGE_GAP_MS) {
                spans[spans.lastIndex] = last.first to maxOf(last.second, e.endMs)
            } else {
                spans += e.startMs to e.endMs
            }
        }
        return spans
    }

    fun compose(input: ReportInput): ReportContent {
        val finding = input.finding
        return when {
            finding != null && finding.kind == FindingKind.Died -> ReportContent.Died(finding, input.before)
            finding != null -> ReportContent.WithFinding(finding, input.guard)
            else -> ReportContent.Normal(input.sleepMs, input.level0, input.level1, input.guard)
        }
    }

    /** Before Android 13 notifications need no permission; from 13 they need the owner's yes. */
    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The tablet woke after [sleptSinceMs]. Returns at once; the log is read on the report's own thread. */
    fun onWoke(context: Context, sleptSinceMs: Long) {
        val app = context.applicationContext
        if (!BatteryAlerts.reportEnabled(app)) return
        val nowMs = System.currentTimeMillis()
        worker.execute {
            runCatching { wokeReport(app, sleptSinceMs, nowMs) }.onFailure { log.w("morning report failed", error = it) }
        }
    }

    /** The app started after a real boot. Returns at once; a death in the last 12 hours is reported once. */
    fun onBooted(context: Context) {
        val app = context.applicationContext
        if (!BatteryAlerts.reportEnabled(app)) return
        val nowMs = System.currentTimeMillis()
        worker.execute {
            runCatching { bootedReport(app, nowMs) }.onFailure { log.w("ran-out report failed", error = it) }
        }
    }

    /**
     * Posts the longest sleep of the last 48 hours now, whatever the switch and the dedupe say. Blocking: call it off the
     * main thread. False when the log has no sleep of four hours.
     */
    fun previewNow(context: Context): Boolean {
        val app = context.applicationContext
        val nowMs = System.currentTimeMillis()
        val view = BatteryModel.load(app, PREVIEW_WINDOW_MS, fresh = true, nowMs = nowMs)
        val (fromMs, toMs) = sleepSpans(view.timeline.episodes)
            .maxByOrNull { it.second - it.first }
            ?.takeIf { it.second - it.first >= MIN_SLEEP_MS } ?: return false
        val level0 = levelAt(view.rows, fromMs) ?: return false
        val level1 = levelAt(view.rows, toMs) ?: return false
        post(app, compose(inputFor(view, guardWithin(app, fromMs, toMs), fromMs, toMs, level0, level1)))
        return true
    }

    private fun wokeReport(app: Context, sleptSinceMs: Long, nowMs: Long) {
        val sleepMs = nowMs - sleptSinceMs
        if (sleepMs < MIN_SLEEP_MS || BatteryAlerts.lastReportKey(app) == sleptSinceMs) return
        val view = BatteryModel.load(app, maxOf(sleepMs + 30 * MINUTE_MS, MIN_WINDOW_MS), fresh = true, nowMs = nowMs)
        if (!qualifies(sleepMs, chargingMs(view.timeline.episodes, sleptSinceMs, nowMs))) return
        val level0 = levelAt(view.rows, sleptSinceMs) ?: return
        val level1 = levelAt(view.rows, nowMs) ?: return
        BatteryAlerts.setLastReportKey(app, sleptSinceMs)
        send(app, compose(inputFor(view, guardWithin(app, sleptSinceMs, nowMs), sleptSinceMs, nowMs, level0, level1)))
    }

    private fun bootedReport(app: Context, nowMs: Long) {
        val view = BatteryModel.load(app, BOOT_WINDOW_MS, fresh = true, nowMs = nowMs)
        val findings = view.verdict.findings
        val died = findings
            .filter { it.kind == FindingKind.Died && it.endMs >= nowMs - DIED_WITHIN_MS }
            .maxByOrNull { it.startMs } ?: return
        send(app, ReportContent.Died(died, displayOnBefore(findings, died)))
    }

    /** One report per death, whichever path (a night's wake or a boot) gets to it first. */
    private fun send(app: Context, content: ReportContent) {
        if (!notificationsAllowed(app)) return
        if (content is ReportContent.Died) {
            if (BatteryAlerts.lastDiedKey(app) == content.finding.startMs) return
            BatteryAlerts.setLastDiedKey(app, content.finding.startMs)
        }
        post(app, content)
    }

    private fun inputFor(view: BatteryView, guard: GuardRecord?, fromMs: Long, toMs: Long, level0: Int, level1: Int): ReportInput {
        val findings = view.verdict.findings
        val overlapping = findings.filter { it.startMs < toMs && it.endMs > fromMs }
        val died = overlapping.firstOrNull { it.kind == FindingKind.Died }
        return ReportInput(
            sleepMs = toMs - fromMs,
            level0 = level0,
            level1 = level1,
            finding = died ?: overlapping.firstOrNull(),
            before = died?.let { displayOnBefore(findings, it) },
            guard = guard,
        )
    }

    private fun displayOnBefore(findings: List<Finding>, died: Finding): Finding? =
        findings.filter { it.kind == FindingKind.DisplayOn && it.startMs < died.startMs }.maxByOrNull { it.startMs }

    private fun guardWithin(app: Context, fromMs: Long, toMs: Long): GuardRecord? =
        BatteryAlerts.lastGuard(app)?.takeIf { it.atMs in fromMs..toMs }

    /** The battery level the log had at [atMs]: the last row at or before it, else the first row after. */
    private fun levelAt(rows: List<BatteryRow>, atMs: Long): Int? =
        rows.filter { it.epoch <= atMs }.maxByOrNull { it.epoch }?.level ?: rows.minByOrNull { it.epoch }?.level

    private fun post(app: Context, content: ReportContent) {
        if (!notificationsAllowed(app)) return
        val (title, text) = texts(app, content)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.ba_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(
            app,
            OPEN_REQUEST,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "Battery").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_ink)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(NOTIFICATION_ID, note)
        log.i("morning report posted")
    }

    private fun texts(app: Context, content: ReportContent): Pair<String, String> = when (content) {
        is ReportContent.Normal -> {
            val title = app.getString(R.string.ba_normal_title, BatteryText.duration(app, content.sleepMs), maxOf(0, content.level0 - content.level1))
            val guard = content.guard?.let { guardClause(app, it) }
            val text = if (guard == null) {
                app.getString(R.string.ba_normal_text, content.level0, content.level1)
            } else {
                app.getString(R.string.ba_normal_text_guard, content.level0, content.level1, guard)
            }
            title to text
        }
        is ReportContent.WithFinding -> {
            val evidence = evidence(app, content.finding)
            val text = content.guard?.let { app.getString(R.string.ba_finding_text, evidence, guardClause(app, it)) } ?: evidence
            BatteryText.findingTitle(app, content.finding) to text
        }
        is ReportContent.Died -> {
            val died = app.getString(R.string.ba_died_text, clock(app, content.finding.startMs), BatteryText.day(app, content.finding.startMs))
            val before = content.before?.let { app.getString(R.string.ba_before_text, BatteryText.findingTitle(app, it), evidence(app, it)) }
            BatteryText.findingTitle(app, content.finding) to listOfNotNull(died, before).joinToString(" ")
        }
    }

    /** The evidence line without the app or wake lock it names: a notification carries no app names. */
    private fun evidence(app: Context, finding: Finding): String = BatteryText.findingEvidence(app, finding.copy(subject = null))

    private fun guardClause(app: Context, guard: GuardRecord): String {
        val step = guard.healedByStep ?: return app.getString(R.string.ba_guard_tried)
        val what = when (step) {
            1 -> R.string.ba_step_refresh
            2 -> R.string.ba_step_lock
            else -> R.string.ba_step_key
        }
        return app.getString(R.string.ba_guard_healed, app.getString(what))
    }

    private fun clock(app: Context, ms: Long): String = DateFormat.getTimeFormat(app).format(Date(ms))
}
