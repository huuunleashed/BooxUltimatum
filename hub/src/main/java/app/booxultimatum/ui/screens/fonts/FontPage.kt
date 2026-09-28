package app.booxultimatum.ui.screens.fonts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.booxultimatum.R
import app.booxultimatum.core.FontChange
import app.booxultimatum.core.FontFamilyInfo
import app.booxultimatum.core.FontLibrary
import app.booxultimatum.core.FontNames
import app.booxultimatum.core.FontPlace
import app.booxultimatum.core.FontState
import app.booxultimatum.core.FontUse
import app.booxultimatum.core.Fonts
import app.booxultimatum.core.InstalledFont
import app.booxultimatum.core.SystemFont
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.LampRow
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.launch

private const val MANAGE = "manage"
private const val USE = "use"

/**
 * One family: how it stands on the tablet and what to do with it (when installed), what to use it for, and its
 * specimen. The Installed list and the Google Fonts browser both open this page.
 */
@Composable
internal fun FontPage(
    base: String,
    info: FontFamilyInfo?,
    library: FontLibrary?,
    sample: String,
    onSample: (String) -> Unit,
    compact: Boolean,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenTweaks: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val font = library?.font(base)
    val uses = library?.usesOf(base).orEmpty()
    val name = info?.name ?: font?.family ?: FontNames.display(base)
    val fileFace = rememberFileFamily(font?.preview?.path)
    val webFace = rememberFamily(if (font?.preview == null) info else null)
    val face = fileFace ?: webFace
    var busy by remember { mutableStateOf<String?>(null) }
    var note by rememberSaveable(base) { mutableStateOf<String?>(null) }
    var noteAt by rememberSaveable(base) { mutableStateOf("") }
    var failed by rememberSaveable(base) { mutableStateOf(false) }

    fun act(label: String, at: String, block: suspend () -> Result<FontChange>, say: (FontChange) -> String?, after: (FontChange) -> Unit = {}) {
        busy = label; note = null
        scope.launch {
            val r = block()
            noteAt = at
            r.onSuccess { note = say(it); failed = false }.onFailure { note = context.getString(R.string.tweak_failed, it.message ?: it.toString()); failed = true }
            busy = null
            onChanged()
            r.getOrNull()?.let(after)
            if (r.getOrNull()?.restart == true) restart(context)
        }
    }

    val noteLine: @Composable (String) -> Unit = { at ->
        if (noteAt == at) note?.takeIf { it.isNotEmpty() }?.let {
            if (failed) ErrorLine(it) else Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.m))
        }
    }
    val text = sample.ifBlank { stringResource(if (info?.vietnamese == true) R.string.fonts_sample_vi else R.string.fonts_sample) }

    InstrumentPage(compact) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = Space.m)) {
                    Text(name, style = TextStyle(fontFamily = face ?: FontFamily.Default, fontSize = 44.sp, lineHeight = 52.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (info != null) catalogMeta(info, font, uses) else stylesText(font?.styles.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Legend,
                    )
                }
                Key(stringResource(R.string.fonts_back), onClick = onBack)
            }
            Spacer(Modifier.height(Space.l))
            SampleField(sample, onSample)
            // After a delete from here the Installed plate is gone, so its message shows at the top.
            if (font == null) noteLine(MANAGE)
            Spacer(Modifier.height(Space.xl))
        }
        if (font == null && info == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val useAct: (String, suspend () -> Result<FontChange>, (FontChange) -> String?) -> Unit = { label, block, say -> act(label, USE, block, say) }
        if (font != null) {
            item {
                ManagePlate(
                    font, uses, busy, { noteLine(MANAGE) },
                    onOff = {
                        act("off", MANAGE, { Fonts.turnOff(context, base) }, { c ->
                            if (c.released.isEmpty()) context.getString(R.string.fonts_turned_off, name)
                            else context.getString(R.string.fonts_turned_off_released, name, useWords(context, c.released))
                        })
                    },
                    onOn = {
                        act("on", MANAGE, { Fonts.turnOn(context, base) }, { c ->
                            if (c.restored.isEmpty()) context.getString(R.string.fonts_turned_on, name)
                            else context.getString(R.string.fonts_turned_on_restored, name, useWords(context, c.restored))
                        })
                    },
                    onDelete = {
                        act("delete", MANAGE, { Fonts.delete(context, base) }, { c -> context.getString(R.string.fonts_deleted, name, sizeText(context, c.bytes)) }) {
                            if (info == null) onBack()
                        }
                    },
                )
                Spacer(Modifier.height(Space.xl))
            }
            item {
                UsePlate(base, name, info, font, library, busy, { noteLine(USE) }, useAct, onOpenTweaks)
                Spacer(Modifier.height(Space.xl))
            }
        }
        item {
            Plate(stringResource(R.string.fonts_specimen)) {
                if (face == null) Reading()
                listOf(16, 22, 30, 44).forEach { size ->
                    Text(stringResource(R.string.fonts_size, size), style = MaterialTheme.typography.labelSmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
                    Text(text, style = TextStyle(fontFamily = face ?: FontFamily.Default, fontSize = size.sp, lineHeight = (size * 1.35f).sp, color = if (face == null) Ink.Rule else Ink.Black))
                }
                Spacer(Modifier.height(Space.m))
                Text(
                    stringResource(if (info?.vietnamese == true) R.string.fonts_charset_vi else R.string.fonts_charset),
                    style = TextStyle(fontFamily = face ?: FontFamily.Default, fontSize = 22.sp, lineHeight = 32.sp),
                )
            }
            Spacer(Modifier.height(Space.xl))
        }
        if (info != null) item {
            StylesPlate(info)
            Spacer(Modifier.height(Space.xl))
        }
        if (font == null && info != null) item {
            UsePlate(base, name, info, null, library, busy, { noteLine(USE) }, useAct, onOpenTweaks)
        }
    }
}

