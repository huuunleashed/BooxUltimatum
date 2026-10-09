package app.booxultimatum.kit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * OWNER: C. A labelled horizontal bar with its value printed at the end. [fraction] is 0..1 of the track; [hatched]
 * fills it with hatching instead of solid black. [detail] is a smaller second line under the label.
 */
@Composable
fun RateBar(label: String, valueText: String, fraction: Float, modifier: Modifier = Modifier, hatched: Boolean = false, detail: String? = null) {}