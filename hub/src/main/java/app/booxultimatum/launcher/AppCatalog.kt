package app.booxultimatum.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray

enum class IconStyle { Original, Monochrome, Grayscale }

enum class IconShape { Original, Circle, Rounded, Squircle, Square }

enum class WallMode { Paper, System, Image }

enum class FontSource { System, App, Custom }

enum class IndicatorStyle { Glyphs, GlyphsAndText, Text }

data class Folder(val id: String, val name: String, val apps: List<String>)

/** Favourites hold app keys, or folder references in this form. */
const val FOLDER_PREFIX = "folder:"

/**
 * One launchable entry. [key] is stable across restarts: component plus user serial, or `boox:<name>` for the Boox
 * functions that live inside the Boox home and have no launcher activity of their own (Notes, Library, Storage…).
 * Those carry the [intent] that opens them.
 */
data class LaunchTarget(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val isBoox: Boolean,
    val intent: android.content.Intent? = null,
    /** True when Boox froze the package: it has a launch entry but Android hides it from launchers. */
    val frozen: Boolean = false,
)

enum class DrawerSort { Name, Colour, Recent }

enum class DrawerView { Grid, List }

data class LauncherPrefs(
    val favourites: List<String>,
    val hidden: Set<String>,
    val columns: Int,
    val rows: Int,
    val labels: Boolean,
    val iconStyle: IconStyle,
    val sideButtons: Boolean,
    /** Absolute path of a downloaded font for the launcher, or null to follow the system font. */
    val fontFile: String? = null,
    val fontSource: FontSource = FontSource.System,
    val iconShape: IconShape = IconShape.Original,
    val wallMode: WallMode = WallMode.Paper,
    val wallDim: Int = 55,
    val statusBar: Boolean = true,
    val indicatorStyle: IndicatorStyle = IndicatorStyle.Glyphs,
    val wifiGlyph: WifiGlyph = WifiGlyph.Arcs,
    val batteryGlyph: BatteryGlyph = BatteryGlyph.Cell,
    /** Header elements, each individually switchable. */
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showWifi: Boolean = true,
    val showWifiName: Boolean = true,
    val showBluetooth: Boolean = true,
    val showBatteryPct: Boolean = true,
    val folders: Map<String, Folder> = emptyMap(),
    val drawerSort: DrawerSort = DrawerSort.Name,
    val drawerView: DrawerView = DrawerView.Grid,
)

