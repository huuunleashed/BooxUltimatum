package app.booxultimatum.kit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.booxultimatum.kit.core.SuiteLanguage
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Space

/**
 * The language the app's words are in: the tablet's own, or one of the languages the app has. The one in
 * use is marked, the others are keys, and each language is named in its own words.
 *
 * The caller passes the words in, because the hub and Nib each describe themselves by name.
 */
@Composable
fun LanguageChoice(
    languages: List<SuiteLanguage>,
    current: SuiteLanguage?,
    detail: String,
    systemLabel: String,
    onChange: (SuiteLanguage?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.s)) {
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        Spacer(Modifier.height(Space.s))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Key(systemLabel, primary = current == null, onClick = { onChange(null) })
            languages.forEach { language ->
                Key(language.endonym, primary = current == language, onClick = { onChange(language) })
            }
        }
    }
}
