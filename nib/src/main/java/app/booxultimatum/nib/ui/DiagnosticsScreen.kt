package app.booxultimatum.nib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.ink.SurfaceInk
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.brush.WidthSteps
import app.booxultimatum.nib.diag.Answer
import app.booxultimatum.nib.diag.Band
import app.booxultimatum.nib.diag.Probe
import app.booxultimatum.nib.diag.ProbeView
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PreviewMatch
import app.booxultimatum.nib.ui.studio.ButtonKind
import app.booxultimatum.nib.ui.studio.EntryBar
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioSlider
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toggle
import app.booxultimatum.nib.ui.studio.ValueScale
import app.booxultimatum.nib.ui.studio.slab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Diagnostics: the questions about the display's pen path that only the tablet can answer, set out so the owner can
 * run them alone. A status page, the Match preview page, then one page per probe; every answer is logged under
 * `nib.probe`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, startOnMatch: Boolean = false) {
    var page by rememberSaveable { mutableIntStateOf(if (startOnMatch) 1 + Probe.entries.indexOf(Probe.MatchPreview) else 0) }
    var entry by remember { mutableStateOf<EntryRequest?>(null) }
    val probes = Probe.entries
    Box(Modifier.fillMaxSize()) {
        StudioPage(stringResource(R.string.title_diagnostics), stringResource(R.string.action_back), onBack) {
            FlowRow(Modifier.padding(horizontal = Studio.S4), horizontalArrangement = Arrangement.spacedBy(Studio.S2), verticalArrangement = Arrangement.spacedBy(Studio.S2)) {
                SlabButton(stringResource(R.string.diag_status), onClick = { page = 0 }, kind = if (page == 0) ButtonKind.Primary else ButtonKind.Plain)
                probes.forEachIndexed { i, p ->
                    SlabButton(stringResource(probeTitle(p)), onClick = { page = i + 1 }, kind = if (page == i + 1) ButtonKind.Primary else ButtonKind.Plain)
                }
            }
            Spacer(Modifier.height(Studio.S3))
            when {
                page == 0 -> StatusPage()
                probes[page - 1] == Probe.MatchPreview -> MatchPage(type = { entry = it })
                // Each probe gets its own surface and session: sharing one across tabs kept the first probe's bands
                // and logged nothing for the others (seen on the tablet, 2026-09-28).
                else -> androidx.compose.runtime.key(probes[page - 1]) { ProbePage(probes[page - 1]) }
            }
        }
        entry?.let { r ->
            EntryBar(r, stringResource(R.string.action_set), stringResource(R.string.action_cancel), onDone = { entry = null }, shields = null, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

private fun probeTitle(p: Probe): Int = when (p) {
    Probe.MatchPreview -> R.string.probe_match
    Probe.Styles -> R.string.probe_styles
    Probe.WidthsFountain -> R.string.probe_widths_fountain
    Probe.WidthsPencil -> R.string.probe_widths_pencil
    Probe.Colours -> R.string.probe_colours
    Probe.MarkerColours -> R.string.probe_marker_colours
    Probe.SwapDelay -> R.string.probe_swap
}

private fun probeHelp(p: Probe): Int = when (p) {
    Probe.MatchPreview -> R.string.probe_match_help
    Probe.Styles -> R.string.probe_styles_help
    Probe.WidthsFountain, Probe.WidthsPencil -> R.string.probe_widths_help
    Probe.Colours -> R.string.probe_colours_help
    Probe.MarkerColours -> R.string.probe_marker_colours_help
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
    val on = stringResource(R.string.toggle_on)
    val off = stringResource(R.string.toggle_off)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Studio.S5, vertical = Studio.S3),
        verticalArrangement = Arrangement.spacedBy(Studio.S5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val route = remember(probed) { SurfaceInk.route }
        val pressure = remember(probed) { SurfaceInk.maxTouchPressure }
        val sessionState = remember(probed) { NibPen.session.state }
        val range = remember(probed) { NibPen.stylusDevice()?.getMotionRange(android.view.MotionEvent.AXIS_PRESSURE)?.max }
        StudioSection(stringResource(R.string.diag_display), action = {
            SlabButton(stringResource(R.string.diag_probe_display), onClick = {
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
            SpecLine(stringResource(R.string.diag_route), route?.name ?: stringResource(R.string.diag_none), note = stringResource(R.string.diag_route_note))
            SpecLine(stringResource(R.string.diag_max_pressure), pressure?.let { WidthSteps.label(it) } ?: stringResource(R.string.diag_unknown))
            SpecLine(stringResource(R.string.diag_session), sessionState.name, note = stringResource(R.string.diag_session_note))
            SpecLine(stringResource(R.string.diag_tablet), "${tablet.name} · ${tablet.firmware ?: "?"}")
            SpecLine(stringResource(R.string.diag_stylus), tablet.pen?.name ?: stringResource(R.string.diag_none), note = range?.let { stringResource(R.string.diag_stylus_range, WidthSteps.label(it)) })
        }
        StudioSection(stringResource(R.string.diag_preview)) {
            Toggle(stringResource(R.string.diag_try_unverified), stringResource(R.string.diag_try_unverified_detail), settings.tryUnverifiedStyles, onToggle = {
                settings.tryUnverifiedStyles = it
                log.i("setting", "try unverified styles" to it)
            }, onText = on, offText = off)
            Text(stringResource(R.string.diag_swap_delay), style = StudioType.Title, modifier = Modifier.padding(top = Studio.S2))
            Text(stringResource(R.string.diag_swap_delay_detail), style = StudioType.Body, color = Studio.Legend)
            Segmented(
                NibSettings.SWAP_DELAYS.map { stringResource(R.string.unit_ms, it) }, NibSettings.SWAP_DELAYS.indexOf(settings.swapDelayMs),
                onSelect = {
                    settings.swapDelayMs = NibSettings.SWAP_DELAYS[it]
                    log.i("setting", "swap delay ms" to settings.swapDelayMs)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StudioSection(stringResource(R.string.diag_recorder)) {
            Toggle(stringResource(R.string.diag_recorder_switch), stringResource(R.string.diag_recorder_detail), settings.penRecorder, onToggle = {
                settings.penRecorder = it
                recorded = PenRecorderStore.strokeCount
                log.i("setting", "pen recorder" to it)
            }, onText = on, offText = off)
            Text(pluralStringResource(R.plurals.diag_recorded, recorded, recorded), style = StudioType.Body, color = Studio.Legend)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Studio.S2), verticalArrangement = Arrangement.spacedBy(Studio.S2)) {
                SlabButton(stringResource(R.string.diag_recorder_share), enabled = recorded > 0, onClick = {
                    scope.launch {
                        val (w, h) = NibPen.panelPortrait(context)
                        val max = NibPen.pressureRangeMax(null) ?: SurfaceInk.maxTouchPressure ?: 1f
                        val rec = PenRecorderStore.recording(w, h, max)
                        val file = withContext(Dispatchers.IO) { PenRecorderStore.export(File(File(context.cacheDir, "recordings"), "nib-pen.penrec"), rec) }
                        runCatching { context.startActivity(Exporter.shareFile(context, file, "application/octet-stream")) }
                    }
                })
                SlabButton(stringResource(R.string.diag_recorder_clear), enabled = recorded > 0, onClick = {
                    PenRecorderStore.clear()
                    recorded = 0
                })
                SlabButton(stringResource(R.string.diag_refresh), onClick = {
                    recorded = PenRecorderStore.strokeCount
                    probed++
                })
            }
        }
        Text(stringResource(R.string.diag_how), style = StudioType.Body, color = Studio.Legend, modifier = Modifier.fillMaxWidth())
    }
}

/** The probe's drawing surface on white paper, in a card. */
@Composable
private fun ProbeSurface(probe: Probe, labels: List<String>, previewFor: ((Band) -> app.booxultimatum.kit.ink.PreviewStroke)?, onView: (ProbeView) -> Unit, modifier: Modifier) {
    Box(modifier.padding(end = Studio.ShadowCard, bottom = Studio.ShadowCard).slab(radius = Studio.RadiusS, shadow = Studio.ShadowCard)) {
        AndroidView(
            factory = { c -> ProbeView(c).apply { id = R.id.nib_probe } },
            update = { v ->
                onView(v)
                v.previewFor = previewFor
                v.probe = probe
                v.labels = labels
            },
            modifier = Modifier.fillMaxSize().padding(Studio.Outline).clipToBounds(),
        )
    }
}

@Composable
private fun ProbePage(probe: Probe) {
    val log = remember { Logbook.logger("nib.probe") }
    val context = LocalContext.current
    val match = remember { NibSettings.get(context).previewMatch }
    // The Styles bands are judged on shape, so their preview is sized like the editor's: to the band brush's width at
    // the owner's usual pressure. The width probes send raw widths on purpose.
    val previewFor: ((Band) -> app.booxultimatum.kit.ink.PreviewStroke)? = if (probe == Probe.Styles) {
        { b -> app.booxultimatum.kit.ink.PreviewStroke(b.preview.style, match.width(b.brush, b.preview.style, b.preview.widthPx), b.preview.argb) }
    } else null
    val answers = remember(probe) { mutableStateMapOf<Int, Answer>() }
    var view by remember { mutableStateOf<ProbeView?>(null) }
    val labels = probe.bands.indices.map { i -> bandLabel(probe, i) }
    Column(Modifier.fillMaxSize().padding(horizontal = Studio.S5, vertical = Studio.S2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(probeHelp(probe)), style = StudioType.Body, color = Studio.Legend, modifier = Modifier.weight(1f).padding(end = Studio.S4))
            SlabButton(stringResource(R.string.diag_clear), onClick = { view?.clear() })
        }
        Spacer(Modifier.height(Studio.S3))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ProbeSurface(probe, labels, previewFor, onView = { view = it }, modifier = Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(430.dp).fillMaxHeight().padding(start = Studio.S3)) {
                probe.bands.forEachIndexed { i, band ->
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        Text(labels[i], style = StudioType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(Studio.S1))
                        Segmented(
                            Answer.entries.map { stringResource(answerLabel(it)) }, Answer.entries.indexOf(answers[i]),
                            onSelect = { k ->
                                val a = Answer.entries[k]
                                answers[i] = a
                                log.i(
                                    "answer",
                                    "probe" to probe.id, "band" to band.key, "answer" to a.id,
                                    "style" to band.preview.style, "width" to (previewFor?.invoke(band)?.widthPx ?: band.preview.widthPx),
                                    "argb" to Integer.toHexString(band.preview.argb), "delay ms" to band.swapDelayMs,
                                    "route" to SurfaceInk.route?.name, "session" to NibPen.session.state.name,
                                    "strokes" to (view?.strokeCount ?: 0),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (i < probe.bands.lastIndex) Box(Modifier.fillMaxWidth().height(Studio.Hairline).background(Studio.Faint))
                }
            }
        }
    }
}

/** How the owner judges one Match preview band. */
private enum class MatchAnswer(val id: String, val label: Int) {
    Wider("preview_wider", R.string.match_wider),
    Matches("matches", R.string.match_matches),
    Thinner("preview_thinner", R.string.match_thinner),
}

/**
 * Match preview: a band per verified preview style, each drawn with a brush that thins with pressure. The owner draws,
 * compares what the display previewed with the stroke that replaced it, and sets that style's preview size until the
 * two match. Every change and answer is logged.
 */
@Composable
private fun MatchPage(type: (EntryRequest) -> Unit) {
    val context = LocalContext.current
    val settings = remember { NibSettings.get(context) }
    val match = settings.previewMatch
    val log = remember { Logbook.logger("nib.probe") }
    val probe = Probe.MatchPreview
    val answers = remember { mutableStateMapOf<Int, MatchAnswer>() }
    var view by remember { mutableStateOf<ProbeView?>(null) }
    val labels = probe.bands.indices.map { i -> bandLabel(probe, i) }
    val tryUnverified = settings.tryUnverifiedStyles
    val previewFor: (Band) -> app.booxultimatum.kit.ink.PreviewStroke = { b -> PreviewPolicy.preview(b.brush, b.color, 1f, tryUnverified, match, settings.markerPreview) }
    val scale = remember { ValueScale.factor(PreviewMatch.FACTOR_RANGE.start, PreviewMatch.FACTOR_RANGE.endInclusive, PreviewMatch.FACTOR_STEP) }
    fun setFactor(style: Int, v: Float) {
        match.setFactor(style, v)
        log.i("match factor", "style" to style, "factor" to match.factor(style), "typical p" to match.typicalPressure, "session" to NibPen.session.state.name)
        view?.refreshPreview()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Studio.S5, vertical = Studio.S2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Studio.S4)) {
                Text(stringResource(R.string.probe_match_help), style = StudioType.Body, color = Studio.Legend)
                Text(stringResource(R.string.match_typical, ValueScale.formatNumber(match.typicalPressure)), style = StudioType.Value)
            }
            SlabButton(stringResource(R.string.match_reset), onClick = {
                match.reset()
                log.i("match reset")
                view?.refreshPreview()
            })
            Spacer(Modifier.width(Studio.S2))
            SlabButton(stringResource(R.string.diag_clear), onClick = { view?.clear() })
        }
        Spacer(Modifier.height(Studio.S3))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ProbeSurface(probe, labels, previewFor, onView = { view = it }, modifier = Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(450.dp).fillMaxHeight().padding(start = Studio.S3)) {
                probe.bands.forEachIndexed { i, band ->
                    val style = previewFor(band).style
                    val styleName = HardwareStyle.fromCode(style)?.let { stringResource(Names.style(it)) } ?: style.toString()
                    val factor = match.factor(style)
                    val label = stringResource(R.string.match_size, styleName)
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        Text(labels[i], style = StudioType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StudioSlider(
                            label, factor, scale, "×",
                            onCommit = { setFactor(style, it) },
                            onType = {
                                type(EntryRequest(label, scale.format(factor), numeric = true, hint = context.getString(R.string.entry_range, scale.format(scale.min), scale.format(scale.max), "×"), suffix = "×") { text ->
                                    when (val r = scale.parse(text)) {
                                        ValueScale.Parsed.Invalid -> context.getString(R.string.entry_not_a_number)
                                        is ValueScale.Parsed.Ok -> {
                                            setFactor(style, r.value)
                                            null
                                        }
                                    }
                                })
                            },
                            minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
                            typeLabel = stringResource(R.string.slider_type_value, label, scale.format(factor) + " ×"),
                            ticks = listOf(1f),
                        )
                        Segmented(
                            MatchAnswer.entries.map { stringResource(it.label) }, MatchAnswer.entries.indexOf(answers[i]),
                            onSelect = { k ->
                                val a = MatchAnswer.entries[k]
                                answers[i] = a
                                val sent = previewFor(band)
                                log.i(
                                    "match answer",
                                    "band" to band.key, "answer" to a.id, "style" to sent.style, "preview width" to sent.widthPx,
                                    "brush width" to band.brush.width, "factor" to match.factor(sent.style), "typical p" to match.typicalPressure,
                                    "route" to SurfaceInk.route?.name, "session" to NibPen.session.state.name, "strokes" to (view?.strokeCount ?: 0),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (i < probe.bands.lastIndex) Box(Modifier.fillMaxWidth().height(Studio.Hairline).background(Studio.Faint))
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
        Probe.MatchPreview -> stringResource(R.string.band_match, stringResource(Names.brush(b.brush.kind)), WidthSteps.label(b.brush.width))
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
        Probe.MarkerColours -> stringResource(MARKER_BAND_LABELS[i])
        Probe.SwapDelay -> stringResource(R.string.unit_ms, b.swapDelayMs.toInt())
    }
}

/** The Marker colours bands' names, in [Probe.MarkerColours]' order. */
private val MARKER_BAND_LABELS = listOf(
    R.string.band_marker_red_half,
    R.string.band_marker_red_opaque,
    R.string.band_marker_red_blended,
    R.string.band_marker_red_grey,
    R.string.band_marker_blue_opaque,
    R.string.band_marker_blue_grey,
    R.string.band_marker_mid_grey,
    R.string.band_marker_light_grey,
)