/** Launcher state on disk. Plain preferences: small, synchronous, and easy to back up later. */
class LauncherStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun load(): LauncherPrefs = LauncherPrefs(
        favourites = prefs.getString("favourites", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } } ?: emptyList(),
        hidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet(),
        columns = prefs.getInt("columns", 5),
        rows = prefs.getInt("rows", 4),
        labels = prefs.getBoolean("labels", true),
        iconStyle = runCatching { IconStyle.valueOf(prefs.getString("icon_style", null) ?: "") }.getOrDefault(IconStyle.Original),
        sideButtons = prefs.getBoolean("side_buttons", true),
        fontFile = prefs.getString("font_file", null)?.takeIf { java.io.File(it).canRead() },
        fontSource = runCatching { FontSource.valueOf(prefs.getString("font_source", null) ?: "") }
            .getOrElse { if (prefs.getString("font_file", null) != null) FontSource.Custom else FontSource.System },
        iconShape = runCatching { IconShape.valueOf(prefs.getString("icon_shape", null) ?: "") }.getOrDefault(IconShape.Original),
        wallMode = runCatching { WallMode.valueOf(prefs.getString("wall_mode", null) ?: "") }.getOrDefault(WallMode.Paper),
        wallDim = prefs.getInt("wall_dim", 50).let { ((it + 5) / 10 * 10).coerceIn(0, 90) },
        statusBar = prefs.getBoolean("status_bar", true),
        indicatorStyle = runCatching { IndicatorStyle.valueOf(prefs.getString("indicator_style", null) ?: "") }.getOrDefault(IndicatorStyle.Glyphs),
        wifiGlyph = runCatching { WifiGlyph.valueOf(prefs.getString("wifi_glyph", null) ?: "") }.getOrDefault(WifiGlyph.Arcs),
        batteryGlyph = runCatching { BatteryGlyph.valueOf(prefs.getString("battery_glyph", null) ?: "") }.getOrDefault(BatteryGlyph.Cell),
        showTime = prefs.getBoolean("show_time", true),
        showDate = prefs.getBoolean("show_date", true),
        showWifi = prefs.getBoolean("show_wifi", true),
        showWifiName = prefs.getBoolean("show_wifi_name", true),
        showBluetooth = prefs.getBoolean("show_bt", true),
        showBatteryPct = prefs.getBoolean("show_batt_pct", true),
        folders = prefs.getString("folders", null)?.let { raw ->
            runCatching {
                val o = org.json.JSONObject(raw)
                o.keys().asSequence().associateWith { id ->
                    val f = o.getJSONObject(id)
                    val a = f.getJSONArray("apps")
                    Folder(id, f.getString("name"), (0 until a.length()).map { a.getString(it) })
                }
            }.getOrNull()
        } ?: emptyMap(),
        drawerSort = runCatching { DrawerSort.valueOf(prefs.getString("drawer_sort", null) ?: "") }.getOrDefault(DrawerSort.Name),
        drawerView = runCatching { DrawerView.valueOf(prefs.getString("drawer_view", null) ?: "") }.getOrDefault(DrawerView.Grid),
    )

    /** First-run seeding happens once; kept separate from [save] so settings written elsewhere never skip it. */
    fun seeded() = prefs.getBoolean("seeded_v2", false)

    fun markSeeded() = prefs.edit().putBoolean("seeded_v2", true).apply()

    fun save(p: LauncherPrefs) {
        prefs.edit()
            .putString("favourites", JSONArray(p.favourites).toString())
            .putStringSet("hidden", p.hidden)
            .putInt("columns", p.columns)
            .putInt("rows", p.rows)
            .putBoolean("labels", p.labels)
            .putString("icon_style", p.iconStyle.name)
            .putBoolean("side_buttons", p.sideButtons)
            .putString("font_file", p.fontFile)
            .putString("font_source", p.fontSource.name)
            .putString("icon_shape", p.iconShape.name)
            .putString("wall_mode", p.wallMode.name)
            .putInt("wall_dim", p.wallDim)
            .putBoolean("status_bar", p.statusBar)
            .putString("indicator_style", p.indicatorStyle.name)
            .putString("wifi_glyph", p.wifiGlyph.name)
            .putString("battery_glyph", p.batteryGlyph.name)
            .putBoolean("show_time", p.showTime)
            .putBoolean("show_date", p.showDate)
            .putBoolean("show_wifi", p.showWifi)
            .putBoolean("show_wifi_name", p.showWifiName)
            .putBoolean("show_bt", p.showBluetooth)
            .putBoolean("show_batt_pct", p.showBatteryPct)
            .putString("folders", org.json.JSONObject().apply {
                p.folders.values.forEach { f -> put(f.id, org.json.JSONObject().put("name", f.name).put("apps", JSONArray(f.apps))) }
            }.toString())
            .putString("drawer_sort", p.drawerSort.name)
            .putString("drawer_view", p.drawerView.name)
            .apply()
    }
}

