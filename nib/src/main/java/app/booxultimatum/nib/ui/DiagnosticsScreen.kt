package app.booxultimatum.nib.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.ink.SurfaceInk
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.WidthSteps
import app.booxultimatum.nib.diag.Answer
import app.booxultimatum.nib.diag.Probe
import app.booxultimatum.nib.diag.ProbeView
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.pen.PenRecorderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Diagnostics: the questions about the display's pen path that only the tablet can answer, set out so the owner can
 * run them alone. A status page, then one page per probe; every answer is logged under `nib.probe`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val probes = Probe.entries
    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            ToolKey(Glyphs.ChevronLeft, stringResource(R.string.action_back), onClick = onBack)
            Spacer(Modifier.width(Space.m))
            Text(stringResource(R.string.title_diagnostics), style = MaterialTheme.typography.headlineMedium)
        }
        FlowRow(Modifier.padding(horizontal = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            ChipKey(stringResource(R.string.diag_status), page == 0, onClick = { page = 0 })
            probes.forEachIndexed { i, p -> ChipKey(stringResource(probeTitle(p)), page == i + 1, onClick = { page = i + 1 }) }
        }
        Spacer(Modifier.height(Space.s))
        HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        if (page == 0) StatusPage() else ProbePage(probes[page - 1])
    }
}

private fun probeTitle(p: Probe): Int = when (p) {
    Probe.Styles -> R.string.probe_styles
    Probe.WidthsFountain -> R.string.probe_widths_fountain
    Probe.WidthsPencil -> R.string.probe_widths_pencil
    Probe.Colours -> R.string.probe_colours
    Probe.SwapDelay -> R.string.probe_swap
}

