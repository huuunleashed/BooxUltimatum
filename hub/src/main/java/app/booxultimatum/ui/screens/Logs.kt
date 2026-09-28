package app.booxultimatum.ui.screens

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.booxultimatum.R
import app.booxultimatum.core.suite.LogExport
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.log.Level
import app.booxultimatum.kit.log.LogEvent
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.log.SuiteLogs
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.CodeStyle
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.launch

private const val DAY_MS = 86_400_000L
private const val PER_PAGE = 20

/**
 * What the suite apps recorded: detailed logging for a day when tracking a problem, one zip with every app's logs to
 * share, and this app's recent entries. Nothing here reads anything new; it only shows what the logbook kept.
 */
@Composable
fun LogsScreen(readKey: Int, compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var changeKey by remember { mutableIntStateOf(0) }
    var until by remember(readKey, changeKey) { mutableLongStateOf(Logbook.detailedUntil(context)) }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val files = rememberReading(readKey to changeKey) { Logbook.files(context) }
    val events = remember(readKey, changeKey, problemsOnly) {
        Logbook.recent(500).filter { !problemsOnly || it.level >= Level.Warn }.asReversed()
    }

    fun setDetailed(value: Long) {
        Logbook.setDetailedUntil(context, value)
        SuiteApp.entries.filter { it.packageName != context.packageName && Suite.installed(context, it) != null }
            .forEach { SuiteLogs.setDetailedUntil(context, it.packageName, value) }
        until = value
        changeKey++
    }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.logs_title), stringResource(R.string.logs_subtitle)) {
                Key(stringResource(if (sharing) R.string.logs_sharing else R.string.logs_share), primary = true, enabled = !sharing, onClick = {
                    sharing = true; error = null
                    scope.launch {
                        runCatching {
                            val zip = LogExport.build(context)
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", zip)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, zip.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { error = context.getString(R.string.logs_share_failed, it.message ?: it.javaClass.simpleName) }
                        sharing = false
                    }
                })
            }
            error?.let { ErrorLine(it) }
            Paragraph(stringResource(R.string.logs_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.l))
        }
        item {
            val now = System.currentTimeMillis()
            val on = until > now
            Plate(stringResource(R.string.logs_detailed)) {
                SpecRow(
                    stringResource(R.string.logs_detailed_state),
                    if (on) stringResource(R.string.logs_detailed_until, DateFormat.getTimeFormat(context).format(until)) else stringResource(R.string.logs_detailed_off),
                    note = stringResource(R.string.logs_detailed_explain),
                )
                Row(Modifier.padding(top = Space.s)) {
                    if (on) Key(stringResource(R.string.logs_detailed_stop), onClick = { setDetailed(0L) })
                    else Key(stringResource(R.string.logs_detailed_on), onClick = { setDetailed(now + DAY_MS) })
                }
                files?.let { list ->
                    val bytes = list.sumOf { it.length() }
                    SpecRow(stringResource(R.string.dest_logs), pluralStringResource(R.plurals.logs_files, list.size, list.size, android.text.format.Formatter.formatShortFileSize(context, bytes)))
                    val stops = list.filter { it.name.startsWith("crash-") || it.name.startsWith("exit-") }
                    SpecRow(stringResource(R.string.logs_crashes), if (stops.isEmpty()) stringResource(R.string.logs_no_crashes) else stops.take(5).joinToString("\n") { it.name })
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.logs_recent), action = {
                Key(stringResource(if (problemsOnly) R.string.logs_filter_all else R.string.logs_filter_problems), onClick = { problemsOnly = !problemsOnly; page = 0 })
            }) {
                if (events.isEmpty()) Paragraph(stringResource(R.string.logs_empty), color = Ink.Legend)
            }
        }
        val pages = ((events.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val shown = events.drop(page.coerceIn(0, pages - 1) * PER_PAGE).take(PER_PAGE)
        items(shown) { e -> EventRow(e) }
        if (pages > 1) item { Pager(page.coerceIn(0, pages - 1), pages, onPage = { page = it }) }
    }
}

@Composable
private fun EventRow(e: LogEvent) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(DateFormat.getTimeFormat(context).format(e.wallMs), style = MaterialTheme.typography.labelMedium, color = Ink.Legend)
            Tag(e.level.name, strong = e.level >= Level.Warn)
            Text(e.category, style = MaterialTheme.typography.labelMedium)
        }
        Text(e.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        if (e.fields.isNotEmpty()) Text(e.fields.entries.joinToString("  ") { "${it.key}=${it.value}" }, style = CodeStyle, color = Ink.Legend)
        e.error?.lineSequence()?.firstOrNull()?.let { Text(it, style = CodeStyle, color = Ink.Alert) }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule, modifier = Modifier.padding(top = Space.s))
    }
}
