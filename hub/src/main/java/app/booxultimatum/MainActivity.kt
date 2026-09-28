package app.booxultimatum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.ui.theme.InstrumentTheme
import app.booxultimatum.kit.update.Updates
import app.booxultimatum.ui.BooxUltimatumApp
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    // Bumped by Shizuku callbacks so access readings refresh without polling.
    private val accessEvents = mutableIntStateOf(0)
    private val onBinder = Shizuku.OnBinderReceivedListener { app.booxultimatum.core.exec.Privileged.retryNow(); accessEvents.intValue++ }
    private val onBinderDead = Shizuku.OnBinderDeadListener { accessEvents.intValue++ }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> accessEvents.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge with an ink strip under the status bar (see StatusStrip): this firmware keeps the icons white here.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
            show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        app.booxultimatum.core.BatteryLog.schedule(this)
        app.booxultimatum.core.BatteryLog.record(this, "open")
        app.booxultimatum.core.BatteryLog.ensureNotRestricted(this)
        Updates.init(this, SuiteApp.entries.toSet())
        Updates.checkIfDue(this)
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        // A font chosen in Fonts › Use in this app replaces Archivo everywhere in the UI, at every weight it uses.
        val appFont = app.booxultimatum.core.Fonts.appFont(this)?.let { app.booxultimatum.core.UiFonts.family(it) }
        val boost = app.booxultimatum.core.UiFonts.weightBoost(this)
        setContent { InstrumentTheme(customFont = appFont, weightBoost = boost) { BooxUltimatumApp(accessEvents.intValue, initialDestination()) } }
    }

    private fun initialDestination(): String? = intent?.getStringExtra(EXTRA_DESTINATION)

    companion object {
        /** Opens the app on a given screen (a [app.booxultimatum.ui.Destination] name); used by BooxUltimatum home. */
        const val EXTRA_DESTINATION = "app.booxultimatum.extra.DESTINATION"
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        super.onDestroy()
    }
}
