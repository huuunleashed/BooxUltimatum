package app.booxultimatum.nib.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

/** The pages of Settings, each a screen's worth, so nothing needs a long scroll on e-ink. */
private enum class SettingsPage(val title: Int, val detail: Int) {
    Writing(R.string.settings_tab_writing, R.string.settings_tab_writing_detail),
    Display(R.string.settings_tab_display, R.string.settings_tab_display_detail),
    Fingers(R.string.settings_fingers, R.string.settings_tab_fingers_detail),
    Layout(R.string.settings_tab_layout, R.string.settings_tab_layout_detail),
}

/**
 * Nib's settings in four pages: writing (the pen, the eraser end and when the finished ink shows), the display (its
 * previews and refreshes), fingers, and the layout of the tools and cards. Diagnostics keeps the switches that are
 * only for testing the tablet.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onMatchPreview: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { NibSettings.get(context) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var entry by remember { mutableStateOf<EntryRequest?>(null) }
    val pages = SettingsPage.entries
    Box(Modifier.fillMaxSize()) {
        StudioPage(stringResource(R.string.menu_settings), stringResource(R.string.action_back), onBack) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Studio.S5, vertical = Studio.S3),
                verticalArrangement = Arrangement.spacedBy(Studio.S5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Segmented(
                    pages.map { stringResource(it.title) }, page,
                    onSelect = { page = it },
                    modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
                    descriptions = pages.map { stringResource(it.detail) },
                )
                when (pages[page.coerceIn(0, pages.size - 1)]) {
                    SettingsPage.Writing -> WritingPage(settings, type = { entry = it })
                    SettingsPage.Display -> DisplayPage(settings, onMatchPreview)
                    SettingsPage.Fingers -> FingersPage(settings)
                    SettingsPage.Layout -> LayoutPage(settings)
                }
            }
        }
        entry?.let { r ->
            EntryBar(r, stringResource(R.string.action_set), stringResource(R.string.action_cancel), onDone = { entry = null }, shields = null, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun SettingToggle(title: Int, detail: Int, on: Boolean, logKey: String, set: (Boolean) -> Unit) {
    val log = remember { Logbook.logger("nib.ui") }
    Toggle(stringResource(title), stringResource(detail), on, onToggle = {
        set(it)
        log.i("setting", logKey to it)
    }, onText = stringResource(R.string.toggle_on), offText = stringResource(R.string.toggle_off))
}

@Composable
private fun WritingPage(settings: NibSettings, type: (EntryRequest) -> Unit) {
    StudioSection(stringResource(R.string.settings_instant_ink)) {
        SettingToggle(R.string.setting_instant_ink, R.string.setting_instant_ink_detail, settings.instantInk, "instant ink") { settings.instantInk = it }
        Text(stringResource(if (settings.instantInk) R.string.setting_instant_ink_on_note else R.string.setting_instant_ink_off_note), style = StudioType.Small, color = Studio.Legend)
    }
    StudioSection(stringResource(R.string.settings_pen)) {
        SettingToggle(R.string.setting_straight, R.string.setting_straight_detail, settings.straightLineHold, "straight line hold") { settings.straightLineHold = it }
        SettingToggle(R.string.setting_eraser_end, R.string.setting_eraser_end_detail, settings.eraserEndPreview, "eraser end preview") { settings.eraserEndPreview = it }
    }
    StudioSection(stringResource(R.string.setting_reveal)) {
        RevealSetting(settings, type)
    }
}

@Composable
private fun DisplayPage(settings: NibSettings, onMatchPreview: () -> Unit) {
    val log = remember { Logbook.logger("nib.ui") }
    StudioSection(stringResource(R.string.setting_marker_preview)) {
        Text(stringResource(R.string.setting_marker_preview_detail), style = StudioType.Body, color = Studio.Legend)
        Segmented(
            listOf(stringResource(R.string.marker_preview_solid), stringResource(R.string.marker_preview_grey)),
            MarkerPreview.entries.indexOf(settings.markerPreview),
            onSelect = {
                settings.markerPreview = MarkerPreview.entries[it]
                log.i("setting", "marker preview" to settings.markerPreview.id)
            },
            modifier = Modifier.fillMaxWidth().padding(top = Studio.S2),
            descriptions = listOf(stringResource(R.string.marker_preview_solid_detail), stringResource(R.string.marker_preview_grey_detail)),
        )
        Text(
            stringResource(if (settings.markerPreview == MarkerPreview.SolidColour) R.string.marker_preview_solid_detail else R.string.marker_preview_grey_detail),
            style = StudioType.Small, color = Studio.Legend, modifier = Modifier.padding(top = Studio.S2),
        )
    }
    StudioSection(stringResource(R.string.settings_refresh)) {
        SettingToggle(R.string.setting_fast_gestures, R.string.setting_fast_gestures_detail, settings.fastGestures, "fast gestures") { settings.fastGestures = it }
    }
    StudioSection(stringResource(R.string.settings_preview_size)) {
        Text(stringResource(R.string.settings_preview_detail), style = StudioType.Body)
        SlabButton(stringResource(R.string.preview_match_link), onClick = onMatchPreview, icon = StudioGlyphs.Diagnostics, modifier = Modifier.padding(top = Studio.S2))
    }
}

@Composable
private fun FingersPage(settings: NibSettings) {
    StudioSection(stringResource(R.string.settings_fingers)) {
        SettingToggle(R.string.setting_finger_drawing, R.string.setting_finger_drawing_detail, settings.fingerDrawing, "finger drawing") { settings.fingerDrawing = it }
        SettingToggle(R.string.setting_finger_pan, R.string.setting_finger_pan_detail, settings.fingerPan, "finger pan") { settings.fingerPan = it }
        SettingToggle(R.string.setting_palm_guard, R.string.setting_palm_guard_detail, settings.palmGuard, "palm guard") { settings.palmGuard = it }
        Text(stringResource(R.string.setting_gestures), style = StudioType.Small, color = Studio.Legend)
    }
}

@Composable
private fun LayoutPage(settings: NibSettings) {
    val context = LocalContext.current
    val log = remember { Logbook.logger("nib.ui") }
    var panelsReset by remember { mutableStateOf(false) }
    StudioSection(stringResource(R.string.settings_tools)) {
        SettingToggle(R.string.setting_left_handed, R.string.setting_left_handed_detail, settings.leftHanded, "left handed") { settings.leftHanded = it }
    }
    StudioSection(stringResource(R.string.settings_panels)) {
        Text(stringResource(R.string.settings_panels_detail), style = StudioType.Body)
        SlabButton(stringResource(if (panelsReset) R.string.settings_panels_done else R.string.settings_panels_reset), onClick = {
            PanelMemory(context.getSharedPreferences("nib.panels", Context.MODE_PRIVATE)).forgetSpots()
            panelsReset = true
            log.i("panels put back")
        }, enabled = !panelsReset, modifier = Modifier.padding(top = Studio.S2))
    }
}

/** Settings › Writing › Show the finished ink, and the pause it waits for. */
@Composable
private fun RevealSetting(settings: NibSettings, type: (EntryRequest) -> Unit) {
    val context = LocalContext.current
    val log = remember { Logbook.logger("nib.ui") }
    val choices = RevealChoice.entries
    Text(stringResource(R.string.setting_reveal_detail), style = StudioType.Body, color = Studio.Legend)
    Segmented(
        choices.map { stringResource(revealName(it)) }, choices.indexOf(settings.reveal),
        onSelect = {
            settings.reveal = choices[it]
            log.i("setting", "reveal" to settings.reveal.id)
        },
        modifier = Modifier.fillMaxWidth().padding(top = Studio.S2),
        descriptions = choices.map { stringResource(revealDetail(it)) },
    )
    Text(stringResource(revealDetail(settings.reveal)), style = StudioType.Small, color = Studio.Legend, modifier = Modifier.padding(top = Studio.S2))
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