private fun probeHelp(p: Probe): Int = when (p) {
    Probe.Styles -> R.string.probe_styles_help
    Probe.WidthsFountain, Probe.WidthsPencil -> R.string.probe_widths_help
    Probe.Colours -> R.string.probe_colours_help
    Probe.SwapDelay -> R.string.probe_swap_help
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPage() {
    val context = LocalContext.current
    val settings = remember { NibSettings.get(context) }
    val log = remember { Logbook.logger("nib.probe") }
    val scope = rememberCoroutineScope()
    var probed by remember { mutableIntStateOf(0) }
    var recorded by remember { mutableIntStateOf(PenRecorderStore.strokeCount) }
    val tablet = remember { Tablet.current(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) {
        val route = remember(probed) { SurfaceInk.route }
        val pressure = remember(probed) { SurfaceInk.maxTouchPressure }
        val sessionState = remember(probed) { NibPen.session.state }
        val range = remember(probed) { NibPen.stylusDevice()?.getMotionRange(android.view.MotionEvent.AXIS_PRESSURE)?.max }
        Plate(stringResource(R.string.diag_display), action = {
            Key(stringResource(R.string.diag_probe_display), onClick = {
                val r = SurfaceInk.connect()
                log.i(
                    "display probe",
                    "route" to r?.name, "max pressure" to SurfaceInk.maxTouchPressure, "pen state" to SurfaceInk.penState(),
                    "boox" to tablet.isBoox, "model" to tablet.model, "firmware" to tablet.firmware,
                    "stylus range max" to NibPen.stylusDevice()?.getMotionRange(android.view.MotionEvent.AXIS_PRESSURE)?.max,
                )
                probed++
            })
        }) {
            SpecRow(stringResource(R.string.diag_route), route?.name ?: stringResource(R.string.diag_none), note = stringResource(R.string.diag_route_note))
            SpecRow(stringResource(R.string.diag_max_pressure), pressure?.let { WidthSteps.label(it) } ?: stringResource(R.string.diag_unknown))
            SpecRow(stringResource(R.string.diag_session), sessionState.name, note = stringResource(R.string.diag_session_note))
            SpecRow(stringResource(R.string.diag_tablet), "${tablet.name} · ${tablet.firmware ?: "?"}")
            SpecRow(stringResource(R.string.diag_stylus), tablet.pen?.name ?: stringResource(R.string.diag_none), note = range?.let { stringResource(R.string.diag_stylus_range, WidthSteps.label(it)) })
        }
        Spacer(Modifier.height(Space.xl))
        Plate(stringResource(R.string.diag_preview)) {
            ToggleRow(stringResource(R.string.diag_try_unverified), stringResource(R.string.diag_try_unverified_detail), settings.tryUnverifiedStyles, onToggle = {
                settings.tryUnverifiedStyles = it
                log.i("setting", "try unverified styles" to it)
            })
            Text(stringResource(R.string.diag_swap_delay), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.m))
            Text(stringResource(R.string.diag_swap_delay_detail), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            Row(Modifier.padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (d in NibSettings.SWAP_DELAYS) ChipKey(stringResource(R.string.unit_ms, d), settings.swapDelayMs == d, onClick = {
                    settings.swapDelayMs = d
                    log.i("setting", "swap delay ms" to d)
                })
            }
        }
        Spacer(Modifier.height(Space.xl))
        Plate(stringResource(R.string.diag_recorder)) {
            ToggleRow(stringResource(R.string.diag_recorder_switch), stringResource(R.string.diag_recorder_detail), settings.penRecorder, onToggle = {
                settings.penRecorder = it
                recorded = PenRecorderStore.strokeCount
                log.i("setting", "pen recorder" to it)
            })
            Text(pluralStringResource(R.plurals.diag_recorded, recorded, recorded), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Key(stringResource(R.string.diag_recorder_share), enabled = recorded > 0, onClick = {
                    scope.launch {
                        val (w, h) = NibPen.panelPortrait(context)
                        val max = NibPen.pressureRangeMax(null) ?: SurfaceInk.maxTouchPressure ?: 1f
                        val rec = PenRecorderStore.recording(w, h, max)
                        val file = withContext(Dispatchers.IO) { PenRecorderStore.export(File(File(context.cacheDir, "recordings"), "nib-pen.penrec"), rec) }
                        runCatching { context.startActivity(Exporter.shareFile(context, file, "application/octet-stream")) }
                    }
                })
                Key(stringResource(R.string.diag_recorder_clear), enabled = recorded > 0, onClick = {
                    PenRecorderStore.clear()
                    recorded = 0
                })
                Key(stringResource(R.string.diag_refresh), onClick = { recorded = PenRecorderStore.strokeCount; probed++ })
            }
        }
        Spacer(Modifier.height(Space.xl))
        Paragraph(stringResource(R.string.diag_how), color = Ink.Legend)
    }
}

@Composable
private fun ProbePage(probe: Probe) {
    val context = LocalContext.current
    val log = remember { Logbook.logger("nib.probe") }
    val answers = remember(probe) { mutableStateMapOf<Int, Answer>() }
    var view by remember { mutableStateOf<ProbeView?>(null) }
    val labels = probe.bands.indices.map { i -> bandLabel(probe, i) }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.l, vertical = Space.m)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(probeHelp(probe)), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.weight(1f).padding(end = Space.m))
            Key(stringResource(R.string.diag_clear), onClick = { view?.clear() })
        }
        Spacer(Modifier.height(Space.s))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c -> ProbeView(c).apply { id = R.id.nib_probe } },
                update = { v ->
                    view = v
                    v.probe = probe
                    v.labels = labels
                },
                modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
            )
            Column(Modifier.width(430.dp).fillMaxHeight().padding(start = Space.m)) {
                probe.bands.forEachIndexed { i, band ->
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        Text(labels[i], style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                            for (a in Answer.entries) {
                                ChipKey(stringResource(answerLabel(a)), answers[i] == a, onClick = {
                                    answers[i] = a
                                    log.i(
                                        "answer",
                                        "probe" to probe.id, "band" to band.key, "answer" to a.id,
                                        "style" to band.preview.style, "width" to band.preview.widthPx,
                                        "argb" to Integer.toHexString(band.preview.argb), "delay ms" to band.swapDelayMs,
                                        "route" to SurfaceInk.route?.name, "session" to NibPen.session.state.name,
                                        "strokes" to (view?.strokeCount ?: 0),
                                    )
                                })
                            }
                        }
                    }
                    if (i < probe.bands.lastIndex) HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
            }
        }
    }
}

private fun answerLabel(a: Answer): Int = when (a) {
    Answer.LooksRight -> R.string.answer_looks_right
    Answer.Broken -> R.string.answer_broken
    Answer.NothingDrawn -> R.string.answer_nothing
}

@Composable
private fun bandLabel(probe: Probe, i: Int): String {
    val b = probe.bands[i]
    return when (probe) {
        Probe.Styles -> {
            val style = HardwareStyle.fromCode(b.preview.style)!!
            stringResource(if (style.verified) R.string.band_style else R.string.band_style_unverified, style.code, stringResource(Names.style(style)))
        }
        Probe.WidthsFountain, Probe.WidthsPencil -> stringResource(R.string.band_width, WidthSteps.label(b.preview.widthPx))
        Probe.Colours -> stringResource(
            when (i) {
                0 -> R.string.colour_red
                1 -> R.string.colour_blue
                2 -> R.string.colour_green
                3 -> R.string.band_grey
                4 -> R.string.band_translucent_fountain
                else -> R.string.band_translucent_pencil
            },
        )
        Probe.SwapDelay -> stringResource(R.string.unit_ms, b.swapDelayMs.toInt())
    }
}
