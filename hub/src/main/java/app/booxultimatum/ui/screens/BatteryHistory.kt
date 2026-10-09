package app.booxultimatum.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.battery.BatteryModel
import app.booxultimatum.core.battery.BatteryView
import app.booxultimatum.core.battery.DaySummary
import app.booxultimatum.core.battery.DischargeSession
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.core.battery.StateTotal
import app.booxultimatum.core.battery.Summaries
import app.booxultimatum.core.battery.Timeline
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.RateBar
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.Segmented
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.ui.battery.BatteryText
import app.booxultimatum.ui.battery.EarlierMeasurementsPlate
import app.booxultimatum.ui.battery.MarksPlate
import app.booxultimatum.ui.battery.RecordingPlate
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

private const val HISTORY_MINUTE_MS = 60_000L
private const val HISTORY_HOUR_MS = 3_600_000L
private const val HISTORY_DAY_MS = 24 * HISTORY_HOUR_MS
private const val HISTORY_PER_PAGE = 9
/** Index 2 of [HISTORY_WINDOWS_MS]: 7 days. */
private const val HISTORY_DEFAULT_WINDOW = 2
private const val HISTORY_MIN_ASLEEP_HOURS = 1.0
private const val HISTORY_HATCH_MS = 10 * HISTORY_MINUTE_MS
private const val HISTORY_MIN_SESSION_MS = 15 * HISTORY_MINUTE_MS
private const val HISTORY_DISPLAY_TAG_HOURS = 0.17
private val HISTORY_WINDOWS_MS = listOf(HISTORY_DAY_MS, 3 * HISTORY_DAY_MS, 7 * HISTORY_DAY_MS, 30 * HISTORY_DAY_MS)
private val UNPLUGGED_KINDS = setOf(EpisodeKind.InUse, EpisodeKind.Asleep, EpisodeKind.DisplayOn)

/** One local day's quiet-asleep draw: the charge drawn per hour while asleep. [displayOn] when the display was on while asleep for 10 minutes or more that day. */
internal data class DayStandby(val dayStartMs: Long, val ma: Double, val asleepHours: Double, val displayOn: Boolean)

/**
 * The quiet-asleep draw of each local day with at least an hour asleep, oldest first. Asleep episodes are split at local
 * midnight, so one that crosses it counts on both days. A DisplayOn episode of 10 minutes or more marks each day it touches.
 */
internal fun dailyStandby(timeline: Timeline, zone: TimeZone): List<DayStandby> {
    val zoneId = zone.toZoneId()
    val hoursByDay = sortedMapOf<Long, Double>()
    val mahByDay = mutableMapOf<Long, Double>()
    val displayOn = mutableSetOf<Long>()
    for (e in timeline.episodes) {
        val mah = e.mah
        if (e.kind == EpisodeKind.Asleep && mah != null) {
            val span = (e.endMs - e.startMs).toDouble()
            for ((day, ms) in piecesByDay(e.startMs, e.endMs, zoneId)) {
                hoursByDay[day] = (hoursByDay[day] ?: 0.0) + ms.toDouble() / HISTORY_HOUR_MS
                mahByDay[day] = (mahByDay[day] ?: 0.0) + mah * ms / span
            }
        } else if (e.kind == EpisodeKind.DisplayOn && e.endMs - e.startMs >= HISTORY_HATCH_MS) {
            piecesByDay(e.startMs, e.endMs, zoneId).forEach { displayOn += it.first }
        }
    }
    return hoursByDay.filter { it.value >= HISTORY_MIN_ASLEEP_HOURS }.map { (day, hours) ->
        DayStandby(day, mahByDay.getValue(day) / hours, hours, day in displayOn)
    }
}

