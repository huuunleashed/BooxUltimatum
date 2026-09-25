package app.booxultimatum.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Pure black on white: best contrast on Kaleido 3, and nothing for the EPD to dither.
private val EinkColors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    secondary = Color.Black, onSecondary = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color.White, onSurfaceVariant = Color.Black,
    outline = Color.Black,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EinkColors) {
        // Ripples cause extra partial refreshes and ghosting on e-ink.
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
    }
}

@Composable
fun EinkButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(if (selected) 3.dp else 1.dp, Color.Black),
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun KeyValueRows(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(240.dp))
                Text(v, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Color.Black, thickness = 0.5.dp)
        }
    }
}
