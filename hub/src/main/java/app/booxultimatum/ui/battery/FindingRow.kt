package app.booxultimatum.ui.battery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.battery.Finding
import app.booxultimatum.core.battery.Severity
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space

/** One finding in a list: its severity tag and title, its evidence, and a hairline under it. With [onOpen] the whole row is a button. */
@Composable
fun FindingRow(finding: Finding, modifier: Modifier = Modifier, onOpen: ((Finding) -> Unit)? = null) {
    val context = LocalContext.current
    val severity = BatteryText.severityLabel(context, finding.severity)
    val title = BatteryText.findingTitle(context, finding)
    val evidence = BatteryText.findingEvidence(context, finding)
    val sentence = stringResource(R.string.fr_sentence, severity, title, evidence)
    val interaction = remember { MutableInteractionSource() }
    val tap = if (onOpen == null) Modifier else Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button) { onOpen(finding) }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(tap)
            .semantics(mergeDescendants = true) { contentDescription = sentence },
    ) {
        Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Tag(severity, strong = finding.severity == Severity.Problem)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.s))
        }
        Text(evidence, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs, bottom = Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}
