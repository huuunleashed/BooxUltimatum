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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.booxultimatum.kit.core.TabletProfile
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.ink.InkPrefs
import app.booxultimatum.core.ink.InkStyle
import app.booxultimatum.core.ink.InstantInk
import app.booxultimatum.kit.ink.PenInput
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.rememberReading
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
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
                // Suite apps such as Nib drive the pen path themselves.
                .filter { !app.booxultimatum.kit.core.Suite.isSuitePackage(it.pkg) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    val penFound = remember { Tablet.current(context).hasPen && PenInput.readableNodes().isNotEmpty() }
    val shizuku = remember(readKey) { Privileged.ready() }
    val device = remember { Tablet.current(context) }
    var resumes by remember { mutableIntStateOf(0) }
    val usage = remember(readKey, statusKey, resumes) { InstantInk.usageAccess(context) }
    // Coming back from Android's usage access page, or after the Quick Settings switch or the notification changed
    // things: the page and the service both re-read the settings.
    LifecycleResumeEffect(Unit) {
        resumes++
        val p = InkPrefs.load(context)
        prefs = p
        if (p.enabled && p.apps.isNotEmpty()) { InstantInk.apply(context, p); scope.launch { delay(900); statusKey++ } }
        onPauseOrDispose { }
    }

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
                StatusLine(prefs.enabled && status != InstantInk.Status.Off && usage && penFound, when {
                    !device.isBoox -> stringResource(R.string.ink_status_not_boox)
                    !penFound -> stringResource(R.string.ink_status_nopen)
                    !usage -> stringResource(R.string.ink_status_needs_usage)
                    !prefs.enabled -> stringResource(R.string.ink_status_off)
                    prefs.apps.isEmpty() -> stringResource(R.string.ink_status_noapps)
                    status == InstantInk.Status.NoRoute -> stringResource(if (shizuku) R.string.ink_status_noroute else R.string.ink_status_needs_shizuku)
                    status == InstantInk.Status.NoPen -> stringResource(R.string.ink_status_nopen)
                    status == InstantInk.Status.NoUsageAccess -> stringResource(R.string.ink_status_needs_usage)
                    status == InstantInk.Status.Armed -> stringResource(R.string.ink_status_armed, InstantInk.armedFor.orEmpty())
                    else -> stringResource(R.string.ink_status_ready)
                })
                if (device.isBoox && penFound && !usage) {
                    Spacer(Modifier.height(Space.s))
                    Paragraph(stringResource(R.string.ink_usage_explain), color = Ink.Legend)
                    Spacer(Modifier.height(Space.s))
                    Key(stringResource(R.string.ink_usage_grant), primary = true, onClick = { InstantInk.openUsageAccess(context) })
                }
                val route = remember(statusKey, prefs) { app.booxultimatum.kit.ink.SurfaceInk.route }
                if (prefs.enabled && route != null) {
                    Text(
                        stringResource(when (route) {
                            app.booxultimatum.kit.ink.SurfaceInk.Route.Firmware -> R.string.ink_route_firmware
                            app.booxultimatum.kit.ink.SurfaceInk.Route.Direct -> R.string.ink_route_direct
                            app.booxultimatum.kit.ink.SurfaceInk.Route.Elevated -> R.string.ink_route_shizuku
                        }),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs),
                    )
                }
                Spacer(Modifier.height(Space.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Key(stringResource(R.string.ink_check), onClick = { statusKey++ })
                    Key(stringResource(R.string.ink_recover), onClick = { scope.launch(Dispatchers.IO) { InstantInk.recoverScreen(context) } })
                    if (device.isBoox && android.os.Build.VERSION.SDK_INT >= 33) {
                        val label = stringResource(R.string.ink_title)
                        Key(stringResource(R.string.ink_tile_add), onClick = { requestTile(context, label) })
                    }
                }
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.ink_recover_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                Text(stringResource(R.string.ink_tile_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs))
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.ink_stroke)) {
                Choice(stringResource(R.string.ink_style), InkStyle.entries, prefs.style, { stringResource(when (it) { InkStyle.Fountain -> R.string.ink_style_fountain; InkStyle.Pencil -> R.string.ink_style_pencil; InkStyle.Marker -> R.string.ink_style_marker }) }) { commit(prefs.copy(style = it)) }
                Choice(stringResource(R.string.ink_width), listOf(2, 3, 4, 6, 8), prefs.widthPx, { stringResource(R.string.ink_px, it) }) { commit(prefs.copy(widthPx = it)) }
                Choice(stringResource(R.string.ink_latency), listOf(250, 400, 500, 800, 1200), prefs.latencyMs, { stringResource(R.string.ink_ms, it) }) { commit(prefs.copy(latencyMs = it)) }
                Text(stringResource(R.string.ink_latency_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
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
            Spacer(Modifier.height(Space.xl))
            TabletPlate(device, usage, statusKey)
        }
    }
}

