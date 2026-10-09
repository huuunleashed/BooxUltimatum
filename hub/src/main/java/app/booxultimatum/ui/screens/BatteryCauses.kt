package app.booxultimatum.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import app.booxultimatum.core.battery.BatteryRow
import app.booxultimatum.core.battery.BatteryView
import app.booxultimatum.core.battery.DeepLog
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.core.battery.Summaries
import app.booxultimatum.core.battery.WakeSource
import app.booxultimatum.kit.ui.ErrorLine
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
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.ui.Destination
import app.booxultimatum.ui.battery.BatteryText
import kotlin.math.roundToInt

private const val CAUSES_HOUR_MS = 3_600_000L
private const val CAUSES_DAY_MS = 24 * CAUSES_HOUR_MS
private const val CAUSES_PER_PAGE = 9
private const val ANDROID_PKG = "android"
private const val WIFI_CONNECTED = "connected"
private val CAUSES_WINDOWS_MS = listOf(CAUSES_DAY_MS, 3 * CAUSES_DAY_MS, 7 * CAUSES_DAY_MS)
private val STATES_SHOWN = listOf(EpisodeKind.InUse, EpisodeKind.Asleep, EpisodeKind.DisplayOn)

private class CausesReading(val window: Int, val view: BatteryView, val wakes: List<WakeSource>)

/** The Causes tab: what used the charge, what kept the tablet awake, and the radios and light, for the chosen window. */
@Composable
fun BatteryCausesScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit, onOpen: (Destination) -> Unit) {
    val context = LocalContext.current
    var window by rememberSaveable { mutableIntStateOf(0) }
    // Only a Read again key skips the shared 20-second cache; switching window or tab reuses it.
    var shownKey by remember { mutableIntStateOf(readKey) }
    val fresh = shownKey != readKey
    SideEffect { shownKey = readKey }
    val index = window
    val windowMs = CAUSES_WINDOWS_MS[index]
    val reading = rememberReading(readKey to index) {
        val view = BatteryModel.load(context, windowMs, fresh = fresh)
        CausesReading(index, view, DeepLog.wakeSources(context, view.nowMs - windowMs, view.nowMs))
    }
    val labels = causeWindowLabels()

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_battery_causes), stringResource(R.string.bc_question)) {
                Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true)
            }
            reading?.let {
                Text(stringResource(R.string.bc_read_window, Format.clock(context, it.view.nowMs), labels[it.window]), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Spacer(Modifier.height(Space.m))
            Segmented(labels, window, onSelect = { window = it }, label = stringResource(R.string.bc_window_label))
            Spacer(Modifier.height(Space.xxl))
        }
        when {
            reading == null -> item { Reading() }
            reading.view.rows.isEmpty() -> item { Paragraph(stringResource(R.string.bc_nothing_yet), color = Ink.Legend) }
            else -> {
                item { CausesStatePlate(reading.view); Spacer(Modifier.height(Space.xxl)) }
                item { CausesAppsPlate(reading.view); Spacer(Modifier.height(Space.xxl)) }
            }
        }
        if (reading != null) {
            item { CausesWakesPlate(reading.view.windowMs, reading.wakes); Spacer(Modifier.height(Space.xxl)) }
        }
        if (reading != null && reading.view.rows.isNotEmpty()) {
            item { CausesRadiosPlate(reading.view); Spacer(Modifier.height(Space.xxl)) }
        }
        item { CausesMorePlate(onOpen) }
    }
}

