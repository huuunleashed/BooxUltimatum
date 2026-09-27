package app.booxultimatum.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.booxultimatum.R
import app.booxultimatum.core.feedback.FeedbackInclude
import app.booxultimatum.core.feedback.FeedbackReports
import app.booxultimatum.core.feedback.FeedbackSnapshot
import app.booxultimatum.core.feedback.FeedbackType
import app.booxultimatum.ui.CodeBlock
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FeedbackDialog(
    onDismiss: () -> Unit,
    initialType: FeedbackType = FeedbackType.Bug,
    initialArea: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val areaOptions = feedbackAreaOptions()
    val snapshot by produceState<FeedbackSnapshot?>(null) { value = FeedbackReports.snapshot(context) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    var area by rememberSaveable { mutableStateOf(initialArea ?: areaOptions.first()) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var includedNames by rememberSaveable { mutableStateOf(defaultIncludes(initialType).map { it.name }) }
    var status by rememberSaveable { mutableStateOf("") }

    fun includes() = includedNames.mapNotNull { runCatching { FeedbackInclude.valueOf(it) }.getOrNull() }.toSet()
    fun report(s: FeedbackSnapshot) = FeedbackReports.buildReport(type, area, title, description, includes(), s)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Ink.Paper), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 980.dp).fillMaxSize().padding(horizontal = Space.l, vertical = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                item {
                    ScreenHeader(stringResource(R.string.feedback_title), stringResource(R.string.feedback_subtitle)) {
                        Key(stringResource(R.string.feedback_close), onClick = onDismiss)
                    }
                }
                item {
                    ChoiceRow(
                        label = stringResource(R.string.feedback_type),
                        options = listOf(
                            stringResource(R.string.feedback_type_bug) to FeedbackType.Bug,
                            stringResource(R.string.feedback_type_device) to FeedbackType.Device,
                            stringResource(R.string.feedback_type_idea) to FeedbackType.Idea,
                        ),
                        current = type,
                    ) {
                        type = it
                        if (it == FeedbackType.Device && FeedbackInclude.Inputs.name !in includedNames) includedNames = includedNames + FeedbackInclude.Inputs.name
                    }
                    if (type == FeedbackType.Bug) ChoiceRow(stringResource(R.string.feedback_area), areaOptions.map { it to it }, area) { area = it }
                    Field(stringResource(R.string.feedback_field_title), title, { title = it }, 1)
                    Field(stringResource(R.string.feedback_field_description), description, { description = it }, 6)
                }
                item {
                    Text(stringResource(R.string.feedback_include_title), style = MaterialTheme.typography.titleLarge)
                    Paragraph(stringResource(R.string.feedback_privacy_line), color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                    if (snapshot == null) Reading() else IncludeList(snapshot!!, includedNames.toSet(), onToggle = { include, on ->
                        includedNames = if (on) (includedNames + include.name).distinct() else includedNames - include.name
                    })
                }
                item {
                    Paragraph(stringResource(R.string.feedback_github_note), color = Ink.Legend)
                    Paragraph(stringResource(R.string.feedback_no_send_note), color = Ink.Legend, modifier = Modifier.padding(top = Space.xs))
                    if (status.isNotBlank()) Paragraph(status, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.m)) {
                        Key(stringResource(R.string.feedback_open_github), enabled = snapshot != null, primary = true, onClick = {
                            val s = snapshot ?: return@Key
                            scope.launch {
                                val draft = withContext(Dispatchers.Default) { FeedbackReports.githubDraft(context, type, area, title, description, includes(), s) }
                                status = context.getString(if (draft.copiedFullReport) R.string.feedback_copied_too_long else R.string.feedback_opened_github)
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(draft.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        })
                        Key(stringResource(R.string.feedback_copy), enabled = snapshot != null, onClick = {
                            val s = snapshot ?: return@Key
                            FeedbackReports.copy(context, report(s))
                            status = context.getString(R.string.feedback_copied)
                        })
                        Key(stringResource(R.string.feedback_share), enabled = snapshot != null, onClick = {
                            val s = snapshot ?: return@Key
                            FeedbackReports.share(context, report(s))
                        })
                    }
                    Spacer(Modifier.height(Space.xl))
                }
            }
        }
    }
}

@Composable
private fun IncludeList(snapshot: FeedbackSnapshot, included: Set<String>, onToggle: (FeedbackInclude, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        FeedbackInclude.entries.forEach { include ->
            IncludeRow(include, included = include.name in included, text = snapshot.textFor(include), onToggle = onToggle)
        }
    }
}

@Composable
private fun IncludeRow(include: FeedbackInclude, included: Boolean, text: String, onToggle: (FeedbackInclude, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(include.label(), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (included) R.string.feedback_included else R.string.feedback_not_included), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            Key(stringResource(if (included) R.string.feedback_remove else R.string.feedback_include), onClick = { onToggle(include, !included) })
            Spacer(Modifier.padding(start = Space.s))
            Key(stringResource(if (expanded) R.string.feedback_preview_hide else R.string.feedback_preview_show), onClick = { expanded = !expanded })
        }
        if (expanded) CodeBlock(text, Modifier.padding(bottom = Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun FeedbackInclude.label(): String = stringResource(
    when (this) {
        FeedbackInclude.Device -> R.string.feedback_inc_device
        FeedbackInclude.App -> R.string.feedback_inc_app
        FeedbackInclude.Features -> R.string.feedback_inc_features
        FeedbackInclude.Inputs -> R.string.feedback_inc_inputs
        FeedbackInclude.Battery -> R.string.feedback_inc_battery
        FeedbackInclude.Log -> R.string.feedback_inc_log
    },
)

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, current: T, onChange: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
            options.forEach { (text, value) -> Key(text, primary = value == current, onClick = { onChange(value) }) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, lines: Int) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
        Spacer(Modifier.height(Space.xs))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            minLines = lines,
            maxLines = lines + 2,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black),
            cursorBrush = SolidColor(Ink.Black),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .border(Lines.rim, Ink.Black, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private fun defaultIncludes(type: FeedbackType) = buildSet {
    add(FeedbackInclude.Device)
    add(FeedbackInclude.App)
    add(FeedbackInclude.Features)
    if (type == FeedbackType.Device) add(FeedbackInclude.Inputs)
}

@Composable
private fun feedbackAreaOptions() = listOf(
    stringResource(R.string.feedback_area_home),
    stringResource(R.string.feedback_area_sleep),
    stringResource(R.string.feedback_area_ink),
    stringResource(R.string.feedback_area_battery),
    stringResource(R.string.feedback_area_tweaks),
    stringResource(R.string.feedback_area_appearance),
    stringResource(R.string.feedback_area_other),
)
