package app.booxultimatum.nib.ui

import android.graphics.Rect
import android.view.WindowManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ink.eink.Eink
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.epd.HandwritingLayer
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.diag.Band
import app.booxultimatum.nib.diag.LabProbe
import app.booxultimatum.nib.diag.PanelGeometry
import app.booxultimatum.nib.diag.ProbeView
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.ui.studio.ButtonKind
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.Pill
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioSlider
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toggle
import app.booxultimatum.nib.ui.studio.ValueScale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Diagnostics › Lab: one probe per display call Nib relies on that hasn't been seen working on the tablet yet. Each
 * page has what to do, a surface where one is needed, and questions whose answers are logged at Info under
 * `nib.probe` with what was sent, so they come back with Share logs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabPage(type: (EntryRequest) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(LabProbe.PushInk.id) }
    val probe = LabProbe.entries.firstOrNull { it.id == chosen } ?: LabProbe.PushInk
    Column(Modifier.fillMaxSize().padding(horizontal = Studio.S5, vertical = Studio.S2)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Studio.S2), verticalArrangement = Arrangement.spacedBy(Studio.S2)) {
            for (p in LabProbe.entries) {
                SlabButton(stringResource(labTitle(p)), onClick = { chosen = p.id }, kind = if (p == probe) ButtonKind.Primary else ButtonKind.Plain)
            }
        }
        Spacer(Modifier.height(Studio.S3))
        // Each probe gets its own surface and session, as the probe pages do.
        key(probe) { LabContent(probe, type, Modifier.weight(1f).fillMaxWidth()) }
    }
}

