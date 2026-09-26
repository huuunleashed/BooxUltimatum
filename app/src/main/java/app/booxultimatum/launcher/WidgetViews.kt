package app.booxultimatum.launcher

import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.booxultimatum.R
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Lamp
import app.booxultimatum.ui.SearchField
import app.booxultimatum.ui.TuningScale
import app.booxultimatum.ui.screens.chargeLine
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun widgetHeight(kind: WidgetKind): Dp = when (kind) {
    WidgetKind.Clock -> 236.dp
    WidgetKind.Calendar -> 300.dp
    WidgetKind.Agenda -> 300.dp
    WidgetKind.Weather -> 236.dp
    WidgetKind.Battery -> 236.dp
    WidgetKind.Alarm -> 140.dp
    WidgetKind.Note -> 236.dp
    WidgetKind.Boox -> 96.dp
    WidgetKind.System -> 236.dp
}

/**
 * Boox functions that live inside the Boox home app rather than the app drawer. Entry points verified on
 * NA6C FW 4.3: public actions for Library and Storage, and the Boox home itself (which holds Notes), opened as a
 * normal screen so the default home does not change.
 */
@Composable
fun BooxWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entries = remember {
        listOf(
            R.string.w_boox_library to Intent("com.onyx.action.LIBRARY"),
            R.string.w_boox_notes to Intent("com.onyx.intent.action.MAIN_ACTIVITY").setPackage("com.onyx").putExtra("json", "{\"action\":\"OPEN_NOTE\"}"),
            R.string.w_boox_storage to Intent("com.onyx.action.STORAGE"),
            R.string.boox_fn_settings to BooxIntents.settings(),
        ).filter { (_, i) -> context.packageManager.resolveActivity(i, 0) != null }
    }
    WidgetFace(modifier) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.w_boox), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(end = Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                entries.forEach { (label, intent) ->
                    Key(stringResource(label), onClick = { runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                }
            }
        }
    }
}

/** The face every widget sits on: paper, a 1.5 dp rim, soft corners like a Braun housing. */
@Composable
fun WidgetFace(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .clip(shape)
            .border(Lines.rim, Ink.Black, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        content = content,
    )
}

/** Braun AB1-style dial: minute ticks, heavier hour ticks, two hands. No second hand: it would repaint e-ink every second. */
@Composable
fun ClockWidget(now: Long, wide: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString()
    WidgetFace(modifier, onClick = { runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The written time joins the dial whenever the face has room beside it, not only at full width.
            val roomy = wide || maxWidth > maxHeight * 1.55f
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Canvas(Modifier.fillMaxHeight().aspectRatio(1f).semantics { contentDescription = "$time, $date" }) { dial(cal) }
                if (roomy) Column(Modifier.weight(1f)) {
                    Text(time, style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, maxLines = 1)
                    Text(date, style = MaterialTheme.typography.titleMedium, color = Ink.Legend, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!roomy) Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

private fun DrawScope.dial(cal: Calendar) {
    val r = size.minDimension / 2f
    val c = center
    drawCircle(Ink.Black, r - 1.dp.toPx(), c, style = Stroke(Lines.rim.toPx()))
    for (i in 0 until 60) {
        val a = Math.toRadians(i * 6.0 - 90)
        val hour = i % 5 == 0
        val outer = r - 8.dp.toPx()
        val inner = outer - if (hour) 14.dp.toPx() else 6.dp.toPx()
        drawLine(
            if (hour) Ink.Black else Ink.Rule,
            Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat()),
            Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
            strokeWidth = if (hour) 3.dp.toPx() else 1.dp.toPx(),
        )
    }
    val minute = cal.get(Calendar.MINUTE)
    val hour = cal.get(Calendar.HOUR) + minute / 60f
    fun hand(angleDeg: Double, len: Float, w: Dp) {
        val a = Math.toRadians(angleDeg - 90)
        drawLine(Ink.Black, c, Offset(c.x + (cos(a) * len).toFloat(), c.y + (sin(a) * len).toFloat()), strokeWidth = w.toPx(), cap = StrokeCap.Round)
    }
    hand(hour * 30.0, r * 0.5f, 6.dp)
    hand(minute * 6.0, r * 0.78f, 4.dp)
    drawCircle(Ink.Signal, 6.dp.toPx(), c)
    drawCircle(Ink.Black, 6.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
}

/** A month on one face. Today is a filled black disc; days with events carry a dot when calendar access is given. */
@Composable
fun CalendarWidget(now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val first = today.withDayOfMonth(1)
    val weekStart: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
    val days = first.lengthOfMonth()
    val busy = remember(today.monthValue, today.year) { eventDays(context, first) }
    WidgetFace(modifier, onClick = { openCalendar(context, now) }) {
        Column(Modifier.fillMaxSize()) {
            Text(
                first.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) } + " " + first.year,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Space.s))
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val dow = weekStart.plus(i.toLong())
                    Text(dow.getDisplayName(TextStyle.NARROW_STANDALONE, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = Ink.Legend, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            val cells = lead + days
            val rows = (cells + 6) / 7
            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    for (col in 0 until 7) {
                        val day = row * 7 + col - lead + 1
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (day in 1..days) {
                                val isToday = day == today.dayOfMonth
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).then(if (isToday) Modifier.background(Ink.Black) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelMedium, color = if (isToday) Ink.Paper else Ink.Black)
                                }
                                if (day in busy && !isToday) Box(Modifier.align(Alignment.BottomCenter).size(5.dp).clip(CircleShape).background(Ink.Black))
                            }
                        }
                    }
                }
            }
        }
    }
}

