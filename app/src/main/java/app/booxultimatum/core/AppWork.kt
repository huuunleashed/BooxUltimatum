package app.booxultimatum.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Work that must finish even if the screen that started it goes away. Some changes (the system font overlay,
 * for one) make Android recreate the activity mid-change; a screen-bound scope would be cancelled and report a
 * failure for a change that actually went through.
 */
object AppWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