@Composable
private fun CausesStatePlate(view: BatteryView) {
    val context = LocalContext.current
    val states = remember(view) {
        Summaries.byState(view.timeline, view.nowMs - view.windowMs, view.nowMs).filter { it.kind in STATES_SHOWN }
    }
    val largest = states.maxOfOrNull { it.mah } ?: 0.0
    val drawn = states.sumOf { it.mah }
    Plate(stringResource(R.string.bc_plate_state)) {
        if (states.isEmpty()) Paragraph(stringResource(R.string.bc_state_none), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        states.forEach { s ->
            RateBar(
                label = BatteryText.kindLabel(context, s.kind),
                valueText = stringResource(R.string.bc_value_pair, BatteryText.duration(context, hoursAsMs(s.hours)), BatteryText.mah(context, s.mah)),
                fraction = fractionOf(s.mah, largest).toFloat(),
                hatched = s.kind == EpisodeKind.DisplayOn,
                detail = stringResource(R.string.bc_state_detail, BatteryText.ma(context, s.mah / s.hours), Format.percent(context, fractionOf(s.mah, drawn))),
            )
        }
        states.firstOrNull { it.kind == EpisodeKind.DisplayOn }?.let { s ->
            Paragraph(
                stringResource(R.string.bc_display_on_fact, BatteryText.duration(context, hoursAsMs(s.hours)), BatteryText.mah(context, s.mah)),
                modifier = Modifier.padding(top = Space.s),
            )
        }
    }
}

@Composable
private fun CausesAppsPlate(view: BatteryView) {
    val context = LocalContext.current
    val apps = remember(view) { Summaries.byApp(view.timeline, view.nowMs - view.windowMs, view.nowMs, view.foreground) }
    var page by rememberSaveable(view.windowMs) { mutableIntStateOf(0) }
    val pages = pageCount(apps.size)
    val shown = page.coerceIn(0, pages - 1)
    val largest = apps.maxOfOrNull { it.mah } ?: 0.0
    Plate(stringResource(R.string.bc_plate_apps)) {
        Paragraph(stringResource(R.string.bc_apps_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (apps.isEmpty()) Paragraph(stringResource(R.string.bc_apps_none), modifier = Modifier.padding(vertical = Space.s))
        apps.drop(shown * CAUSES_PER_PAGE).take(CAUSES_PER_PAGE).forEach { app ->
            RateBar(
                label = packageLabel(context, app.pkg),
                valueText = stringResource(R.string.bc_value_pair, BatteryText.duration(context, hoursAsMs(app.hours)), BatteryText.ma(context, app.avgMa)),
                fraction = fractionOf(app.mah, largest).toFloat(),
                detail = BatteryText.mah(context, app.mah),
            )
        }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

@Composable
private fun CausesWakesPlate(windowMs: Long, wakes: List<WakeSource>) {
    val context = LocalContext.current
    var page by rememberSaveable(windowMs) { mutableIntStateOf(0) }
    val pages = pageCount(wakes.size)
    val shown = page.coerceIn(0, pages - 1)
    Plate(stringResource(R.string.bc_plate_wakes)) {
        Paragraph(stringResource(R.string.bc_wakes_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (wakes.isEmpty()) Paragraph(stringResource(R.string.bc_wakes_none), modifier = Modifier.padding(vertical = Space.s))
        wakes.drop(shown * CAUSES_PER_PAGE).take(CAUSES_PER_PAGE).forEach { w ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = Space.m)) {
                        Text(packageLabel(context, w.pkg), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.bc_cpu_time, BatteryText.duration(context, w.cpuMs)), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                    }
                    Text(
                        stringResource(
                            R.string.bc_value_pair,
                            pluralStringResource(R.plurals.bc_wake_locks, w.lockCount, w.lockCount),
                            pluralStringResource(R.plurals.bc_wakeups, w.wakeups, w.wakeups),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
            }
        }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

@Composable
private fun CausesRadiosPlate(view: BatteryView) {
    val context = LocalContext.current
    val spans = remember(view) { unpluggedSpans(view.rows, view.nowMs - view.windowMs, view.nowMs) }
    val wifi = spans.filter { it.first.wifi.isNotEmpty() }
    val wifiMs = wifi.sumOf { it.second }
    val connectedMs = wifi.filter { it.first.wifi == WIFI_CONNECTED }.sumOf { it.second }
    val lit = spans.mapNotNull { (row, ms) -> row.frontlight?.let { it to ms } }
    val litMs = lit.sumOf { it.second }
    Plate(stringResource(R.string.bc_plate_radios)) {
        SpecRow(
            stringResource(R.string.bc_wifi_connected),
            if (wifiMs > 0) Format.percent(context, connectedMs.toDouble() / wifiMs) else stringResource(R.string.bc_no_value),
            note = stringResource(R.string.bc_wifi_note),
        )
        SpecRow(
            stringResource(R.string.bc_front_light),
            if (litMs > 0) (lit.sumOf { (level, ms) -> level.toDouble() * ms } / litMs).roundToInt().toString() else stringResource(R.string.bc_no_value),
            note = stringResource(R.string.bc_front_light_note),
        )
    }
}

@Composable
private fun CausesMorePlate(onOpen: (Destination) -> Unit) {
    val context = LocalContext.current
    var toolError by remember { mutableStateOf<String?>(null) }
    Plate(stringResource(R.string.bc_plate_more)) {
        Paragraph(stringResource(R.string.bc_more_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Key(stringResource(R.string.action_battery_usage), onClick = {
                val opened = runCatching { context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                toolError = if (opened) null else context.getString(R.string.hub_failed, context.getString(R.string.action_battery_usage))
            })
            Key(stringResource(R.string.bc_open_apps), onClick = { onOpen(Destination.Apps) })
        }
        toolError?.let { ErrorLine(it) }
    }
}

@Composable
private fun causeWindowLabels(): List<String> = listOf(
    pluralStringResource(R.plurals.bc_window_hours, 24, 24),
    pluralStringResource(R.plurals.bc_window_days, 3, 3),
    pluralStringResource(R.plurals.bc_window_days, 7, 7),
)

/** The unplugged time between consecutive rows, clipped to [fromMs, toMs], each with the row that opened it. */
private fun unpluggedSpans(rows: List<BatteryRow>, fromMs: Long, toMs: Long): List<Pair<BatteryRow, Long>> =
    rows.zipWithNext { a, b ->
        val start = maxOf(a.epoch, fromMs)
        val end = minOf(b.epoch, toMs)
        if (a.plugged || end <= start) null else a to (end - start)
    }.filterNotNull()

private fun packageLabel(context: Context, pkg: String): String = when (pkg) {
    "" -> context.getString(R.string.bc_not_recorded)
    ANDROID_PKG -> context.getString(R.string.bc_android_itself)
    else -> {
        val pm = context.packageManager
        runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString() }.getOrDefault(pkg)
    }
}

private fun pageCount(items: Int): Int = ((items + CAUSES_PER_PAGE - 1) / CAUSES_PER_PAGE).coerceAtLeast(1)

private fun fractionOf(part: Double, total: Double): Double = if (total > 0) part / total else 0.0

private fun hoursAsMs(hours: Double): Long = (hours * CAUSES_HOUR_MS).toLong()