/** The middle of the sorted values (the mean of the two middle ones for an even count); null for none. */
internal fun medianOf(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/** The average current while unplugged (in use, asleep and display on together), from the states' charge and time; null when there was no unplugged time. */
internal fun unpluggedAverageMa(states: List<StateTotal>): Double? {
    val unplugged = states.filter { it.kind in UNPLUGGED_KINDS }
    val hours = unplugged.sumOf { it.hours }
    return if (hours > 0) unplugged.sumOf { it.mah } / hours else null
}

private class HistoryReading(val window: Int, val view: BatteryView)

/** The History tab: how the battery has been over the chosen window, its standby trend, days and discharge sessions, then marks and recording. */
@Composable
fun BatteryHistoryScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    val context = LocalContext.current
    var window by rememberSaveable { mutableIntStateOf(HISTORY_DEFAULT_WINDOW) }
    var changes by rememberSaveable { mutableIntStateOf(0) }
    val request = readKey to changes
    // Read again and a change to the log skip the shared 20-second cache; switching window reuses it.
    var shownRequest by remember { mutableStateOf(request) }
    val fresh = shownRequest != request
    SideEffect { shownRequest = request }
    val index = window
    val windowMs = HISTORY_WINDOWS_MS[index]
    val reading = rememberReading(Triple(readKey, changes, index)) {
        HistoryReading(index, BatteryModel.load(context, windowMs, fresh = fresh))
    }
    val labels = historyWindowLabels()

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_battery_history), stringResource(R.string.bhi_question)) {
                Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true)
            }
            reading?.let {
                Text(stringResource(R.string.bhi_read_window, Format.clock(context, it.view.nowMs), labels[it.window]), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Spacer(Modifier.height(Space.m))
            Segmented(labels, index, onSelect = { window = it }, label = stringResource(R.string.bhi_window_label))
            Spacer(Modifier.height(Space.xxl))
        }
        if (reading == null) {
            item { Reading() }
        } else {
            val view = reading.view
            item {
                MarksPlate(view, onMarked = { changes++ })
                Spacer(Modifier.height(Space.xxl))
            }
            item { HistoryOverPlate(view); Spacer(Modifier.height(Space.xxl)) }
            item { HistoryStandbyPlate(view); Spacer(Modifier.height(Space.xxl)) }
            item { HistoryDaysPlate(view); Spacer(Modifier.height(Space.xxl)) }
            item { HistorySessionsPlate(view); Spacer(Modifier.height(Space.xxl)) }
            item { EarlierMeasurementsPlate(readKey); Spacer(Modifier.height(Space.xxl)) }
            item { RecordingPlate(readKey, onChanged = { changes++ }) }
        }
    }
}

