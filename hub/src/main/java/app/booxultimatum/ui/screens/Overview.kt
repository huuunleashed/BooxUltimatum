package app.booxultimatum.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.theme.Ink
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.battery.BatteryModel
import app.booxultimatum.core.battery.BatteryView
import app.booxultimatum.core.battery.VerdictState
import app.booxultimatum.core.DeviceReader
import app.booxultimatum.core.PrivilegeStatus
import app.booxultimatum.core.Report
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.LampRow
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.TuningScale
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.ui.battery.BatteryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun OverviewScreen(
    readKey: Int,
    accessKey: Int,
    compact: Boolean,
    onReadAgain: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenTweaks: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val battery = rememberReading(readKey) { BatterySnapshot.read(context) }
    val batteryView = rememberReading(readKey) { BatteryModel.load(context, BATTERY_WINDOW_MS, fresh = readKey > 0) }
    val access = rememberReading(readKey to accessKey) { PrivilegeStatus.check(context) }
    val identity = rememberReading(readKey) { DeviceReader.identity() }

    InstrumentPage(compact) {
        item {
            val subtitle = identity?.let {
                if (it.firmwareVersion != null) stringResource(R.string.overview_subtitle, it.model, it.firmwareVersion, it.androidRelease)
                else stringResource(R.string.overview_subtitle_nofw, it.model, it.androidRelease)
            }
            ScreenHeader(stringResource(R.string.app_name), subtitle) {
                Key(stringResource(R.string.action_share), onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { Report.build(context) }
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title)))
                    }
                })
                Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true)
            }
        }
        item {
            if (battery == null) Reading() else {
                TuningScale(battery.levelPct)
                Spacer(Modifier.height(Space.l))
                StatusLines(battery)
            }
        }
        item {
            Spacer(Modifier.height(Space.xxl))
            PlatePair(
                left = { m -> BatteryPlate(batteryView, battery, m, onOpen = onOpenBattery) },
                right = { m -> AccessSummary(access, m, onOpenAccess) },
            )
        }
        item {
            Spacer(Modifier.height(Space.xxl))
            val tweakStates = rememberReading(readKey to accessKey) {
                app.booxultimatum.core.tweaks.Catalog.all.map { it.state(context) }
            }
            Plate(stringResource(R.string.plate_tweaks_summary), action = { Key(stringResource(R.string.action_open_tweaks), onClick = onOpenTweaks) }) {
                if (tweakStates == null) Reading() else {
                    val on = tweakStates.count { it == app.booxultimatum.core.tweaks.TweakState.On }
                    Text(stringResource(R.string.tweaks_on_count, on, tweakStates.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.s))
                    Paragraph(stringResource(R.string.tweaks_summary_explain), color = Ink.Legend)
                }
            }
        }
        item {
            Spacer(Modifier.height(Space.xxl))
            Plate(stringResource(R.string.plate_planned)) {
                PlannedRow(stringResource(R.string.pillar_profiles), stringResource(R.string.pillar_profiles_purpose))
                PlannedRow(stringResource(R.string.pillar_firewall), stringResource(R.string.pillar_firewall_purpose))
                PlannedRow(stringResource(R.string.pillar_scheduler), stringResource(R.string.pillar_scheduler_purpose))
            }
        }
    }
}

@Composable
private fun BatteryPlate(view: BatteryView?, battery: BatterySnapshot?, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    Plate(stringResource(R.string.dest_battery), modifier, action = { Key(stringResource(R.string.action_details), onClick = onOpen) }) {
        if (view == null || battery == null) Reading() else {
            val state = view.verdict.state
            val worst = view.verdict.findings.firstOrNull().takeIf { state == VerdictState.Watch || state == VerdictState.Problem }
            Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                VerdictMark(state, Modifier.size(20.dp))
                Text(BatteryText.verdictTitle(context, state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.s))
            }
            worst?.let { Text(BatteryText.findingTitle(context, it), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs)) }
            Text(stringResource(R.string.bt_percent, battery.levelPct), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Space.m))
            TimeLeft(view, battery)
        }
    }
}

@Composable
private fun VerdictMark(state: VerdictState, modifier: Modifier) {
    Canvas(modifier) {
        val ring = Lines.engraved.toPx()
        val outline = Stroke(width = ring, join = StrokeJoin.Round)
        val radius = (size.minDimension - ring) / 2
        val triangle = Path().apply {
            moveTo(size.width / 2, size.height * 0.06f)
            lineTo(size.width * 0.96f, size.height * 0.92f)
            lineTo(size.width * 0.04f, size.height * 0.92f)
            close()
        }
        when (state) {
            VerdictState.Normal -> {
                drawCircle(Ink.Black, radius = radius, style = outline)
                val tick = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.72f, size.height * 0.36f)
                }
                drawPath(tick, Ink.Black, style = Stroke(width = ring * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            VerdictState.Watch -> drawPath(triangle, Ink.Black, style = outline)
            VerdictState.Problem -> drawPath(triangle, Ink.Black)
            VerdictState.NoData -> drawCircle(
                Ink.Black,
                radius = radius,
                style = Stroke(width = ring, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
    }
}

@Composable
private fun AccessSummary(access: PrivilegeStatus?, modifier: Modifier, onOpen: () -> Unit) {
    Plate(stringResource(R.string.plate_access), modifier, action = { Key(stringResource(R.string.action_details), onClick = onOpen) }) {
        if (access == null) Reading() else {
            LampRow(true, stringResource(R.string.tier_app), stringResource(R.string.tier_app_short))
            LampRow(
                access.t1Granted == PrivilegeStatus.T1_GRANTS,
                stringResource(R.string.tier_adb),
                stringResource(R.string.tier_adb_count, access.t1Granted, PrivilegeStatus.T1_GRANTS),
            )
            LampRow(access.shizukuRunning && access.shizukuGranted, stringResource(R.string.tier_shizuku), shizukuDetail(access))
            LampRow(false, stringResource(R.string.tier_root), stringResource(R.string.tier_root_short))
        }
    }
}

@Composable
fun shizukuDetail(access: PrivilegeStatus): String = stringResource(
    when {
        !access.shizukuRunning -> R.string.shizuku_not_running
        !access.shizukuGranted -> R.string.shizuku_not_allowed
        else -> R.string.shizuku_ready
    },
)
