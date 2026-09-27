package app.booxultimatum.core.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The sun and the moon, in display form. The moon needs no place. The sun's times need one, and the only place the
 * app knows is the city chosen for the home screen's weather: without it there is no sunrise or sunset to show, and
 * the faces draw the sun by the clock instead.
 */
data class SleepSky(
    /** Lit fraction of the moon's disc, 0 (new) to 1 (full). */
    val moonLit: Float,
    val waxing: Boolean,
    /** "Waxing gibbous", "Full moon", … */
    val moonPhase: String,
    /** "Full moon in 5 days", "New moon tomorrow"; null when it can't be said. */
    val moonNext: String?,
    /** The weather city, when the home screen has one. */
    val place: String?,
    val lat: Double?,
    val lon: Double?,
    /** Minutes after local midnight; null without a place, or on a day the sun doesn't rise or set there. */
    val riseMin: Int?,
    val setMin: Int?,
    /** 1 when the sun stays up all day, -1 when it stays down, 0 otherwise. */
    val polar: Int,
    val sunrise: String?,
    val sunset: String?,
    val dayLength: String?,
    /** The sun's height above the horizon at the face's moment, in degrees. */
    val sunAltitude: Double?,
)

/**
 * The home screen's cached weather, read from its preferences and never fetched from the sleep path. [asOf] always
 * says how old the reading is; [now] is left out once it's over three hours old, when only the day's forecast is kept.
 */
data class SleepWeather(val place: String, val now: String?, val sky: String, val range: String?, val asOf: String)

/** Low-precision astronomy, all local: good to about a minute for the sun and a percent for the moon's disc. */
internal object Astro {
    private fun julian(ms: Long) = ms / 86_400_000.0 + 2440587.5
    private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
    private fun sinD(d: Double) = sin(Math.toRadians(d))

    /** The moon's lit fraction and whether it's waxing, from Meeus' phase angle (Astronomical Algorithms, ch. 48). */
    fun moon(ms: Long): Pair<Double, Boolean> {
        val t = (julian(ms) - 2451545.0) / 36525.0
        val d = norm(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t)
        val m = norm(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t)
        val mp = norm(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t)
        val i = 180.0 - d - 6.289 * sinD(mp) + 2.100 * sinD(m) - 1.274 * sinD(2 * d - mp) - 0.658 * sinD(2 * d) - 0.214 * sinD(2 * mp) - 0.110 * sinD(d)
        return (1.0 + cos(Math.toRadians(i))) / 2.0 to (d < 180.0)
    }

    /** 0 new, 1 waxing crescent, 2 first quarter, 3 waxing gibbous, 4 full, 5 waning gibbous, 6 last quarter, 7 waning crescent. */
    fun phaseIndex(lit: Double, waxing: Boolean): Int = when {
        lit < 0.02 -> 0
        lit > 0.98 -> 4
        lit in 0.44..0.56 -> if (waxing) 2 else 6
        lit < 0.44 -> if (waxing) 1 else 7
        else -> if (waxing) 3 else 5
    }

    /** The next full or new moon, whichever comes first, found by stepping two hours at a time. */
    fun nextPrincipal(ms: Long): Pair<Long, Boolean>? {
        val step = 2 * 3_600_000L
        var prev = moon(ms).first
        var cur = moon(ms + step).first
        for (k in 2..400) {
            val next = moon(ms + k * step).first
            if (cur > prev && cur >= next) return ms + (k - 1) * step to true
            if (cur < prev && cur <= next) return ms + (k - 1) * step to false
            prev = cur
            cur = next
        }
        return null
    }

    /** Declination (radians) and the equation of time (minutes), NOAA's fractional-year series. */
    private fun solar(ms: Long): Pair<Double, Double> {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        val days = if (z.toLocalDate().isLeapYear) 366 else 365
        val g = 2 * PI / days * (z.dayOfYear - 1 + (z.hour + z.minute / 60.0 - 12) / 24.0)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        return decl to eq
    }

    /** The sun's altitude in degrees at [ms], seen from [lat], [lon]. */
    fun altitude(ms: Long, lat: Double, lon: Double): Double {
        val (decl, eq) = solar(ms)
        val utcMin = (((ms % 86_400_000L) + 86_400_000L) % 86_400_000L) / 60_000.0
        val ha = Math.toRadians((utcMin + eq + 4 * lon) / 4.0 - 180.0)
        val la = Math.toRadians(lat)
        val c = sin(la) * sin(decl) + cos(la) * cos(decl) * cos(ha)
        return 90.0 - Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /** Sunrise and sunset on [date] as epoch millis, or the polar state when there's none (1 up all day, -1 down). */
    fun sunDay(date: LocalDate, zone: ZoneId, lat: Double, lon: Double): Triple<Long?, Long?, Int> {
        val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val (decl, eq) = solar(noon)
        val la = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(la) * cos(decl)) - tan(la) * tan(decl)
        if (cosHa > 1) return Triple(null, null, -1)
        if (cosHa < -1) return Triple(null, null, 1)
        val ha = Math.toDegrees(acos(cosHa))
        // Minutes from the UTC midnight of the day that holds local noon; negative or past 1440 is fine.
        val utcMidnight = Instant.ofEpochMilli(noon).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rise = utcMidnight + ((720 - 4 * (lon + ha) - eq) * 60_000).toLong()
        val set = utcMidnight + ((720 - 4 * (lon - ha) - eq) * 60_000).toLong()
        return Triple(rise, set, 0)
    }
}
