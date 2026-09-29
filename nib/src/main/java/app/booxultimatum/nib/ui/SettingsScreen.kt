package app.booxultimatum.nib.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.engine.brush.MarkerPreview
import app.booxultimatum.nib.pen.RevealChoice
import app.booxultimatum.nib.pen.RevealPolicy
import app.booxultimatum.nib.ui.studio.EntryBar
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.PanelMemory
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioSlider
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toggle
import app.booxultimatum.nib.ui.studio.ValueScale

/** Nib's settings: how the pen and fingers behave, where the rail sits, the panels' places, and the display's preview. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onMatchPreview: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { NibSettings.get(context) }
    val log = remember { Logbook.logger("nib.ui") }
    val on = stringResource(R.string.toggle_on)
    val off = stringResource(R.string.toggle_off)
    var panelsReset by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<EntryRequest?>(null) }
    Box(Modifier.fillMaxSize()) {
        StudioPage(stringResource(R.string.menu_settings), stringResource(R.string.action_back), onBack) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Studio.S5, vertical = Studio.S3),
                verticalArrangement = Arrangement.spacedBy(Studio.S5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StudioSection(stringResource(R.string.settings_pen)) {
                    Toggle(stringResource(R.string.setting_straight), stringResource(R.string.setting_straight_detail), settings.straightLineHold, onToggle = {
                        settings.straightLineHold = it
                        log.i("setting", "straight line hold" to it)
                    }, onText = on, offText = off)
                    Toggle(stringResource(R.string.setting_left_handed), stringResource(R.string.setting_left_handed_detail), settings.leftHanded, onToggle = {
                        settings.leftHanded = it
                        log.i("setting", "left handed" to it)
                    }, onText = on, offText = off)
                }
                StudioSection(stringResource(R.string.settings_fingers)) {
                    Toggle(stringResource(R.string.setting_finger_drawing), stringResource(R.string.setting_finger_drawing_detail), settings.fingerDrawing, onToggle = {
                        settings.fingerDrawing = it
                        log.i("setting", "finger drawing" to it)
                    }, onText = on, offText = off)
                    Toggle(stringResource(R.string.setting_finger_pan), stringResource(R.string.setting_finger_pan_detail), settings.fingerPan, onToggle = {
                        settings.fingerPan = it
                        log.i("setting", "finger pan" to it)
                    }, onText = on, offText = off)
                    Toggle(stringResource(R.string.setting_fast_gestures), stringResource(R.string.setting_fast_gestures_detail), settings.fastGestures, onToggle = {
                        settings.fastGestures = it
                        log.i("setting", "fast gestures" to it)
                    }, onText = on, offText = off)
                    Toggle(stringResource(R.string.setting_palm_guard), stringResource(R.string.setting_palm_guard_detail), settings.palmGuard, onToggle = {
                        settings.palmGuard = it
                        log.i("setting", "palm guard" to it)
                    }, onText = on, offText = off)
                    Text(stringResource(R.string.setting_gestures), style = StudioType.Small, color = Studio.Legend)
                }
                StudioSection(stringResource(R.string.settings_panels)) {
                    Text(stringResource(R.string.settings_panels_detail), style = StudioType.Body)
                    SlabButton(stringResource(if (panelsReset) R.string.settings_panels_done else R.string.settings_panels_reset), onClick = {
                        PanelMemory(context.getSharedPreferences("nib.panels", Context.MODE_PRIVATE)).forgetSpots()
                        panelsReset = true
                        log.i("panels put back")
                    }, enabled = !panelsReset)
                }
                StudioSection(stringResource(R.string.settings_preview)) {
                    RevealSetting(settings, type = { entry = it })
                    Text(stringResource(R.string.setting_marker_preview), style = StudioType.Title, modifier = Modifier.padding(top = Studio.S3))
                    Text(stringResource(R.string.setting_marker_preview_detail), style = StudioType.Body, color = Studio.Legend)
                    Segmented(
                        listOf(stringResource(R.string.marker_preview_solid), stringResource(R.string.marker_preview_grey)),
                        MarkerPreview.entries.indexOf(settings.markerPreview),
                        onSelect = {
                            settings.markerPreview = MarkerPreview.entries[it]
                            log.i("setting", "marker preview" to settings.markerPreview.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        descriptions = listOf(stringResource(R.string.marker_preview_solid_detail), stringResource(R.string.marker_preview_grey_detail)),
                    )
                    Text(stringResource(R.string.settings_preview_detail), style = StudioType.Body, modifier = Modifier.padding(top = Studio.S2))
                    SlabButton(stringResource(R.string.preview_match_link), onClick = onMatchPreview, icon = StudioGlyphs.Diagnostics)
                }
            }
        }
        entry?.let { r ->
            EntryBar(r, stringResource(R.string.action_set), stringResource(R.string.action_cancel), onDone = { entry = null }, shields = null, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Settings › Display preview › Show the finished ink, and the pause it waits for. */
