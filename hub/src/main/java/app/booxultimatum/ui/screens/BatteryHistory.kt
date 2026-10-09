package app.booxultimatum.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.ui.Destination
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.ScreenHeader

/** OWNER: E (History page). Replaces this stub. */
@Composable
fun BatteryHistoryScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    InstrumentPage(compact) {
        item { ScreenHeader(stringResource(R.string.dest_battery_history), null) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) } }
    }
}
