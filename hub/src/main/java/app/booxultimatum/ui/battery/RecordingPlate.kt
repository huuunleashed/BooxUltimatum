package app.booxultimatum.ui.battery

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.MeasureResult
import app.booxultimatum.core.Measurement
import app.booxultimatum.core.battery.BatteryReport
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.LampRow
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val DAY_MS = 86_400_000L
private const val MEGABYTE = 1_048_576L
private const val PER_PAGE = 9

/** The log as the Recording plate reads it: the first and last epochs of its rows, and the bytes in its folder. */
private class LogState(val firstMs: Long?, val lastMs: Long?, val bytes: Long)

/** OWNER: E3. The Recording plate: pause or resume the log, where the files are, export, and share a battery report. */
@Composable
fun RecordingPlate(readKey: Int, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toggle by remember { mutableIntStateOf(0) }
    var includeApps by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val enabled = remember(readKey, toggle) { BatteryLog.enabled(context) }
    val state = rememberReading(Pair(readKey, toggle)) { logState(context) }

    fun prepare(work: suspend () -> Unit) {
        preparing = true
        failure = null
        scope.launch {
            runCatching { work() }.onFailure { failure = context.getString(R.string.brc_failed, it.message ?: it.javaClass.simpleName) }
            preparing = false
        }
    }

    Plate(
        stringResource(R.string.brc_recording_title),
        modifier = modifier,
        action = {
            Key(stringResource(if (enabled) R.string.log_pause else R.string.log_resume), onClick = {
                BatteryLog.setEnabled(context, !enabled)
                toggle++
                onChanged()
            })
        },
    ) {
        Paragraph(stringResource(R.string.log_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
        LampRow(enabled, stringResource(if (enabled) R.string.log_status else R.string.log_off), state?.let { coverage(context, it) })
        SpecRow(stringResource(R.string.brc_folder), stringResource(R.string.brc_folder_path), code = true)
        FlowRow(
            Modifier.padding(top = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Key(stringResource(R.string.brc_export), enabled = !preparing, onClick = {
                prepare {
                    val zip = withContext(Dispatchers.IO) { BatteryLog.bundle(context) }
                    if (zip == null) {
                        failure = context.getString(R.string.brc_export_empty)
                    } else {
                        context.startActivity(BatteryReport.shareIntent(context, zip))
                    }
                }
            })
        }
        Text(stringResource(R.string.brc_report_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.l))
        Paragraph(stringResource(R.string.brc_report_explain), color = Ink.Legend, modifier = Modifier.padding(top = Space.xs))
        IncludeAppsRow(includeApps, onChange = { includeApps = it })
        Paragraph(stringResource(R.string.brc_apps_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Key(stringResource(R.string.brc_share_report), enabled = !preparing, onClick = {
                prepare {
                    val file = withContext(Dispatchers.IO) { BatteryReport.build(context, includeApps) }
                    context.startActivity(BatteryReport.shareIntent(context, file))
                }
            })
        }
        if (preparing) {
            Text(stringResource(R.string.brc_preparing), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
        }
        failure?.let { ErrorLine(it) }
    }
}

/** OWNER: E3. The old manual measurements, read-only, kept so past results stay visible. Draws nothing when there are none. */
@Composable
fun EarlierMeasurementsPlate(readKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    val results = rememberReading(readKey) { Measurement.history(context) }
    if (results.isNullOrEmpty()) return
    val pages = (results.size + PER_PAGE - 1) / PER_PAGE
    val shown = page.coerceIn(0, pages - 1)
    Plate(stringResource(R.string.brc_earlier_title), modifier = modifier) {
        Paragraph(stringResource(R.string.brc_earlier_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
        results.drop(shown * PER_PAGE).take(PER_PAGE).forEach { EarlierRow(it) }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

@Composable
private fun IncludeAppsRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Space.s)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(checked)
        Text(stringResource(R.string.brc_include_apps), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = Space.m))
        Text(stringResource(if (checked) R.string.brc_apps_in else R.string.brc_apps_out), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
    }
}

@Composable
private fun EarlierRow(r: MeasureResult) {
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
                Text(earlierRate(context, r), style = MaterialTheme.typography.titleMedium)
                if (r.chargedDuring) Tag(stringResource(R.string.tag_charged), modifier = Modifier.padding(top = Space.xs))
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

private fun earlierRate(context: Context, r: MeasureResult): String {
    val pct = String.format(Locale.getDefault(), "%.2f", r.pctPerHour)
    return r.mahPerHour?.let { context.getString(R.string.rate_value, pct, String.format(Locale.getDefault(), "%.0f", it)) }
        ?: context.getString(R.string.rate_value_pct, pct)
}

private fun logState(context: Context): LogState {
    val files = BatteryLog.files(context)
    var first: Long? = null
    var last: Long? = null
    files.filter { it.name.startsWith("battery-") && it.name.endsWith(".csv") }.forEach { f ->
        f.useLines { lines ->
            lines.forEach { line ->
                val epoch = line.substringBefore(',').toLongOrNull() ?: return@forEach
                first = minOf(first ?: epoch, epoch)
                last = maxOf(last ?: epoch, epoch)
            }
        }
    }
    return LogState(first, last, files.sumOf { it.length() })
}

private fun coverage(context: Context, state: LogState): String {
    val first = state.firstMs
    val last = state.lastMs
    val covered = when {
        first == null || last == null -> context.getString(R.string.brc_nothing_recorded)
        last - first < DAY_MS -> context.getString(R.string.brc_under_a_day)
        else -> {
            val days = ((last - first) / DAY_MS).toInt()
            context.resources.getQuantityString(R.plurals.brc_covered_days, days, days)
        }
    }
    return context.getString(R.string.brc_detail, covered, size(context, state.bytes))
}

private fun size(context: Context, bytes: Long): String =
    if (bytes < MEGABYTE) {
        context.getString(R.string.brc_size_kb, bytes / 1024)
    } else {
        context.getString(R.string.brc_size_mb, String.format(Locale.getDefault(), "%.1f", bytes.toDouble() / MEGABYTE))
    }
