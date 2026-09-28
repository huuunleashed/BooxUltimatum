package app.booxultimatum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
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
import app.booxultimatum.BuildConfig
import app.booxultimatum.R
import app.booxultimatum.core.DeviceReader
import app.booxultimatum.core.feedback.FeedbackType
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.kit.update.UpdateChannel
import app.booxultimatum.kit.update.UpdateText
import app.booxultimatum.kit.update.Updates
import app.booxultimatum.kit.update.UpdatesState

private const val PROPS_PER_PAGE = 24

@Composable
fun DeviceScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    val context = LocalContext.current
    val profile = rememberReading(readKey) { DeviceReader.read(context) }
    val updates by Updates.state.collectAsState()
    var showProps by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showFeedback by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { Updates.init(context, SuiteApp.entries.toSet()) }
    if (showFeedback) FeedbackDialog(onDismiss = { showFeedback = false }, initialType = FeedbackType.Bug)

    InstrumentPage(compact) {
        item {
            ScreenHeader(
                stringResource(R.string.dest_device),
                profile?.let { "${it.identity.manufacturer} ${it.identity.model}" },
            ) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) }
        }
        item {
            ThisAppPlate(updates, onFeedback = { showFeedback = true })
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
private fun ThisAppPlate(updates: UpdatesState, onFeedback: () -> Unit) {
    val context = LocalContext.current
    val hub = updates[SuiteApp.Hub]
    val channel = updates.channel
    Plate(stringResource(R.string.about_plate_this_app)) {
        SpecRow(stringResource(R.string.about_app_version), BuildConfig.VERSION_NAME)
        SpecRow(stringResource(R.string.about_app_build), stringResource(if (updates.releaseBuild) R.string.about_build_release else R.string.about_build_development))
        SpecRow(stringResource(R.string.updates_channel), stringResource(if (channel.previews) R.string.updates_channel_previews else R.string.updates_channel_stable))
        SpecRow(stringResource(R.string.updates_tests), stringResource(if (channel.tests) R.string.updates_tests_on else R.string.updates_tests_off), note = stringResource(R.string.updates_tests_explain))
        SpecRow(
            stringResource(R.string.updates_title),
            if (updates.lastCheck > 0) stringResource(R.string.updates_last_check, android.text.format.DateUtils.getRelativeTimeSpanString(updates.lastCheck).toString())
            else stringResource(R.string.updates_never_checked),
        )
        if (hub != null) {
            Text(UpdateText.describe(context, hub), style = MaterialTheme.typography.bodyLarge, color = if (updates.releaseBuild) Ink.Black else Ink.Legend, modifier = Modifier.padding(top = Space.s))
            hub.offered?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.about_release_notes), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
                Paragraph(it, modifier = Modifier.padding(top = Space.xs))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.m)) {
            Key(stringResource(R.string.about_check_updates), enabled = updates.releaseBuild && !updates.checking, onClick = { Updates.checkNow(context) })
            if (hub?.canInstall == true) Key(stringResource(R.string.about_download_install), primary = true, onClick = { Updates.install(context, SuiteApp.Hub) })
            if (hub?.phase is app.booxultimatum.kit.update.UpdatePhase.NeedsUnknownSources) Key(stringResource(R.string.about_unknown_sources), onClick = { Updates.openUnknownSources(context) })
            Key(
                stringResource(if (channel.previews) R.string.about_hide_pre_releases else R.string.about_include_pre_releases),
                enabled = updates.releaseBuild,
                onClick = { Updates.setChannel(context, channel.copy(previews = !channel.previews)) },
            )
            Key(
                stringResource(if (channel.tests) R.string.updates_tests_disable else R.string.updates_tests_enable),
                enabled = updates.releaseBuild,
                onClick = { Updates.setChannel(context, UpdateChannel(previews = channel.previews, tests = !channel.tests)) },
            )
            Key(stringResource(R.string.about_release_page), onClick = { Updates.openReleasePage(context, SuiteApp.Hub) })
            Key(stringResource(R.string.feedback_entry), onClick = onFeedback)
        }
        if (hub?.phase is app.booxultimatum.kit.update.UpdatePhase.NeedsUnknownSources) Paragraph(stringResource(R.string.about_unknown_sources_note), color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
    }
}
