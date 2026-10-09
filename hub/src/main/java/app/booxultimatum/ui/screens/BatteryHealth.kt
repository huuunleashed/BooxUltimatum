package app.booxultimatum.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.battery.BatteryModel
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Space

/** The capacity the timeline reports while the log has no counter data to learn from. */
private const val UNLEARNED_MAH = 3700.0

/** Is the battery healthy? Capacity as the log learned it, what Android reports, and how it charges. */
@Composable
fun BatteryHealthScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    val context = LocalContext.current
    val b = rememberReading(readKey) { BatterySnapshot.read(context) }
    val learned = rememberReading(readKey) { BatteryModel.load(context, 24 * 3_600_000L).full.capacityMah }
    InstrumentPage(compact) {
        item { ScreenHeader(stringResource(R.string.dest_battery_health), null) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) } }
        if (b == null || learned == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val known = learned.takeIf { it != UNLEARNED_MAH }
        item {
            Plate(stringResource(R.string.bh_plate_capacity)) {
                Paragraph(stringResource(R.string.bh_capacity_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                SpecRow(stringResource(R.string.bh_learned), known?.let { Format.mah(context, it) } ?: stringResource(R.string.bh_not_enough_log))
                SpecRow(
                    stringResource(R.string.spec_full),
                    b.estimatedFullMah?.let { Format.mah(context, it) } ?: stringResource(R.string.bh_unknown),
                    note = stringResource(R.string.spec_full_note),
                )
            }
            Spacer(Modifier.height(Space.xxl))
        }
        item {
            Plate(stringResource(R.string.bh_plate_condition)) {
                SpecRow(stringResource(R.string.spec_health), stringResource(healthName(b.health)))
                SpecRow(stringResource(R.string.spec_cycles), b.cycleCount?.toString() ?: stringResource(R.string.value_not_reported))
                SpecRow(stringResource(R.string.spec_temperature), b.tempC?.let { stringResource(R.string.bh_value_celsius, it) } ?: stringResource(R.string.bh_unknown))
                SpecRow(stringResource(R.string.spec_voltage), b.voltageMv?.let { stringResource(R.string.bh_value_volts, it / 1000.0) } ?: stringResource(R.string.bh_unknown))
            }
            Spacer(Modifier.height(Space.xxl))
        }
        item {
            Plate(stringResource(R.string.charge_charging)) {
                SpecRow(stringResource(R.string.spec_source), stringResource(sourceName(b.source)))
                if (b.charging) {
                    SpecRow(
                        stringResource(R.string.spec_current),
                        b.currentNowMa?.let { stringResource(R.string.bh_value_ma, it) } ?: stringResource(R.string.value_not_reported),
                        note = b.currentNowMa?.let { stringResource(R.string.spec_current_note) },
                    )
                    SpecRow(
                        stringResource(R.string.bh_time_to_full),
                        known?.let { timeToFullText(context, it, b) } ?: stringResource(R.string.bh_unknown),
                        note = stringResource(R.string.bh_time_to_full_note),
                    )
                } else {
                    Paragraph(stringResource(R.string.bh_not_charging), modifier = Modifier.padding(vertical = Space.s))
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
        item {
            Plate(stringResource(R.string.bh_plate_protection)) {
                Paragraph(stringResource(R.string.bh_protection), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
            }
        }
    }
}

private fun timeToFullText(context: Context, learnedMah: Double, b: BatterySnapshot): String {
    val mA = b.currentNowMa?.takeIf { it > 0 } ?: return context.getString(R.string.bh_unknown)
    val leftMah = learnedMah * (100 - b.levelPct) / 100.0
    if (leftMah <= 0) return context.getString(R.string.charge_full)
    return Format.duration(context, (leftMah / mA * 3_600_000).toLong())
}
