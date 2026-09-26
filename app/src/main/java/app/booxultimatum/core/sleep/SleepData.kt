package app.booxultimatum.core.sleep

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import app.booxultimatum.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

data class SleepEvent(val title: String, val whenLabel: String, val begin: Long, val allDay: Boolean)

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
)

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
) {
    fun renderKey(): String = copy(now = 0L).toString()

    companion object {
        /** Blocking (calendar and battery reads); call off the main thread. */
        fun gather(context: Context, spec: SleepFaceSpec, exact: Boolean, now: Long = System.currentTimeMillis()): SleepData {
            val locale = Locale.getDefault()
            val zone = ZoneId.systemDefault()
            val step = spec.intervalMin.coerceAtLeast(1) * 60_000L
            val putDown = if (exact) now - now % 60_000L else now - now % step
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1).coerceIn(0, 100)
            val charging = runCatching { bm.isCharging }.getOrDefault(false)
            val time = DateFormat.getTimeFormat(context).format(Date(putDown))
            // "At" only when the time is exact to the minute; a time rounded down to the refresh step says "around".
            val exactWording = exact || spec.intervalMin == 1
            val calendarAllowed = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val wantsCalendar = spec.shows(SleepElement.Agenda) || (spec.mode == SleepMode.Image && spec.face == SleepFace.Almanac)
            val events = if (calendarAllowed && spec.shows(SleepElement.Agenda)) events(context, now, today, zone, locale) else emptyList()
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
                battery = level,
                charging = charging,
                batteryLine = context.getString(if (charging) R.string.sl_battery_charging else R.string.sl_battery_on, level),
                events = events,
                calendarAllowed = calendarAllowed,
                eventDays = days,
                quote = if (own) spec.ownQuote.trim() else bundled.text,
                quoteAuthor = if (own) spec.ownQuoteAuthor.trim() else bundled.author,
                firstDayOfWeek = first,
                weekdayInitials = (0 until 7).map { first.plus(it.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale) },
                labels = labels,
            )
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
                    out += SleepEvent(c.getString(0)?.trim().orEmpty().ifEmpty { "—" }, label, begin, allDay)
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