@Composable
private fun ManagePlate(
    font: InstalledFont,
    uses: Set<FontUse>,
    busy: String?,
    note: @Composable () -> Unit,
    onOff: () -> Unit,
    onOn: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Plate(stringResource(R.string.fonts_manage_title)) {
        val (title, detail) = when (font.state) {
            FontState.On -> R.string.fonts_state_on to R.string.fonts_state_on_s
            FontState.Off -> R.string.fonts_state_off to R.string.fonts_state_off_s
            FontState.Partial -> R.string.fonts_state_partial to R.string.fonts_state_partial_s
        }
        LampRow(font.state == FontState.On, stringResource(title), stringResource(detail))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
        SpecRow(stringResource(R.string.fonts_spec_styles), stylesText(font.styles))
        SpecRow(stringResource(R.string.fonts_spec_size), sizeText(context, font.size), note = pluralStringResource(R.plurals.fonts_files, font.files.size, font.files.size))
        SpecRow(stringResource(R.string.fonts_spec_where), placesText(font))
        SpecRow(stringResource(R.string.fonts_spec_in_use), if (uses.isEmpty()) stringResource(R.string.fonts_nowhere) else usesText(uses).capitalised())
        SpecRow(stringResource(R.string.fonts_spec_installed), dateText(font.record.installedAt))
        Spacer(Modifier.height(Space.m))
        val working = stringResource(R.string.state_working)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (font.state != FontState.Off) Key(if (busy == "off") working else stringResource(R.string.fonts_turn_off), enabled = busy == null, onClick = onOff)
            if (font.state != FontState.On) Key(if (busy == "on") working else stringResource(R.string.fonts_turn_on), primary = true, enabled = busy == null, onClick = onOn)
            ConfirmKey(if (busy == "delete") working else stringResource(R.string.fonts_delete), stringResource(R.string.fonts_delete_confirm), enabled = busy == null, onConfirm = onDelete)
        }
        note()
        Text(stringResource(R.string.fonts_manage_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
    }
}

@Composable
private fun placesText(font: InstalledFont): String {
    val parts = font.placesOn.sorted().map { p ->
        when (p) {
            FontPlace.App -> stringResource(R.string.fonts_place_app)
            FontPlace.Reader -> stringResource(R.string.fonts_place_reader).let { if (font.readerChecked) it else stringResource(R.string.fonts_place_unchecked, it) }
            FontPlace.Documents -> stringResource(R.string.fonts_place_documents)
        }
    } + if (font.offFiles.isNotEmpty()) listOf(stringResource(R.string.fonts_place_off)) else emptyList()
    return parts.joinToString("\n")
}

/**
 * The five places a font can be used. For a family not yet installed each use downloads what it needs first;
 * for an installed one the files on the tablet are used, fetching a missing weight when the catalogue has it.
 */
@Composable
private fun UsePlate(
    base: String,
    name: String,
    info: FontFamilyInfo?,
    font: InstalledFont?,
    library: FontLibrary?,
    busy: String?,
    note: @Composable () -> Unit,
    act: (String, suspend () -> Result<FontChange>, (FontChange) -> String?) -> Unit,
    onOpenTweaks: () -> Unit,
) {
    val context = LocalContext.current
    val uses = library?.usesOf(base).orEmpty()
    val usable = font == null || font.state == FontState.On
    val shell = remember(library) { Privileged.ready() }

    fun useIt(u: FontUse, weight: Int = 400): suspend () -> Result<FontChange> = {
        val missing = font == null || (if (u == FontUse.Tablet) font.appFile(weight) == null else font.appFile(400) == null && info?.has(400, false) == true)
        if (info != null && missing) Fonts.installFor(context, info, u, weight) else Fonts.use(context, base, u, weight)
    }
    fun stop(u: FontUse): suspend () -> Result<FontChange> = { Fonts.stopUsing(context, base, u) }

    Plate(stringResource(R.string.fonts_use_for)) {
        if (!usable) Paragraph(stringResource(R.string.fonts_off_use_note), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.m))
        TabletRow(base, name, info, font, library, usable, busy, act, ::useIt, ::stop)
        Spacer(Modifier.height(Space.m))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
        val inReader = FontUse.Reader in uses
        ApplyRow(
            stringResource(R.string.fonts_apply_reader), stringResource(if (font != null) R.string.fonts_apply_reader_disk else R.string.fonts_apply_reader_s),
            on = inReader, onLabel = stringResource(R.string.fonts_remove), offLabel = stringResource(R.string.fonts_install),
            enabled = usable && busy == null && shell, busy = busy == "reader",
        ) {
            if (inReader) act("reader", stop(FontUse.Reader)) { context.getString(R.string.fonts_removed, name) }
            else act("reader", useIt(FontUse.Reader)) { c -> context.quantity(R.plurals.fonts_installed, c.files, name, c.files) }
        }
        if (!shell) Text(stringResource(R.string.fonts_needs_shizuku), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        UseRow(FontUse.Home, R.string.fonts_apply_home, R.string.fonts_apply_home_s, R.string.fonts_use_system, R.string.fonts_home_set, R.string.fonts_home_reset, name, uses, usable, busy, act, ::useIt, ::stop)
        UseRow(FontUse.App, R.string.fonts_apply_app, R.string.fonts_apply_app_s, R.string.fonts_use_archivo, R.string.fonts_app_set, R.string.fonts_app_reset, name, uses, usable, busy, act, ::useIt, ::stop)
        UseRow(FontUse.Sleep, R.string.fonts_apply_sleep, R.string.fonts_apply_sleep_s, R.string.fonts_use_tablet_font, R.string.fonts_sleep_set, R.string.fonts_sleep_reset, name, uses, usable, busy, act, ::useIt, ::stop)
        note()
        Spacer(Modifier.height(Space.l))
        Text(stringResource(R.string.fonts_apply_serif), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.fonts_apply_serif_s), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        Spacer(Modifier.height(Space.s))
        Key(stringResource(R.string.fonts_open_serif_tweak), onClick = onOpenTweaks)
    }
}

@Composable
private fun UseRow(
    use: FontUse,
    title: Int,
    summary: Int,
    stopLabel: Int,
    setMessage: Int,
    resetMessage: Int,
    name: String,
    uses: Set<FontUse>,
    usable: Boolean,
    busy: String?,
    act: (String, suspend () -> Result<FontChange>, (FontChange) -> String?) -> Unit,
    useIt: (FontUse, Int) -> (suspend () -> Result<FontChange>),
    stop: (FontUse) -> (suspend () -> Result<FontChange>),
) {
    val context = LocalContext.current
    val on = use in uses
    val label = use.name.lowercase()
    ApplyRow(
        stringResource(title), stringResource(summary),
        on = on, onLabel = stringResource(stopLabel), offLabel = stringResource(R.string.fonts_use),
        enabled = busy == null && (usable || on), busy = busy == label,
    ) {
        if (on) act(label, stop(use)) { context.getString(resetMessage) }
        else act(label, useIt(use, 400)) { context.getString(setMessage, name) }
    }
}

/**
 * The whole tablet's font, through Boox's own font switch. A heavier cut is offered first where the family has
 * one, because the panel thins every stroke.
 */
@Composable
private fun TabletRow(
    base: String,
    name: String,
    info: FontFamilyInfo?,
    font: InstalledFont?,
    library: FontLibrary?,
    usable: Boolean,
    busy: String?,
    act: (String, suspend () -> Result<FontChange>, (FontChange) -> String?) -> Unit,
    useIt: (FontUse, Int) -> (suspend () -> Result<FontChange>),
    stop: (FontUse) -> (suspend () -> Result<FontChange>),
) {
    val context = LocalContext.current
    val offered = info?.let { i -> listOf(400, 500, 600, 700).filter { i.has(it, false) }.ifEmpty { listOf(i.regularKey.removeSuffix("i").toIntOrNull() ?: 400) } }.orEmpty()
    val weights = (offered + font?.uprightWeights.orEmpty()).distinct().sorted().ifEmpty { listOf(400) }
    var picked by rememberSaveable(base) { mutableStateOf<Int?>(null) }
    // Until a weight is picked, prefer 500: the catalogue may arrive after the files on disk, adding weights.
    val weight = picked?.takeIf { it in weights } ?: if (500 in weights) 500 else weights.first()
    val tablet = library?.settings?.tabletPath
    val inUse = FontUse.Tablet in library?.usesOf(base).orEmpty()
    val inUseWeight = if (inUse) tablet?.substringAfterLast('/')?.let { FontNames.styleOf(it)?.weight } else null
    val default = stringResource(R.string.fonts_sys_default)
    Text(stringResource(R.string.fonts_apply_system), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.fonts_apply_system_s), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
    Text(
        stringResource(R.string.fonts_sys_now, SystemFont.nameOf(tablet) ?: default),
        style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs),
    )
    Spacer(Modifier.height(Space.s))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (weights.size > 1) weights.forEach { w -> Key(stringResource(R.string.fonts_style, w), primary = w == weight, onClick = { picked = w }) }
        Key(
            if (busy == "system") stringResource(R.string.state_working) else stringResource(if (inUseWeight == weight) R.string.fonts_sys_in_use else R.string.fonts_sys_use),
            primary = true, enabled = usable && busy == null && inUseWeight != weight,
            onClick = { act("system", useIt(FontUse.Tablet, weight)) { context.getString(R.string.fonts_sys_done, name) } },
        )
        if (inUse) {
            Key(stringResource(R.string.fonts_sys_stop), enabled = busy == null, onClick = {
                act("system", stop(FontUse.Tablet)) { c -> context.getString(R.string.fonts_sys_back, SystemFont.nameOf(c.tabletPath?.takeIf { it.isNotEmpty() }) ?: default) }
            })
        } else if (SystemFont.changed(context)) {
            Key(stringResource(R.string.fonts_sys_restore), enabled = busy == null, onClick = {
                act("system", { SystemFont.restore(context).map { FontChange(name) } }) { context.getString(R.string.fonts_sys_restored) }
            })
        }
        Key(stringResource(R.string.fonts_sys_boox), onClick = { app.booxultimatum.launcher.BooxIntents.openSettings(context) })
    }
}

