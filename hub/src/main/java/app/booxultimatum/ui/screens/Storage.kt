package app.booxultimatum.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.log.Housekeeping
import app.booxultimatum.kit.log.StorageUsage
import app.booxultimatum.kit.log.SuiteLogs
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One suite app's storage, as it reported it; null areas when the app couldn't be asked. */
private class AppStorage(val app: SuiteApp, val self: Boolean, val areas: List<StorageUsage>?)

/**
 * What every suite app keeps on the tablet, by kind, with the two cleanups: temporary files (cache, downloaded
 * updates, shared exports) and logs with crash reports. Documents, fonts and settings are counted but never cleared
 * here. Each app also keeps its own leftovers bounded by itself (see [Housekeeping]).
 */
@Composable
fun StorageScreen(readKey: Int, compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var changeKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var freed by remember { mutableStateOf<Long?>(null) }
    val apps = rememberReading(readKey to changeKey) {
        SuiteApp.entries.mapNotNull { a ->
            when {
                a.packageName == context.packageName -> AppStorage(a, true, Housekeeping.usage(context))
                Suite.installed(context, a) != null -> AppStorage(a, false, SuiteLogs.usage(context, a.packageName))
                else -> null
            }
        }
    }

    fun clean(target: AppStorage, ids: Set<String>) {
        busy = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                if (target.self) Housekeeping.clean(context, ids) else SuiteLogs.clean(context, target.app.packageName, ids) ?: 0L
            }
            freed = bytes
            busy = false
            changeKey++
        }
    }

    InstrumentPage(compact) {
        item {
            val total = apps?.sumOf { s -> s.areas?.sumOf { it.bytes } ?: 0L }
            ScreenHeader(stringResource(R.string.storage_title), total?.let { stringResource(R.string.storage_subtitle, size(context, it)) })
            Paragraph(stringResource(R.string.storage_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
            freed?.let { Paragraph(stringResource(R.string.storage_freed, size(context, it)), modifier = Modifier.padding(bottom = Space.s)) }
            Spacer(Modifier.height(Space.m))
        }
        if (apps == null) {
            item { Reading() }
            return@InstrumentPage
        }
        apps.forEach { s ->
            item(key = s.app.name) {
                val areas = s.areas
                Plate(s.app.title, action = { if (areas != null) androidx.compose.material3.Text(size(context, areas.sumOf { it.bytes }), style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }) {
                    if (areas == null) {
                        Paragraph(stringResource(R.string.storage_unreachable), color = Ink.Legend)
                        return@Plate
                    }
                    areas.filter { it.bytes > 0 || it.cleanable }.forEach { a ->
                        SpecRow(
                            areaLabel(a.id),
                            size(context, a.bytes),
                            note = pluralStringResource(R.plurals.storage_files, a.files, a.files) + " · " +
                                stringResource(if (a.cleanable) R.string.storage_clearable else R.string.storage_kept),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.m)) {
                        Key(stringResource(R.string.storage_clear_temporary), enabled = !busy, onClick = { clean(s, Housekeeping.TEMPORARY) })
                        ConfirmKey(
                            stringResource(R.string.storage_clear_logs),
                            stringResource(R.string.storage_clear_logs_confirm),
                            enabled = !busy,
                            onConfirm = { clean(s, setOf(Housekeeping.LOGS, Housekeeping.REPORTS)) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.xl))
            }
        }
    }
}

private fun size(context: android.content.Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)

@Composable
private fun areaLabel(id: String): String = when (id) {
    Housekeeping.LOGS -> stringResource(R.string.storage_area_logs)
    Housekeeping.REPORTS -> stringResource(R.string.storage_area_reports)
    Housekeeping.CACHE -> stringResource(R.string.storage_area_cache)
    Housekeeping.DOWNLOADS -> stringResource(R.string.storage_area_downloads)
    Housekeeping.EXPORTS -> stringResource(R.string.storage_area_exports)
    Housekeeping.DATA -> stringResource(R.string.storage_area_data)
    "drawings" -> stringResource(R.string.storage_area_drawings)
    "battery_log" -> stringResource(R.string.storage_area_battery_log)
    "sleep_screen" -> stringResource(R.string.storage_area_sleep)
    "fonts" -> stringResource(R.string.storage_area_fonts)
    else -> id
}
