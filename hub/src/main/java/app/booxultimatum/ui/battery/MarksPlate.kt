package app.booxultimatum.ui.battery

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.battery.BatteryView
import app.booxultimatum.core.battery.BeforeAfter
import app.booxultimatum.core.battery.BeforeAfterVerdict
import app.booxultimatum.core.battery.Mark
import app.booxultimatum.core.battery.MarkSource
import app.booxultimatum.core.battery.Marks
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.launch
import java.text.NumberFormat

private const val MARKS_PER_PAGE = 9
private const val MARK_LABEL_MAX = 40

/**
 * OWNER: E2. Marks and before-and-after: the entry for a new mark at the top of the plate, the marks newest first (paged),
 * and for each mark what the quiet-asleep rate did either side of it. [onMarked] is called after a mark was written.
 */
@Composable
fun MarksPlate(view: BatteryView, onMarked: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val recording = BatteryLog.enabled(context)
    val canAdd = recording && label.isNotBlank()
    val marks = remember(view.marks) { view.marks.sortedByDescending { it.epoch } }
    val pages = pageCount(marks.size)
    val shown = page.coerceIn(0, pages - 1)
    val add = {
        val text = label.trim()
        label = ""
        page = 0
        // mark() has no completion callback, and the write takes a while: the page re-reads once the row is on disk.
        BatteryLog.record(context, "mark", text) { scope.launch { onMarked() } }
    }

    Plate(stringResource(R.string.bm_title), modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabelField(value = label, onValueChange = { label = clipLabel(it) }, onSubmit = { if (canAdd) add() }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            Key(stringResource(R.string.bm_add), onClick = add, enabled = canAdd)
        }
        if (!recording) Paragraph(stringResource(R.string.bm_paused), modifier = Modifier.padding(top = Space.s))
        Paragraph(stringResource(R.string.bm_legend), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        if (marks.isEmpty()) Paragraph(stringResource(R.string.bm_none), modifier = Modifier.padding(vertical = Space.s))
        marks.drop(shown * MARKS_PER_PAGE).take(MARKS_PER_PAGE).forEach { MarkRow(view, it) }
        if (pages > 1) Pager(shown, pages, onPage = { page = it })
    }
}

/** The text entry: the look of the hub's sample field (a hairline frame, Ink black), with its own hint. */
@Composable
private fun LabelField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.border(Lines.hairline, Ink.Black, shape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(stringResource(R.string.bm_hint), style = MaterialTheme.typography.bodyLarge, color = Ink.Legend, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black),
            cursorBrush = SolidColor(Ink.Black),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSubmit() }),
        )
    }
}

@Composable
private fun MarkRow(view: BatteryView, mark: Mark) {
    val context = LocalContext.current
    val line = remember(mark, view.nowMs) { beforeAfterLine(Marks.beforeAfter(view.full, mark, view.baseline)) }
    val result = resultText(context, line)
    val whenText = stringResource(R.string.bt_day_time, BatteryText.day(context, mark.epoch), Format.clock(context, mark.epoch))
    val sentence = stringResource(R.string.bm_row, whenText, mark.label, result)
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = sentence }) {
        Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text(mark.label, style = MaterialTheme.typography.titleMedium)
            if (mark.source == MarkSource.Tweak) Tag(stringResource(R.string.bm_tag_tweak), modifier = Modifier.padding(start = Space.s))
        }
        Text(whenText, style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs))
        Text(result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs, bottom = Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

/** A before-and-after result as the sentence it reads and the numbers it takes. Pure, so the mapping is tested on the JVM. */
internal sealed interface BeforeAfterLine {
    data class NotEnough(val beforeHours: Double, val afterHours: Double) : BeforeAfterLine {
        val showHours: Boolean get() = beforeHours > 0 || afterHours > 0
    }

    data class WithinNormal(val beforeMa: Double, val afterMa: Double, val spreadMa: Double?) : BeforeAfterLine

    data class Lower(val beforeMa: Double, val afterMa: Double, val spreadMa: Double?) : BeforeAfterLine

    data class Higher(val beforeMa: Double, val afterMa: Double, val spreadMa: Double?) : BeforeAfterLine
}

internal fun beforeAfterLine(result: BeforeAfter): BeforeAfterLine {
    val before = result.beforeMa
    val after = result.afterMa
    val notEnough = BeforeAfterLine.NotEnough(result.beforeHours, result.afterHours)
    if (before == null || after == null) return notEnough
    return when (result.verdict) {
        BeforeAfterVerdict.NotEnoughData -> notEnough
        BeforeAfterVerdict.WithinNormal -> BeforeAfterLine.WithinNormal(before, after, result.spreadMa)
        BeforeAfterVerdict.Lower -> BeforeAfterLine.Lower(before, after, result.spreadMa)
        BeforeAfterVerdict.Higher -> BeforeAfterLine.Higher(before, after, result.spreadMa)
    }
}

/** The entry's text cut to [MARK_LABEL_MAX] characters, counting code points so an emoji is never split. */
internal fun clipLabel(text: String): String {
    var end = 0
    var count = 0
    while (end < text.length && count < MARK_LABEL_MAX) {
        end += Character.charCount(text.codePointAt(end))
        count++
    }
    return text.substring(0, end)
}

private fun resultText(context: Context, line: BeforeAfterLine): String = when (line) {
    is BeforeAfterLine.NotEnough -> listOfNotNull(
        context.getString(R.string.bm_not_enough),
        if (line.showHours) context.getString(R.string.bm_hours_before_after, hours(context, line.beforeHours), hours(context, line.afterHours)) else null,
    ).joinToString(" ")
    is BeforeAfterLine.WithinNormal -> rateText(context, R.string.bm_within, line.beforeMa, line.afterMa, line.spreadMa)
    is BeforeAfterLine.Lower -> rateText(context, R.string.bm_lower, line.beforeMa, line.afterMa, line.spreadMa)
    is BeforeAfterLine.Higher -> rateText(context, R.string.bm_higher, line.beforeMa, line.afterMa, line.spreadMa)
}

private fun rateText(context: Context, sentence: Int, beforeMa: Double, afterMa: Double, spreadMa: Double?): String = listOfNotNull(
    context.getString(sentence, BatteryText.ma(context, beforeMa), BatteryText.ma(context, afterMa)),
    spreadMa?.let { context.getString(R.string.bm_spread, BatteryText.ma(context, it)) },
).joinToString(" ")

private fun hours(context: Context, value: Double): String =
    NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

private fun pageCount(items: Int): Int = ((items + MARKS_PER_PAGE - 1) / MARKS_PER_PAGE).coerceAtLeast(1)
