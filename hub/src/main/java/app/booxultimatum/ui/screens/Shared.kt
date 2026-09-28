package app.booxultimatum.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.ChargeStatus
import app.booxultimatum.core.PowerSource
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space

/** "Charging from AC", "On battery", ... */
fun chargeLine(context: Context, b: BatterySnapshot): String = when (b.status) {
    ChargeStatus.Charging -> when (b.source) {
        PowerSource.AC -> context.getString(R.string.charge_charging_ac)
        PowerSource.USB -> context.getString(R.string.charge_charging_usb)
        PowerSource.Wireless -> context.getString(R.string.charge_charging_wireless)
        else -> context.getString(R.string.charge_charging)
    }
    ChargeStatus.Full -> context.getString(R.string.charge_full)
    ChargeStatus.NotCharging -> context.getString(R.string.charge_not_charging)
    ChargeStatus.Discharging -> context.getString(R.string.charge_on_battery)
    ChargeStatus.Unknown -> context.getString(R.string.charge_unknown)
}

/** The plain-language reading under the scale. Only states what was measured. */
@Composable
fun StatusLines(b: BatterySnapshot) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(on = b.charging, size = 16.dp)
            Spacer(Modifier.size(12.dp))
            Text(chargeLine(context, b), style = MaterialTheme.typography.titleLarge)
        }
        if (b.chargeCounterMah != null && b.estimatedFullMah != null) {
            Paragraph(stringResource(R.string.status_stored, Format.mah(context, b.chargeCounterMah), Format.mah(context, b.estimatedFullMah)))
        }
        Paragraph(stringResource(R.string.status_asleep, Format.percent(context, b.asleepFraction), Format.duration(context, b.sinceBootMs)))
        // Plugged in, the tablet charges and usually stays awake, so a low figure then is expected, not a fault.
        if (b.source != app.booxultimatum.core.PowerSource.None && b.asleepFraction < 0.5) {
            Text(stringResource(R.string.status_asleep_usb), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        }
    }
}

/** The one number that matters most: the latest clean measurement, an open one, or an honest empty state. */
@Composable
fun DrainPlate(modifier: Modifier = Modifier, onMeasure: (() -> Unit)? = null) {
    val context = LocalContext.current
    val latest = app.booxultimatum.core.Measurement.latestClean(context)
    val open = app.booxultimatum.core.Measurement.open(context)
    Plate(
        stringResource(R.string.plate_drain),
        modifier,
        action = onMeasure?.let { { app.booxultimatum.kit.ui.Key(stringResource(R.string.action_measure), onClick = it) } },
    ) {
        when {
            latest != null -> {
                Text(
                    stringResource(R.string.drain_latest, String.format(java.util.Locale.getDefault(), "%.2f", latest.pctPerHour)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = Space.s),
                )
                Spacer(Modifier.height(Space.xs))
                Paragraph(
                    stringResource(R.string.drain_latest_detail, Format.duration(context, (latest.hours * 3_600_000).toLong()), Format.percent(context, latest.asleepFraction)),
                    color = Ink.Legend,
                )
            }
            else -> {
                Text(stringResource(R.string.drain_not_measured), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.s))
                Spacer(Modifier.height(Space.s))
                Paragraph(stringResource(R.string.drain_explain), color = Ink.Legend)
            }
        }
        if (open != null) {
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.drain_open, Format.clock(context, open.epoch)), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(Space.m))
        Text(stringResource(R.string.drain_reference), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
    }
}

/** Two plates side by side on a wide face, stacked when narrow. */
@Composable
fun PlatePair(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 680.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                left(Modifier.weight(1f))
                right(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                left(Modifier)
                right(Modifier)
            }
        }
    }
}

/** A ruled row for something planned but not built. Says so plainly. */
@Composable
fun PlannedRow(name: String, purpose: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(purpose, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Text(stringResource(R.string.state_not_built), style = MaterialTheme.typography.labelMedium, color = Ink.Legend, modifier = Modifier.padding(top = 3.dp))
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

private val readLog = app.booxultimatum.kit.log.Logbook.logger("ui")
private const val SLOW_READ_MS = 3_000L

/** Runs a page's reading and logs how long it took; a slow one is logged as a warning, so it shows in every build. */
internal suspend fun <T> timedRead(page: String, block: suspend () -> T): T {
    val started = android.os.SystemClock.elapsedRealtime()
    return try {
        block()
    } finally {
        val ms = android.os.SystemClock.elapsedRealtime() - started
        if (ms >= SLOW_READ_MS) readLog.w("slow page", "page" to page, "ms" to ms) else readLog.d("page read", "page" to page, "ms" to ms)
    }
}
