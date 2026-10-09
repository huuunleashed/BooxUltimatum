package app.booxultimatum.ui.screens

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.battery.BatteryModel
import app.booxultimatum.core.battery.BatteryView
import app.booxultimatum.core.battery.Episode
import app.booxultimatum.core.battery.Finding
import app.booxultimatum.core.battery.VerdictState
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.TuningScale
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.ui.Destination
import app.booxultimatum.ui.battery.BatteryAlertsPlate
import app.booxultimatum.ui.battery.BatteryText
import app.booxultimatum.ui.battery.DayStrip
import app.booxultimatum.ui.battery.EpisodeRow
import app.booxultimatum.ui.battery.FindingRow
import app.booxultimatum.ui.battery.VerdictBlock
import app.booxultimatum.ui.battery.episodesOverlapping
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/** The window the battery pages read: the last 24 hours. */
internal const val BATTERY_WINDOW_MS = 24 * 3_600_000L

private const val MINUTE_MS = 60_000L
private const val PER_PAGE = 9
private const val RECHECK_MS = 2_000L

private class TodayReading(val view: BatteryView, val snapshot: BatterySnapshot?)

/** Today: whether the battery is OK, how long it lasts, and what happened in the last 24 hours. */
@Composable
fun BatteryTodayScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit, onOpen: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rereads by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val reading = rememberReading(readKey to rereads) {
        val fresh = readKey > 0 || rereads > 0
        TodayReading(BatteryModel.load(context, BATTERY_WINDOW_MS, fresh = fresh), runCatching { BatterySnapshot.read(context) }.getOrNull())
    }
    val episodes = remember(reading) { reading?.let { windowEpisodes(it.view) }.orEmpty() }
    val listed = remember(episodes) { episodes.indices.reversed().filter { episodes[it].endMs - episodes[it].startMs >= MINUTE_MS } }
    fun choose(index: Int?) {
        selected = index
        index?.let { i -> listed.indexOf(i).takeIf { it >= 0 }?.let { page = it / PER_PAGE } }
    }
    val openFinding: (Finding) -> Unit = { f ->
        val inside = episodes.indexOfFirst { it.startMs <= f.startMs && f.startMs < it.endMs }
        (if (inside >= 0) inside else episodes.indexOfLast { it.startMs <= f.startMs }).takeIf { it >= 0 }?.let { choose(it) }
    }

    InstrumentPage(compact) {
        item { ScreenHeader(stringResource(R.string.dest_battery), null) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) } }
        if (reading == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val view = reading.view
        val verdict = view.verdict
        val others = verdict.findings.drop(if (verdict.state == VerdictState.Watch || verdict.state == VerdictState.Problem) 1 else 0)
        val pages = ((listed.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val safePage = page.coerceIn(0, pages - 1)
        val open = if (episodes.isEmpty()) null else openFinding

        item { ReadLine(view) }
        item {
            Spacer(Modifier.height(Space.l))
            VerdictBlock(verdict, onOpenFinding = open)
        }
        items(others.take(3)) { f -> FindingRow(f, onOpen = open) }
        if (others.size > 3) item {
            Text(
                pluralStringResource(R.plurals.bd_more_findings, others.size - 3, others.size - 3),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Legend,
                modifier = Modifier.padding(top = Space.s),
            )
        }
        item {
            Spacer(Modifier.height(Space.xxl))
            Plate(stringResource(R.string.bd_plate_now)) {
                reading.snapshot?.let { b ->
                    TuningScale(b.levelPct)
                    Spacer(Modifier.height(Space.l))
                    StatusLines(b)
                }
                TimeLeft(view, reading.snapshot)
            }
        }
        if (view.rows.isEmpty()) return@InstrumentPage

        item {
            Spacer(Modifier.height(Space.l))
            Text(
                stringResource(R.string.bd_recorded, hours(context, verdict.recordedHours.toInt()), hours(context, verdict.windowHours.roundToInt())),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Legend,
            )
        }
        if (view.signals.hubBackgroundAllowed == false) item {
            Text(stringResource(R.string.bd_background_restricted), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
            Key(
                stringResource(R.string.bd_allow_background),
                onClick = {
                    BatteryLog.ensureNotRestricted(context)
                    scope.launch { delay(RECHECK_MS); rereads++ }
                },
                modifier = Modifier.padding(top = Space.s),
            )
        }
        if (!view.signals.recordingEnabled) item {
            Text(stringResource(R.string.bd_recording_paused), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
            Key(stringResource(R.string.dest_battery_history), onClick = { onOpen(Destination.BatteryHistory) }, modifier = Modifier.padding(top = Space.s))
        }

        item {
            Spacer(Modifier.height(Space.xxl))
            Plate(stringResource(R.string.bd_plate_last_24h)) {
                DayStrip(view.timeline, fromMs = view.nowMs - BATTERY_WINDOW_MS, toMs = view.nowMs, selected = selected, onSelect = { choose(it) })
            }
        }
        item {
            Spacer(Modifier.height(Space.xxl))
            Plate(stringResource(R.string.bd_plate_happened)) {
                if (listed.isEmpty()) Paragraph(stringResource(R.string.ds_none), color = Ink.Legend)
                listed.drop(safePage * PER_PAGE).take(PER_PAGE).forEach { i ->
                    EpisodeRow(episodes[i], expanded = selected == i, onToggle = { choose(if (selected == i) null else i) })
                }
                if (pages > 1) Pager(safePage, pages, onPage = { page = it })
            }
        }
        item {
            Spacer(Modifier.height(Space.xxl))
            BatteryAlertsPlate()
        }
    }
}

/** The episodes of the window, oldest first: the order the strip's `selected` index counts in. */
private fun windowEpisodes(view: BatteryView): List<Episode> =
    episodesOverlapping(view.timeline.episodes, view.nowMs - BATTERY_WINDOW_MS, view.nowMs)

@Composable
private fun ReadLine(view: BatteryView) {
    val context = LocalContext.current
    val clock = Format.clock(context, view.nowMs)
    val first = view.rows.minOfOrNull { it.epoch }
    val text = if (first == null) {
        stringResource(R.string.bd_read_at_empty, clock)
    } else {
        val zone = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(first).atZone(zone).toLocalDate(), Instant.ofEpochMilli(view.nowMs).atZone(zone).toLocalDate()).toInt()
        stringResource(R.string.bd_read_at_since, clock, shortDate(context, first), pluralStringResource(R.plurals.bd_days, days, days))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
}

/** The time left: the charge rate while charging, else the estimate with its basis, else what is still missing. */
@Composable
internal fun TimeLeft(view: BatteryView, battery: BatterySnapshot?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val estimate = view.estimate
    val rate = battery?.currentNowMa
    val top = modifier.padding(top = Space.s)
    when {
        battery?.charging == true -> Text(
            if (rate == null) stringResource(R.string.bd_rate_unknown) else stringResource(R.string.bd_charging_at, BatteryText.ma(context, abs(rate))),
            style = MaterialTheme.typography.titleMedium,
            modifier = top,
        )
        estimate == null -> Text(stringResource(R.string.bd_needs_day), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = top)
        else -> {
            val days = estimate.basisDays.roundToInt()
            Text(stringResource(R.string.bd_estimate_about, hours(context, estimate.hours.roundToInt().coerceAtLeast(1))), style = MaterialTheme.typography.titleMedium, modifier = top)
            Text(
                pluralStringResource(
                    R.plurals.bd_estimate_range,
                    days,
                    hours(context, estimate.lowHours.roundToInt().coerceAtLeast(1)),
                    hours(context, estimate.highHours.roundToInt().coerceAtLeast(1)),
                    days,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Legend,
            )
        }
    }
}

private fun hours(context: Context, value: Int): String = context.getString(R.string.bd_hours, value)

private fun shortDate(context: Context, ms: Long): String {
    val locale = context.resources.configuration.locales[0]
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMM d"), locale).format(Date(ms))
}
