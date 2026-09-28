package app.booxultimatum.nib.ui.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.booxultimatum.nib.R
import app.booxultimatum.nib.pen.PenShields

/**
 * Something to type, asked for in the entry bar at the top of the screen, where the keyboard can't cover it: an exact
 * slider value, a colour's hex, a name. [submit] returns an error to show, or null when the text was taken.
 */
class EntryRequest(
    val title: String,
    val initial: String,
    val numeric: Boolean,
    val hint: String? = null,
    val suffix: String? = null,
    val maxLength: Int = 80,
    val submit: (String) -> String?,
)

/** The entry bar: a card at the top centre with the field, what it takes, and Set and Cancel. */
@Composable
fun EntryBar(request: EntryRequest, setLabel: String, cancelLabel: String, onDone: () -> Unit, shields: PenShields?, modifier: Modifier = Modifier) {
    val fieldLabel = stringResource(R.string.entry_field, request.title)
    var field by remember(request) { mutableStateOf(TextFieldValue(request.initial, TextRange(0, request.initial.length))) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val focus = remember(request) { FocusRequester() }
    LaunchedEffect(request) { focus.requestFocus() }
    val submit = {
        val e = request.submit(field.text)
        if (e == null) onDone() else error = e
    }
    Column(
        modifier
            .padding(top = Studio.Edge)
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .padding(horizontal = Studio.Edge)
            .penShield(shields, "entry")
            .slab(radius = Studio.RadiusL, shadow = Studio.ShadowCard)
            .padding(16.dp),
    ) {
        Text(request.title, style = StudioType.Title)
        request.hint?.let { Text(it, style = StudioType.Small, color = Studio.Legend) }
        Spacer(Modifier.padding(top = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .slab(radius = Studio.RadiusS, shadow = 0.dp)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it.copy(text = it.text.take(request.maxLength))
                        error = null
                    },
                    singleLine = true,
                    textStyle = (if (request.numeric) StudioType.Figure else StudioType.Title).copy(color = Studio.Ink),
                    cursorBrush = SolidColor(Studio.Ink),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (request.numeric) KeyboardType.Decimal else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = fieldLabel },
                )
            }
            if (request.suffix != null) {
                Spacer(Modifier.width(10.dp))
                Text(request.suffix, style = StudioType.Value, color = Studio.Legend)
            }
            Spacer(Modifier.width(12.dp))
            SlabButton(setLabel, onClick = { submit() }, kind = ButtonKind.Primary)
            Spacer(Modifier.width(4.dp))
            SlabIconKey(StudioGlyphs.Close, cancelLabel, onClick = onDone)
        }
        error?.let { Text(it, style = StudioType.Label, color = Studio.Alert, modifier = Modifier.padding(top = 8.dp)) }
    }
}