@Composable
private fun StylesPlate(info: FontFamilyInfo) {
    var showAll by remember { mutableStateOf(false) }
    Plate(
        stringResource(R.string.fonts_styles_title, info.styles),
        action = if (info.styles > 6) { { Key(stringResource(if (showAll) R.string.action_hide else R.string.action_show_all), onClick = { showAll = !showAll }) } } else null,
    ) {
        val shown = if (showAll) info.variants else info.variants.take(6)
        shown.forEach { v ->
            val w = v.removeSuffix("i").toIntOrNull() ?: 400
            val italic = v.endsWith("i")
            val fam = rememberFamily(info, w, italic)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(stringResource(if (italic) R.string.fonts_style_italic else R.string.fonts_style, w), style = MaterialTheme.typography.labelSmall, color = Ink.Legend)
                Text(
                    stringResource(R.string.fonts_style_sample),
                    style = TextStyle(fontFamily = fam ?: FontFamily.Default, fontSize = 26.sp, color = if (fam == null) Ink.Rule else Ink.Black),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
        }
    }
}

@Composable
private fun ApplyRow(title: String, summary: String, on: Boolean, onLabel: String, offLabel: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Lamp(on)
            Spacer(Modifier.padding(horizontal = Space.s))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Key(if (busy) stringResource(R.string.state_working) else if (on) onLabel else offLabel, onClick = onClick, enabled = enabled, primary = !on)
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}
