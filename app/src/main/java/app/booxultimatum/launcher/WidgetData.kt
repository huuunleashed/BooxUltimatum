package app.booxultimatum.launcher

import android.content.Context
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

enum class WidgetKind { Clock, Calendar, Agenda, Weather, Battery, Alarm, Note, Boox, System }

/** One widget on the home shelf. [span] is 1 (half width) or 2 (full width). [extra] adds or removes height in [HEIGHT_STEP] steps. */
data class WidgetSpec(val id: String, val kind: WidgetKind, val span: Int, val appWidgetId: Int = -1, val extra: Int = 0) {
    companion object {
        val HEIGHT_STEP = 40.dp
        val EXTRA_RANGE = -3..10
    }
}

class WidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_widgets", Context.MODE_PRIVATE)
    private val boox = app.booxultimatum.core.Tablet.current(context).isBoox

    /** The Boox shortcuts widget has nothing to open on other tablets, so it is left out there. */
    fun list(): List<WidgetSpec> = read().filter { boox || it.kind != WidgetKind.Boox }

    private fun read(): List<WidgetSpec> {
        val default = defaults(boox)
        val raw = prefs.getString("widgets", null) ?: return default
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val kind = runCatching { WidgetKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                WidgetSpec(o.getString("id"), kind, o.optInt("span", 1).coerceIn(1, 2), o.optInt("appWidgetId", -1), o.optInt("extra", 0).coerceIn(WidgetSpec.EXTRA_RANGE))
            }
        }.getOrDefault(default)
    }

    fun save(list: List<WidgetSpec>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("span", it.span).put("appWidgetId", it.appWidgetId).put("extra", it.extra)) }
        prefs.edit().putString("widgets", a.toString()).apply()
    }

    var note: String
        get() = prefs.getString("note", "").orEmpty()
        set(v) = prefs.edit().putString("note", v).apply()

    companion object {
        fun newId() = UUID.randomUUID().toString()
        fun defaults(boox: Boolean) = listOf(
            WidgetSpec("clock", WidgetKind.Clock, 1),
            WidgetSpec("calendar", WidgetKind.Calendar, 1),
            if (boox) WidgetSpec("boox", WidgetKind.Boox, 2) else WidgetSpec("battery", WidgetKind.Battery, 2),
        )
    }
}

data class Place(val name: String, val region: String, val lat: Double, val lon: Double, val country: String = "")

data class DayForecast(val epochDay: Long, val code: Int, val max: Double, val min: Double)

data class Weather(
    val place: Place,
    val fetchedAt: Long,
    val temp: Double,
    val code: Int,
    val wind: Double,
    val fahrenheit: Boolean,
    val days: List<DayForecast>,
)

/**
 * Weather from Open-Meteo (free, no key, no account). Location is a city the user picks, never the GPS, and
 * requests happen only while the launcher is visible and the cached reading is over an hour old.
 */
class WeatherRepo(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_weather", Context.MODE_PRIVATE)

    /** Until units are picked in Edit, they follow the chosen city's country, then the tablet's region. */
    val fahrenheit: Boolean
        get() = if (prefs.contains("fahrenheit")) prefs.getBoolean("fahrenheit", false)
        else (place()?.country?.ifBlank { null } ?: Locale.getDefault().country).uppercase() in setOf("US", "LR", "MM")

    fun setFahrenheit(v: Boolean) = prefs.edit().putBoolean("fahrenheit", v).remove("cache").remove("cache_at").apply()

    fun place(): Place? = prefs.getString("place", null)?.let {
        runCatching { JSONObject(it).let { o -> Place(o.getString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon"), o.optString("country")) } }.getOrNull()
    }

    fun setPlace(p: Place) {
        prefs.edit().putString("place", JSONObject().put("name", p.name).put("region", p.region).put("lat", p.lat).put("lon", p.lon).put("country", p.country).toString())
            .remove("cache").remove("cache_at").apply()
    }

    fun cached(): Weather? {
        val p = place() ?: return null
        val raw = prefs.getString("cache", null) ?: return null
        return runCatching { parse(p, JSONObject(raw), prefs.getLong("cache_at", 0), fahrenheit) }.getOrNull()
    }

    fun stale(): Boolean = System.currentTimeMillis() - prefs.getLong("cache_at", 0) > 60 * 60 * 1000L

    /** Blocking; call off the main thread. */
    fun refresh(): Result<Weather> = runCatching {
        val p = place() ?: error("No place chosen")
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=temperature_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=4&timezone=auto$unit"
        val json = JSONObject(get(url))
        val now = System.currentTimeMillis()
        prefs.edit().putString("cache", json.toString()).putLong("cache_at", now).apply()
        parse(p, json, now, fahrenheit)
    }

    /** Blocking; call off the main thread. */
    fun search(name: String): Result<List<Place>> = runCatching {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val lang = Locale.getDefault().language
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=$lang&format=json"))
        val arr = json.optJSONArray("results") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(
                o.getString("name"),
                listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                o.getDouble("latitude"),
                o.getDouble("longitude"),
                o.optString("country_code"),
            )
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "BooxUltimatum")
        return try {
            check(c.responseCode == 200) { "Weather service answered ${c.responseCode}" }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(p: Place, j: JSONObject, at: Long, f: Boolean): Weather {
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val times = d.getJSONArray("time")
        val days = (0 until times.length()).map { i ->
            DayForecast(
                java.time.LocalDate.parse(times.getString(i)).toEpochDay(),
                d.getJSONArray("weather_code").getInt(i),
                d.getJSONArray("temperature_2m_max").getDouble(i),
                d.getJSONArray("temperature_2m_min").getDouble(i),
            )
        }
        return Weather(p, at, cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.optDouble("wind_speed_10m"), f, days)
    }
}

/** WMO weather code families, enough to pick words and a glyph. */
enum class Sky { Clear, PartCloud, Cloud, Fog, Drizzle, Rain, Snow, Storm }

fun skyOf(code: Int) = when (code) {
    0 -> Sky.Clear
    1, 2 -> Sky.PartCloud
    3 -> Sky.Cloud
    45, 48 -> Sky.Fog
    51, 53, 55, 56, 57 -> Sky.Drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.Rain
    71, 73, 75, 77, 85, 86 -> Sky.Snow
    95, 96, 99 -> Sky.Storm
    else -> Sky.Cloud
}
