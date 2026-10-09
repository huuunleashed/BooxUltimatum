package app.booxultimatum.kit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** OWNER: C. Choices joined in one rimmed pill; the chosen one is solid black. [label] names the group for screen readers. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, label: String? = null) {}