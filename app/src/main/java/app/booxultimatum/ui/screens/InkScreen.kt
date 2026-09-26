package app.booxultimatum.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.ink.InkPrefs
import app.booxultimatum.core.ink.InkStyle
import app.booxultimatum.core.ink.InstantInk
import app.booxultimatum.core.ink.PenInput
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Lamp
import app.booxultimatum.ui.Pager
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InkApp(val pkg: String, val label: String)

/** Instant ink: Boox's direct-to-panel pen preview, lent to the drawing and note apps the owner picks. */
@Composable
fun InkScreen(readKey: Int, compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(InkPrefs.load(context)) }
    var statusKey by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val apps = rememberReading(readKey) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { InkApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    val penFound = remember { PenInput.device() != null }
    val shizuku = remember(readKey) { Privileged.ready() }

    fun commit(next: InkPrefs) {
        prefs = next
        InstantInk.apply(context, next)
        // The service settles on its own thread; read its state again once it has.
        scope.launch { delay(900); statusKey++ }
    }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.ink_title), stringResource(R.string.ink_subtitle)) {
                Key(stringResource(if (prefs.enabled) R.string.ink_turn_off else R.string.ink_turn_on), primary = !prefs.enabled, enabled = prefs.enabled || prefs.apps.isNotEmpty(), onClick = { commit(prefs.copy(enabled = !prefs.enabled)) })
            }
            Paragraph(stringResource(R.string.ink_explain))
            Spacer(Modifier.height(Space.xl))
            val status = remember(statusKey, prefs) { InstantInk.status }
            Plate(stringResource(R.string.ink_status)) {
                StatusLine(prefs.enabled && status != InstantInk.Status.Off, when {
                    !penFound -> stringResource(R.string.ink_status_nopen)
                    !prefs.enabled -> stringResource(R.string.ink_status_off)
                    prefs.apps.isEmpty() -> stringResource(R.string.ink_status_noapps)
                    status == InstantInk.Status.NoRoute -> stringResource(if (shizuku) R.string.ink_status_noroute else R.string.ink_status_needs_shizuku)
                    status == InstantInk.Status.NoPen -> stringResource(R.string.ink_status_nopen)
                    status == InstantInk.Status.Armed -> stringResource(R.string.ink_status_armed, InstantInk.armedFor.orEmpty())
                    else -> stringResource(R.string.ink_status_ready)
                })
                val route = remember(statusKey, prefs) { app.booxultimatum.core.ink.SurfaceInk.route }
                if (prefs.enabled && route != null) {
                    Text(
                        stringResource(when (route) {
                            app.booxultimatum.core.ink.SurfaceInk.Route.Firmware -> R.string.ink_route_firmware
                            app.booxultimatum.core.ink.SurfaceInk.Route.Direct -> R.string.ink_route_direct
                            app.booxultimatum.core.ink.SurfaceInk.Route.Shizuku -> R.string.ink_route_shizuku
                        }),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs),
                    )
                }
                Spacer(Modifier.height(Space.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Key(stringResource(R.string.ink_check), onClick = { statusKey++ })
                    Key(stringResource(R.string.ink_recover), onClick = { scope.launch(Dispatchers.IO) { InstantInk.recoverScreen(context) } })
                }
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.ink_recover_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.ink_stroke)) {
                Choice(stringResource(R.string.ink_style), InkStyle.entries, prefs.style, { stringResource(when (it) { InkStyle.Fountain -> R.string.ink_style_fountain; InkStyle.Pencil -> R.string.ink_style_pencil; InkStyle.Marker -> R.string.ink_style_marker }) }) { commit(prefs.copy(style = it)) }
                Choice(stringResource(R.string.ink_width), listOf(2, 3, 4, 6, 8), prefs.widthPx, { stringResource(R.string.ink_px, it) }) { commit(prefs.copy(widthPx = it)) }
                Choice(stringResource(R.string.ink_latency), listOf(250, 400, 500, 800, 1200), prefs.latencyMs, { stringResource(R.string.ink_ms, it) }) { commit(prefs.copy(latencyMs = it)) }
                Text(stringResource(R.string.ink_latency_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(bottom = Space.m))
                Choice(stringResource(R.string.ink_hold), listOf(true, false), prefs.holdAppInk, { stringResource(if (it) R.string.ink_hold_on else R.string.ink_hold_off) }) { commit(prefs.copy(holdAppInk = it)) }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.ink_apps), action = { Text(pluralStringResource(R.plurals.ink_apps_count, prefs.apps.size, prefs.apps.size), style = MaterialTheme.typography.labelLarge, color = Ink.Legend) }) {
                val list = apps
                if (list == null) { Reading(); return@Plate }
                // Chosen apps lead, so the ones in use are always on the first page.
                val ordered = list.sortedByDescending { it.pkg in prefs.apps }
                val perPage = if (compact) 8 else 12
                val pages = maxOf(1, (ordered.size + perPage - 1) / perPage)
                val p = page.coerceIn(0, pages - 1)
                ordered.drop(p * perPage).take(perPage).forEach { a ->
                    val on = a.pkg in prefs.apps
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lamp(on)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = Ink.Legend, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Key(stringResource(if (on) R.string.ink_app_remove else R.string.ink_app_add), onClick = {
                            val next = if (on) prefs.apps - a.pkg else prefs.apps + a.pkg
                            commit(prefs.copy(apps = next, enabled = prefs.enabled && next.isNotEmpty()))
                        })
                    }
                    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
                if (pages > 1) Pager(p, pages, { page = it })
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.ink_limits)) {
                Paragraph(stringResource(R.string.ink_limits_body), color = Ink.Legend)
            }
        }
    }
}

@Composable
private fun StatusLine(on: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Lamp(on)
        Spacer(Modifier.size(Space.m))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = Space.m)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = Space.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            options.forEach { o -> Key(label(o), primary = o == selected, onClick = { onPick(o) }) }
        }
    }
}
