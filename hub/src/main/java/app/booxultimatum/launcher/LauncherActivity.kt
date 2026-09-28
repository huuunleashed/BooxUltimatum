package app.booxultimatum.launcher

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.kit.ui.theme.InstrumentTheme
import java.util.concurrent.Executors

enum class Overlay { None, Drawer, Edit, WidgetPicker }

/** Typing on home happens in a panel at the top of the screen, where the keyboard can never cover it. */
enum class Entry { City, Note }

/**
 * All launcher state. Updates arrive only from system events (time tick, battery change, package change) and
 * only while the launcher is visible; nothing polls.
 */
class LauncherModel(private val context: Context) {
    val catalog = AppCatalog(context)
    val store = LauncherStore(context)
    val widgetStore = WidgetStore(context)
    val weather = WeatherRepo(context)
    val host = AppWidgetHost(context, HOST_ID)
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)

    val apps = mutableStateOf<List<LaunchTarget>>(emptyList())
    val prefs = mutableStateOf(store.load())
    val widgets = mutableStateOf(widgetStore.list())
    val now = mutableLongStateOf(System.currentTimeMillis())
    val battery = mutableStateOf<BatterySnapshot?>(null)
    val page = mutableIntStateOf(0)
    val drawerPage = mutableIntStateOf(0)
    /** Page counts as laid out on screen, so keys and side buttons stop at the last page. */
    val pages = mutableIntStateOf(1)
    val drawerPages = mutableIntStateOf(1)
    /** Widgets the current orientation has no room for; Edit tells the person so. */
    val widgetsOffscreen = mutableIntStateOf(0)
    val overlay = mutableStateOf(Overlay.None)
    val selected = mutableStateOf<LaunchTarget?>(null)
    /** Increments each time the launcher becomes visible; widgets key their "refresh if stale" checks on it. */
    val visibleKey = mutableIntStateOf(0)
    val homeKey = mutableIntStateOf(0)
    val indicators = mutableStateOf(Indicators())
    /** Folder currently open on home, by id. */
    val openFolder = mutableStateOf<String?>(null)
    /** App whose "Add to folder" chooser is showing. */
    val folderChooser = mutableStateOf<LaunchTarget?>(null)
    val entry = mutableStateOf<Entry?>(null)
    val wallpaper = mutableStateOf<android.graphics.Bitmap?>(null)

    fun readIndicators() {
        val cr = context.contentResolver
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val next = Indicators(
            wifiOn = runCatching { wifi.isWifiEnabled }.getOrDefault(false),
            wifiConnected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true,
            wifiLevel = caps?.signalStrength?.let { rssi ->
                when {
                    rssi == Int.MIN_VALUE || rssi == android.net.NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 3
                    rssi >= -60 -> 3
                    rssi >= -70 -> 2
                    rssi >= -80 -> 1
                    else -> 0
                }
            } ?: 0,
            online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            bluetooth = runCatching { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.BLUETOOTH_ON, 0) == 1 }.getOrDefault(false),
            airplane = runCatching { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrDefault(false),
            dnd = runCatching { nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL }.getOrDefault(false),
            ssid = indicators.value.ssid.takeIf { caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true },
        )
        if (next != indicators.value) indicators.value = next
        // The name is read only when the connection itself changes, never on battery or clock ticks.
        if (next.wifiConnected && (next.ssid == null || !lastConnected)) readSsid()
        lastConnected = next.wifiConnected
    }

    private var lastConnected = false

    private fun readSsid() {
        io.execute {
            val name = runCatching {
                if (!app.booxultimatum.core.exec.Privileged.ready()) return@runCatching null
                val out = kotlinx.coroutines.runBlocking { app.booxultimatum.core.exec.Privileged.sh("cmd wifi status") }.out
                Regex("""connected to "([^"]+)"""").find(out)?.groupValues?.get(1)
            }.getOrNull()
            if (name != null) main.post { indicators.value = indicators.value.copy(ssid = name) }
        }
    }

    /** Loads the custom wallpaper at half resolution in RGB_565: about 2.3 MB instead of 18 MB at full size. */
    fun loadWallpaper() {
        readBackdrop()
        if (prefs.value.wallMode != WallMode.Image) { wallpaper.value = null; wallStamp = 0; return }
        val f = java.io.File(context.filesDir, WALLPAPER_FILE)
        // Returning home must not re-decode the picture: only a changed file is loaded again.
        if (wallpaper.value != null && f.lastModified() == wallStamp) return
        io.execute {
            val bmp = if (f.exists()) runCatching {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })
            }.getOrNull() else null
            val lum = bmp?.let { averageLuminance(it) }
            main.post { wallpaper.value = bmp; wallStamp = f.lastModified(); if (lum != null) pictureLum = lum; readBackdrop() }
        }
    }

    private var wallStamp = 0L
    private var pictureLum = 1f
    private var systemLum = 1f

    /**
     * How light the backdrop behind home's labels is, 0 black to 1 white, after the paper veil. Labels switch to
     * white ink with a dark halo below 0.5, so they stay legible on any wallpaper.
     */
    val backdropLum = mutableStateOf(1f)

    /** The text-weight offset chosen in Appearance, shared with the app. */
    val weightBoost = androidx.compose.runtime.mutableIntStateOf(app.booxultimatum.core.UiFonts.weightBoost(context))

    /**
     * The Boox system font as a full family. Android hands apps the file's default instance, which for a variable
     * font like the Manrope Boox often ships is its lightest; loading the file ourselves gets every weight.
     */
    val systemFamily = mutableStateOf<androidx.compose.ui.text.font.FontFamily?>(null)
    private var systemFontSource: String? = null

    fun readSystemFont() {
        io.execute {
            val src = app.booxultimatum.core.UiFonts.systemFontPath()
            if (src == systemFontSource && systemFamily.value != null) return@execute
            val fam = app.booxultimatum.core.UiFonts.systemFontCopy(context)?.let { app.booxultimatum.core.UiFonts.family(it.absolutePath) }
            main.post { systemFontSource = src; systemFamily.value = fam }
        }
    }

    private fun readBackdrop() {
        val p = prefs.value
        if (p.wallMode == WallMode.System) io.execute {
            val lum = runCatching {
                val colors = android.app.WallpaperManager.getInstance(context).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                when {
                    colors == null -> 1f
                    android.os.Build.VERSION.SDK_INT >= 31 && colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 -> 0.85f
                    else -> colors.primaryColor.luminance()
                }
            }.getOrDefault(1f)
            main.post { systemLum = lum; backdropLum.value = veiled(systemLum) }
        }
        backdropLum.value = when (p.wallMode) {
            WallMode.Paper -> 1f
            WallMode.Image -> veiled(pictureLum)
            WallMode.System -> veiled(systemLum)
        }
    }

    private fun veiled(lum: Float): Float { val v = prefs.value.wallDim / 100f; return v + (1 - v) * lum }

    private fun averageLuminance(b: android.graphics.Bitmap): Float {
        var sum = 0.0; var n = 0
        val stepX = maxOf(1, b.width / 48); val stepY = maxOf(1, b.height / 48)
        for (x in 0 until b.width step stepX) for (y in 0 until b.height step stepY) {
            val c = b.getPixel(x, y)
            sum += (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)) / 255.0
            n++
        }
        return if (n == 0) 1f else (sum / n).toFloat()
    }

    /** Copies a picked image into app storage, downscaled to half the panel so it costs little memory. */
    fun importWallpaper(uri: android.net.Uri, done: (Boolean) -> Unit) {
        io.execute {
            val ok = LauncherWallpaper.import(context, uri)
            main.post {
                if (ok) { update { it.copy(wallMode = WallMode.Image) }; loadWallpaper() }
                done(ok)
            }
        }
    }

    /** Settings may change from the BooxUltimatum app while home is in the background; re-read on return. */
    fun reloadPrefs() {
        val fresh = store.load()
        if (fresh != prefs.value) {
            if (fresh.iconShape != prefs.value.iconShape || fresh.iconStyle != prefs.value.iconStyle) catalog.trim()
            prefs.value = fresh
        }
        readBackdrop()
        weightBoost.intValue = app.booxultimatum.core.UiFonts.weightBoost(context)
        readSystemFont()
    }

    // ---------- Folders ----------

    fun createFolderWith(t: LaunchTarget, name: String) = update { p ->
        val id = WidgetStore.newId().take(8)
        val entry = FOLDER_PREFIX + id
        val favs = p.favourites.toMutableList()
        val at = favs.indexOf(t.key)
        if (at >= 0) favs[at] = entry else favs += entry
        p.copy(favourites = favs.distinct(), folders = p.folders + (id to Folder(id, name, listOf(t.key))))
    }

    fun addToFolder(t: LaunchTarget, id: String) = update { p ->
        val f = p.folders[id] ?: return@update p
        p.copy(favourites = p.favourites - t.key, folders = p.folders + (id to f.copy(apps = (f.apps + t.key).distinct())))
    }

    fun removeFromFolder(t: LaunchTarget, id: String) = update { p ->
        val f = p.folders[id] ?: return@update p
        val rest = f.apps - t.key
        val favs = p.favourites.toMutableList().apply { val i = indexOf(FOLDER_PREFIX + id); add(if (i >= 0) i + 1 else size, t.key) }
        if (rest.isEmpty()) p.copy(favourites = favs - (FOLDER_PREFIX + id), folders = p.folders - id)
        else p.copy(favourites = favs.distinct(), folders = p.folders + (id to f.copy(apps = rest)))
    }

    fun renameFolder(id: String, name: String) = update { p ->
        val f = p.folders[id] ?: return@update p
        p.copy(folders = p.folders + (id to f.copy(name = name.ifBlank { f.name })))
    }

    /** Deleting a folder puts its apps back on home where the folder was. */
    fun deleteFolder(id: String) = update { p ->
        val f = p.folders[id] ?: return@update p
        val favs = p.favourites.toMutableList()
        val i = favs.indexOf(FOLDER_PREFIX + id)
        if (i >= 0) { favs.removeAt(i); favs.addAll(i, f.apps) }
        p.copy(favourites = favs.distinct(), folders = p.folders - id)
    }

    fun folderOf(key: String): Folder? = prefs.value.folders.values.firstOrNull { key in it.apps }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun reloadApps() {
        io.execute {
            val list = catalog.load()
            main.post {
                apps.value = list
                if (!store.seeded()) seed(list)
            }
        }
    }

    private fun seed(list: List<LaunchTarget>) {
        val preferred = listOf("com.onyx.android.note", "com.onyx.kreader", "com.android.vending", "com.android.chrome", "app.booxultimatum", "com.onyx.gallery", "com.android.settings")
        val picked = preferred.mapNotNull { pkg -> list.firstOrNull { it.component.packageName == pkg }?.key }
        update { it.copy(favourites = (it.favourites + picked).distinct()) }
        store.markSeeded()
    }

    fun update(block: (LauncherPrefs) -> LauncherPrefs) {
        val next = block(prefs.value)
        prefs.value = next
        store.save(next)
    }

    fun setWidgets(list: List<WidgetSpec>) {
        widgets.value = list
        widgetStore.save(list)
    }

    fun togglePin(t: LaunchTarget) = update { p ->
        val folder = p.folders.values.firstOrNull { t.key in it.apps }
        when {
            folder != null -> {
                // Pinned inside a folder: "Remove from home" takes it out of the folder too.
                val rest = folder.apps - t.key
                if (rest.isEmpty()) p.copy(favourites = p.favourites - (FOLDER_PREFIX + folder.id), folders = p.folders - folder.id)
                else p.copy(folders = p.folders + (folder.id to folder.copy(apps = rest)))
            }
            t.key in p.favourites -> p.copy(favourites = p.favourites - t.key)
            else -> p.copy(favourites = p.favourites + t.key)
        }
    }

    fun move(t: LaunchTarget, delta: Int) = update { p ->
        val i = p.favourites.indexOf(t.key)
        val j = (i + delta).coerceIn(0, p.favourites.size - 1)
        if (i < 0 || i == j) p else p.copy(favourites = p.favourites.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun toggleHidden(t: LaunchTarget) = update { p ->
        if (t.key in p.hidden) p.copy(hidden = p.hidden - t.key) else p.copy(hidden = p.hidden + t.key, favourites = p.favourites - t.key)
    }

    fun readBattery() {
        val b = BatterySnapshot.read(context)
        val old = battery.value
        if (old == null || old.levelPct != b.levelPct || old.status != b.status || old.source != b.source) battery.value = b
    }

    fun goHome() {
        overlay.value = Overlay.None
        selected.value = null
        openFolder.value = null
        folderChooser.value = null
        page.intValue = 0
        drawerPage.intValue = 0
        homeKey.intValue++
    }

    fun removeWidget(w: WidgetSpec) {
        if (w.kind == WidgetKind.System && w.appWidgetId >= 0) runCatching { host.deleteAppWidgetId(w.appWidgetId) }
        setWidgets(widgets.value - w)
    }

    fun providers(): List<AppWidgetProviderInfo> {
        val users = context.getSystemService(UserManager::class.java).userProfiles
        return users.flatMap { u -> runCatching { widgetManager.getInstalledProvidersForProfile(u) }.getOrDefault(emptyList()) }
            .sortedBy { it.loadLabel(context.packageManager).lowercase() }
    }

    val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reloadApps()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reloadApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reloadApps()
    }

    companion object {
        const val HOST_ID = 0xB00C
        const val WALLPAPER_FILE = "launcher_wallpaper.jpg"
    }
}