@Composable
private fun LabContent(probe: LabProbe, type: (EntryRequest) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val settings = remember { NibSettings.get(context) }
    val log = remember { Logbook.logger("nib.probe") }
    var view by remember { mutableStateOf<ProbeView?>(null) }
    val answers = remember { mutableStateMapOf<String, String>() }
    val facts = remember { mutableStateMapOf<String, Any?>() }

    // Per probe: what the surface is set up with, and what the page shows beside it.
    var strokes by remember { mutableIntStateOf(0) }
    val counterArea = remember { Rect() }
    var fast by remember { mutableStateOf(true) }
    var guard by remember { mutableStateOf(false) }
    var nearTouches by remember { mutableIntStateOf(0) }
    var guardedTouches by remember { mutableIntStateOf(0) }
    var sensitivity by remember { mutableFloatStateOf(0.3f) }
    var smoothing by remember { mutableFloatStateOf(0.6f) }
    var fed by remember { mutableStateOf<List<Float?>?>(null) }
    val shadowPx = with(LocalDensity.current) { Studio.ShadowSlab.toPx() }

    fun context(): Array<Pair<String, Any?>> = arrayOf(
        "route" to Epd.route?.name, "session" to NibPen.session.state.name, "strokes" to (view?.strokeCount ?: 0),
        *facts.entries.map { it.key to it.value }.toTypedArray(),
    )

    val surface = probe.surface
    val previewFor: ((Band) -> InkStroke)? = if (probe == LabProbe.StyleParams) {
        { b -> InkStroke(HardwareStyle.Fountain.code, b.preview.widthPx, b.preview.argb, floatArrayOf(sensitivity, smoothing)) }
    } else null
    LaunchedEffect(strokes) { if (strokes > 0) view?.controlsChangedSoon() }

    Row(modifier) {
        if (surface != null) {
            val labels = surface.bands.map { labBandLabel(it) }
            ProbeSurface(
                surface, labels, previewFor,
                onView = { v ->
                    view = v
                    v.onStrokeAdded = { strokes++ }
                    v.pushControls = if (probe == LabProbe.PushControls) {
                        { counterArea.width() > 0 && HandwritingLayer.pushArea(v.rootView, counterArea) }
                    } else null
                    v.fastGestures = fast
                    v.palmGuard = guard
                    v.pauseMs = settings.revealPauseMs.toLong()
                    v.onFingerTouch = { near, on ->
                        if (near) nearTouches++
                        if (near && on) guardedTouches++
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Column(
            Modifier.then(if (surface != null) Modifier.width(430.dp) else Modifier.weight(1f)).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(start = if (surface != null) Studio.S3 else 0.dp),
            verticalArrangement = Arrangement.spacedBy(Studio.S3),
        ) {
            Text(labHelp(probe, settings.revealPauseMs), style = StudioType.Body, color = Studio.Legend)
            when (probe) {
                LabProbe.PushControls -> Pill(Modifier.onGloballyPositioned { c ->
                    // The pill and its shadow, in window pixels: what's pushed after each stroke.
                    val b = c.boundsInWindow()
                    counterArea.set(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right + shadowPx).toInt(), ceil(b.bottom + shadowPx).toInt())
                }) {
                    Text(pluralStringResource(R.plurals.lab_counter, strokes, strokes), style = StudioType.Value, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                LabProbe.FastMode -> Toggle(stringResource(R.string.lab_fast_toggle), null, fast, onToggle = {
                    fast = it
                    view?.fastGestures = it
                    log.i("lab setting", "probe" to probe.id, "fast mode" to it)
                }, onText = stringResource(R.string.toggle_on), offText = stringResource(R.string.toggle_off))
                LabProbe.PalmGuard -> {
                    Toggle(stringResource(R.string.lab_palm_toggle), null, guard, onToggle = {
                        guard = it
                        view?.palmGuard = it
                        log.i("lab setting", "probe" to probe.id, "palm guard" to it)
                    }, onText = stringResource(R.string.toggle_on), offText = stringResource(R.string.toggle_off))
                    Text(pluralStringResource(R.plurals.lab_palm_near, nearTouches, nearTouches), style = StudioType.Label)
                    Text(pluralStringResource(R.plurals.lab_palm_guarded, guardedTouches, guardedTouches), style = StudioType.Small, color = Studio.Legend)
                }
                LabProbe.StyleParams -> {
                    val scale = remember { ValueScale.linear(0f, 1f, 0.05f) }
                    ParamSlider(stringResource(R.string.lab_sensitivity), sensitivity, scale, type) {
                        sensitivity = it
                        facts["sensitivity"] = it
                        log.i("lab parameters", "probe" to probe.id, "sensitivity" to sensitivity, "smoothing" to smoothing)
                        view?.refreshPreview()
                    }
                    ParamSlider(stringResource(R.string.lab_smoothing), smoothing, scale, type) {
                        smoothing = it
                        facts["smoothing"] = it
                        log.i("lab parameters", "probe" to probe.id, "sensitivity" to sensitivity, "smoothing" to smoothing)
                        view?.refreshPreview()
                    }
                }
                LabProbe.FedStroke -> {
                    SlabButton(stringResource(R.string.lab_feed), onClick = { fed = view?.feedStroke() }, kind = ButtonKind.Primary)
                    fed?.let { w ->
                        val answered = w.filterNotNull()
                        Text(
                            if (answered.isEmpty()) stringResource(R.string.lab_fed_none)
                            else stringResource(R.string.lab_fed_widths, answered.joinToString(", ") { ValueScale.formatNumber(it) }),
                            style = StudioType.Small, color = Studio.Legend,
                        )
                    }
                }
                LabProbe.Geometry -> GeometryCard()
                LabProbe.CleanScreen -> Row(horizontalArrangement = Arrangement.spacedBy(Studio.S2)) {
                    SlabButton(stringResource(R.string.lab_clean_deep), onClick = {
                        val ok = Eink.cleanScreen(deep = true)
                        log.i("lab clean screen", "deep" to true, "ok" to ok)
                    })
                    SlabButton(stringResource(R.string.lab_clean_gc), onClick = {
                        val ok = Eink.cleanScreen(deep = false)
                        log.i("lab clean screen", "deep" to false, "ok" to ok)
                    })
                }
                else -> Unit
            }
            for (q in probe.questions) {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(labQuestion(q.key)), style = StudioType.Label)
                    Spacer(Modifier.height(Studio.S1))
                    Segmented(
                        q.answers.map { stringResource(labAnswer(it)) }, q.answers.indexOf(answers[q.key]),
                        onSelect = { k ->
                            val a = q.answers[k]
                            answers[q.key] = a
                            log.i(
                                "lab answer", "probe" to probe.id, "question" to q.key, "answer" to a,
                                "fast mode" to fast.takeIf { probe == LabProbe.FastMode }, "palm guard" to guard.takeIf { probe == LabProbe.PalmGuard },
                                "near touches" to nearTouches.takeIf { probe == LabProbe.PalmGuard }, "counter" to strokes.takeIf { probe == LabProbe.PushControls },
                                "reveal pause ms" to settings.revealPauseMs.takeIf { probe == LabProbe.PushInk },
                                *context(),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (surface != null) {
                SlabButton(stringResource(R.string.diag_clear), onClick = {
                    view?.clear()
                    strokes = 0
                })
            }
        }
    }
}

/** A 0 to 1 slider for one of the fountain style's own settings, with a value to type. */
@Composable
private fun ParamSlider(label: String, value: Float, scale: ValueScale, type: (EntryRequest) -> Unit, onValue: (Float) -> Unit) {
    val context = LocalContext.current
    StudioSlider(
        label, value, scale, "",
        onCommit = onValue,
        onType = {
            type(EntryRequest(label, scale.format(value), numeric = true, hint = context.getString(R.string.entry_range, scale.format(scale.min), scale.format(scale.max), "").trim()) { text ->
                when (val r = scale.parse(text)) {
                    ValueScale.Parsed.Invalid -> context.getString(R.string.entry_not_a_number)
                    is ValueScale.Parsed.Ok -> {
                        onValue(r.value)
                        null
                    }
                }
            })
        },
        minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
        typeLabel = stringResource(R.string.slider_type_value, label, scale.format(value)),
    )
}

/**
 * Lab › Geometry: the panel's and the digitizer's size, the panel-to-screen matrix, and where the display maps each
 * corner of the screen for the way the tablet is turned now, checked against the matrix. Read again after a turn.
 */
@Composable
private fun GeometryCard() {
    val context = LocalContext.current
    val view = LocalView.current
    val log = remember { Logbook.logger("nib.probe") }
    var reads by remember { mutableIntStateOf(0) }
    val reading = remember(reads) {
        Epd.connect()
        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val matrix = Epd.panelToScreenMatrix()
        val inverse = matrix?.let { PanelGeometry.invert(it) }
        val corners = PanelGeometry.corners(bounds.width().toFloat(), bounds.height().toFloat()).map { c ->
            val mapped = Epd.mapToPanel(c[0], c[1])
            val expected = inverse?.let { PanelGeometry.apply(it, c[0], c[1]) }
            Triple(c, mapped, PanelGeometry.agree(mapped, expected) to expected)
        }
        GeometryReading(Epd.panelSize(), Epd.digitizerSize(), matrix, view.display?.rotation ?: -1, bounds.width(), bounds.height(), corners)
    }
    LaunchedEffect(reading) {
        log.i(
            "geometry", "rotation" to reading.rotation, "screen" to "${reading.width}x${reading.height}",
            "panel" to reading.panel?.let { "${it.first}x${it.second}" }, "digitizer" to reading.digitizer?.let { "${it.first}x${it.second}" },
            "matrix" to reading.matrix?.joinToString(","),
            "corners" to reading.corners.joinToString("; ") { (c, m, a) -> "${fmt(c)}→${m?.let { fmt(it) } ?: "none"}${if (a.first) " ok" else " expected ${a.second?.let { fmt(it) } ?: "?"}"}" },
            "route" to Epd.route?.name,
        )
    }
    val none = stringResource(R.string.diag_unknown)
    Column(Modifier.fillMaxWidth().background(Studio.Card)) {
        SpecLine(stringResource(R.string.lab_rotation), "${reading.rotation} · ${reading.width} × ${reading.height}")
        SpecLine(stringResource(R.string.lab_panel_size), reading.panel?.let { "${fmtN(it.first)} × ${fmtN(it.second)}" } ?: none)
        SpecLine(stringResource(R.string.lab_digitizer_size), reading.digitizer?.let { "${fmtN(it.first)} × ${fmtN(it.second)}" } ?: none)
        SpecLine(stringResource(R.string.lab_matrix), reading.matrix?.joinToString(" ") { fmtN(it) } ?: none)
        reading.corners.forEachIndexed { i, (c, mapped, check) ->
            SpecLine(
                stringResource(R.string.lab_corner, i + 1, fmt(c)), mapped?.let { fmt(it) } ?: none,
                note = if (check.first) stringResource(R.string.lab_corner_agrees) else stringResource(R.string.lab_corner_differs, check.second?.let { fmt(it) } ?: none),
            )
        }
        Spacer(Modifier.height(Studio.S2))
        SlabButton(stringResource(R.string.diag_refresh), onClick = { reads++ })
    }
}

private class GeometryReading(
    val panel: Pair<Float, Float>?,
    val digitizer: Pair<Float, Float>?,
    val matrix: FloatArray?,
    val rotation: Int,
    val width: Int,
    val height: Int,
    val corners: List<Triple<FloatArray, FloatArray?, Pair<Boolean, FloatArray?>>>,
)

private fun fmtN(v: Float): String = ValueScale.formatNumber(v, 3)

private fun fmt(p: FloatArray): String = "(${fmtN(p[0])}, ${fmtN(p[1])})"

private fun labTitle(p: LabProbe): Int = when (p) {
    LabProbe.PushInk -> R.string.lab_push_ink
    LabProbe.PushControls -> R.string.lab_push_controls
    LabProbe.EraserEnd -> R.string.lab_eraser_end
    LabProbe.Lasso -> R.string.lab_lasso
    LabProbe.FastMode -> R.string.lab_fast_mode
    LabProbe.PalmGuard -> R.string.lab_palm_guard
    LabProbe.StyleParams -> R.string.lab_style_params
    LabProbe.Limit -> R.string.lab_limit
    LabProbe.FedStroke -> R.string.lab_fed_stroke
    LabProbe.PenState4 -> R.string.lab_pen_state_4
    LabProbe.EraserPainters -> R.string.lab_eraser_painters
    LabProbe.Geometry -> R.string.lab_geometry
    LabProbe.CleanScreen -> R.string.lab_clean_screen
}

@Composable
private fun labHelp(p: LabProbe, pauseMs: Int): String = when (p) {
    LabProbe.PushInk -> stringResource(R.string.lab_push_ink_help, stringResource(R.string.unit_ms, pauseMs))
    LabProbe.PushControls -> stringResource(R.string.lab_push_controls_help)
    LabProbe.EraserEnd -> stringResource(R.string.lab_eraser_end_help)
    LabProbe.Lasso -> stringResource(R.string.lab_lasso_help)
    LabProbe.FastMode -> stringResource(R.string.lab_fast_mode_help)
    LabProbe.PalmGuard -> stringResource(R.string.lab_palm_guard_help)
    LabProbe.StyleParams -> stringResource(R.string.lab_style_params_help)
    LabProbe.Limit -> stringResource(R.string.lab_limit_help)
    LabProbe.FedStroke -> stringResource(R.string.lab_fed_stroke_help)
    LabProbe.PenState4 -> stringResource(R.string.lab_pen_state_4_help)
    LabProbe.EraserPainters -> stringResource(R.string.lab_eraser_painters_help)
    LabProbe.Geometry -> stringResource(R.string.lab_geometry_help)
    LabProbe.CleanScreen -> stringResource(R.string.lab_clean_screen_help)
}

private fun labQuestion(key: String): Int = when (key) {
    "push black" -> R.string.lab_q_push_black
    "push red" -> R.string.lab_q_push_red
    "push charcoal" -> R.string.lab_q_push_charcoal
    "pushed colour" -> R.string.lab_q_pushed_colour
    "counter" -> R.string.lab_q_counter
    "eraser track" -> R.string.lab_q_eraser_track
    "strokes erased" -> R.string.lab_q_strokes_erased
    "lasso path" -> R.string.lab_q_lasso_path
    "fast on" -> R.string.lab_q_fast_on
    "fast off" -> R.string.lab_q_fast_off
    "resting hand" -> R.string.lab_q_resting_hand
    "preview follows" -> R.string.lab_q_preview_follows
    "limit" -> R.string.lab_q_limit
    "fed stroke drawn" -> R.string.lab_q_fed_drawn
    "state 4 preview" -> R.string.lab_q_state4_preview
    "painter 0" -> R.string.lab_q_painter_0
    "painter 1" -> R.string.lab_q_painter_1
    "painter 2" -> R.string.lab_q_painter_2
    "painter 3" -> R.string.lab_q_painter_3
    "painter 4" -> R.string.lab_q_painter_4
    "painter 5" -> R.string.lab_q_painter_5
    "painter 6" -> R.string.lab_q_painter_6
    "painter 7" -> R.string.lab_q_painter_7
    "painter 8" -> R.string.lab_q_painter_8
    "deep clean" -> R.string.lab_clean_deep
    else -> R.string.lab_clean_gc
}

private fun labAnswer(id: String): Int = when (id) {
    "exact" -> R.string.lab_a_exact
    "nothing_changed" -> R.string.lab_a_nothing_changed
    "garbled" -> R.string.lab_a_garbled
    "wrong_place" -> R.string.lab_a_wrong_place
    "in_colour" -> R.string.lab_a_in_colour
    "as_grey" -> R.string.lab_a_as_grey
    "not_shown" -> R.string.lab_a_not_shown
    "kept_up" -> R.string.lab_a_kept_up
    "stuck" -> R.string.lab_a_stuck
    "track_shown" -> R.string.lab_a_track_shown
    "no_track" -> R.string.lab_a_no_track
    "drew_ink" -> R.string.lab_a_drew_ink
    "erased" -> R.string.lab_a_erased
    "not_erased" -> R.string.lab_a_not_erased
    "dashed" -> R.string.lab_a_dashed
    "solid" -> R.string.lab_a_solid
    "nothing" -> R.string.lab_a_nothing
    "smooth" -> R.string.lab_a_smooth
    "jumps" -> R.string.lab_a_jumps
    "smears" -> R.string.lab_a_smears
    "ignored" -> R.string.lab_a_ignored
    "touched" -> R.string.lab_a_touched
    "stayed_off" -> R.string.lab_a_stayed_off
    "follows" -> R.string.lab_a_follows
    "no_change" -> R.string.lab_a_no_change
    "broken" -> R.string.answer_broken
    "inside_only" -> R.string.lab_a_inside_only
    "everywhere" -> R.string.lab_a_everywhere
    "nowhere" -> R.string.lab_a_nowhere
    "drawn" -> R.string.lab_a_drawn
    "preview_shown" -> R.string.lab_a_preview_shown
    "no_preview" -> R.string.lab_a_no_preview
    "clean" -> R.string.lab_a_clean
    "ghosting" -> R.string.lab_a_ghosting
    else -> R.string.lab_a_no_refresh
}

/** The label drawn in a Lab surface's band. */
@Composable
internal fun labBandLabel(b: Band): String {
    if (b.key.startsWith("painter ")) {
        return stringResource(R.string.band_painter, b.key.removePrefix("painter ").toIntOrNull() ?: -1)
    }
    return stringResource(
        when (b.key) {
            "push black" -> R.string.band_push_black
        "push red" -> R.string.band_push_red
        "push charcoal" -> R.string.band_push_charcoal
        "counter" -> R.string.band_counter
        "eraser end" -> R.string.band_eraser_end
        "ink" -> R.string.band_lasso_ink
        "lasso" -> R.string.band_lasso
        "fast mode" -> R.string.band_fast
        "palm guard" -> R.string.band_palm
        "parameters" -> R.string.band_params
        "above the limit" -> R.string.band_limit_above
        "inside the limit" -> R.string.band_limit_inside
        "below the limit" -> R.string.band_limit_below
        "state 4" -> R.string.band_state4
        else -> R.string.band_fed
    },
    )
}