@Composable
private fun RevealSetting(settings: NibSettings, type: (EntryRequest) -> Unit) {
    val context = LocalContext.current
    val log = remember { Logbook.logger("nib.ui") }
    val choices = RevealChoice.entries
    Text(stringResource(R.string.setting_reveal), style = StudioType.Title)
    Text(stringResource(R.string.setting_reveal_detail), style = StudioType.Body, color = Studio.Legend)
    Segmented(
        choices.map { stringResource(revealName(it)) }, choices.indexOf(settings.reveal),
        onSelect = {
            settings.reveal = choices[it]
            log.i("setting", "reveal" to settings.reveal.id)
        },
        modifier = Modifier.fillMaxWidth(),
        descriptions = choices.map { stringResource(revealDetail(it)) },
    )
    Text(stringResource(revealDetail(settings.reveal)), style = StudioType.Small, color = Studio.Legend)
    if (settings.reveal == RevealChoice.Auto || settings.reveal == RevealChoice.AfterPause) {
        val scale = remember { ValueScale.linear(RevealPolicy.MIN_PAUSE_MS.toFloat(), RevealPolicy.MAX_PAUSE_MS.toFloat(), RevealPolicy.PAUSE_STEP_MS.toFloat(), decimals = 0) }
        val label = stringResource(R.string.setting_reveal_pause)
        val ms = stringResource(R.string.unit_ms_short)
        fun set(v: Float) {
            settings.revealPauseMs = v.toInt()
            log.i("setting", "reveal pause ms" to settings.revealPauseMs)
        }
        StudioSlider(
            label, settings.revealPauseMs.toFloat(), scale, ms,
            onCommit = { set(it) },
            onType = {
                type(EntryRequest(label, scale.format(settings.revealPauseMs.toFloat()), numeric = true, hint = context.getString(R.string.entry_range, scale.format(scale.min), scale.format(scale.max), ms), suffix = ms) { text ->
                    when (val r = scale.parse(text)) {
                        ValueScale.Parsed.Invalid -> context.getString(R.string.entry_not_a_number)
                        is ValueScale.Parsed.Ok -> {
                            set(r.value)
                            null
                        }
                    }
                })
            },
            minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
            typeLabel = stringResource(R.string.slider_type_value, label, scale.format(settings.revealPauseMs.toFloat()) + " " + ms),
            modifier = Modifier.padding(top = Studio.S2),
        )
    }
}

private fun revealName(c: RevealChoice): Int = when (c) {
    RevealChoice.Auto -> R.string.reveal_auto
    RevealChoice.AtBreaks -> R.string.reveal_breaks
    RevealChoice.AfterPause -> R.string.reveal_pause
    RevealChoice.EveryStroke -> R.string.reveal_every
}

private fun revealDetail(c: RevealChoice): Int = when (c) {
    RevealChoice.Auto -> R.string.reveal_auto_detail
    RevealChoice.AtBreaks -> R.string.reveal_breaks_detail
    RevealChoice.AfterPause -> R.string.reveal_pause_detail
    RevealChoice.EveryStroke -> R.string.reveal_every_detail
}
