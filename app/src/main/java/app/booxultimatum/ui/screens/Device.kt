package app.booxultimatum.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.BuildConfig
import app.booxultimatum.core.DeviceReader
import app.booxultimatum.core.feedback.FeedbackType
import app.booxultimatum.core.update.BuildKind
import app.booxultimatum.core.update.UpdateManager
import app.booxultimatum.core.update.UpdateState
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
    val update by UpdateManager.state.collectAsState()
    var showProps by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showFeedback by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { UpdateManager.initialise(context) }
    if (showFeedback) FeedbackDialog(onDismiss = { showFeedback = false }, initialType = FeedbackType.Bug)

    InstrumentPage(compact) {
        item {
            ScreenHeader(
                stringResource(R.string.dest_device),
                profile?.let { "${it.identity.manufacturer} ${it.identity.model}" },
            ) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) }
        }
        item {
            ThisAppPlate(update, onFeedback = { showFeedback = true })
            Spacer(Modifier.height(Space.xl))
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

@Composable
private fun ThisAppPlate(update: UpdateState, onFeedback: () -> Unit) {
    val context = LocalContext.current
    Plate(stringResource(R.string.about_plate_this_app)) {
        SpecRow(stringResource(R.string.about_app_version), BuildConfig.VERSION_NAME)
        SpecRow(stringResource(R.string.about_app_build), stringResource(if (update.buildKind == BuildKind.Release) R.string.about_build_release else R.string.about_build_development))
        SpecRow(stringResource(R.string.about_pre_releases), stringResource(if (update.includePrereleases) R.string.about_yes else R.string.about_no))
        Text(update.line.arg?.let { stringResource(update.line.resId, it) } ?: stringResource(update.line.resId), style = MaterialTheme.typography.bodyLarge, color = if (update.disabled) Ink.Legend else Ink.Black, modifier = Modifier.padding(top = Space.s))
        update.release?.notes?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.about_release_notes), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
            Paragraph(it, modifier = Modifier.padding(top = Space.xs))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.m)) {
            Key(stringResource(R.string.about_check_updates), enabled = !update.busy && !update.disabled, onClick = { UpdateManager.checkNow(context) })
            Key(
                stringResource(if (update.includePrereleases) R.string.about_hide_pre_releases else R.string.about_include_pre_releases),
                enabled = !update.busy && !update.disabled,
                onClick = { UpdateManager.setIncludePrereleases(context, !update.includePrereleases) },
            )
            if (update.canDownload) Key(stringResource(R.string.about_download_install), primary = true, onClick = { UpdateManager.downloadAndInstall(context) })
            if (update.needsUnknownSources) Key(stringResource(R.string.about_unknown_sources), onClick = { UpdateManager.openUnknownSources(context) })
            Key(stringResource(R.string.about_release_page), onClick = { UpdateManager.openReleasePage(context) })
            Key(stringResource(R.string.feedback_entry), onClick = onFeedback)
        }
        if (update.needsUnknownSources) Paragraph(stringResource(R.string.about_unknown_sources_note), color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
    }
}
