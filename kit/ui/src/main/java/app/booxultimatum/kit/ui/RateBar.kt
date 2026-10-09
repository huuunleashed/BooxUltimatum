package app.booxultimatum.kit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.R
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlin.math.max

/**
 * A labelled horizontal bar with its value printed at the end. [fraction] is 0..1 of the track; [hatched] fills it with
 * hatching instead of solid black. [detail] is a smaller second line under the label. The bar is decorative: the row
 * reads the label, the value and the detail as one item.
 */
@Composable
fun RateBar(label: String, valueText: String, fraction: Float, modifier: Modifier = Modifier, hatched: Boolean = false, detail: String? = null) {
    val description = if (detail == null) {
        stringResource(R.string.rate_bar_description, label, valueText)
    } else {
        stringResource(R.string.rate_bar_description_detail, label, valueText, detail)
    }
    val share = fraction.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(4.dp)
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = Space.m))
            Text(valueText, style = MaterialTheme.typography.titleMedium)
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        Spacer(Modifier.height(Space.s))
        Canvas(
            Modifier.fillMaxWidth().height(14.dp).clip(shape).background(Ink.Paper).border(Lines.rim, Ink.Black, shape).clearAndSetSemantics {},
        ) {
            val rim = Lines.rim.toPx()
            val innerWidth = size.width - 2 * rim
            val innerHeight = size.height - 2 * rim
            if (share > 0f && innerWidth > 0f) {
                val end = rim + max(4.dp.toPx(), share * innerWidth).coerceAtMost(innerWidth)
                if (hatched) {
                    drawHatch(Offset(rim, rim), Size(end - rim, innerHeight))
                    if (end < rim + innerWidth) drawRect(Ink.Black, Offset(end - rim / 2, rim), Size(rim, innerHeight))
                } else {
                    drawRect(Ink.Black, Offset(rim, rim), Size(end - rim, innerHeight))
                }
            }
        }
    }
}
