package app.booxultimatum.ui.battery

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.booxultimatum.core.battery.BatteryView

/**
 * OWNER: E2. Marks and before-and-after: the entry for a new mark at the top of the plate, the marks newest first (paged),
 * and for each mark what the quiet-asleep rate did either side of it. [onMarked] is called after a mark was written.
 */
@Composable
fun MarksPlate(view: BatteryView, onMarked: () -> Unit, modifier: Modifier = Modifier) {}