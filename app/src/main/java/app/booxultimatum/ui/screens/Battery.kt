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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.BatteryHealth
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.ChargeStatus
import app.booxultimatum.core.MeasureResult
import app.booxultimatum.core.Measurement
import app.booxultimatum.core.PowerSource
import app.booxultimatum.core.SystemState
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.ui.ErrorLine
import app.booxultimatum.ui.Format
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.SpecRow
import app.booxultimatum.ui.Tag
import app.booxultimatum.ui.TuningScale
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.CodeStyle
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class WakeReport(
    val doze: app.booxultimatum.core.DozeState?,
    val wakeups: List<app.booxultimatum.core.WakeupSource>?,
    val wakelocks: List<app.booxultimatum.core.HeldWakelock>?,
    val shell: Boolean,
)

@Composable
fun BatteryScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var changeKey by remember { mutableIntStateOf(0) }
    val key = readKey to changeKey
    val b = rememberReading(key) { BatterySnapshot.read(context) }
    val open = rememberReading(key) { Measurement.open(context) }
    val history = rememberReading(key) { Measurement.history(context) }
    val wake = rememberReading(key) { WakeReport(SystemState.doze(), SystemState.alarmWakeups(), SystemState.heldWakelocks(), Privileged.ready()) }
    var toolError by remember { mutableStateOf<String?>(null) }

    InstrumentPage(compact) {
        item {
            ScreenHeader(
                stringResource(R.string.dest_battery),
                b?.let { stringResource(R.string.battery_subtitle, Format.clock(context, it.readAtMillis)) },
            ) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) }
        }
        if (b == null) {
            item { Reading() }
            return@InstrumentPage
        }
        item {
            TuningScale(b.levelPct)
            Spacer(Modifier.height(Space.l))
            StatusLines(b)
            estimate(context, b, history.orEmpty().firstOrNull { !it.chargedDuring })?.let {
                Spacer(Modifier.height(Space.s))
                Paragraph(it)
            }
            Spacer(Modifier.height(Space.xxl))
        }

        item {
            Plate(stringResource(R.string.plate_measure)) {
                val start = open
                if (start == null) {
                    Paragraph(stringResource(R.string.measure_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                    if (b.source != PowerSource.None) {
                        Text(stringResource(R.string.measure_unplug_first), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = Space.s))
                    }
                    Key(stringResource(R.string.action_start_measuring), onClick = { Measurement.start(context, b); changeKey++ })
                } else {
                    val r = Measurement.evaluate(start, b)
                    SpecRow(stringResource(R.string.measure_started), stringResource(R.string.measure_started_value, Format.clock(context, start.epoch), Format.duration(context, System.currentTimeMillis() - start.epoch)))
                    if (r == null) {
                        ErrorLine(stringResource(R.string.measure_restarted))
                        Spacer(Modifier.height(Space.m))
                        Key(stringResource(R.string.action_discard), onClick = { Measurement.discard(context); changeKey++ })
                    } else {
                        SpecRow(stringResource(R.string.measure_level), stringResource(R.string.measure_level_value, r.startLevel, r.endLevel))
                        r.usedMah?.let { SpecRow(stringResource(R.string.measure_used), Format.mah(context, it)) }
                        SpecRow(
                            stringResource(R.string.measure_rate),
                            if (Measurement.longEnough(r)) rateText(context, r) else stringResource(R.string.measure_too_short),
                        )
                        SpecRow(stringResource(R.string.measure_asleep), Format.percent(context, r.asleepFraction))
                        if (r.chargedDuring) ErrorLine(stringResource(R.string.measure_charged))
                        Spacer(Modifier.height(Space.m))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Key(stringResource(R.string.action_finish), enabled = Measurement.longEnough(r), onClick = { Measurement.finish(context, r); changeKey++ })
                            Key(stringResource(R.string.action_discard), onClick = { Measurement.discard(context); changeKey++ })
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }

        if (!history.isNullOrEmpty()) {
            item {
                Plate(stringResource(R.string.plate_history)) {
                    history.forEach { r -> HistoryRow(r) }
                }
                Spacer(Modifier.height(Space.xxl))
            }
        }

        item {
            BatteryLogPlate(key)
            Spacer(Modifier.height(Space.xxl))
        }

        item {
            Plate(stringResource(R.string.plate_wakes)) {
                Paragraph(stringResource(R.string.wakes_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                if (wake == null) Reading() else WakeSection(context, wake)
            }
            Spacer(Modifier.height(Space.xxl))
        }

        item {
            Plate(stringResource(R.string.plate_readings)) {
                SpecRow(stringResource(R.string.spec_status), chargeLine(context, b))
                SpecRow(stringResource(R.string.spec_source), stringResource(sourceName(b.source)))
                b.chargeCounterMah?.let { SpecRow(stringResource(R.string.spec_stored), Format.mah(context, it)) }
                b.estimatedFullMah?.let { SpecRow(stringResource(R.string.spec_full), Format.mah(context, it), note = stringResource(R.string.spec_full_note)) }
                b.voltageMv?.let { SpecRow(stringResource(R.string.spec_voltage), String.format(Locale.getDefault(), "%.2f V", it / 1000.0)) }
                b.tempC?.let { SpecRow(stringResource(R.string.spec_temperature), String.format(Locale.getDefault(), "%.1f °C", it)) }
                SpecRow(stringResource(R.string.spec_health), stringResource(healthName(b.health)))
                b.cycleCount?.let { SpecRow(stringResource(R.string.spec_cycles), it.toString()) }
                b.technology?.let { SpecRow(stringResource(R.string.spec_technology), it) }
                SpecRow(
                    stringResource(R.string.spec_current),
                    b.currentNowMa?.let { String.format(Locale.getDefault(), "%.0f mA", it) } ?: stringResource(R.string.value_not_reported),
                    note = b.currentNowMa?.let { stringResource(R.string.spec_current_note) },
                )
                SpecRow(stringResource(R.string.spec_since_boot), Format.duration(context, b.sinceBootMs))
                SpecRow(stringResource(R.string.spec_asleep), Format.percent(context, b.asleepFraction), note = stringResource(R.string.spec_asleep_note))
            }
            Spacer(Modifier.height(Space.xxl))
        }

        item {
            Plate(stringResource(R.string.plate_tools)) {
                Spacer(Modifier.height(Space.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Key(stringResource(R.string.action_battery_usage), onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { toolError = context.getString(R.string.hub_failed, context.getString(R.string.action_battery_usage)) }
                    })
                    val forced = wake?.doze?.forced == true
                    Key(
                        stringResource(if (forced) R.string.action_unforce_idle else R.string.action_force_idle),
                        enabled = wake?.shell == true,
                        onClick = {
                            scope.launch {
                                val r = SystemState.forceIdle(!forced)
                                toolError = if (r.ok) null else context.getString(R.string.tweak_failed, r.message)
                                changeKey++
                            }
                        },
                    )
                }
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.tools_force_idle_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                toolError?.let { ErrorLine(it) }
            }
        }
    }
}

@Composable
private fun WakeSection(context: Context, w: WakeReport) {
    val d = w.doze
    if (d == null && w.wakeups == null && w.wakelocks == null) {
        Paragraph(stringResource(R.string.wakes_no_access), color = Ink.Legend)
        return
    }
    if (d != null) {
        SpecRow(
            stringResource(R.string.wakes_doze),
            stringResource(if (d.deepEnabled) R.string.doze_on else R.string.doze_off_firmware),
            note = if (d.deepEnabled) stringResource(R.string.doze_state, d.deep.lowercase(), d.light.lowercase()) else stringResource(R.string.doze_off_note),
        )
    }
    w.wakelocks?.let { locks ->
        SpecRow(
            stringResource(R.string.wakes_locks),
            if (locks.isEmpty()) stringResource(R.string.wakes_locks_none) else locks.joinToString("\n") { "${it.tag} (${it.type.removeSuffix("_WAKE_LOCK").lowercase()})" },
        )
    }
    w.wakeups?.let { list ->
        val top = list.filter { it.wakeups > 0 }.take(8)
        Spacer(Modifier.height(Space.m))
        Text(stringResource(R.string.wakes_alarms_title), style = MaterialTheme.typography.titleSmall)
        if (top.isEmpty()) {
            Paragraph(stringResource(R.string.wakes_alarms_none), color = Ink.Legend)
        } else top.forEach { s ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = Space.m)) {
                        Text(appLabel(context, s.pkg), style = MaterialTheme.typography.bodyLarge)
                        Text(s.pkg, style = CodeStyle, color = Ink.Legend)
                    }
                    Text(stringResource(R.string.wakes_count, s.wakeups), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
            }
        }
    }
}

@Composable
private fun HistoryRow(r: MeasureResult) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startEpoch)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.history_detail, Format.duration(context, (r.hours * 3_600_000).toLong()), Format.percent(context, r.asleepFraction)),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Legend,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rateText(context, r), style = MaterialTheme.typography.titleMedium)
                if (r.chargedDuring) Tag(stringResource(R.string.tag_charged), modifier = Modifier.padding(top = Space.xs))
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