data class AgendaItem(val title: String, val begin: Long, val allDay: Boolean)

@Composable
fun AgendaWidget(now: Long, modifier: Modifier = Modifier, onRequestCalendar: () -> Unit) {
    val context = LocalContext.current
    val granted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val items = remember(now / (15 * 60_000L), granted) { if (granted) agenda(context, now) else emptyList() }
    WidgetFace(modifier, onClick = { openCalendar(context, now) }) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.w_agenda), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            when {
                !granted -> {
                    Text(stringResource(R.string.w_agenda_permission), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                    Spacer(Modifier.height(Space.s))
                    Key(stringResource(R.string.w_allow_calendar), onClick = onRequestCalendar)
                }
                items.isEmpty() -> Text(stringResource(R.string.w_agenda_empty), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                else -> items.take(5).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Text(agendaWhen(context, e, now), style = MaterialTheme.typography.labelMedium, color = Ink.Legend, modifier = Modifier.weight(0.38f))
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.62f))
                    }
                }
            }
        }
    }
}

private fun agendaWhen(context: Context, e: AgendaItem, now: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (d) {
        today -> context.getString(R.string.w_today)
        today.plusDays(1) -> context.getString(R.string.w_tomorrow)
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
    return if (e.allDay) day else "$day ${DateFormat.getTimeFormat(context).format(Date(e.begin))}"
}

private fun agenda(context: Context, now: Long): List<AgendaItem> = runCatching {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now)
        ContentUris.appendId(it, now + 7L * 24 * 3600 * 1000)
    }.build()
    val out = mutableListOf<AgendaItem>()
    context.contentResolver.query(
        uri,
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
        null, null, "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { c ->
        while (c.moveToNext() && out.size < 8) out += AgendaItem(c.getString(0) ?: "—", c.getLong(1), c.getInt(2) == 1)
    }
    out
}.getOrDefault(emptyList())

private fun eventDays(context: Context, first: LocalDate): Set<Int> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptySet()
    return runCatching {
        val zone = ZoneId.systemDefault()
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
        val out = mutableSetOf<Int>()
        context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN), null, null, null)?.use { c ->
            while (c.moveToNext()) out += Instant.ofEpochMilli(c.getLong(0)).atZone(zone).dayOfMonth
        }
        out
    }.getOrDefault(emptySet())
}

