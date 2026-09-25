package app.booxultimatum.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.Fonts
import app.booxultimatum.core.StatusBar
import app.booxultimatum.core.StatusSlot
import app.booxultimatum.launcher.FontSource
import app.booxultimatum.launcher.IconShape
import app.booxultimatum.launcher.IconStyle
import app.booxultimatum.launcher.IndicatorStyle
import app.booxultimatum.launcher.LauncherPrefs
import app.booxultimatum.launcher.LauncherStore
import app.booxultimatum.launcher.LauncherWallpaper
import app.booxultimatum.launcher.WallMode
import app.booxultimatum.ui.ErrorLine
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Lamp
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Look and feel, managed in one place: the system status bar (applies everywhere, Boox home included) and the
 * look of BooxUltimatum home. Arranging home itself (grid, widgets, folders) stays in the launcher's Edit.
 */
@Composable
fun AppearanceScreen(readKey: Int, compact: Boolean, onOpenFonts: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LauncherStore(context) }
    var home by remember { mutableStateOf(store.load()) }
    fun setHome(block: (LauncherPrefs) -> LauncherPrefs) { home = block(home); store.save(home) }

    var sbKey by remember { mutableIntStateOf(0) }
    val slots = rememberReading(readKey) { StatusBar.slots() }
    val hidden = rememberReading(Pair(readKey, sbKey)) { StatusBar.hidden(context) }
    val pct = rememberReading(Pair(readKey, sbKey)) { StatusBar.batteryPercent(context) }
    var error by remember { mutableStateOf<String?>(null) }
    var allSlots by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { LauncherWallpaper.import(context, uri) }
            if (ok) setHome { it.copy(wallMode = WallMode.Image) } else error = context.getString(R.string.ap_wall_failed)
        }
    }

    fun sb(block: suspend () -> Result<Unit>) {
        error = null
        scope.launch { block().onFailure { error = context.getString(R.string.tweak_failed, it.message ?: "") }; sbKey++ }
    }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_appearance), stringResource(R.string.ap_subtitle))
        }
        item {
            Plate(
                stringResource(R.string.ap_statusbar),
                action = if (StatusBar.changed(context)) { { Key(stringResource(R.string.ap_restore_boox), onClick = { sb { StatusBar.restore(context) } }) } } else null,
            ) {
                Paragraph(stringResource(R.string.ap_statusbar_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                if (slots == null || hidden == null) Reading() else {
                    // The icons people actually tune come first; the rest wait behind one key, but a hidden icon always shows.
                    val common = setOf("wifi", "battery", "bluetooth", "clock", "refresh_mode", "tp_touch_mode", "zen", "alarm_clock", "volume", "airplane")
                    val shown = if (allSlots) slots else slots.filter { it.id in common || it.id in hidden }
                    shown.forEach { s -> SlotRow(s, s.id !in hidden) { show -> sb { StatusBar.setHidden(context, s.id, !show) } } }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lamp(pct == true)
                        Text(stringResource(R.string.ap_battery_pct), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = Space.m))
                        Key(stringResource(if (pct == true) R.string.ap_hide else R.string.ap_show), onClick = { sb { StatusBar.setBatteryPercent(context, pct != true) } })
                    }
                    if (slots.size > shown.size || allSlots) {
                        Spacer(Modifier.height(Space.s))
                        Key(
                            if (allSlots) stringResource(R.string.ap_fewer_icons) else stringResource(R.string.ap_all_icons, slots.size),
                            onClick = { allSlots = !allSlots },
                        )
                    }
                }
                Text(stringResource(R.string.ap_statusbar_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                error?.let { ErrorLine(it) }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(Space.xxl))
        }
        item {
            Plate(stringResource(R.string.ap_home)) {
                Paragraph(stringResource(R.string.ap_home_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                Choice(
                    stringResource(R.string.l_icon_shape),
                    listOf(
                        stringResource(R.string.l_shape_original) to IconShape.Original, stringResource(R.string.l_shape_circle) to IconShape.Circle,
                        stringResource(R.string.l_shape_rounded) to IconShape.Rounded, stringResource(R.string.l_shape_squircle) to IconShape.Squircle,
                        stringResource(R.string.l_shape_square) to IconShape.Square,
                    ),
                    home.iconShape,
                ) { v -> setHome { it.copy(iconShape = v) } }
                Choice(
                    stringResource(R.string.l_icons),
                    listOf(stringResource(R.string.l_icon_original) to IconStyle.Original, stringResource(R.string.l_icon_mono) to IconStyle.Monochrome, stringResource(R.string.l_icon_gray) to IconStyle.Grayscale),
                    home.iconStyle,
                ) { v -> setHome { it.copy(iconStyle = v) } }
                Choice(stringResource(R.string.l_labels), listOf(stringResource(R.string.l_on) to true, stringResource(R.string.l_off) to false), home.labels) { v -> setHome { it.copy(labels = v) } }
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule, modifier = Modifier.padding(vertical = Space.s))
                Choice(
                    stringResource(R.string.l_background),
                    listOf(stringResource(R.string.l_wall_paper) to WallMode.Paper, stringResource(R.string.l_wall_system) to WallMode.System, stringResource(R.string.l_wall_image) to WallMode.Image),
                    home.wallMode,
                ) { v ->
                    // A picture already imported is reused; picking another stays one key away.
                    if (v == WallMode.Image && !LauncherWallpaper.exists(context)) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else setHome { it.copy(wallMode = v) }
                }
                if (home.wallMode == WallMode.Image) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Key(stringResource(R.string.l_wall_choose), onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        Key(stringResource(R.string.l_wall_remove), onClick = { LauncherWallpaper.clear(context); setHome { it.copy(wallMode = WallMode.Paper) } })
                    }
                }
                if (home.wallMode != WallMode.Paper) Stepper(stringResource(R.string.l_veil), home.wallDim, 0..90, 10) { v -> setHome { it.copy(wallDim = v) } }
                Text(stringResource(R.string.l_wall_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule, modifier = Modifier.padding(vertical = Space.s))
                Choice(
                    stringResource(R.string.l_indicators),
                    listOf(stringResource(R.string.l_ind_glyphs) to IndicatorStyle.Glyphs, stringResource(R.string.l_ind_both) to IndicatorStyle.GlyphsAndText, stringResource(R.string.l_ind_text) to IndicatorStyle.Text),
                    home.indicatorStyle,
                ) { v -> setHome { it.copy(indicatorStyle = v) } }
                Text(stringResource(R.string.ap_wifi_symbol), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.s))
                Spacer(Modifier.height(Space.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    app.booxultimatum.launcher.WifiGlyph.entries.forEach { g ->
                        GlyphTile(stringResource(g.label), selected = home.wifiGlyph == g, onClick = { setHome { it.copy(wifiGlyph = g) } }) {
                            app.booxultimatum.launcher.StatusGlyphs.Wifi(true, true, 3, g, 30.dp)
                            app.booxultimatum.launcher.StatusGlyphs.Wifi(true, true, 1, g, 30.dp)
                            app.booxultimatum.launcher.StatusGlyphs.Wifi(false, false, 0, g, 30.dp)
                        }
                    }
                }
                Text(stringResource(R.string.ap_battery_symbol), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.m))
                Spacer(Modifier.height(Space.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    app.booxultimatum.launcher.BatteryGlyph.entries.forEach { g ->
                        GlyphTile(stringResource(g.label), selected = home.batteryGlyph == g, onClick = { setHome { it.copy(batteryGlyph = g) } }) {
                            app.booxultimatum.launcher.StatusGlyphs.Battery(80, true, g, 28.dp)
                            app.booxultimatum.launcher.StatusGlyphs.Battery(45, false, g, 28.dp)
                            app.booxultimatum.launcher.StatusGlyphs.Battery(12, false, g, 28.dp)
                        }
                    }
                }
                Text(stringResource(R.string.ap_symbols_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                Text(stringResource(R.string.l_header_items), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.s))
                Spacer(Modifier.height(Space.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Key(stringResource(R.string.l_hdr_time), primary = home.showTime, onClick = { setHome { it.copy(showTime = !it.showTime) } })
                    Key(stringResource(R.string.l_hdr_date), primary = home.showDate, onClick = { setHome { it.copy(showDate = !it.showDate) } })
                    Key(stringResource(R.string.l_hdr_wifi), primary = home.showWifi, onClick = { setHome { it.copy(showWifi = !it.showWifi) } })
                    Key(stringResource(R.string.l_hdr_wifi_name), primary = home.showWifiName, enabled = home.showWifi, onClick = { setHome { it.copy(showWifiName = !it.showWifiName) } })
                    Key(stringResource(R.string.l_hdr_bt), primary = home.showBluetooth, onClick = { setHome { it.copy(showBluetooth = !it.showBluetooth) } })
                    Key(stringResource(R.string.l_hdr_batt_pct), primary = home.showBatteryPct, onClick = { setHome { it.copy(showBatteryPct = !it.showBatteryPct) } })
                }
                Text(stringResource(R.string.l_header_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule, modifier = Modifier.padding(vertical = Space.s))
                val appFont = Fonts.appFontFamily(context)
                val fonts = remember { Fonts.keptDir(context).listFiles { f -> f.name.endsWith("-Regular.ttf") }?.sortedBy { it.name }.orEmpty() }
                val options = buildList {
                    add(stringResource(R.string.l_font_system) to (FontSource.System to null as String?))
                    if (appFont != null) add(stringResource(R.string.l_font_app, appFont) to (FontSource.App to null as String?))
                    fonts.forEach { add(it.name.removeSuffix("-Regular.ttf") to (FontSource.Custom to it.absolutePath as String?)) }
                }
                Choice(stringResource(R.string.l_font_source), options, home.fontSource to (if (home.fontSource == FontSource.Custom) home.fontFile else null)) { (src, path) ->
                    setHome { it.copy(fontSource = src, fontFile = path ?: it.fontFile) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.s)) {
                    Text(stringResource(R.string.ap_more_fonts), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.weight(1f))
                    Key(stringResource(R.string.dest_fonts), onClick = onOpenFonts)
                }
            }
        }
    }
}

/** One symbol design shown in three states; a heavier rim marks the chosen one. */
@Composable
private fun GlyphTile(name: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Column(
        Modifier
            .clip(shape)
            .border(if (selected) 3.dp else Lines.hairline, if (selected) Ink.Black else Ink.Rule, shape)
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = if (selected) Ink.Black else Ink.Legend)
    }
}

@Composable
private fun SlotRow(s: StatusSlot, shown: Boolean, onToggle: (Boolean) -> Unit) {
    val name = when {
        s.label != null -> stringResource(s.label)
        s.id.contains("easytransfer") -> stringResource(R.string.sb_booxdrop)
        else -> s.raw.removePrefix("custom(").substringAfterLast('.').removeSuffix(")")
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Lamp(shown)
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = Space.m))
            Key(stringResource(if (shown) R.string.ap_hide else R.string.ap_show), onClick = { onToggle(!shown) })
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, current: T, onChange: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            options.forEach { (text, v) -> Key(text, primary = v == current, onClick = { onChange(v) }) }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Key("−", onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first)
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        Key("+", onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last)
    }
}