fun rateText(context: Context, r: MeasureResult): String {
    val pct = String.format(Locale.getDefault(), "%.2f", r.pctPerHour)
    return r.mahPerHour?.let { context.getString(R.string.rate_value, pct, String.format(Locale.getDefault(), "%.0f", it)) }
        ?: context.getString(R.string.rate_value_pct, pct)
}

/** "About 31 h left at your last measured rate" — only when on battery and a clean measurement exists. */
private fun estimate(context: Context, b: BatterySnapshot, clean: MeasureResult?): String? {
    if (clean == null || b.status != ChargeStatus.Discharging || clean.pctPerHour <= 0.01) return null
    val hours = b.levelPct / clean.pctPerHour
    return context.getString(R.string.estimate_line, Format.duration(context, (hours * 3_600_000).toLong()), String.format(Locale.getDefault(), "%.2f", clean.pctPerHour))
}

private fun appLabel(context: Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(context.packageManager).toString()
}.getOrDefault(pkg)

fun sourceName(s: PowerSource) = when (s) {
    PowerSource.AC -> R.string.source_ac
    PowerSource.USB -> R.string.source_usb
    PowerSource.Wireless -> R.string.source_wireless
    PowerSource.Dock -> R.string.source_dock
    PowerSource.None -> R.string.source_none
}