/** Thin, public-API-only wrapper over [LauncherApps]. */
class AppCatalog(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val infos = HashMap<String, LauncherActivityInfo>()
    private val icons = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun keyOf(component: ComponentName, user: UserHandle) = "${component.flattenToShortString()}#${users.getSerialNumberForUser(user)}"

    @Synchronized
    fun load(): List<LaunchTarget> {
        infos.clear()
        virtualIcons.clear()
        icons.evictAll()
        val result = mutableListOf<LaunchTarget>()
        val seen = HashSet<String>()
        for (user in users.userProfiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                val key = keyOf(info.componentName, user)
                infos[key] = info
                seen += info.componentName.packageName
                val pkg = info.componentName.packageName
                val label = RENAMES[info.componentName.flattenToShortString()]?.let { context.getString(it) } ?: info.label.toString()
                result += LaunchTarget(key, info.componentName, user, label, pkg == "com.onyx" || pkg.startsWith("com.onyx."))
            }
        }
        result += frozenApps(seen)
        result += booxFunctions()
        return result.sortedWith(compareBy({ it.label.lowercase() }, { it.key }))
    }

    /**
     * Packages Boox froze: they keep a launch entry and stock still opens them, but Android hides them from
     * launchers, so they never appear above. They are listed greyed instead of missing (issue #3).
     */
    private fun frozenApps(seen: Set<String>): List<LaunchTarget> {
        val pm = context.packageManager
        val user = users.userProfiles.firstOrNull() ?: android.os.Process.myUserHandle()
        val installed = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            }
        }.getOrDefault(emptyList())
        return installed.mapNotNull { app ->
            if (app.packageName in seen || app.enabled) return@mapNotNull null
            val launch = runCatching { pm.getLaunchIntentForPackage(app.packageName) } .getOrNull() ?: return@mapNotNull null
            val component = launch.component ?: return@mapNotNull null
            runCatching { virtualIcons["frozen:${app.packageName}"] = pm.getApplicationIcon(app.packageName) }
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
            val key = keyOf(component, user)
            virtualIcons[key] = virtualIcons["frozen:${app.packageName}"] ?: return@mapNotNull null
            LaunchTarget(key, component, user, label, app.packageName == "com.onyx" || app.packageName.startsWith("com.onyx."), frozen = true)
        }
    }

    private val virtualIcons = HashMap<String, Drawable>()

    /**
     * Boox functions without a launcher activity, reached through the entry points the Boox home itself uses
     * (verified on NA6C FW 4.3). Only those that resolve on this tablet are listed.
     */
    private fun booxFunctions(): List<LaunchTarget> {
        val pm = context.packageManager
        val me = android.os.Process.myUserHandle()
        fun main(action: String) = android.content.Intent("com.onyx.intent.action.MAIN_ACTIVITY").setPackage("com.onyx").putExtra("json", "{\"action\":\"$action\"}")
        val entries = listOf(
            Triple("notes", app.booxultimatum.R.string.boox_fn_notes, main("OPEN_NOTE")) to "com.onyx.android.note",
            Triple("library", app.booxultimatum.R.string.boox_fn_library, android.content.Intent("com.onyx.action.LIBRARY").setPackage("com.onyx")) to null,
            Triple("storage", app.booxultimatum.R.string.boox_fn_storage, android.content.Intent("com.onyx.action.STORAGE").setPackage("com.onyx")) to null,
            Triple("shop", app.booxultimatum.R.string.boox_fn_shop, android.content.Intent("com.onyx.action.SHOP").setPackage("com.onyx")) to null,
            Triple("settings", app.booxultimatum.R.string.boox_fn_settings, BooxIntents.settings()) to null,
        )
        return entries.mapNotNull { (e, iconPkg) ->
            val (id, label, intent) = e
            val ri = pm.resolveActivity(intent, 0) ?: return@mapNotNull null
            val key = "boox:$id"
            val icon = iconPkg?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() } ?: runCatching { ri.loadIcon(pm) }.getOrNull()
            icon?.let { virtualIcons[key] = it }
            LaunchTarget(key, ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), me, context.getString(label), true, intent)
        }
    }

    @Synchronized
    fun icon(key: String, sizePx: Int, style: IconStyle, shape: IconShape = IconShape.Original): Bitmap? {
        val cacheKey = "$key|$sizePx|${style.name}|${shape.name}"
        icons.get(cacheKey)?.let { return it }
        val info = infos[key]
        val raw = (if (info != null) runCatching { info.getBadgedIcon(context.resources.displayMetrics.densityDpi) }.getOrNull() else virtualIcons[key]) ?: return null
        val bmp = runCatching { if (shape == IconShape.Original) render(raw, sizePx, style) else renderShaped(raw, sizePx, style, shape) }.getOrNull() ?: return null
        icons.put(cacheKey, bmp)
        return bmp
    }

    private fun shapePath(shape: IconShape, size: Float): android.graphics.Path {
        val p = android.graphics.Path()
        val inset = size * 0.04f
        val r = android.graphics.RectF(inset, inset, size - inset, size - inset)
        when (shape) {
            IconShape.Circle -> p.addOval(r, android.graphics.Path.Direction.CW)
            IconShape.Rounded -> p.addRoundRect(r, size * 0.22f, size * 0.22f, android.graphics.Path.Direction.CW)
            IconShape.Square -> p.addRoundRect(r, size * 0.05f, size * 0.05f, android.graphics.Path.Direction.CW)
            IconShape.Squircle -> {
                // Superellipse |x|^4 + |y|^4 = 1, sampled finely enough to be smooth at icon size.
                val cx = size / 2f; val cy = size / 2f; val a = (size - 2 * inset) / 2f
                for (i in 0..96) {
                    val t = i / 96.0 * 2 * Math.PI
                    val c = Math.cos(t); val s = Math.sin(t)
                    val x = cx + a * Math.signum(c) * Math.pow(Math.abs(c), 0.5)
                    val y = cy + a * Math.signum(s) * Math.pow(Math.abs(s), 0.5)
                    if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
                }
                p.close()
            }
            IconShape.Original -> p.addRect(r, android.graphics.Path.Direction.CW)
        }
        return p
    }

    /**
     * The inner edge of a drawn frame, in px, when the icon has one: on all four sides, a dark opaque stroke that
     * starts near the edge, is thin, and has lighter pixels just inside it. Null for icons without a frame.
     */
    private fun frameInset(b: Bitmap): Int? {
        val n = b.width
        val mid = n / 2
        fun dark(x: Int, y: Int): Boolean {
            val p = b.getPixel(x, y)
            val lum = (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
            return android.graphics.Color.alpha(p) > 180 && lum < 90
        }
        val limitStart = (n * 0.10f).toInt()
        val limitEnd = (n * 0.18f).toInt()
        var inner = 0
        for (side in 0 until 4) {
            fun at(i: Int) = when (side) { 0 -> dark(i, mid); 1 -> dark(n - 1 - i, mid); 2 -> dark(mid, i); else -> dark(mid, n - 1 - i) }
            val start = (0 until limitStart).firstOrNull { at(it) } ?: return null
            var end = start
            while (end + 1 < limitEnd && at(end + 1)) end++
            // A frame is a stroke, not a filled glyph: something lighter must follow it.
            if (end + 1 >= limitEnd || (end + 1..minOf(end + 4, n - 1)).all { at(it) }) return null
            inner = maxOf(inner, end + 1)
        }
        return inner
    }

    /** Draws the icon inside a uniform shape, with the adaptive layers filling it when the app provides them. */
    private fun renderShaped(d: Drawable, size: Int, style: IconStyle, shape: IconShape): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = shapePath(shape, size.toFloat())
        c.save()
        c.clipPath(path)
        c.drawColor(android.graphics.Color.WHITE)
        val inset = (size * 0.25f).toInt()
        if (d is AdaptiveIconDrawable) {
            if (style == IconStyle.Monochrome && Build.VERSION.SDK_INT >= 33 && d.monochrome != null) {
                val mono = d.monochrome!!.constantState?.newDrawable()?.mutate() ?: d.monochrome!!
                mono.colorFilter = PorterDuffColorFilter(android.graphics.Color.BLACK, PorterDuff.Mode.SRC_IN)
                mono.setBounds(-inset, -inset, size + inset, size + inset); mono.draw(c)
            } else {
                d.background?.let { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(c) }
                d.foreground?.let { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(c) }
            }
        } else {
            val src = d.toBitmap(size, size, Bitmap.Config.ARGB_8888)
            // Many Boox icons draw their own rounded frame at the edge. Inside our shape that would be a second
            // outline, so a detected frame is cropped off and only the glyph within is placed.
            val frame = frameInset(src)
            val pad = (size * 0.16f).toInt()
            if (frame != null) {
                val crop = frame + (size * 0.02f).toInt()
                val dst = android.graphics.RectF(pad.toFloat(), pad.toFloat(), (size - pad).toFloat(), (size - pad).toFloat())
                // A frame's rounded corners sit further in than its sides; a generous corner clip removes what is left of them.
                val corner = dst.width() * 0.3f
                c.save()
                c.clipPath(android.graphics.Path().apply { addRoundRect(dst, corner, corner, android.graphics.Path.Direction.CW) })
                c.drawBitmap(src, android.graphics.Rect(crop, crop, size - crop, size - crop), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.restore()
            } else {
                c.drawBitmap(src, android.graphics.Rect(0, 0, size, size), android.graphics.Rect(pad, pad, size - pad, size - pad), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        }
        c.restore()
        // A hairline rim so white icons keep their shape on white paper.
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = size * 0.02f; color = android.graphics.Color.BLACK })
        if (style == IconStyle.Grayscale) {
            val gray = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(gray).drawBitmap(out, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
            return gray
        }
        return out
    }

    private fun render(d: Drawable, size: Int, style: IconStyle): Bitmap {
        if (style == IconStyle.Monochrome && Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable && d.monochrome != null) {
            // The app's own themed-icon layer drawn in ink on paper: the cleanest icon an e-ink panel can show.
            val mono = d.monochrome!!.constantState?.newDrawable()?.mutate() ?: d.monochrome!!
            mono.colorFilter = PorterDuffColorFilter(android.graphics.Color.BLACK, PorterDuff.Mode.SRC_IN)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            // Adaptive layers are 108 units with a 72-unit visible core; scale so the core fills the tile.
            val inset = (size * 0.25f).toInt()
            mono.setBounds(-inset, -inset, size + inset, size + inset)
            mono.draw(Canvas(out))
            return out
        }
        val bmp = d.toBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (style == IconStyle.Original) return bmp
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
        return out
    }

    fun launch(t: LaunchTarget): Boolean = runCatching {
        if (t.intent != null) {
            context.startActivity(android.content.Intent(t.intent).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } else if (t.frozen) {
            val launch = context.packageManager.getLaunchIntentForPackage(t.component.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return@runCatching false
            context.startActivity(launch)
            true
        } else {
            launcherApps.startMainActivity(t.component, t.user, null, null)
            true
        }
    }.getOrDefault(false)

    fun openAppInfo(t: LaunchTarget) {
        runCatching {
            if (t.intent != null || t.frozen || infos[t.key] == null) context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${t.component.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else launcherApps.startAppDetailsActivity(t.component, t.user, null, null)
        }
    }

    fun canShowShortcuts() = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    /** App shortcuts (the long-press actions apps publish). Android only hands them to the default home app. */
    fun shortcuts(t: LaunchTarget): List<ShortcutInfo> {
        if (t.intent != null || !canShowShortcuts()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(t.component.packageName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        return runCatching { launcherApps.getShortcuts(q, t.user).orEmpty() }.getOrDefault(emptyList())
            .filter { it.isEnabled }
            .sortedBy { it.rank }
            .take(6)
    }

    fun startShortcut(s: ShortcutInfo) {
        runCatching { launcherApps.startShortcut(s, null, null) }
    }

    fun register(callback: LauncherApps.Callback) = launcherApps.registerCallback(callback)
    fun unregister(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)

    /** Drop rendered icons when Android asks for memory back; they re-render for the visible page only. */
    fun trim() = icons.evictAll()

    fun isSystem(t: LaunchTarget): Boolean {
        if (t.intent != null) return true
        infos[t.key]?.applicationInfo?.let { return it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
        return runCatching { context.packageManager.getApplicationInfo(t.component.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(true)
    }

    /**
     * An icon's colour as a sort key: hue for colourful icons, then the near-greys by lightness at the end, so a
     * colour-sorted drawer reads as a spectrum followed by the black-and-white Boox icons.
     */
    @Synchronized
    fun colourKey(key: String): Float {
        hues[key]?.let { return it }
        val bmp = icon(key, 24, IconStyle.Original) ?: return 2f
        var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
        val hsv = FloatArray(3)
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
            val p = bmp.getPixel(x, y)
            if (android.graphics.Color.alpha(p) < 128) continue
            android.graphics.Color.colorToHSV(p, hsv)
            val w = 0.05 + hsv[1] * hsv[2]
            r += android.graphics.Color.red(p) * w; g += android.graphics.Color.green(p) * w; b += android.graphics.Color.blue(p) * w; weight += w
        }
        if (weight == 0.0) return 2f
        android.graphics.Color.RGBToHSV((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt(), hsv)
        val k = if (hsv[1] < 0.18f) 1f + (1f - hsv[2]) * 0.99f else hsv[0] / 360f
        hues[key] = k
        return k
    }

    private val hues = HashMap<String, Float>()

    companion object {
        /** Two apps are both called "Settings"; home names them apart. */
        private val RENAMES = mapOf(
            "com.android.settings/.Settings" to app.booxultimatum.R.string.label_android_settings,
            "com.onyx/.StartupActivity" to app.booxultimatum.R.string.label_boox_home,
        )
    }
}

/** Entry points into the Boox system app (com.onyx), verified on NA6C FW 4.3. */
object BooxIntents {
    /** Boox's own Settings, as the Boox home opens it. */
    fun settings(): android.content.Intent = android.content.Intent("com.onyx.action.SETTING")
        .setComponent(ComponentName("com.onyx", "com.onyx.tablet.settings.SettingsActivity"))

    /** Opens Boox Settings, falling back to Android's when the Boox app is missing. */
    fun openSettings(context: Context) {
        val tries = listOf(settings(), android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        for (i in tries) if (runCatching { context.startActivity(android.content.Intent(i).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
