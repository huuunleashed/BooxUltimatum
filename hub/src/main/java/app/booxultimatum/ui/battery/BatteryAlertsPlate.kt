package app.booxultimatum.ui.battery

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.battery.BatteryAlerts
import app.booxultimatum.core.battery.GuardRecord
import app.booxultimatum.core.battery.MorningReport
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PreviewResult(val text: Int) {
    Sent(R.string.ba_preview_sent),
    NoSleep(R.string.ba_preview_none),
    Failed(R.string.ba_preview_failed),
}

/** The alerts at the foot of Today: the morning report's switch, and the sleep guard's (Experimental, off until turned on). */
@Composable
fun BatteryAlertsPlate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reportOn by remember { mutableStateOf(BatteryAlerts.reportEnabled(context)) }
    var guardOn by remember { mutableStateOf(BatteryAlerts.guardEnabled(context)) }
    var notifyKey by remember { mutableIntStateOf(0) }
    val notifications = remember(notifyKey) { MorningReport.notificationsAllowed(context) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifyKey++ }
    val lastGuard = remember { BatteryAlerts.lastGuard(context) }
    var previewing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PreviewResult?>(null) }

    Plate(stringResource(R.string.ba_plate_title), modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
            Lamp(reportOn, Modifier.padding(top = 5.dp))
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ba_report_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(Space.s))
                    Tag(stringResource(if (reportOn) R.string.ba_state_on else R.string.ba_state_off))
                }
                Paragraph(stringResource(R.string.ba_report_body), color = Ink.Legend)
                if (reportOn && !notifications) {
                    Text(stringResource(R.string.ba_notify_blocked), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                }
                Key(
                    stringResource(R.string.ba_preview),
                    onClick = {
                        previewing = true
                        scope.launch {
                            preview = withContext(Dispatchers.IO) {
                                runCatching { if (MorningReport.previewNow(context)) PreviewResult.Sent else PreviewResult.NoSleep }
                                    .getOrDefault(PreviewResult.Failed)
                            }
                            previewing = false
                        }
                    },
                    modifier = Modifier.padding(top = Space.s),
                    enabled = !previewing,
                )
                preview?.let {
                    val line = if (it == PreviewResult.Sent && !notifications) R.string.ba_notify_blocked else it.text
                    Text(stringResource(line), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Key(
                    stringResource(if (reportOn) R.string.ba_turn_off else R.string.ba_turn_on),
                    onClick = {
                        reportOn = !reportOn
                        BatteryAlerts.setReportEnabled(context, reportOn)
                        if (reportOn && !notifications) askNotifications.launch(MorningReport.POST_NOTIFICATIONS)
                    },
                )
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
        Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
            Lamp(guardOn, Modifier.padding(top = 5.dp))
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ba_guard_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(Space.s))
                    Tag(stringResource(R.string.ba_experimental), strong = true)
                    Spacer(Modifier.size(Space.s))
                    Tag(stringResource(if (guardOn) R.string.ba_state_on else R.string.ba_state_off))
                }
                Paragraph(stringResource(R.string.ba_guard_body), color = Ink.Legend)
                lastGuard?.takeIf { guardOn }?.let {
                    Text(lastGuardLine(context, it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Key(
                    stringResource(if (guardOn) R.string.ba_turn_off else R.string.ba_turn_on),
                    onClick = {
                        guardOn = !guardOn
                        BatteryAlerts.setGuardEnabled(context, guardOn)
                    },
                )
            }
        }
    }
}

private fun lastGuardLine(context: Context, last: GuardRecord): String {
    val at = context.getString(R.string.ba_when, BatteryText.day(context, last.atMs), Format.clock(context, last.atMs))
    val how = last.healedByStep?.let { context.getString(R.string.ba_guard_healed_by, it) } ?: context.getString(R.string.ba_guard_not_healed_by)
    return context.getString(R.string.ba_guard_last, at, how)
}