/** What the header shows. Updated only by system broadcasts and a network callback while home is visible. */
data class Indicators(
    val wifiOn: Boolean = false,
    val wifiConnected: Boolean = false,
    /** 0-3 from the connection's signal strength; only meaningful while connected. */
    val wifiLevel: Int = 0,
    /** Network name, when it can be read (Shizuku; Android hides it from apps without location access). */
    val ssid: String? = null,
    val online: Boolean = false,
    val bluetooth: Boolean = false,
    val airplane: Boolean = false,
    val dnd: Boolean = false,
)

/** Wallpaper storage shared by the launcher and the BooxUltimatum app's Appearance screen. */
object LauncherWallpaper {
    /** Blocking. Stores the picture at half the panel size as JPEG; returns false if it cannot be read. */
    // The framework ExifInterface is reliable from API 25, and minSdk is 30, so the androidx copy would add nothing.
    @android.annotation.SuppressLint("ExifInterface")
    fun import(context: Context, uri: android.net.Uri): Boolean = runCatching {
        val metrics = context.resources.displayMetrics
        val targetW = maxOf(metrics.widthPixels, metrics.heightPixels) / 2
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetW) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })
        }!!
        // Camera photos keep their rotation in EXIF; without applying it a portrait photo would lie on its side.
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)!!.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        val bmp = if (degrees == 0f) decoded else android.graphics.Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(degrees) }, true,
        ).also { decoded.recycle() }
        java.io.File(context.filesDir, LauncherModel.WALLPAPER_FILE).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
    }.isSuccess

    fun exists(context: Context) = java.io.File(context.filesDir, LauncherModel.WALLPAPER_FILE).exists()

    /** Deletes the stored picture; home falls back to plain paper. */
    fun clear(context: Context) = java.io.File(context.filesDir, LauncherModel.WALLPAPER_FILE).delete()
}