fun healthName(h: BatteryHealth) = when (h) {
    BatteryHealth.Good -> R.string.health_good
    BatteryHealth.Overheat -> R.string.health_overheat
    BatteryHealth.Dead -> R.string.health_dead
    BatteryHealth.OverVoltage -> R.string.health_over_voltage
    BatteryHealth.Cold -> R.string.health_cold
    BatteryHealth.Failure -> R.string.health_failure
    BatteryHealth.Unknown -> R.string.health_unknown
}

/**
 * The always-on battery log: standby and awake drain worked out automatically from samples the tablet takes
 * whenever it is awake anyway, plus a level chart and export for deeper analysis.
 */
@Composable
private fun BatteryLogPlate(key: Any) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    var toggle by remember { mutableIntStateOf(0) }
    val enabled = remember(toggle) { app.booxultimatum.core.BatteryLog.enabled(context) }
    val summary = rememberReading(Pair(key, toggle)) { app.booxultimatum.core.BatteryLog.summary(context) }
    Plate(
        stringResource(R.string.plate_log),
        action = {
            Key(stringResource(if (enabled) R.string.log_pause else R.string.log_resume), onClick = {
                app.booxultimatum.core.BatteryLog.setEnabled(context, !enabled); toggle++
            })
        },
    ) {
        Paragraph(stringResource(R.string.log_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (summary == null) { Reading(); return@Plate }
        SpecRow(stringResource(R.string.log_status), stringResource(if (enabled) R.string.log_on else R.string.log_off))
        SpecRow(stringResource(R.string.log_samples), stringResource(R.string.log_samples_value, summary.samples, Format.duration(context, (summary.spanHours * 3_600_000).toLong())))
        SpecRow(
            stringResource(R.string.log_asleep_rate),
            summary.asleepPctPerHour?.let { stringResource(R.string.rate_value_pct, String.format(Locale.getDefault(), "%.2f", it)) } ?: stringResource(R.string.log_not_enough),
            note = if (summary.asleepHours * 60 >= 1) stringResource(R.string.log_over, Format.duration(context, (summary.asleepHours * 3_600_000).toLong())) else null,
        )
        SpecRow(
            stringResource(R.string.log_awake_rate),
            summary.awakePctPerHour?.let { stringResource(R.string.rate_value_pct, String.format(Locale.getDefault(), "%.2f", it)) } ?: stringResource(R.string.log_not_enough),
            note = if (summary.awakeHours * 60 >= 1) stringResource(R.string.log_over, Format.duration(context, (summary.awakeHours * 3_600_000).toLong())) else null,
        )
        if (summary.recent.size >= 2) {
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.log_chart), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.s))
            LevelChart(summary.recent)
        }
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Key(stringResource(R.string.log_sample_now), onClick = { app.booxultimatum.core.BatteryLog.record(context, "manual") { toggle++ } })
            Key(stringResource(R.string.log_share), onClick = {
                shareScope.launch {
                    val zip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.booxultimatum.core.BatteryLog.bundle(context) }.getOrNull() }
                    if (zip != null) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", zip)
                        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, zip.name)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = android.content.ClipData.newRawUri(zip.name, uri)
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.log_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            })
        }
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.log_path), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
    }
}

