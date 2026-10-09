package app.booxultimatum.ui.battery

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.battery.Episode
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlin.math.roundToInt

/**
 * One episode as a datasheet row, the accessible twin of the strip. A tap expands it in place, and the row reads as one
 * sentence to a screen reader.
 */
@Composable
fun EpisodeRow(episode: Episode, modifier: Modifier = Modifier, expanded: Boolean = false, onToggle: (() -> Unit)? = null) {
    val context = LocalContext.current
    val range = BatteryText.timeRange(context, episode.startMs, episode.endMs)
    val duration = BatteryText.duration(context, episode.endMs - episode.startMs)
    val kind = BatteryText.kindLabel(context, episode.kind)
    val mah = episode.mah?.let { BatteryText.mah(context, it) }
    val ma = episode.avgMa?.let { BatteryText.ma(context, it) }
    val problem = episode.kind == EpisodeKind.DisplayOn
    val problemTag = stringResource(R.string.ep_tag_problem)
    val inferredTag = stringResource(R.string.ep_tag_inferred)
    val flags = listOfNotNull(problemTag.takeIf { problem }, inferredTag.takeIf { episode.inferred })
    val appPackage = episode.topApp.takeIf { episode.kind == EpisodeKind.InUse }
    val appName = remember(appPackage) { appPackage?.let { appLabel(context, it) } }
    val app = appName?.let { stringResource(R.string.ep_top_app, it) }
    val levels = stringResource(R.string.ep_levels, episode.level0, episode.level1)
    val slept = stringResource(R.string.ep_slept, percent(episode.sleptShare))
    val state = stringResource(if (expanded) R.string.ep_expanded else R.string.ep_collapsed)
    val sentence = buildList {
        add(range)
        add(duration)
        add(kind)
        addAll(flags)
        mah?.let { add(it) }
        ma?.let { add(it) }
        if (expanded) {
            add(levels)
            add(slept)
            app?.let { add(it) }
        }
    }.joinToString(", ")
    val rowModifier = modifier.fillMaxWidth()
        .let {
            if (onToggle == null) it
            else it.clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClick = onToggle)
        }
        .semantics(mergeDescendants = true) {
            contentDescription = sentence
            if (onToggle != null) stateDescription = state
        }

    Column(rowModifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(0.36f)) {
                Text(range, style = MaterialTheme.typography.bodyLarge)
                Text(duration, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Column(Modifier.weight(0.4f).padding(horizontal = Space.s)) {
                Text(kind, style = MaterialTheme.typography.titleMedium)
                if (flags.isNotEmpty()) {
                    Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (problem) Tag(problemTag, strong = true)
                        if (episode.inferred) Tag(inferredTag)
                    }
                }
            }
            Column(Modifier.weight(0.24f), horizontalAlignment = Alignment.End) {
                mah?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                ma?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.s)) {
                Text(levels, style = MaterialTheme.typography.bodyMedium)
                Text(slept, style = MaterialTheme.typography.bodyMedium)
                app?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

private fun percent(share: Double): Int = (share * 100).roundToInt().coerceIn(0, 100)

private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
}.getOrDefault(pkg)
