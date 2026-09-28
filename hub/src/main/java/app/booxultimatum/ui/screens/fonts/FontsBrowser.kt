package app.booxultimatum.ui.screens.fonts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.booxultimatum.R
import app.booxultimatum.core.FontCategory
import app.booxultimatum.core.FontFamilyInfo
import app.booxultimatum.core.FontLibrary
import app.booxultimatum.core.FontState
import app.booxultimatum.core.Fonts
import app.booxultimatum.core.InstalledFont
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.SearchField
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space

private const val FONTS_PER_PAGE = 7

private enum class FontFilter { Picks, All, Installed, Serif, Sans, Display, Handwriting, Mono }
private enum class FontSort { Popular, Name }

/** The Google Fonts catalogue, with what's already installed marked and one tap from being managed. */
@Composable
internal fun FontBrowser(
    catalog: Result<List<FontFamilyInfo>>?,
    library: FontLibrary?,
    sample: String,
    onSample: (String) -> Unit,
    compact: Boolean,
    header: @Composable () -> Unit,
    onOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FontFilter.Picks) }
    var sort by rememberSaveable { mutableStateOf(FontSort.Popular) }
    var viOnly by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val installed = library?.fonts.orEmpty().associateBy { it.base }

    InstrumentPage(compact) {
        item {
            header()
            SearchField(query, { query = it; page = 0 }, stringResource(R.string.fonts_search))
            Spacer(Modifier.height(Space.m))
            SampleField(sample, onSample)
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
                    FontFilter.Installed -> Fonts.fileBase(f.name) in installed
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
            val base = Fonts.fileBase(f.name)
            FontRow(f, sample, installed[base], library?.usesOf(base).orEmpty()) { onOpen(base) }
        }
        if (pages > 1) item { Pager(safe, pages, onPage = { page = it }) }
    }
}

@Composable
private fun FontRow(f: FontFamilyInfo, sample: String, installed: InstalledFont?, uses: Set<app.booxultimatum.core.FontUse>, onOpen: () -> Unit) {
    val family = rememberFamily(f)
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (installed != null) {
                    Tag(stringResource(if (installed.state == FontState.On) R.string.fonts_tag_installed else R.string.fonts_tag_off), strong = installed.state == FontState.On)
                    Spacer(Modifier.width(Space.m))
                }
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
            Text(catalogMeta(f, installed, uses), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

/** Category, styles and script, then, for an installed family, where it's used. */
@Composable
internal fun catalogMeta(f: FontFamilyInfo, installed: InstalledFont?, uses: Set<app.booxultimatum.core.FontUse>): String {
    val parts = mutableListOf(stringResource(categoryName(f.category)), pluralStringResource(R.plurals.fonts_styles, f.styles, f.styles))
    if (f.vietnamese) parts += stringResource(R.string.fonts_vietnamese)
    if (installed != null && uses.isNotEmpty()) parts += stringResource(R.string.fonts_meta_in_use, usesText(uses))
    return parts.joinToString("  ·  ")
}

private fun filterName(f: FontFilter) = when (f) {
    FontFilter.Picks -> R.string.fonts_f_picks
    FontFilter.All -> R.string.fonts_f_all
    FontFilter.Installed -> R.string.fonts_tag_installed
    FontFilter.Serif -> R.string.fonts_serif
    FontFilter.Sans -> R.string.fonts_sans
    FontFilter.Display -> R.string.fonts_f_display
    FontFilter.Handwriting -> R.string.fonts_f_hand
    FontFilter.Mono -> R.string.fonts_f_mono
}
