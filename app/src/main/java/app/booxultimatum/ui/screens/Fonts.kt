package app.booxultimatum.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.booxultimatum.R
import app.booxultimatum.core.FontCategory
import app.booxultimatum.core.FontFamilyInfo
import app.booxultimatum.core.Fonts
import app.booxultimatum.launcher.LauncherStore
import app.booxultimatum.ui.ErrorLine
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Pager
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.SearchField
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.launch

private const val FONTS_PER_PAGE = 7

private enum class FontFilter { Picks, All, Serif, Sans, Display, Handwriting, Mono }
private enum class FontSort { Popular, Name }

@Composable
fun FontsScreen(readKey: Int, compact: Boolean, onOpenTweaks: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var sample by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val catalog by produceState<Result<List<FontFamilyInfo>>?>(null, readKey, refreshKey) { value = Fonts.catalog(context, forceRefresh = refreshKey > 0) }
    val installed by produceState<Set<String>?>(null, readKey, selected) { value = Fonts.installedNames() }

    val current = selected?.let { name -> catalog?.getOrNull()?.firstOrNull { it.name == name } }
    if (current != null) {
        BackHandler { selected = null }
        FontDetail(current, sample, { sample = it }, installed, compact, onBack = { selected = null }, onOpenTweaks = onOpenTweaks)
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FontFilter.Picks) }
    var sort by rememberSaveable { mutableStateOf(FontSort.Popular) }
    var viOnly by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }

    InstrumentPage(compact) {
        item {
            val count = catalog?.getOrNull()?.size
            ScreenHeader(stringResource(R.string.dest_fonts), count?.let { stringResource(R.string.fonts_subtitle, it) }) {
                Key(stringResource(R.string.fonts_refresh), onClick = { refreshKey++ })
            }
            SearchField(query, { query = it; page = 0 }, stringResource(R.string.fonts_search))
            Spacer(Modifier.height(Space.m))
            SampleField(sample, { sample = it })
            Spacer(Modifier.height(Space.m))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                FontFilter.entries.forEach { f -> Key(stringResource(filterName(f)), primary = f == filter, onClick = { filter = f; page = 0 }) }
            }
            Spacer(Modifier.height(Space.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Key(stringResource(R.string.fonts_vietnamese), primary = viOnly, onClick = { viOnly = !viOnly; page = 0 })
                Key(stringResource(R.string.fonts_sort_popular), primary = sort == FontSort.Popular, onClick = { sort = FontSort.Popular; page = 0 })
                Key(stringResource(R.string.fonts_sort_name), primary = sort == FontSort.Name, onClick = { sort = FontSort.Name; page = 0 })
            }
            Spacer(Modifier.height(Space.l))
            HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        }
        val result = catalog
        if (result == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val all = result.getOrElse {
            item { ErrorLine(stringResource(R.string.fonts_catalog_failed, it.message ?: "")) }
            return@InstrumentPage
        }
        val q = query.trim()
        val list = all.asSequence()
            .filter { f ->
                when (filter) {
                    FontFilter.Picks -> f.name in Fonts.einkPicks
                    FontFilter.All -> true
                    FontFilter.Serif -> f.category == FontCategory.Serif
                    FontFilter.Sans -> f.category == FontCategory.Sans
                    FontFilter.Display -> f.category == FontCategory.Display
                    FontFilter.Handwriting -> f.category == FontCategory.Handwriting
                    FontFilter.Mono -> f.category == FontCategory.Mono
                }
            }
            .filter { !viOnly || it.vietnamese }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .let { s -> if (sort == FontSort.Popular) s.sortedBy { it.popularity } else s.sortedBy { it.name.lowercase() } }
            .toList()
        val pages = ((list.size + FONTS_PER_PAGE - 1) / FONTS_PER_PAGE).coerceAtLeast(1)
        val safe = page.coerceIn(0, pages - 1)
        item {
            Text(pluralStringResource(R.plurals.fonts_count, list.size, list.size), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.m))
            if (list.isEmpty()) Paragraph(stringResource(R.string.fonts_none, q), color = Ink.Legend)
        }
        items(list.drop(safe * FONTS_PER_PAGE).take(FONTS_PER_PAGE), key = { it.name }) { f ->
            FontRow(f, sample, installed) { selected = f.name }
        }
        if (pages > 1) item { Pager(safe, pages, onPage = { page = it }) }
    }
}