/** Battery level over time on the instrument's scale: 0-100 rules, hours along the bottom, plugged spans marked. */
@Composable
private fun LevelChart(samples: List<app.booxultimatum.core.LogSample>) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = Ink.Legend)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 36.dp.toPx(); val bottom = size.height - 22.dp.toPx(); val top = 6.dp.toPx()
        val t0 = samples.first().epoch.toFloat(); val t1 = samples.last().epoch.toFloat().coerceAtLeast(t0 + 1)
        fun x(t: Long) = left + (size.width - left) * ((t - t0) / (t1 - t0))
        fun y(level: Int) = bottom - (bottom - top) * level / 100f
        listOf(0, 50, 100).forEach { l ->
            drawLine(if (l == 0) Ink.Black else Ink.Rule, androidx.compose.ui.geometry.Offset(left, y(l)), androidx.compose.ui.geometry.Offset(size.width, y(l)), strokeWidth = 1.dp.toPx())
            val m = measurer.measure("$l", label)
            drawText(m, topLeft = androidx.compose.ui.geometry.Offset(left - m.size.width - 6.dp.toPx(), y(l) - m.size.height / 2f))
        }
        samples.zipWithNext().forEach { (a, b) ->
            if (a.plugged) drawRect(Ink.Signal.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(x(a.epoch), top), androidx.compose.ui.geometry.Size(x(b.epoch) - x(a.epoch), bottom - top))
            drawLine(Ink.Black, androidx.compose.ui.geometry.Offset(x(a.epoch), y(a.level)), androidx.compose.ui.geometry.Offset(x(b.epoch), y(b.level)), strokeWidth = 2.5.dp.toPx())
        }
        val hours = ((t1 - t0) / 3_600_000f)
        val m = measurer.measure(String.format(Locale.getDefault(), "%.0f h", hours), label)
        drawText(m, topLeft = androidx.compose.ui.geometry.Offset(size.width - m.size.width, bottom + 4.dp.toPx()))
    }
}