private fun openCalendar(context: Context, now: Long) {
    val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, now) }.build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun WeatherWidget(repo: WeatherRepo, visibleKey: Int, wide: Boolean, modifier: Modifier = Modifier, onPickCity: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keyed on visibleKey so a city chosen in the picker panel shows as soon as home is back.
    var weather by remember(visibleKey) { mutableStateOf(repo.cached()) }
    val place = remember(visibleKey) { repo.place() }
    var error by remember { mutableStateOf<String?>(null) }

    fun update() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { repo.refresh() }
            r.onSuccess { weather = it; error = null }.onFailure { error = context.getString(R.string.w_weather_offline) }
        }
    }
    // Refresh only when the launcher becomes visible and the reading is over an hour old.
    LaunchedEffect(visibleKey, place) { if (place != null && repo.stale()) update() }

    WidgetFace(modifier, onClick = if (place == null) onPickCity else null) {
        if (place == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.w_weather), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.w_weather_empty), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                Spacer(Modifier.height(Space.m))
                Key(stringResource(R.string.w_weather_pick), primary = true, onClick = onPickCity)
            }
            return@WidgetFace
        }
        val w = weather
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.w_weather_change), onClick = onPickCity), contentAlignment = Alignment.CenterStart) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable { update() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.w_weather_update), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (w == null) {
                Text(error ?: stringResource(R.string.w_weather_loading), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                return@Column
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(72.dp)) { skyGlyph(skyOf(w.code)) }
                Spacer(Modifier.size(Space.m))
                Column {
                    Text("${w.temp.roundToInt()}°", style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(skyName(skyOf(w.code))), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                }
                if (wide) {
                    Spacer(Modifier.weight(1f))
                    w.days.drop(1).take(3).forEach { d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 10.dp)) {
                            Text(LocalDate.ofEpochDay(d.epochDay).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = Ink.Legend)
                            Canvas(Modifier.size(32.dp)) { skyGlyph(skyOf(d.code)) }
                            Text("${d.max.roundToInt()}° ${d.min.roundToInt()}°", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            w.days.firstOrNull()?.let {
                Text(
                    stringResource(R.string.w_weather_today, it.max.roundToInt(), it.min.roundToInt(), Format2.clock(context, w.fetchedAt)),
                    style = MaterialTheme.typography.labelMedium, color = Ink.Legend,
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.Legend) }
        }
    }
}

private object Format2 {
    fun clock(context: Context, t: Long): String = DateFormat.getTimeFormat(context).format(Date(t))
}

/** City search for the weather widget, shown in the top entry panel. One lookup per pause in typing. */
@Composable
fun CityPicker(repo: WeatherRepo, onDone: (chosen: Boolean) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Each keystroke cancels the previous wait, so results never arrive out of order.
    LaunchedEffect(query) {
        val q = query.trim()
        failed = false
        if (q.length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(450)
        val r = withContext(Dispatchers.IO) { repo.search(q) }
        results = r.getOrDefault(emptyList())
        failed = r.isFailure
        searching = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.w_weather_pick), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Key(stringResource(R.string.l_close), onClick = { onDone(false) })
    }
    Spacer(Modifier.height(Space.m))
    SearchField(query, { query = it }, stringResource(R.string.w_weather_search), Modifier.focusRequester(focus))
    Spacer(Modifier.height(Space.s))
    val q = query.trim()
    val status = when {
        searching -> stringResource(R.string.w_weather_searching)
        failed -> stringResource(R.string.w_weather_search_failed)
        q.length >= 2 && results.isEmpty() -> stringResource(R.string.w_weather_no_city, q)
        q.length < 2 -> stringResource(R.string.w_weather_privacy)
        else -> null
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s)) }
    results.take(6).forEach { p ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { repo.setPlace(p); onDone(true) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(Space.m))
            Text(p.region, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

/** Editing the note widget, in the top entry panel. Saved as you type. */
@Composable
fun NoteEditor(store: WidgetStore, onDone: () -> Unit) {
    var text by remember { mutableStateOf(store.note) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.w_note), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Key(stringResource(R.string.l_done), primary = true, onClick = onDone)
    }
    Spacer(Modifier.height(Space.m))
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).border(Lines.rim, Ink.Black, shape).padding(16.dp)) {
        if (text.isEmpty()) Text(stringResource(R.string.w_note_hint), style = MaterialTheme.typography.bodyLarge, color = Ink.Legend)
        BasicTextField(
            value = text,
            onValueChange = { text = it; store.note = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black),
            cursorBrush = SolidColor(Ink.Black),
            modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp).focusRequester(focus),
        )
    }
    Spacer(Modifier.height(Space.s))
    Text(stringResource(R.string.w_note_private), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
}

private fun skyName(s: Sky) = when (s) {
    Sky.Clear -> R.string.sky_clear
    Sky.PartCloud -> R.string.sky_part
    Sky.Cloud -> R.string.sky_cloud
    Sky.Fog -> R.string.sky_fog
    Sky.Drizzle -> R.string.sky_drizzle
    Sky.Rain -> R.string.sky_rain
    Sky.Snow -> R.string.sky_snow
    Sky.Storm -> R.string.sky_storm
}