@Composable
private fun HistoryOverPlate(view: BatteryView) {
    val context = LocalContext.current
    val states = remember(view) {
        Summaries.byState(view.timeline, view.nowMs - view.windowMs, view.nowMs).filter { it.kind != EpisodeKind.Off }
    }
    val average = unpluggedAverageMa(states)
    Plate(stringResource(R.string.bhi_plate_over)) {
        if (states.isEmpty()) Paragraph(stringResource(R.string.bhi_nothing_yet), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        states.forEach { s ->
            SpecRow(
                BatteryText.kindLabel(context, s.kind),
                stringResource(R.string.bhi_value_pair, BatteryText.duration(context, hoursAsMs(s.hours)), BatteryText.mah(context, s.mah)),
            )
        }
        average?.let { ma ->
            SpecRow(
                stringResource(R.string.bhi_average_draw),
                stringResource(R.string.bhi_value_pair, BatteryText.ma(context, ma), BatteryText.pctPerHour(context, ma, view.full.capacityMah)),
                note = stringResource(R.string.bhi_average_note),
            )
        }
    }
}

@Composable
private fun HistoryStandbyPlate(view: BatteryView) {
    val context = LocalContext.current
    val days = remember(view) { dailyStandby(view.timeline, TimeZone.getDefault()).reversed() }
    val largest = days.maxOfOrNull { it.ma } ?: 0.0
    val median = medianOf(days.map { it.ma })
    var page by rememberSaveable(view.windowMs) { mutableIntStateOf(0) }
    val pages = pageCount(days.size)
    val shown = page.coerceIn(0, pages - 1)
    Plate(stringResource(R.string.bhi_plate_standby)) {
        Paragraph(stringResource(R.string.bhi_standby_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (days.isEmpty()) Paragraph(stringResource(R.string.bhi_standby_none), modifier = Modifier.padding(vertical = Space.s))
        median?.let { SpecRow(stringResource(R.string.bhi_median), BatteryText.ma(context, it)) }
        view.baseline.dayToDaySpreadMa?.let {
            SpecRow(stringResource(R.string.bhi_normal_spread), BatteryText.ma(context, it), note = stringResource(R.string.bhi_normal_spread_note))
        }
        days.drop(shown * HISTORY_PER_PAGE).take(HISTORY_PER_PAGE).forEach { day ->
            RateBar(
                label = BatteryText.day(context, day.dayStartMs),
                valueText = BatteryText.ma(context, day.ma),
                fraction = fractionOf(day.ma, largest).toFloat(),
                hatched = day.displayOn,
                detail = stringResource(
                    if (day.displayOn) R.string.bhi_standby_detail_display else R.string.bhi_standby_detail,
                    BatteryText.duration(context, hoursAsMs(day.asleepHours)),
                ),
            )
        }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

@Composable
private fun HistoryDaysPlate(view: BatteryView) {
    val days = remember(view) {
        Summaries.byDay(view.timeline, TimeZone.getDefault(), view.nowMs - view.windowMs, view.nowMs).reversed()
    }
    var page by rememberSaveable(view.windowMs) { mutableIntStateOf(0) }
    val pages = pageCount(days.size)
    val shown = page.coerceIn(0, pages - 1)
    Plate(stringResource(R.string.bhi_plate_days)) {
        Paragraph(stringResource(R.string.bhi_days_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (days.isEmpty()) Paragraph(stringResource(R.string.bhi_days_none), modifier = Modifier.padding(vertical = Space.s))
        days.drop(shown * HISTORY_PER_PAGE).take(HISTORY_PER_PAGE).forEach { HistoryDayRow(it) }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

@Composable
private fun HistoryDayRow(day: DaySummary) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = Space.s), verticalAlignment = Alignment.Top) {
            Text(BatteryText.day(context, day.dayStartMs), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = Space.m))
            Text(stringResource(R.string.bhi_used, BatteryText.mah(context, day.usedMah)), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(
                R.string.bhi_day_figures,
                timeOrNone(context, day.inUseHours),
                timeOrNone(context, day.asleepHours),
                timeOrNone(context, day.chargingHours),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Legend,
            modifier = Modifier.padding(top = Space.xs),
        )
        if (day.displayOnHours >= HISTORY_DISPLAY_TAG_HOURS) {
            Tag(stringResource(R.string.bhi_display_on_tag), modifier = Modifier.padding(top = Space.xs), strong = true)
        }
        Spacer(Modifier.height(Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun HistorySessionsPlate(view: BatteryView) {
    val sessions = remember(view) {
        Summaries.sessions(view.timeline).filter { it.endMs - it.startMs >= HISTORY_MIN_SESSION_MS }.reversed()
    }
    var page by rememberSaveable(view.windowMs) { mutableIntStateOf(0) }
    val pages = pageCount(sessions.size)
    val shown = page.coerceIn(0, pages - 1)
    Plate(stringResource(R.string.bhi_plate_sessions)) {
        Paragraph(stringResource(R.string.bhi_sessions_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (sessions.isEmpty()) Paragraph(stringResource(R.string.bhi_sessions_none), modifier = Modifier.padding(vertical = Space.s))
        sessions.drop(shown * HISTORY_PER_PAGE).take(HISTORY_PER_PAGE).forEach { HistorySessionRow(it) }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

@Composable
private fun HistorySessionRow(session: DischargeSession) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = Space.s), verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.bhi_session_range, dayAndClock(context, session.startMs), dayAndClock(context, session.endMs)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = Space.m),
            )
            Text(stringResource(R.string.bhi_percent_range, session.level0, session.level1), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(
                R.string.bhi_session_figures,
                BatteryText.mah(context, session.mah),
                timeOrNone(context, session.inUseHours),
                timeOrNone(context, session.asleepHours),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Legend,
            modifier = Modifier.padding(top = Space.xs),
        )
        if (session.displayOnHours >= HISTORY_DISPLAY_TAG_HOURS) {
            Tag(stringResource(R.string.bhi_display_on_tag), modifier = Modifier.padding(top = Space.xs))
        }
        Spacer(Modifier.height(Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun historyWindowLabels(): List<String> = listOf(
    pluralStringResource(R.plurals.bhi_window_hours, 24, 24),
    pluralStringResource(R.plurals.bhi_window_days, 3, 3),
    pluralStringResource(R.plurals.bhi_window_days, 7, 7),
    pluralStringResource(R.plurals.bhi_window_days, 30, 30),
)

/** The local days a span touches, each with the milliseconds of the span that fall inside it. */
private fun piecesByDay(startMs: Long, endMs: Long, zone: ZoneId): List<Pair<Long, Long>> {
    val pieces = mutableListOf<Pair<Long, Long>>()
    var cursor = startMs
    while (cursor < endMs) {
        val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val nextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pieceEnd = minOf(endMs, nextDay)
        pieces += Pair(day.atStartOfDay(zone).toInstant().toEpochMilli(), pieceEnd - cursor)
        cursor = pieceEnd
    }
    return pieces
}

private fun timeOrNone(context: Context, hours: Double): String =
    if (hours > 0) BatteryText.duration(context, hoursAsMs(hours)) else context.getString(R.string.bhi_none)

private fun dayAndClock(context: Context, ms: Long): String =
    context.getString(R.string.bhi_day_time, BatteryText.day(context, ms), Format.clock(context, ms))

private fun pageCount(items: Int): Int = ((items + HISTORY_PER_PAGE - 1) / HISTORY_PER_PAGE).coerceAtLeast(1)

private fun fractionOf(part: Double, total: Double): Double = if (total > 0) part / total else 0.0

private fun hoursAsMs(hours: Double): Long = (hours * HISTORY_HOUR_MS).toLong()
