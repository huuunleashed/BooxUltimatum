package app.booxultimatum.core.sleep

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import app.booxultimatum.R
import app.booxultimatum.launcher.Sky
import app.booxultimatum.launcher.WeatherRepo
import app.booxultimatum.launcher.skyOf
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** [time] is the start time alone ("9:30"), empty for all-day events; [end] is when it finishes. */
data class SleepEvent(val title: String, val whenLabel: String, val begin: Long, val allDay: Boolean, val end: Long = begin, val time: String = "")

/**
 * What changes while the tablet sleeps, in display form. [live] is true when the face is drawn by an update while
 * asleep; otherwise the time is the moment the tablet was put down and the sleep has only just begun.
 */
data class SleepLive(
    val live: Boolean,
    /** The time the face shows, to the minute, and its parts for a dial. */
    val time: String,
    val amPm: String,
    val hour: Int,
    val minute: Int,
    /** "1 h 25 min", or null in the first minute of sleep. */
    val asleepFor: String?,
    val sleptAt: String,
    /** Battery used since the tablet went to sleep, with its rate once there is enough to say, e.g. "2 % · 1.4 % an hour". */
    val used: String?,
    /** The next timed event as a countdown, e.g. "Standup in 40 min". */
    val countdown: String?,
    val nextAlarm: String?,
    /** When this face was drawn, e.g. "Updated 10:25 · every 5 min". */
    val updated: String,
    /** The minute the face shows, as epoch millis, and when the sleep began (the put-down moment without live updates). */
    val nowMs: Long = 0L,
    val sleptAtMs: Long = 0L,
    val asleepMin: Int = 0,
    /** The next alarm within a day, as epoch millis. */
    val alarmAt: Long? = null,
    /** [used] without the word "used", for a line already headed "Used asleep": "2 % · 1.4 % an hour". */
    val usedShort: String? = null,
) {
    companion object {
        val NONE = SleepLive(false, "", "", 0, 0, null, "", null, null, null, "")
    }
}

/**
 * Words and templates the newer faces print, resolved up front like [SleepLabels]. Templates take `%1$s`/`%1$d`
 * arguments and are filled in by the renderer, which has no Context.
 */
data class SleepWords(
    val am: String,
    val pm: String,
    val week: String,
    val dayOfYear: String,
    val daysLeft: String,
    val durMin: String,
    val durH: String,
    val durHMin: String,
    val sunrise: String,
    val sunset: String,
    val daylight: String,
    val moon: String,
    val lit: String,
    val weather: String,
    val alarmNone: String,
    val today: String,
    val date: String,
    val day: String,
    val since: String,
    val polarDay: String,
    val polarNight: String,
    val masthead: String,
    val issue: String,
    val edition: String,
    val sleepReport: String,
    val ahead: String,
    val almanac: String,
    val thought: String,
    val nothingToday: String,
    val leftToday: String,
    val sunByClock: String,
) {
    /** "45 min", "1 h 5 min", "3 h", from the same templates as the rest of the app. */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> String.format(durMin, m)
            m == 0 -> String.format(durH, h)
            else -> String.format(durHMin, h, m)
        }
    }
}

/** Words the faces print, resolved from resources up front so the renderer needs no Context. */
data class SleepLabels(
    val battery: String,
    val putDown: String,
    val next: String,
    val charging: String,
    val onBattery: String,
    val returnHeading: String,
    val reward: String,
    val contact: String,
    val nothingPlanned: String,
    val noteEmpty: String,
    val photoEmpty: String,
    val ownerEmpty: String,
    val asleep: String,
    val asleepSinceFormat: String,
    val justPutDown: String,
    val usedAsleep: String,
    val alarm: String,
    val nothingUsed: String,
    val now: String,
) {
    fun asleepSince(time: String) = String.format(asleepSinceFormat, time)
}

/**
 * The facts a face shows, gathered once per render. Everything here is already in display form; [renderKey] leaves
 * out the exact clock so a render within the same rounding window is recognised as unchanged and skipped.
 */
