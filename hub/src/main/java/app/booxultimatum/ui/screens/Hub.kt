package app.booxultimatum.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.HubEntry
import app.booxultimatum.core.SettingsIndex
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.SearchField
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space

@Composable
fun HubScreen(readKey: Int, compact: Boolean) {
    val context = LocalContext.current
    val entries = rememberReading(readKey) { SettingsIndex.available(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf<String?>(null) }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_hub), entries?.let { stringResource(R.string.hub_subtitle, it.size) }) {
                app.booxultimatum.kit.ui.Key(stringResource(R.string.hub_open_android), onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent().setComponent(android.content.ComponentName("com.android.settings", "com.android.settings.Settings")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
            }
            SearchField(query, { query = it }, stringResource(R.string.hub_search))
            failed?.let { ErrorLine(it) }
            Spacer(Modifier.height(Space.xl))
        }
        if (entries == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val q = query.trim()
        val titled = entries.map { it to (context.getString(it.title) + " " + context.getString(it.summary)) }
        val matches = if (q.isEmpty()) titled else titled.filter { (_, text) -> text.contains(q, ignoreCase = true) }
        if (matches.isEmpty()) {
            item { Paragraph(stringResource(R.string.hub_empty, q), color = Ink.Legend) }
        }
        matches.map { it.first }.groupBy { it.group }.forEach { (group, list) ->
            item(key = group.name) {
                Plate(stringResource(group.title)) {
                    list.forEach { e ->
                        HubRow(e) {
                            failed = try {
                                context.startActivity(e.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); null
                            } catch (_: ActivityNotFoundException) {
                                context.getString(R.string.hub_failed, context.getString(e.title))
                            } catch (_: SecurityException) {
                                context.getString(R.string.hub_failed, context.getString(e.title))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
            }
        }
    }
}

@Composable
private fun HubRow(e: HubEntry, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(stringResource(e.title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(e.summary), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Text(stringResource(R.string.action_open), style = MaterialTheme.typography.labelLarge)
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}