@Composable
private fun SampleField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().border(Lines.hairline, Ink.Black, shape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(stringResource(R.string.fonts_sample_hint), style = MaterialTheme.typography.bodyLarge, color = Ink.Legend)
        BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black), cursorBrush = SolidColor(Ink.Black), modifier = Modifier.fillMaxWidth())
    }
}

/** Loads the family's regular style on demand and returns it as a Compose family, or null until it arrives. */
@Composable
private fun rememberFamily(f: FontFamilyInfo, weight: Int? = null, italic: Boolean = false): FontFamily? {
    val context = LocalContext.current
    val state by produceState<FontFamily?>(null, f.name, weight, italic) {
        val file = (if (weight == null) Fonts.regular(context, f) else Fonts.style(context, f, weight, italic)).getOrNull()
        value = file?.let { runCatching { FontFamily(Font(it)) }.getOrNull() }
    }
    return state
}

@Composable
private fun FontRow(f: FontFamilyInfo, sample: String, installed: Set<String>?, onOpen: () -> Unit) {
    val family = rememberFamily(f)
    val context = LocalContext.current
    val usedByApp = Fonts.appFontFamily(context) == f.name
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.action_open), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                sample.ifBlank { stringResource(if (f.vietnamese) R.string.fonts_sample_vi_short else R.string.fonts_sample_short) },
                style = TextStyle(fontFamily = family ?: FontFamily.Default, fontSize = 28.sp, lineHeight = 36.sp, color = if (family == null) Ink.Rule else Ink.Black),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.xs))
            Text(meta(f, installed, usedByApp), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun meta(f: FontFamilyInfo, installed: Set<String>?, usedByApp: Boolean): String {
    val parts = mutableListOf(stringResource(categoryName(f.category)), pluralStringResource(R.plurals.fonts_styles, f.styles, f.styles))
    if (f.vietnamese) parts += stringResource(R.string.fonts_vietnamese)
    if (Fonts.isInstalled(f.name, installed)) parts += stringResource(R.string.fonts_in_reader)
    if (usedByApp) parts += stringResource(R.string.fonts_in_app)
    return parts.joinToString("  ·  ")
}

@Composable
private fun FontDetail(
    f: FontFamilyInfo,
    sample: String,
    onSample: (String) -> Unit,
    installed: Set<String>?,
    compact: Boolean,
    onBack: () -> Unit,
    onOpenTweaks: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val family = rememberFamily(f)
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reader by remember(installed) { mutableStateOf(Fonts.isInstalled(f.name, installed)) }
    var homeFont by remember { mutableStateOf(LauncherStore(context).load().fontFile) }
    var showAllStyles by remember { mutableStateOf(false) }
    val text = sample.ifBlank { stringResource(if (f.vietnamese) R.string.fonts_sample_vi else R.string.fonts_sample) }
    val keptRegular = Fonts.keptRegular(context, f.name)?.absolutePath
    val inHome = homeFont != null && homeFont == keptRegular
    val inApp = Fonts.appFontFamily(context) == f.name

    fun act(label: String, block: suspend () -> String) {
        busy = label; error = null; message = null
        scope.launch {
            runCatching { block() }.onSuccess { message = it }.onFailure { error = context.getString(R.string.tweak_failed, it.message ?: it.toString()) }
            busy = null
        }
    }

    InstrumentPage(compact) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = Space.m)) {
                    Text(f.name, style = TextStyle(fontFamily = family ?: FontFamily.Default, fontSize = 44.sp, lineHeight = 52.sp))
                    Text(meta(f, installed, inApp), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                }
                Key(stringResource(R.string.fonts_back), onClick = onBack)
            }
            Spacer(Modifier.height(Space.l))
            SampleField(sample, onSample)
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.fonts_specimen)) {
                if (family == null) Reading()
                listOf(16, 22, 30, 44).forEach { size ->
                    Text(stringResource(R.string.fonts_size, size), style = MaterialTheme.typography.labelSmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
                    Text(text, style = TextStyle(fontFamily = family ?: FontFamily.Default, fontSize = size.sp, lineHeight = (size * 1.35f).sp, color = if (family == null) Ink.Rule else Ink.Black))
                }
                Spacer(Modifier.height(Space.m))
                Text(
                    stringResource(if (f.vietnamese) R.string.fonts_charset_vi else R.string.fonts_charset),
                    style = TextStyle(fontFamily = family ?: FontFamily.Default, fontSize = 22.sp, lineHeight = 32.sp),
                )
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(
                stringResource(R.string.fonts_styles_title, f.styles),
                action = if (f.styles > 6) { { Key(stringResource(if (showAllStyles) R.string.action_hide else R.string.action_show_all), onClick = { showAllStyles = !showAllStyles }) } } else null,
            ) {
                val shown = if (showAllStyles) f.variants else f.variants.take(6)
                shown.forEach { v ->
                    val w = v.removeSuffix("i").toIntOrNull() ?: 400
                    val italic = v.endsWith("i")
                    val fam = rememberFamily(f, w, italic)
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
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.fonts_apply)) {
                ApplyRow(
                    stringResource(R.string.fonts_apply_reader), stringResource(R.string.fonts_apply_reader_s),
                    on = reader, onLabel = stringResource(R.string.fonts_remove), offLabel = stringResource(R.string.fonts_install),
                    enabled = installed != null && busy == null, busy = busy == "reader",
                ) {
                    act("reader") {
                        if (reader) { Fonts.removeFromReader(context, f.name).getOrThrow(); reader = false; context.getString(R.string.fonts_removed, f.name) }
                        else { val n = Fonts.installForReader(context, f).getOrThrow(); reader = true; context.resources.getQuantityString(R.plurals.fonts_installed, n, f.name, n) }
                    }
                }
                if (installed == null) Text(stringResource(R.string.fonts_needs_shizuku), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                ApplyRow(
                    stringResource(R.string.fonts_apply_home), stringResource(R.string.fonts_apply_home_s),
                    on = inHome, onLabel = stringResource(R.string.fonts_use_system), offLabel = stringResource(R.string.fonts_use),
                    enabled = busy == null, busy = busy == "home",
                ) {
                    act("home") {
                        val store = LauncherStore(context)
                        val path = if (inHome) null else Fonts.keep(context, f).getOrThrow().first { !it.italic }.file.absolutePath
                        store.save(store.load().copy(fontFile = path))
                        homeFont = path
                        context.getString(if (path == null) R.string.fonts_home_reset else R.string.fonts_home_set, f.name)
                    }
                }
                ApplyRow(
                    stringResource(R.string.fonts_apply_app), stringResource(R.string.fonts_apply_app_s),
                    on = inApp, onLabel = stringResource(R.string.fonts_use_archivo), offLabel = stringResource(R.string.fonts_use),
                    enabled = busy == null, busy = busy == "app",
                ) {
                    act("app") {
                        if (inApp) Fonts.setAppFont(context, null, null)
                        else Fonts.setAppFont(context, f.name, Fonts.keep(context, f).getOrThrow().first { !it.italic }.file.absolutePath)
                        (context as? Activity)?.recreate()
                        ""
                    }
                }
                Spacer(Modifier.height(Space.m))
                SystemFontRow(f, busy, onAct = ::act)
                Spacer(Modifier.height(Space.m))
                Text(stringResource(R.string.fonts_apply_serif), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.fonts_apply_serif_s), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                Spacer(Modifier.height(Space.s))
                Key(stringResource(R.string.fonts_open_serif_tweak), onClick = onOpenTweaks)
                message?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.m)) }
                error?.let { ErrorLine(it) }
            }
        }
    }
}