/** Weather glyphs in the instrument's line grammar: circles, arcs and short strokes. */
private fun DrawScope.skyGlyph(s: Sky) {
    val w = size.minDimension
    val stroke = Stroke(w * 0.05f, cap = StrokeCap.Round)
    fun sun(cx: Float, cy: Float, r: Float) {
        drawCircle(Ink.Black, r, Offset(cx, cy), style = stroke)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            drawLine(Ink.Black, Offset(cx + (cos(a) * r * 1.45f).toFloat(), cy + (sin(a) * r * 1.45f).toFloat()), Offset(cx + (cos(a) * r * 1.9f).toFloat(), cy + (sin(a) * r * 1.9f).toFloat()), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
    fun cloud(cx: Float, cy: Float, s2: Float) {
        drawCircle(Ink.Paper, s2 * 0.3f, Offset(cx - s2 * 0.22f, cy))
        drawCircle(Ink.Paper, s2 * 0.38f, Offset(cx + s2 * 0.12f, cy - s2 * 0.1f))
        drawCircle(Ink.Black, s2 * 0.3f, Offset(cx - s2 * 0.22f, cy), style = stroke)
        drawCircle(Ink.Black, s2 * 0.38f, Offset(cx + s2 * 0.12f, cy - s2 * 0.1f), style = stroke)
        drawLine(Ink.Paper, Offset(cx - s2 * 0.5f, cy + s2 * 0.3f), Offset(cx + s2 * 0.48f, cy + s2 * 0.3f), strokeWidth = stroke.width * 3)
        drawLine(Ink.Black, Offset(cx - s2 * 0.5f, cy + s2 * 0.3f), Offset(cx + s2 * 0.5f, cy + s2 * 0.3f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
    fun drops(n: Int, dotted: Boolean) {
        for (i in 0 until n) {
            val x = w * (0.3f + i * 0.2f)
            if (dotted) drawCircle(Ink.Black, w * 0.035f, Offset(x, w * 0.85f))
            else drawLine(Ink.Black, Offset(x, w * 0.76f), Offset(x - w * 0.05f, w * 0.92f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
    when (s) {
        Sky.Clear -> sun(w / 2, w / 2, w * 0.2f)
        Sky.PartCloud -> { sun(w * 0.62f, w * 0.36f, w * 0.14f); cloud(w * 0.45f, w * 0.6f, w * 0.6f) }
        Sky.Cloud -> cloud(w / 2, w * 0.5f, w * 0.75f)
        Sky.Fog -> for (i in 0 until 4) drawLine(Ink.Black, Offset(w * 0.15f, w * (0.3f + i * 0.14f)), Offset(w * 0.85f, w * (0.3f + i * 0.14f)), strokeWidth = stroke.width, cap = StrokeCap.Round)
        Sky.Drizzle -> { cloud(w / 2, w * 0.42f, w * 0.7f); drops(3, true) }
        Sky.Rain -> { cloud(w / 2, w * 0.42f, w * 0.7f); drops(3, false) }
        Sky.Snow -> { cloud(w / 2, w * 0.42f, w * 0.7f); drops(3, true); drawCircle(Ink.Black, w * 0.035f, Offset(w * 0.4f, w * 0.95f)) }
        Sky.Storm -> {
            cloud(w / 2, w * 0.4f, w * 0.7f)
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.55f, w * 0.62f); lineTo(w * 0.42f, w * 0.8f); lineTo(w * 0.54f, w * 0.8f); lineTo(w * 0.45f, w * 0.98f) }
            drawPath(p, Ink.Black, style = stroke)
        }
    }
}

@Composable
fun BatteryWidget(battery: BatterySnapshot?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    WidgetFace(modifier, onClick = onOpen) {
        if (battery == null) return@WidgetFace
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            TuningScale(battery.levelPct, figureStyle = MaterialTheme.typography.displaySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(battery.charging)
                Spacer(Modifier.size(Space.s))
                Text(chargeLine(context, battery), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun AlarmWidget(now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val next = remember(now / 60_000L) { context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }
    WidgetFace(modifier, onClick = { runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.w_alarm), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
            Text(
                next?.let { DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE " + if (DateFormat.is24HourFormat(context)) "HHmm" else "hmma"), it).toString() }
                    ?: stringResource(R.string.w_alarm_none),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
fun NoteWidget(store: WidgetStore, visibleKey: Int, modifier: Modifier = Modifier, onEdit: () -> Unit) {
    // Read again whenever home comes back, so an edit made in the entry panel shows at once.
    val text = remember(visibleKey) { store.note }
    WidgetFace(modifier, onClick = onEdit) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.w_note), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
            Spacer(Modifier.height(Space.xs))
            Text(
                text.ifEmpty { stringResource(R.string.w_note_hint) },
                style = MaterialTheme.typography.bodyLarge, color = if (text.isEmpty()) Ink.Legend else Ink.Black,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A third-party widget hosted through Android's AppWidgetHost. */
@Composable
fun SystemWidget(host: AppWidgetHost, appWidgetId: Int, widthDp: Int, heightDp: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val info = remember(appWidgetId) { manager.getAppWidgetInfo(appWidgetId) }
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.clip(shape).border(Lines.rim, Ink.Black, shape).padding(6.dp), contentAlignment = Alignment.Center) {
        if (info == null) {
            Text(stringResource(R.string.w_system_missing), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(Space.m))
        } else {
            AndroidView(
                factory = { ctx -> host.createView(ctx.applicationContext, appWidgetId, info).apply { setAppWidget(appWidgetId, info) } },
                update = { v ->
                    val opts = Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                    }
                    runCatching { v.updateAppWidgetOptions(opts) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
