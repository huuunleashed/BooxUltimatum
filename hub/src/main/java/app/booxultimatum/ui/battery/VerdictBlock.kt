package app.booxultimatum.ui.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.battery.Finding
import app.booxultimatum.core.battery.Verdict
import app.booxultimatum.core.battery.VerdictState
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space

/** The verdict: a marker and its word. For a Watch or a Problem, the worst finding and the key that opens it. */
@Composable
fun VerdictBlock(verdict: Verdict, modifier: Modifier = Modifier, onOpenFinding: ((Finding) -> Unit)? = null) {
    val context = LocalContext.current
    val word = BatteryText.verdictTitle(context, verdict.state)
    val worst = verdict.findings.firstOrNull().takeIf { verdict.state == VerdictState.Watch || verdict.state == VerdictState.Problem }
    val line = when (verdict.state) {
        VerdictState.Normal -> stringResource(R.string.vb_normal_line)
        VerdictState.NoData -> stringResource(R.string.vb_no_data_line)
        else -> null
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        VerdictMarker(verdict.state, Modifier.size(32.dp).clearAndSetSemantics {})
        Column(Modifier.weight(1f).padding(start = Space.m)) {
            Column(Modifier.semantics(mergeDescendants = true) { stateDescription = word }) {
                Text(word, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (worst != null) {
                    Text(BatteryText.findingTitle(context, worst), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.s))
                    Text(BatteryText.findingEvidence(context, worst), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                }
                if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
            }
            if (worst != null && onOpenFinding != null) {
                Key(stringResource(R.string.vb_see_what_happened), onClick = { onOpenFinding(worst) }, modifier = Modifier.padding(top = Space.m))
            }
        }
    }
}

@Composable
private fun VerdictMarker(state: VerdictState, modifier: Modifier) {
    Canvas(modifier) {
        val ring = Lines.engraved.toPx()
        val bar = (Lines.engraved * 1.5f).toPx()
        val outline = Stroke(width = ring, join = StrokeJoin.Round)
        val radius = (size.minDimension - ring) / 2
        when (state) {
            VerdictState.Normal -> {
                drawCircle(Ink.Black, radius = radius, style = outline)
                drawPath(tick(), Ink.Black, style = Stroke(width = bar, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            VerdictState.Watch -> {
                drawPath(triangle(), Ink.Black, style = outline)
                exclamation(Ink.Black, bar)
            }
            VerdictState.Problem -> {
                drawPath(triangle(), Ink.Black)
                exclamation(Ink.Paper, bar)
            }
            VerdictState.NoData -> {
                val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                drawCircle(Ink.Black, radius = radius, style = Stroke(width = ring, pathEffect = dashes))
            }
        }
    }
}

private fun DrawScope.tick(): Path = Path().apply {
    moveTo(size.width * 0.28f, size.height * 0.52f)
    lineTo(size.width * 0.44f, size.height * 0.68f)
    lineTo(size.width * 0.72f, size.height * 0.36f)
}

private fun DrawScope.triangle(): Path = Path().apply {
    moveTo(size.width / 2, size.height * 0.06f)
    lineTo(size.width * 0.96f, size.height * 0.92f)
    lineTo(size.width * 0.04f, size.height * 0.92f)
    close()
}

private fun DrawScope.exclamation(color: Color, bar: Float) {
    val x = size.width / 2
    drawLine(color, Offset(x, size.height * 0.36f), Offset(x, size.height * 0.62f), strokeWidth = bar, cap = StrokeCap.Round)
    drawCircle(color, radius = bar * 0.6f, center = Offset(x, size.height * 0.77f))
}