class LauncherActivity : ComponentActivity() {
    private lateinit var model: LauncherModel
    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> model.readBattery()
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED ->
                    model.now.longValue = System.currentTimeMillis()
                else -> model.readIndicators()
            }
        }
    }

    private val network = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) { runOnUiThread { model.readIndicators() } }
        override fun onLost(n: android.net.Network) { runOnUiThread { model.readIndicators() } }
    }

    /**
     * Status bar and wallpaper are window properties. Home draws edge to edge and paints an ink strip under the
     * status bar itself: this firmware keeps status-bar icons white over apps it tunes with EinkWise, whatever the
     * app requests (verified on NA6C FW 4.3: `LIGHT_STATUS_BARS` is stripped from the window), so on paper they vanish.
     */
    fun applyWindow() {
        val p = model.prefs.value
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
        if (p.statusBar) controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        else {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        // The system wallpaper only shows through a window with no background of its own.
        if (p.wallMode == WallMode.System) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        }
        model.loadWallpaper()
    }

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.now.longValue = System.currentTimeMillis() }

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = pendingProvider
        if (r.resultCode == RESULT_OK && p != null) configureOrAdd(pendingWidgetId, p) else cancelPending()
    }

    private val configureWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) addPending() else cancelPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.BLACK), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        super.onCreate(savedInstanceState)
        model = LauncherModel(this)
        model.catalog.register(model.launcherCallback)
        model.reloadApps()
        applyWindow()
        setContent {
            val p = model.prefs.value
            // Font source: the system font (default), the font chosen for BooxUltimatum, or one picked for home.
            val path = when (p.fontSource) {
                FontSource.System -> null
                FontSource.App -> app.booxultimatum.core.Fonts.appFont(this)
                FontSource.Custom -> p.fontFile
            }
            val custom = androidx.compose.runtime.remember(path) { path?.let { app.booxultimatum.core.UiFonts.family(it) } }
            InstrumentTheme(systemFont = true, customFont = custom, weightBoost = model.weightBoost.intValue, systemFamily = model.systemFamily.value) {
                LauncherScreen(
                    model = model,
                    onRequestCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                    onAddSystemWidget = ::addSystemWidget,
                    onOpenApp = { openMainApp() },
                    onOpenAppearance = { openMainApp("Appearance") },
                    onWindowChanged = ::applyWindow,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
        model.reloadPrefs()
        applyWindow()
        model.now.longValue = System.currentTimeMillis()
        model.readBattery()
        model.readIndicators()
        model.visibleKey.intValue++
        runCatching { model.host.startListening() }
        // Saving any EinkWise setting makes Boox restrict the app in the background again (verified on FW 4.3),
        // which would stop the battery log; home is the app's most frequent entry point, so it checks here too.
        app.booxultimatum.core.BatteryLog.ensureNotRestricted(this)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        runCatching { model.host.stopListening() }
        super.onStop()
    }

    override fun onDestroy() {
        model.catalog.unregister(model.launcherCallback)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) model.goHome()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) model.catalog.trim()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (model.overlay.value == Overlay.Edit || model.overlay.value == Overlay.WidgetPicker) return super.onKeyDown(keyCode, event)
        val forward = keyCode == KeyEvent.KEYCODE_PAGE_DOWN || (model.prefs.value.sideButtons && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        val back = keyCode == KeyEvent.KEYCODE_PAGE_UP || (model.prefs.value.sideButtons && keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        if (!forward && !back) return super.onKeyDown(keyCode, event)
        val drawer = model.overlay.value == Overlay.Drawer
        val target = if (drawer) model.drawerPage else model.page
        val last = (if (drawer) model.drawerPages.intValue else model.pages.intValue) - 1
        target.intValue = (target.intValue + if (forward) 1 else -1).coerceIn(0, last.coerceAtLeast(0))
        return true
    }

    private fun openMainApp(destination: String? = null) {
        startActivity(Intent(this, app.booxultimatum.MainActivity::class.java).putExtra(app.booxultimatum.MainActivity.EXTRA_DESTINATION, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun addSystemWidget(provider: AppWidgetProviderInfo) {
        val id = model.host.allocateAppWidgetId()
        pendingWidgetId = id
        pendingProvider = provider
        if (model.widgetManager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, null)) {
            configureOrAdd(id, provider)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile),
            )
        }
    }

    private fun configureOrAdd(id: Int, provider: AppWidgetProviderInfo) {
        val configure: ComponentName? = provider.configure
        if (configure != null && (provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val launched = runCatching { configureWidget.launch(intent) }.isSuccess
            if (!launched) addPending()
        } else {
            addPending()
        }
    }

    private fun addPending() {
        val id = pendingWidgetId
        if (id >= 0) model.setWidgets(model.widgets.value + WidgetSpec(WidgetStore.newId(), WidgetKind.System, 2, id))
        pendingWidgetId = -1
        pendingProvider = null
        model.overlay.value = Overlay.Edit
    }

    private fun cancelPending() {
        if (pendingWidgetId >= 0) runCatching { model.host.deleteAppWidgetId(pendingWidgetId) }
        pendingWidgetId = -1
        pendingProvider = null
    }
}