data class SleepData(
    val now: Long,
    val putDown: Long,
    val exact: Boolean,
    val today: LocalDate,
    val weekday: String,
    val month: String,
    val year: String,
    val dayNumber: String,
    val dateShort: String,
    val dateLong: String,
    val dateYear: String,
    val putDownTime: String,
    val putDownLead: String,
    val putDownLine: String,
    val battery: Int,
    /** False when the firmware doesn't report the charge level, so faces show no figure rather than a wrong one. */
    val batteryKnown: Boolean = true,
    /** The level on its own, or a dash when it isn't reported. */
    val batteryText: String = battery.toString(),
    val charging: Boolean,
    val batteryLine: String,
    val events: List<SleepEvent>,
    val calendarAllowed: Boolean,
    val eventDays: Set<Int>,
    val quote: String,
    val quoteAuthor: String,
    val firstDayOfWeek: DayOfWeek,
    val weekdayInitials: List<String>,
    val labels: SleepLabels,
    val live: SleepLive,
    /** Local midnight today, as epoch millis. */
    val dayStart: Long = 0L,
    val weekdayShort: String = "",
    val monthShort: String = "",
    /** The week of the year by the locale's own counting, the day of the year and the year's length. */
    val week: Int = 0,
    val dayOfYear: Int = 0,
    val daysInYear: Int = 365,
    /** Today's events, past ones too, for the faces that draw the whole day ([SleepFace.drawsDay]). */
    val day: List<SleepEvent> = emptyList(),
    val weather: SleepWeather? = null,
    val sky: SleepSky? = null,
    val words: SleepWords,
) {
    /** Leaves out the exact clock, unless the face shows the time itself, in which case every minute is a change. */
    fun renderKey(): String = copy(now = 0L).toString()

    companion object {
        /**
         * Blocking (calendar and battery reads); call off the main thread. [putDownAt] pins the put-down moment, for
         * live updates while asleep: the date, battery and agenda move on, the time the tablet went down doesn't.
         */
        fun gather(
            context: Context,
            spec: SleepFaceSpec,
            exact: Boolean,
            now: Long = System.currentTimeMillis(),
            putDownAt: Long? = null,
            levelAtSleep: Int? = null,
            stepMin: Int? = null,
        ): SleepData {
            val locale = Locale.getDefault()
            val zone = ZoneId.systemDefault()
            val step = spec.intervalMin.coerceAtLeast(1) * 60_000L
            val moment = putDownAt ?: now
            val putDown = if (exact) moment - moment % 60_000L else moment - moment % step
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val bm = context.getSystemService(BatteryManager::class.java)
            // An unsupported property answers Integer.MIN_VALUE, which must not become "0 %" on a face: the battery
            // parts are left out instead (the same as no reading).
            val level = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
                .getOrDefault(-1)
                .takeIf { it in 0..100 }
                ?: -1
            val charging = runCatching { bm.isCharging }.getOrDefault(false)
            val time = DateFormat.getTimeFormat(context).format(Date(putDown))
            // "At" only when the time is exact to the minute; a time rounded down to the refresh step says "around".
            val exactWording = exact || spec.intervalMin == 1
            val calendarAllowed = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val liveFace = spec.mode == SleepMode.Image && spec.face.live
            val face = if (spec.mode == SleepMode.Image) spec.face else null
            val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            // The moment the face stands for: the minute it's drawn while asleep, or the put-down moment.
            val faceAt = if (putDownAt != null) now - now % 60_000L else putDown
            val wantsCalendar = spec.shows(SleepElement.Agenda) || (spec.mode == SleepMode.Image && spec.face == SleepFace.Almanac)
            val events = if (calendarAllowed && (spec.shows(SleepElement.Agenda) || liveFace)) events(context, now, today, zone, locale) else emptyList()
            val days = if (calendarAllowed && wantsCalendar && spec.face == SleepFace.Almanac) eventDays(context, today, zone) else emptySet()
            val bundled = SleepQuotes.forDay(today.toEpochDay())
            val own = spec.useOwnQuote && spec.ownQuote.isNotBlank()
            val first = WeekFields.of(locale).firstDayOfWeek
            val labels = SleepLabels(
                battery = context.getString(R.string.sl_battery),
                putDown = context.getString(R.string.sl_put_down),
                next = context.getString(R.string.sl_next),
                charging = context.getString(R.string.sl_charging),
                onBattery = context.getString(R.string.sl_on_battery),
                returnHeading = context.getString(R.string.sl_return_heading),
                reward = context.getString(R.string.sl_reward),
                contact = context.getString(R.string.sl_contact),
                nothingPlanned = context.getString(R.string.sl_nothing_planned),
                noteEmpty = context.getString(R.string.sl_note_empty),
                photoEmpty = context.getString(R.string.sl_photo_empty),
                ownerEmpty = context.getString(R.string.sl_owner_empty),
                asleep = context.getString(R.string.sl_lab_asleep),
                asleepSinceFormat = context.getString(R.string.sl_lab_asleep_since),
                justPutDown = context.getString(R.string.sl_lab_just_now),
                usedAsleep = context.getString(R.string.sl_lab_used),
                alarm = context.getString(R.string.sl_lab_alarm),
                nothingUsed = context.getString(R.string.sl_lab_nothing_used),
                now = context.getString(R.string.sl_lab_now),
            )
            return SleepData(
                now = now,
                putDown = putDown,
                exact = exact,
                today = today,
                weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) },
                month = today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) },
                year = today.year.toString(),
                dayNumber = today.dayOfMonth.toString(),
                dateShort = format(locale, "dMMMM", now),
                dateLong = format(locale, "EEEEdMMMMyyyy", now),
                dateYear = format(locale, "dMMMMyyyy", now),
                putDownTime = time,
                putDownLead = context.getString(if (exactWording) R.string.sl_put_down_lead_at else R.string.sl_put_down_lead_around),
                putDownLine = context.getString(if (exactWording) R.string.sl_put_down_at else R.string.sl_put_down_around, time),
                battery = level.coerceIn(0, 100),
                batteryKnown = level in 0..100,
                batteryText = if (level in 0..100) level.toString() else "—",
                charging = charging,
                batteryLine = if (level in 0..100) {
                    context.getString(if (charging) R.string.sl_battery_charging else R.string.sl_battery_on, level)
                } else {
                    context.getString(R.string.sl_battery_unknown)
                },
                events = events,
                calendarAllowed = calendarAllowed,
                eventDays = days,
                quote = if (own) spec.ownQuote.trim() else bundled.text,
                quoteAuthor = if (own) spec.ownQuoteAuthor.trim() else bundled.author,
                firstDayOfWeek = first,
                weekdayInitials = (0 until 7).map { first.plus(it.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale) },
                labels = labels,
                // Only a face that shows live readings carries them, so the others still skip unchanged renders.
                live = if (liveFace || putDownAt != null) live(context, now, putDown, putDownAt, level, levelAtSleep, charging, events, stepMin) else SleepLive.NONE,
                dayStart = dayStart,
                weekdayShort = today.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) },
                monthShort = today.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) },
                week = today.get(WeekFields.of(locale).weekOfWeekBasedYear()),
                dayOfYear = today.dayOfYear,
                daysInYear = today.lengthOfYear(),
                day = if (calendarAllowed && face?.drawsDay == true) dayEvents(context, dayStart, today, zone) else emptyList(),
                weather = if (face?.readsSky == true) weather(context, now, today, zone, locale) else null,
                sky = if (face?.readsSky == true) sky(context, faceAt, today, zone, dayStart) else null,
                words = words(context, today),
            )
        }

        private val MOON = listOf(
            R.string.sl_moon_new, R.string.sl_moon_wax_crescent, R.string.sl_moon_first, R.string.sl_moon_wax_gibbous,
            R.string.sl_moon_full, R.string.sl_moon_wane_gibbous, R.string.sl_moon_last, R.string.sl_moon_wane_crescent,
        )

        private fun words(context: Context, today: LocalDate): SleepWords {
            val ampm = java.text.DateFormatSymbols.getInstance(Locale.getDefault()).amPmStrings
            val left = today.lengthOfYear() - today.dayOfYear
            return SleepWords(
                am = ampm.getOrElse(0) { "AM" },
                pm = ampm.getOrElse(1) { "PM" },
                week = context.getString(R.string.sw_week),
                dayOfYear = context.getString(R.string.sw_day_of_year),
                daysLeft = context.resources.getQuantityString(R.plurals.sw_days_left, left, left),
                durMin = context.getString(R.string.sl_dur_min),
                durH = context.getString(R.string.sl_dur_h),
                durHMin = context.getString(R.string.sl_dur_h_min),
                sunrise = context.getString(R.string.sw_sunrise),
                sunset = context.getString(R.string.sw_sunset),
                daylight = context.getString(R.string.sw_daylight),
                moon = context.getString(R.string.sw_moon),
                lit = context.getString(R.string.sw_lit),
                weather = context.getString(R.string.sw_weather),
                alarmNone = context.getString(R.string.sw_alarm_none),
                today = context.getString(R.string.sl_today),
                date = context.getString(R.string.sw_date),
                day = context.getString(R.string.sw_day),
                since = context.getString(R.string.sw_since),
                polarDay = context.getString(R.string.sw_polar_day),
                polarNight = context.getString(R.string.sw_polar_night),
                masthead = context.getString(R.string.sw_masthead),
                issue = context.getString(R.string.sw_issue),
                edition = context.getString(R.string.sw_edition),
                sleepReport = context.getString(R.string.sw_sleep_report),
                ahead = context.getString(R.string.sw_ahead),
                almanac = context.getString(R.string.sw_almanac),
                thought = context.getString(R.string.sw_thought),
                nothingToday = context.getString(R.string.sw_nothing_today),
                leftToday = context.getString(R.string.sw_left_today),
                sunByClock = context.getString(R.string.sw_sun_clock),
            )
        }

        /**
         * The moon always; the sun only for the home screen's weather city, the one place the app knows. [at] is the
         * moment the face shows, so a face drawn at put-down doesn't claim a later sky.
         */
        private fun sky(context: Context, at: Long, today: LocalDate, zone: ZoneId, dayStart: Long): SleepSky {
            val (lit, waxing) = Astro.moon(at)
            val next = Astro.nextPrincipal(at)?.let { (t, full) ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(t).atZone(zone).toLocalDate()).toInt()
                when {
                    days <= 0 -> context.getString(if (full) R.string.sw_full_today else R.string.sw_new_today)
                    days == 1 -> context.getString(if (full) R.string.sw_full_tomorrow else R.string.sw_new_tomorrow)
                    else -> context.resources.getQuantityString(if (full) R.plurals.sw_full_in else R.plurals.sw_new_in, days, days)
                }
            }
            val place = runCatching { WeatherRepo(context).place() }.getOrNull()
            val tf = DateFormat.getTimeFormat(context)
            var rise: Long? = null
            var set: Long? = null
            var polar = 0
            if (place != null) {
                val d = Astro.sunDay(today, zone, place.lat, place.lon)
                rise = d.first
                set = d.second
                polar = d.third
            }
            return SleepSky(
                moonLit = lit.toFloat(),
                waxing = waxing,
                moonPhase = context.getString(MOON[Astro.phaseIndex(lit, waxing)]),
                moonNext = next,
                place = place?.name,
                lat = place?.lat,
                lon = place?.lon,
                riseMin = rise?.let { ((it - dayStart) / 60_000L).toInt() },
                setMin = set?.let { ((it - dayStart) / 60_000L).toInt() },
                polar = polar,
                sunrise = rise?.let { tf.format(Date(it)) },
                sunset = set?.let { tf.format(Date(it)) },
                dayLength = if (rise != null && set != null) duration(context, ((set - rise) / 60_000L).toInt()) else null,
                sunAltitude = place?.let { Astro.altitude(at, it.lat, it.lon) },
            )
        }

        /**
         * The home screen's cached weather, never fetched here. The current reading is kept for three hours, today's
         * forecast for as long as the cache holds today (it covers four days); older than that, nothing is shown.
         */
        private fun weather(context: Context, now: Long, today: LocalDate, zone: ZoneId, locale: Locale): SleepWeather? = runCatching {
            val w = WeatherRepo(context).cached() ?: return null
            val age = now - w.fetchedAt
            if (age < -3_600_000L || age > 3 * 24 * 3_600_000L) return null
            val fresh = age <= 3 * 3_600_000L
            val forecast = w.days.firstOrNull { it.epochDay == today.toEpochDay() }
            if (!fresh && forecast == null) return null
            val code = if (fresh) w.code else forecast!!.code
            val fetched = Instant.ofEpochMilli(w.fetchedAt).atZone(zone).toLocalDate()
            val at = DateFormat.getTimeFormat(context).format(Date(w.fetchedAt))
            val stamp = if (fetched == today) at else "${fetched.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} $at"
            SleepWeather(
                place = w.place.name,
                now = if (fresh) "${w.temp.roundToInt()}°" else null,
                sky = context.getString(skyWord(skyOf(code))),
                range = forecast?.let { "${it.max.roundToInt()}° / ${it.min.roundToInt()}°" },
                asOf = context.getString(R.string.sw_as_of, stamp),
            )
        }.getOrNull()

        private fun skyWord(s: Sky) = when (s) {
            Sky.Clear -> R.string.sky_clear
            Sky.PartCloud -> R.string.sky_part
            Sky.Cloud -> R.string.sky_cloud
            Sky.Fog -> R.string.sky_fog
            Sky.Drizzle -> R.string.sky_drizzle
            Sky.Rain -> R.string.sky_rain
            Sky.Snow -> R.string.sky_snow
            Sky.Storm -> R.string.sky_storm
        }

        /** Today's events from midnight to midnight, the ones already over included, for faces that draw the whole day. */
        private fun dayEvents(context: Context, dayStart: Long, today: LocalDate, zone: ZoneId): List<SleepEvent> = runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, dayStart)
                ContentUris.appendId(it, dayStart + 24 * 3_600_000L)
            }.build()
            val tf = DateFormat.getTimeFormat(context)
            val out = mutableListOf<SleepEvent>()
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.END),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 16) {
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    // All-day instances sit at UTC midnight, so one from yesterday can overlap this morning; keep today's.
                    if (allDay && Instant.ofEpochMilli(begin).atZone(ZoneId.of("UTC")).toLocalDate() != today) continue
                    val time = if (allDay) "" else tf.format(Date(begin))
                    out += SleepEvent(c.getString(0)?.trim().orEmpty().ifEmpty { "—" }, time.ifEmpty { context.getString(R.string.sl_today) }, begin, allDay, c.getLong(3), time)
                }
            }
            out
        }.getOrDefault(emptyList())

        /**
         * The live readings. The time is the minute the face is drawn; without live updates ([putDownAt] null) it's the
         * moment the tablet goes down, which is what a clock face then honestly shows.
         */
        private fun live(
            context: Context,
            now: Long,
            putDown: Long,
            putDownAt: Long?,
            level: Int,
            levelAtSleep: Int?,
            charging: Boolean,
            events: List<SleepEvent>,
            stepMin: Int?,
        ): SleepLive {
            val live = putDownAt != null
            // Without live updates the face stands for the moment it was put down, rounded like the put-down line.
            val minuteNow = if (live) now - now % 60_000L else putDown
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = minuteNow }
            val h24 = DateFormat.is24HourFormat(context)
            val time = java.text.SimpleDateFormat(if (h24) "H:mm" else "h:mm", Locale.getDefault()).format(Date(minuteNow))
            val amPm = if (h24) "" else java.text.SimpleDateFormat("a", Locale.getDefault()).format(Date(minuteNow))
            val sleptAt = putDownAt ?: now
            val asleepMin = ((now - sleptAt) / 60_000L).toInt()
            val used = if (charging) context.getString(R.string.sl_live_used_charging) else if (live && levelAtSleep != null) {
                val d = (levelAtSleep - level).coerceAtLeast(0)
                val hours = (now - sleptAt) / 3_600_000.0
                // A rate from under an hour or from one percent is noise; it's given once both are past.
                if (hours >= 1.0 && d >= 2) context.getString(R.string.sl_live_used_rate, d, "%.1f".format(Locale.getDefault(), d / hours))
                else context.getString(R.string.sl_live_used, d)
            } else null
            val usedShort = if (!charging && live && levelAtSleep != null) {
                val d = (levelAtSleep - level).coerceAtLeast(0)
                val hours = (now - sleptAt) / 3_600_000.0
                if (hours >= 1.0 && d >= 2) context.getString(R.string.sl_live_used_short_rate, d, "%.1f".format(Locale.getDefault(), d / hours))
                else context.getString(R.string.sl_live_used_short, d)
            } else null
            val next = events.firstOrNull { !it.allDay && it.begin > now }
            val countdown = next?.let {
                val mins = ((it.begin - now) / 60_000L).toInt()
                if (mins > 12 * 60) null else context.getString(R.string.sl_live_countdown, it.title.trim(), duration(context, mins))
            }
            val alarmAt = runCatching { context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime }.getOrNull()
                ?.takeIf { it - now in 0..(24 * 3_600_000L) }
            val alarm = alarmAt?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
            val step = stepMin ?: LivePrefs.load(context).stepMin
            val clock = DateFormat.getTimeFormat(context).format(Date(minuteNow))
            return SleepLive(
                live = live,
                time = time,
                amPm = amPm,
                hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                minute = cal.get(java.util.Calendar.MINUTE),
                asleepFor = if (asleepMin >= 1) duration(context, asleepMin) else null,
                sleptAt = DateFormat.getTimeFormat(context).format(Date(sleptAt)),
                used = used,
                countdown = countdown,
                nextAlarm = alarm,
                updated = if (live) context.getString(R.string.sl_live_updated, clock, step) else context.getString(R.string.sl_live_put_down, clock),
                nowMs = minuteNow,
                sleptAtMs = sleptAt,
                asleepMin = asleepMin.coerceAtLeast(0),
                alarmAt = alarmAt,
                usedShort = usedShort,
            )
        }

        /** "45 min", "1 h 5 min", "3 h". */
        fun duration(context: Context, minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h == 0 -> context.getString(R.string.sl_dur_min, m)
                m == 0 -> context.getString(R.string.sl_dur_h, h)
                else -> context.getString(R.string.sl_dur_h_min, h, m)
            }
        }

        private fun format(locale: Locale, skeleton: String, at: Long): String =
            java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(at))

        private fun events(context: Context, now: Long, today: LocalDate, zone: ZoneId, locale: Locale): List<SleepEvent> = runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, now)
                ContentUris.appendId(it, now + 7L * 24 * 3600 * 1000)
            }.build()
            val out = mutableListOf<SleepEvent>()
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.END),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 5) {
                    if (c.getLong(3) <= now) continue
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    // All-day instances are stored at UTC midnight; read their date in UTC so they don't slip a day.
                    val d = if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneId.of("UTC")).toLocalDate() else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
                    val day = when (d) {
                        today -> context.getString(R.string.sl_today)
                        today.plusDays(1) -> context.getString(R.string.sl_tomorrow)
                        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                    }
                    val label = if (allDay || d.isBefore(today)) day else "$day ${DateFormat.getTimeFormat(context).format(Date(begin))}"
                    out += SleepEvent(
                        c.getString(0)?.trim().orEmpty().ifEmpty { "—" }, label, begin, allDay, c.getLong(3),
                        if (allDay) "" else DateFormat.getTimeFormat(context).format(Date(begin)),
                    )
                }
            }
            out
        }.getOrDefault(emptyList())

        private fun eventDays(context: Context, today: LocalDate, zone: ZoneId): Set<Int> = runCatching {
            val first = today.withDayOfMonth(1)
            val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
            val out = mutableSetOf<Int>()
            context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val d = Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate()
                    if (d.month == today.month) out += d.dayOfMonth
                }
            }
            out
        }.getOrDefault(emptySet())
    }
}
