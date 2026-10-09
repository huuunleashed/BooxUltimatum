package app.booxultimatum.kit.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.booxultimatum.kit.ui.R
import app.booxultimatum.kit.ui.theme.Space

/** Previous, a centred label (a date), next: 56 dp round keys either side, disabled at the ends. */
@Composable
fun DayPager(label: String, onPrevious: () -> Unit, onNext: () -> Unit, canPrevious: Boolean, canNext: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconKey(ChartGlyphs.ChevronLeft, stringResource(R.string.chart_previous), onPrevious, enabled = canPrevious)
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = Space.s))
        IconKey(ChartGlyphs.ChevronRight, stringResource(R.string.chart_next), onNext, enabled = canNext)
    }
}