/**
 * The whole tablet's font, through Boox's own font switch. A heavier cut is offered first where the family has
 * one, because the panel thins every stroke.
 */
@Composable
private fun SystemFontRow(f: FontFamilyInfo, busy: String?, onAct: (String, suspend () -> String) -> Unit) {
    val context = LocalContext.current
    val weights = listOf(400, 500, 600, 700).filter { f.has(it, false) }.ifEmpty { listOf(f.regularKey.removeSuffix("i").toIntOrNull() ?: 400) }
    var weight by rememberSaveable(f.name) { mutableIntStateOf(if (500 in weights) 500 else weights.first()) }
    var current by remember { mutableStateOf(app.booxultimatum.core.SystemFont.current()) }
    val inUse = current?.substringAfterLast('/') == Fonts.fileName(f.name, weight, false)
    Text(stringResource(R.string.fonts_apply_system), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.fonts_apply_system_s), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
    Text(
        stringResource(R.string.fonts_sys_now, app.booxultimatum.core.SystemFont.nameOf(current) ?: stringResource(R.string.fonts_sys_default)),
        style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs),
    )
    Spacer(Modifier.height(Space.s))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (weights.size > 1) weights.forEach { w -> Key(stringResource(R.string.fonts_style, w), primary = w == weight, onClick = { weight = w }) }
        Key(
            if (busy == "system") stringResource(R.string.state_working) else stringResource(if (inUse) R.string.fonts_sys_in_use else R.string.fonts_sys_use),
            primary = true, enabled = busy == null && !inUse,
            onClick = {
                onAct("system") {
                    val file = Fonts.style(context, f, weight, false).getOrThrow()
                    val named = java.io.File(Fonts.keptDir(context), Fonts.fileName(f.name, weight, false)).also { if (it.absolutePath != file.absolutePath) file.copyTo(it, overwrite = true) }
                    current = app.booxultimatum.core.SystemFont.apply(context, named, "${f.name} $weight").getOrThrow()
                    context.getString(R.string.fonts_sys_done, f.name)
                }
            },
        )
        if (app.booxultimatum.core.SystemFont.changed(context)) Key(stringResource(R.string.fonts_sys_restore), enabled = busy == null, onClick = {
            onAct("system") {
                app.booxultimatum.core.SystemFont.restore(context).getOrThrow()
                current = app.booxultimatum.core.SystemFont.current()
                context.getString(R.string.fonts_sys_restored)
            }
        })
        Key(stringResource(R.string.fonts_sys_boox), onClick = { app.booxultimatum.launcher.BooxIntents.openSettings(context) })
    }
}

@Composable
private fun ApplyRow(title: String, summary: String, on: Boolean, onLabel: String, offLabel: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            app.booxultimatum.ui.Lamp(on)
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

private fun filterName(f: FontFilter) = when (f) {
    FontFilter.Picks -> R.string.fonts_f_picks
    FontFilter.All -> R.string.fonts_f_all
    FontFilter.Serif -> R.string.fonts_serif
    FontFilter.Sans -> R.string.fonts_sans
    FontFilter.Display -> R.string.fonts_f_display
    FontFilter.Handwriting -> R.string.fonts_f_hand
    FontFilter.Mono -> R.string.fonts_f_mono
}

private fun categoryName(c: FontCategory) = when (c) {
    FontCategory.Serif -> R.string.fonts_serif
    FontCategory.Sans -> R.string.fonts_sans
    FontCategory.Display -> R.string.fonts_f_display
    FontCategory.Handwriting -> R.string.fonts_f_hand
    FontCategory.Mono -> R.string.fonts_f_mono
}
