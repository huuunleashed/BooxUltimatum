package app.booxultimatum.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.core.DeviceReader
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Pager
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.SpecRow
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Space

private const val PROPS_PER_PAGE = 24

@Composable
fun DeviceScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    val context = LocalContext.current
    val profile = rememberReading(readKey) { DeviceReader.read(context) }
    var showProps by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }

    InstrumentPage(compact) {
        item {
            ScreenHeader(
                stringResource(R.string.dest_device),
                profile?.let { "${it.identity.manufacturer} ${it.identity.model}" },
            ) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) }
        }
        if (profile == null) {
            item { Reading() }
            return@InstrumentPage
        }
        profile.groups.forEach { group ->
            item(key = group.title) {
                Plate(group.title) { group.lines.forEach { SpecRow(it.label, it.value) } }
                Spacer(Modifier.height(Space.xl))
            }
        }
        item {
            val count = profile.properties.size
            Plate(
                stringResource(R.string.plate_properties),
                action = {
                    Key(
                        stringResource(if (showProps == 1) R.string.action_hide else R.string.action_show_all),
                        onClick = { showProps = 1 - showProps; page = 0 },
                    )
                },
            ) {
                Paragraph(pluralStringResource(R.plurals.properties_explain, count, count), color = Ink.Legend, modifier = Modifier)
            }
        }
        if (showProps == 1) {
            val pages = (profile.properties.size + PROPS_PER_PAGE - 1) / PROPS_PER_PAGE
            val slice = profile.properties.drop(page * PROPS_PER_PAGE).take(PROPS_PER_PAGE)
            items(slice, key = { it.first }) { (k, v) -> SpecRow(k, v.ifEmpty { "—" }, code = true) }
            item { Pager(page, pages.coerceAtLeast(1), onPage = { page = it }) }
        }
    }
}
