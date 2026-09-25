package app.booxultimatum.core

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Where a drain measurement starts. Taken once; nothing runs in the background while it is open. */
data class MeasureStart(val epoch: Long, val elapsed: Long, val uptime: Long, val counterMah: Double?, val level: Int, val plugged: Boolean)

data class MeasureResult(
    val startEpoch: Long,
    val hours: Double,
    val usedMah: Double?,
    val mahPerHour: Double?,
    val pctPerHour: Double,
    val asleepFraction: Double,
    val startLevel: Int,
    val endLevel: Int,
    val chargedDuring: Boolean,
)

/**
 * Drain measurement from two readings of the charge counter. The phone is never woken to sample it:
 * the rate is the difference between "start" and "now", which is both cheaper and more honest.
 */
object Measurement {
    private const val PREFS = "measurement"
    private const val MIN_MINUTES = 10

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context, b: BatterySnapshot) {
        val s = JSONObject()
            .put("epoch", System.currentTimeMillis())
            .put("elapsed", SystemClock.elapsedRealtime())
            .put("uptime", SystemClock.uptimeMillis())
            .put("counter", b.chargeCounterMah ?: JSONObject.NULL)
            .put("level", b.levelPct)
            .put("plugged", b.source != PowerSource.None)
        prefs(context).edit().putString("open", s.toString()).apply()
    }

    fun open(context: Context): MeasureStart? = prefs(context).getString("open", null)?.let {
        runCatching {
            val o = JSONObject(it)
            MeasureStart(o.getLong("epoch"), o.getLong("elapsed"), o.getLong("uptime"), if (o.isNull("counter")) null else o.getDouble("counter"), o.getInt("level"), o.getBoolean("plugged"))
        }.getOrNull()
    }

    /** Null when the tablet restarted since the start (the clocks no longer line up). */
    fun evaluate(start: MeasureStart, now: BatterySnapshot): MeasureResult? {
        val elapsed = SystemClock.elapsedRealtime() - start.elapsed
        if (elapsed <= 0) return null
        val awake = SystemClock.uptimeMillis() - start.uptime
        val hours = elapsed / 3_600_000.0
        val used = if (start.counterMah != null && now.chargeCounterMah != null) start.counterMah - now.chargeCounterMah else null
        val full = now.estimatedFullMah
        val pct = if (used != null && full != null && full > 0) used / full * 100 else (start.level - now.levelPct).toDouble()
        return MeasureResult(
            startEpoch = start.epoch,
            hours = hours,
            usedMah = used,
            mahPerHour = used?.div(hours),
            pctPerHour = pct / hours,
            asleepFraction = (1.0 - awake.toDouble() / elapsed).coerceIn(0.0, 1.0),
            startLevel = start.level,
            endLevel = now.levelPct,
            chargedDuring = start.plugged || now.source != PowerSource.None || (used != null && used < 0),
        )
    }

    fun longEnough(r: MeasureResult) = r.hours * 60 >= MIN_MINUTES

    fun finish(context: Context, r: MeasureResult) {
        val arr = history(context).map { toJson(it) }.toMutableList()
        arr.add(0, toJson(r))
        val out = JSONArray()
        arr.take(12).forEach { out.put(it) }
        prefs(context).edit().putString("history", out.toString()).remove("open").apply()
    }

    fun discard(context: Context) {
        prefs(context).edit().remove("open").apply()
    }

    fun history(context: Context): List<MeasureResult> {
        val arr = runCatching { JSONArray(prefs(context).getString("history", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull() }
    }

    /** The most recent finished, unplugged measurement: the only kind worth quoting as "drain". */
    fun latestClean(context: Context) = history(context).firstOrNull { !it.chargedDuring }

    private fun toJson(r: MeasureResult) = JSONObject()
        .put("start", r.startEpoch).put("hours", r.hours).put("used", r.usedMah ?: JSONObject.NULL)
        .put("mahh", r.mahPerHour ?: JSONObject.NULL).put("pcth", r.pctPerHour).put("asleep", r.asleepFraction)
        .put("l0", r.startLevel).put("l1", r.endLevel).put("charged", r.chargedDuring)

    private fun fromJson(o: JSONObject) = MeasureResult(
        startEpoch = o.getLong("start"), hours = o.getDouble("hours"),
        usedMah = if (o.isNull("used")) null else o.getDouble("used"),
        mahPerHour = if (o.isNull("mahh")) null else o.getDouble("mahh"),
        pctPerHour = o.getDouble("pcth"), asleepFraction = o.getDouble("asleep"),
        startLevel = o.getInt("l0"), endLevel = o.getInt("l1"), chargedDuring = o.getBoolean("charged"),
    )
}
