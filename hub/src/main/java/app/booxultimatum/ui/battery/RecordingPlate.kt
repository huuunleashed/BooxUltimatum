package app.booxultimatum.ui.battery

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** OWNER: E3. The Recording plate: pause or resume the log, where the files are, export, and share a battery report. */
@Composable
fun RecordingPlate(readKey: Int, onChanged: () -> Unit, modifier: Modifier = Modifier) {}

/** OWNER: E3. The old manual measurements, read-only, kept so past results stay visible. Draws nothing when there are none. */
@Composable
fun EarlierMeasurementsPlate(readKey: Int, modifier: Modifier = Modifier) {}