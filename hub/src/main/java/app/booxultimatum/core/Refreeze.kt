package app.booxultimatum.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.booxultimatum.core.battery.ForegroundReader
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.kit.core.AppWork
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Freezes again the apps the hub unfroze to open them. Boox's own auto-freeze doesn't do it: it freezes an app that has
 * gone unused for the number of days in `auto_freeze_delay`, and Boox's home leaves an app it opened enabled too
 * (read from the decompiled `CheckAndFreezeUncommonlyUsedAppsRequest` and `UnFreezeApplicationRequest`, 2026-10-12, when
 * an app opened from here stayed enabled). So the hub keeps a list of what it unfroze, on disk because an install or a
 * crash ends the process, and freezes those apps again about a minute after the owner is back on the home screen, unless
 * the app is in front again, and when the tablet locks. It never touches an app that isn't on the list, and it needs
 * Shizuku: without it the list waits for the next trigger.
 */
object Refreeze {
    private const val PREFS = "refreeze"
    private const val PENDING = "pending"
    private const val HOME_GRACE_MS = 60_000L
    private const val SCREEN_GRACE_MS = 5_000L

    /** A power lock for the screen grace: the tablet suspends within seconds of locking, and a timer doesn't run while it does. */
    private const val HOLD_MS = 20_000L

    /** An app opened this recently may still be starting, so a check that finds it not in front yet leaves it alone. */
    private const val STARTING_MS = 30_000L
    private const val FRONT_LOOKBACK_MS = 15 * 60_000L
    private const val FRONT_FRESH_MS = 2_000L

    enum class Trigger { Home, ScreenOff, Start }

    /** An app the hub unfroze: [atMs] is when. */
    data class Entry(val pkg: String, val user: Int, val atMs: Long)

    private val log = Logbook.logger("launcher")
    private val main = Handler(Looper.getMainLooper())
    private val scheduled = HashMap<Trigger, Runnable>()
    private val mutex = Mutex()
    private var watching = false

    internal fun encode(e: Entry): String = "${e.user}|${e.pkg}|${e.atMs}"

    internal fun decode(s: String): Entry? {
        val p = s.split('|')
        if (p.size != 3) return null
        val user = p[0].toIntOrNull() ?: return null
        val at = p[2].toLongOrNull() ?: return null
        return Entry(p[1], user, at).takeIf { it.pkg.isNotBlank() }
    }

    /**
     * Which of [entries] to freeze now. A lock, or a start of the process while the tablet is locked, freezes all of them.
     * Coming home freezes those not in front, but only when the app in front is known (without usage access it isn't, and
     * guessing could freeze an app in use) and not those opened less than [STARTING_MS] ago.
     */
    internal fun due(entries: List<Entry>, trigger: Trigger, front: String?, frontKnown: Boolean, interactive: Boolean, nowMs: Long): List<Entry> =
        when (trigger) {
            Trigger.ScreenOff, Trigger.Start -> if (interactive) emptyList() else entries
            Trigger.Home -> if (!frontKnown) emptyList() else entries.filter { it.pkg != front && nowMs - it.atMs >= STARTING_MS }
        }

    /** Disabled by the owner or by Boox's freeze; an app with no state of its own set counts as enabled, one that is gone as frozen. */
    fun isFrozen(context: Context, pkg: String): Boolean = runCatching {
        when (context.packageManager.getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false
        }
    }.getOrDefault(true)

    /** Registers the lock receiver, once per process. Main thread. */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { runCatching { onScreenOff(app) } }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onStart(app)
    }

    /** The hub unfroze [pkg] for [user] to open it: it is to be frozen again. */
    fun opened(context: Context, pkg: String, user: Int) {
        val app = context.applicationContext
        synchronized(this) {
            write(app, read(app).filter { it.pkg != pkg || it.user != user } + Entry(pkg, user, System.currentTimeMillis()))
        }
        log.i("will freeze again", "pkg" to pkg, "user" to user)
    }

    /** The home screen came to the front. Main thread. */
    fun onHome(context: Context) {
        val app = context.applicationContext
        if (read(app).isEmpty()) return
        schedule(app, Trigger.Home, HOME_GRACE_MS)
    }

    private fun onScreenOff(app: Context) {
        if (read(app).isEmpty()) return
        // A short hold, so the grace passes even if the tablet suspends first; the app also gets a moment to save its state.
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BooxUltimatum:refreeze")
            .apply { setReferenceCounted(false); acquire(HOLD_MS) }
        schedule(app, Trigger.ScreenOff, SCREEN_GRACE_MS)
    }

    /** The process started with apps still on the list (an install ends it): if the tablet is locked, they are frozen now. */
    private fun onStart(app: Context) {
        if (read(app).isEmpty()) return
        run(app, Trigger.Start)
    }

    private fun schedule(app: Context, trigger: Trigger, delayMs: Long) {
        scheduled.remove(trigger)?.let { main.removeCallbacks(it) }
        val task = Runnable {
            scheduled.remove(trigger)
            run(app, trigger)
        }
        scheduled[trigger] = task
        main.postDelayed(task, delayMs)
    }

    private fun run(app: Context, trigger: Trigger) {
        AppWork.scope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching { freezeDue(app, trigger) }.onFailure { log.w("freezing again failed", error = it) }
            }
        }
    }

    private suspend fun freezeDue(app: Context, trigger: Trigger) {
        val all = read(app)
        if (all.isEmpty()) return
        // Someone froze it already, or it was uninstalled: nothing is left to do for it.
        val stale = all.filter { isFrozen(app, it.pkg) }
        if (stale.isNotEmpty()) remove(app, stale)
        val live = all - stale.toSet()
        val (front, known) = if (trigger == Trigger.Home) frontPackage(app) else null to false
        val interactive = app.getSystemService(PowerManager::class.java).isInteractive
        val toFreeze = due(live, trigger, front, known, interactive, System.currentTimeMillis())
        if (toFreeze.isEmpty()) return
        if (!Privileged.ready()) {
            log.i("freezing again waits for Shizuku", "apps" to toFreeze.size, "trigger" to trigger.name)
            return
        }
        for (e in toFreeze) {
            SystemState.checkPkg(e.pkg)
            val result = runCatching { Privileged.sh("pm disable-user --user ${e.user} ${e.pkg}") }.getOrNull()
            val ok = result?.ok == true
            log.i("froze again", "pkg" to e.pkg, "trigger" to trigger.name, "ok" to ok, "after s" to (System.currentTimeMillis() - e.atMs) / 1000)
            if (!ok) log.w("freeze again refused", "pkg" to e.pkg, "why" to (result?.message ?: "no shell"))
            if (ok) remove(app, listOf(e))
        }
    }

    /** Takes exactly these entries off the list: an app opened again meanwhile has a newer one, which stays. */
    private fun remove(app: Context, gone: List<Entry>) {
        synchronized(this) { write(app, read(app).filter { it !in gone }) }
    }

    /** The package in front now, and whether that is known at all (it isn't without usage access, or with no events). */
    private fun frontPackage(app: Context): Pair<String?, Boolean> {
        val now = System.currentTimeMillis()
        val last = ForegroundReader.read(app, now - FRONT_LOOKBACK_MS, now).lastOrNull() ?: return null to false
        return (if (now - last.endMs <= FRONT_FRESH_MS) last.pkg else null) to true
    }

    private fun read(app: Context): List<Entry> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(PENDING, emptySet()).orEmpty().mapNotNull(::decode)

    private fun write(app: Context, entries: List<Entry>) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(PENDING, entries.map(::encode).toSet()) }
    }
}