/** What Instant ink found on this tablet, in the words a report needs, plus a live pen test. */
@Composable
private fun TabletPlate(device: TabletProfile, usage: Boolean, statusKey: Int) {
    val route = remember(statusKey) { app.booxultimatum.kit.ink.SurfaceInk.route }
    val pressure = remember(statusKey) { app.booxultimatum.kit.ink.SurfaceInk.maxTouchPressure }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(emptyList<PenInput.Event>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val node = remember(statusKey, testing) { PenInput.remembered(context) }
    Plate(stringResource(R.string.ink_tablet)) {
        SpecRow(
            stringResource(R.string.ink_tablet_pen),
            device.pen?.let { p -> if (node != null) "${p.name} · ${node.removePrefix("/dev/input/")}" else stringResource(R.string.ink_tablet_pen_unseen, p.name) }
                ?: stringResource(R.string.ink_tablet_pen_none),
        )
        SpecRow(stringResource(R.string.ink_tablet_route), stringResource(when (route) {
            null -> R.string.ink_tablet_route_none
            app.booxultimatum.kit.ink.SurfaceInk.Route.Firmware -> R.string.ink_tablet_route_firmware
            app.booxultimatum.kit.ink.SurfaceInk.Route.Direct -> R.string.ink_tablet_route_direct
            app.booxultimatum.kit.ink.SurfaceInk.Route.Elevated -> R.string.ink_tablet_route_shizuku
        }))
        if (pressure != null) SpecRow(stringResource(R.string.ink_tablet_pressure), "%.0f".format(pressure))
        SpecRow(stringResource(R.string.ink_tablet_usage), stringResource(if (usage) R.string.ink_yes else R.string.ink_no))
        SpecRow(stringResource(R.string.ink_tablet_system), listOfNotNull(device.name, device.firmware, "Android ${device.android}").joinToString(" · "))
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.ink_tablet_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        Spacer(Modifier.height(Space.m))
        if (device.pen != null) {
            Key(stringResource(if (testing) R.string.ink_pen_test_stop else R.string.ink_pen_test), onClick = { if (!testing) seen = emptyList(); testing = !testing; tested = true })
            if (tested) {
                Spacer(Modifier.height(Space.s))
                val words = seen.map { stringResource(penWord(it)) }.distinct()
                Text(
                    when {
                        words.isNotEmpty() -> stringResource(R.string.ink_pen_test_seen, words.joinToString(", "))
                        testing -> stringResource(R.string.ink_pen_test_waiting)
                        else -> stringResource(R.string.ink_pen_test_none)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
    if (testing) {
        // A second reader on the pen node is fine: the kernel hands every reader its own copy of the events.
        DisposableEffect(Unit) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val input = PenInput(context) { e -> main.post { if (seen.size < 64) seen = seen + e } }
            if (!input.start()) testing = false
            onDispose { input.stop() }
        }
        LaunchedEffect(Unit) { delay(45_000); testing = false }
    }
}

/** Asks Quick Settings to add the Instant ink switch; the system shows its own confirmation. */
@androidx.annotation.RequiresApi(33)
private fun requestTile(context: android.content.Context, label: String) {
    runCatching {
        context.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            android.content.ComponentName(context, app.booxultimatum.core.ink.InkTileService::class.java),
            label,
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_tile_ink),
            context.mainExecutor,
        ) { }
    }
}

private fun penWord(e: PenInput.Event): Int = when (e) {
    PenInput.Event.Near -> R.string.ink_pen_near
    PenInput.Event.Down -> R.string.ink_pen_down
    PenInput.Event.Up -> R.string.ink_pen_up
    PenInput.Event.Away -> R.string.ink_pen_away
    PenInput.Event.EraserNear, PenInput.Event.EraserAway -> R.string.ink_pen_eraser
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
