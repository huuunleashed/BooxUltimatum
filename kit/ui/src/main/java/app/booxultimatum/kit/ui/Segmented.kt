package app.booxultimatum.kit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space

/**
 * Choices joined in one rimmed pill; the chosen one is solid black. [label] names the group for screen readers. Labels
 * wrap at large font sizes rather than being cut short.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(Ink.Paper)
            .border(Lines.rim, Ink.Black, shape)
            .selectableGroup()
            .semantics { if (label != null) contentDescription = label },
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(Lines.hairline).fillMaxHeight().background(Ink.Black))
            val chosen = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(if (chosen) Ink.Black else Ink.Paper)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 14.dp, vertical = Space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = MaterialTheme.typography.labelLarge, color = if (chosen) Ink.Paper else Ink.Black, textAlign = TextAlign.Center)
            }
        }
    }
}
