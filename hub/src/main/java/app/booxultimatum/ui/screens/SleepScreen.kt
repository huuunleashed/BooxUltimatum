package app.booxultimatum.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.booxultimatum.R
import app.booxultimatum.kit.core.AppWork
import app.booxultimatum.core.Fonts
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.sleep.FaceOptions
import app.booxultimatum.core.sleep.PhotoFit
import app.booxultimatum.core.sleep.SleepAccent
import app.booxultimatum.core.sleep.SleepElement
import app.booxultimatum.core.sleep.SleepFace
import app.booxultimatum.core.sleep.SleepFaceSpec
import app.booxultimatum.core.sleep.SleepFont
import app.booxultimatum.core.sleep.SleepInk
import app.booxultimatum.core.sleep.SleepMode
import app.booxultimatum.core.sleep.SleepOverlay
import app.booxultimatum.core.sleep.SleepPhoto
import app.booxultimatum.core.sleep.SleepPublisher
import app.booxultimatum.core.sleep.SleepScheduler
import app.booxultimatum.core.sleep.SleepStatus
import app.booxultimatum.core.sleep.SleepStore
import app.booxultimatum.core.sleep.SleepStudio
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.Format
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Tag
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SleepField { Note, Owner, Quote, Caption }

/**
 * The sleep screen studio: pick a face, see it exactly as the panel will, and keep it current. Two panes in landscape
 * (sheet and keys left, settings right); in portrait the sheet and keys sit on top and the settings scroll below.
 * Every choice is saved as it is made, so rotation and process death lose nothing; typing happens in a panel at the top.
 */
@Composable
fun SleepScreen(readKey: Int, accessEvents: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spec by remember { mutableStateOf(SleepStore.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var statusKey by remember { mutableIntStateOf(0) }
    var photoKey by remember { mutableIntStateOf(0) }
    var calendarKey by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf<SleepField?>(null) }
    var liveGroup by rememberSaveable { mutableStateOf(spec.face.live) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val shell = rememberReading(Pair(readKey, accessEvents)) { Privileged.ready() } ?: false
    val status = rememberReading(Triple(readKey, statusKey, spec.active)) { SleepStore.status(context) }
    val calendarAllowed = remember(calendarKey) { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED }

    fun edit(block: (SleepFaceSpec) -> SleepFaceSpec) {
        val before = spec
        var next = block(before)
        // Switching between the image and the sticker changes what Boox shows, so it waits for Apply instead of happening behind the user's back.
        if (next.active && next.mode != before.mode) {
            next = next.copy(active = false)
            SleepScheduler.cancel(context)
            message = context.getString(R.string.sl_mode_changed)
        }
        spec = next
        SleepStore.save(context, next)
        if (next.active) {
            if (next.intervalMin != before.intervalMin) SleepScheduler.schedule(context)
            SleepScheduler.request(context, "edit", 3_000)
        }
    }

    fun act(ok: String?, block: suspend () -> Result<*>) {
        busy = true; error = null; message = null
        scope.launch {
            // The work runs in the app scope, so leaving the screen or rotating mid-apply can't cut it short.
            val r = AppWork.scope.async { block() }.await()
            spec = SleepStore.load(context)
            r.onSuccess { message = ok }.onFailure { error = it.message ?: it.toString() }
            busy = false
            statusKey++
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { SleepPhoto.import(context, uri) }
            if (ok) { photoKey++; edit { it.copy(mode = SleepMode.Image, face = SleepFace.Photo) } } else error = context.getString(R.string.sl_photo_failed)
        }
    }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) SleepScheduler.watchCalendar(context)
        calendarKey++
    }

    // The sheet is drawn by the same renderer as the published file, at half size: every size is a fraction of the short side.
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewMs by remember { mutableStateOf<Long?>(null) }
    val renderSpec = spec.copy(active = false)
    LaunchedEffect(renderSpec, landscape, photoKey, calendarKey, readKey) {
        delay(120)
        withContext(Dispatchers.Default) { runCatching { SleepStudio.render(context, renderSpec, scale = 0.5f, asleepSample = true) }.getOrNull() }?.let {
            preview = it.bitmap.asImageBitmap()
            previewMs = it.renderMs
        }
    }

    // Thumbnails of the other orientation would show stretched, so a rotation starts a fresh set.
    val thumbs = remember(landscape) { mutableStateMapOf<String, ImageBitmap>() }
    val styleKey = renderSpec.copy(face = SleepFace.Almanac, overlay = SleepOverlay.BottomBand)
    LaunchedEffect(styleKey, spec.mode, landscape, photoKey, calendarKey) {
        val items: List<Pair<String, SleepFaceSpec>> = if (spec.mode == SleepMode.Image) {
            // The tab in view first, so its tiles fill in before the other's.
            SleepFace.entries.sortedBy { it.live != liveGroup }.map { it.name to renderSpec.copy(face = it) }
        } else SleepOverlay.entries.map { "ov" + it.name to renderSpec.copy(overlay = it) }
        for ((id, s) in items) {
            withContext(Dispatchers.Default) { runCatching { SleepStudio.render(context, s, scale = 0.1f, asleepSample = true) }.getOrNull() }?.let { thumbs[id] = it.bitmap.asImageBitmap() }
        }
    }

    // Tapping the sheet shows the face at the panel's full size, 1:1, as the sleep screen will; a tap closes it.
    var zoom by rememberSaveable { mutableStateOf(false) }
    var full by remember { mutableStateOf<ImageBitmap?>(null) }
    var fullMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(zoom, renderSpec, landscape, photoKey) {
        if (!zoom) { full = null; return@LaunchedEffect }
        withContext(Dispatchers.Default) { runCatching { SleepStudio.render(context, renderSpec, scale = 1f, asleepSample = true) }.getOrNull() }?.let {
            full = it.bitmap.asImageBitmap()
            fullMs = it.renderMs
        }
    }
    if (zoom) {
        Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val closeLabel = stringResource(R.string.sl_zoom_close)
            Box(
                Modifier.fillMaxSize().background(Ink.Paper).clickable(onClickLabel = closeLabel) { zoom = false },
                contentAlignment = Alignment.Center,
            ) {
                full?.let { Image(it, contentDescription = stringResource(R.string.sl_preview_description), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }

    val head: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(bottom = Space.m)) {
            Text(stringResource(R.string.sl_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.sl_subtitle), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = 2.dp))
        }
    }
    val sheet: @Composable (Modifier) -> Unit = { m ->
        val openLabel = stringResource(R.string.sl_zoom_open)
        SheetPreview(preview, landscape, spec.mode == SleepMode.Overlay, spec.leavesClockRoom, m.clickable(onClickLabel = openLabel) { zoom = true })
    }
    val legend: @Composable () -> Unit = {
        Text(
            stringResource(if (spec.mode == SleepMode.Image && spec.face.live) R.string.sl_preview_legend_live else R.string.sl_preview_legend),
            style = MaterialTheme.typography.labelSmall, color = Ink.Legend,
            modifier = Modifier.padding(top = Space.xs),
        )
    }
    val keys: @Composable () -> Unit = {
        Column {
            StateLine(spec, status, previewMs, fullMs)
            Spacer(Modifier.height(Space.s + Space.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Key(
                    stringResource(if (spec.active) R.string.sl_apply_again else R.string.sl_apply),
                    primary = true,
                    enabled = !busy && (spec.mode == SleepMode.Image || shell),
                    onClick = { act(context.getString(R.string.sl_applied)) { SleepStudio.apply(context) } },
                )
                Key(stringResource(R.string.sl_sleep_now), enabled = !busy && shell, onClick = { act(null) { SleepStudio.sleepNow(context) } })
                ConfirmKey(
                    stringResource(R.string.sl_restore),
                    stringResource(R.string.sl_restore_confirm),
                    enabled = !busy && (spec.active || SleepStudio.changed(context)),
                    onConfirm = { act(context.getString(R.string.sl_restored)) { SleepStudio.restore(context) } },
                )
                Key(stringResource(R.string.sl_open_boox), onClick = { SleepPublisher.openScreensaverSettings(context) })
            }
            // The power-off picture: the same face, set once, without the moment-bound battery and put-down time.
            var powerOff by remember { mutableStateOf(SleepStudio.powerOffSet(context)) }
            Spacer(Modifier.height(Space.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
                Key(
                    stringResource(if (powerOff) R.string.sl_poweroff_again else R.string.sl_poweroff),
                    enabled = !busy && spec.mode == SleepMode.Image,
                    onClick = { act(context.getString(R.string.sl_poweroff_done)) { SleepStudio.applyPowerOff(context).map { powerOff = true } } },
                )
                if (powerOff) Key(stringResource(R.string.sl_poweroff_restore), enabled = !busy, onClick = {
                    act(context.getString(R.string.sl_poweroff_restored)) { SleepStudio.restorePowerOff(context).also { if (it.isSuccess) powerOff = false } }
                })
            }
            Text(stringResource(R.string.sl_poweroff_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs))
            if (!shell) Text(stringResource(R.string.sl_shell_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
            error?.let { ErrorLine(it) }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s)) }
        }
    }

    val controls: @Composable (Modifier) -> Unit = { m ->
        LazyColumn(m, contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xl)) {
            item {
                SleepPlate(stringResource(R.string.sl_mode)) {
                    ModeRow(
                        stringResource(R.string.sl_mode_image), stringResource(R.string.sl_mode_image_detail), "T0",
                        selected = spec.mode == SleepMode.Image,
                    ) { edit { it.copy(mode = SleepMode.Image) } }
                    ModeRow(
                        stringResource(R.string.sl_mode_overlay), stringResource(R.string.sl_mode_overlay_detail), "T2",
                        selected = spec.mode == SleepMode.Overlay,
                    ) { edit { it.copy(mode = SleepMode.Overlay) } }
                }
                Spacer(Modifier.height(Space.l))
            }
            item {
                SleepPlate(stringResource(if (spec.mode == SleepMode.Image) R.string.sl_face else R.string.sl_plate)) {
                    // Small tiles in rows. The faces are split in two tabs, live and still, so each stays one glance.
                    val tile = if (landscape) 150.dp else 112.dp
                    if (spec.mode == SleepMode.Image) {
                        FlowRow(Modifier.padding(top = Space.xs, bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            val liveCount = SleepFace.entries.count { it.live }
                            Key(pluralStringResource(R.plurals.sl_group_live, liveCount, liveCount), primary = liveGroup, onClick = { liveGroup = true })
                            val stillCount = SleepFace.entries.size - liveCount
                            Key(pluralStringResource(R.plurals.sl_group_still, stillCount, stillCount), primary = !liveGroup, onClick = { liveGroup = false })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (spec.mode == SleepMode.Image) {
                            SleepFace.entries.filter { it.live == liveGroup }.forEach { f ->
                                FaceTile(stringResource(SleepStudio.faceLabel(f)), thumbs[f.name], landscape, spec.face == f, Modifier.width(tile), live = f.live) { edit { it.copy(face = f) } }
                            }
                        } else {
                            SleepOverlay.entries.forEach { o ->
                                FaceTile(stringResource(SleepStudio.overlayLabel(o)), thumbs["ov" + o.name], landscape, spec.overlay == o, Modifier.width(tile), overlay = true) { edit { it.copy(overlay = o) } }
                            }
                        }
                    }
                    if (spec.mode == SleepMode.Image && spec.face.live != liveGroup) {
                        Text(stringResource(R.string.sl_group_selected, stringResource(SleepStudio.faceLabel(spec.face))), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.s))
                    }
                    Text(stringResource(faceNote(spec)), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                }
                Spacer(Modifier.height(Space.l))
            }
            // The selected face's own settings, right under the picker.
            val own = if (spec.mode == SleepMode.Image) FaceOptions.of(spec.face) else emptyList()
            if (own.isNotEmpty()) item {
                SleepPlate(stringResource(R.string.sl_face_options, stringResource(SleepStudio.faceLabel(spec.face)))) {
                    own.forEach { o ->
                        if (o.multi) {
                            val on = spec.optionSet(o)
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(stringResource(o.label), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(Space.xs + 2.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    o.values.forEach { v -> Key(stringResource(v.label), primary = v.id in on, onClick = { edit { it.toggleOption(o, v.id) } }) }
                                }
                            }
                        } else {
                            Choice(stringResource(o.label), o.values.map { stringResource(it.label) to it.id }, spec.option(o)) { v -> edit { it.withOption(o, v) } }
                        }
                        o.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(bottom = Space.xs)) }
                    }
                }
                Spacer(Modifier.height(Space.l))
            }
            // Updates while asleep come straight after the faces: the live faces are what makes them worth having.
            if (spec.mode == SleepMode.Image) item {
                LivePlate(spec, shell, readKey)
                Spacer(Modifier.height(Space.l))
            }
            item {
                SleepPlate(stringResource(R.string.sl_shows)) {
                    // A face that picks its own readings still needs the calendar allowed to show events.
                    if (spec.mode == SleepMode.Image && spec.face.usesCalendar && SleepElement.Agenda !in spec.options && !calendarAllowed) {
                        ToggleRow(stringResource(R.string.sl_el_agenda), stringResource(R.string.sl_calendar_off), false) {
                            Key(stringResource(R.string.sl_allow_calendar), onClick = { askCalendar.launch(Manifest.permission.READ_CALENDAR) })
                        }
                    }
                    SleepElement.entries.filter { it in spec.options }.forEach { e ->
                        val on = e in spec.elements
                        if (e == SleepElement.Agenda && !calendarAllowed) {
                            ToggleRow(stringResource(R.string.sl_el_agenda), stringResource(R.string.sl_calendar_off), false) {
                                Key(stringResource(R.string.sl_allow_calendar), onClick = { askCalendar.launch(Manifest.permission.READ_CALENDAR) })
                            }
                        } else {
                            ToggleRow(stringResource(elementLabel(e)), elementDetail(e, spec), on) {
                                if (e == SleepElement.Note || e == SleepElement.Owner || (e == SleepElement.Quote && spec.useOwnQuote)) {
                                    Key(stringResource(R.string.sl_edit), onClick = { editing = fieldFor(e) })
                                    Spacer(Modifier.size(Space.s))
                                }
                                Key(stringResource(if (on) R.string.ap_hide else R.string.ap_show), onClick = {
                                    edit { it.copy(elements = if (on) it.elements - e else it.elements + e) }
                                })
                            }
                        }
                    }
                    if (spec.mode == SleepMode.Image && spec.face == SleepFace.Note) {
                        ToggleRow(stringResource(R.string.sl_el_note), spec.note.ifBlank { stringResource(R.string.sl_note_none) }, spec.note.isNotBlank()) {
                            Key(stringResource(R.string.sl_edit), onClick = { editing = SleepField.Note })
                        }
                    }
                    if (spec.mode == SleepMode.Image && spec.face == SleepFace.ReturnCard) {
                        ToggleRow(stringResource(R.string.sl_el_owner), ownerDetail(spec), spec.ownerName.isNotBlank()) {
                            Key(stringResource(R.string.sl_edit), onClick = { editing = SleepField.Owner })
                        }
                    }
                    if (SleepElement.Quote in spec.options) {
                        Choice(
                            stringResource(R.string.sl_quote_source),
                            listOf(stringResource(R.string.sl_quote_daily) to false, stringResource(R.string.sl_quote_own) to true),
                            spec.useOwnQuote,
                        ) { v -> edit { it.copy(useOwnQuote = v) }; if (v && spec.ownQuote.isBlank()) editing = SleepField.Quote }
                    }
                    if (spec.mode == SleepMode.Image && spec.face == SleepFace.Photo) {
                        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule, modifier = Modifier.padding(vertical = Space.s))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Key(stringResource(R.string.sl_photo_choose), onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            if (SleepPhoto.exists(context)) Key(stringResource(R.string.sl_photo_remove), onClick = { SleepPhoto.clear(context); photoKey++; edit { it } })
                            Key(stringResource(R.string.sl_caption_edit), onClick = { editing = SleepField.Caption })
                        }
                        Choice(stringResource(R.string.sl_photo_fit), listOf(stringResource(R.string.sl_fit_fill) to PhotoFit.Fill, stringResource(R.string.sl_fit_fit) to PhotoFit.Fit), spec.photoFit) { v -> edit { it.copy(photoFit = v) } }
                        Choice(stringResource(R.string.sl_dither), listOf(stringResource(R.string.sl_dither_off) to false, stringResource(R.string.sl_dither_on) to true), spec.dither) { v -> edit { it.copy(dither = v) } }
                        Text(stringResource(R.string.sl_dither_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                    }
                    if (!(spec.mode == SleepMode.Image && spec.face.keepsClockRoom)) {
                        // Rows already end on a hairline; only the photo controls above need one of their own.
                        if (spec.mode == SleepMode.Image && spec.face == SleepFace.Photo) HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule, modifier = Modifier.padding(vertical = Space.s))
                        ToggleRow(stringResource(R.string.sl_clock_room), stringResource(R.string.sl_clock_room_detail), spec.clockRoom) {
                            Key(stringResource(if (spec.clockRoom) R.string.l_off else R.string.l_on), onClick = { edit { it.copy(clockRoom = !it.clockRoom) } })
                        }
                    }
                }
                Spacer(Modifier.height(Space.l))
            }
            item {
                val families = remember {
                    Fonts.keptDir(context).listFiles { f -> f.name.endsWith(".ttf") && !f.name.contains("Italic") }.orEmpty()
                        .groupBy { it.name.substringBefore('-') }
                        .map { (base, files) -> base.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ") to (files.firstOrNull { it.name.endsWith("-Regular.ttf") } ?: files.first()).absolutePath }
                        .sortedBy { it.first }
                }
                SleepPlate(stringResource(R.string.sl_type)) {
                    val options = buildList {
                        add(stringResource(R.string.sl_font_system) to (SleepFont.System to null as String?))
                        add(stringResource(R.string.sl_font_archivo) to (SleepFont.Archivo to null as String?))
                        families.forEach { (name, path) -> add(name to (SleepFont.File to path as String?)) }
                    }
                    Choice(stringResource(R.string.sl_font), options, spec.font to (if (spec.font == SleepFont.File) spec.fontFile else null)) { (f, path) ->
                        edit { it.copy(font = f, fontFile = path ?: it.fontFile) }
                    }
                    Choice(
                        stringResource(R.string.sl_display_weight),
                        SleepFaceSpec.DISPLAY_WEIGHTS.map { stringResource(weightName(it)) to it },
                        spec.displayWeight,
                    ) { v -> edit { it.copy(displayWeight = v) } }
                    Choice(
                        stringResource(R.string.sl_body_weight),
                        SleepFaceSpec.BODY_WEIGHTS.map { stringResource(weightName(it)) to it },
                        spec.bodyWeight,
                    ) { v -> edit { it.copy(bodyWeight = v) } }
                    Text(stringResource(R.string.sl_type_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                }
                Spacer(Modifier.height(Space.l))
            }
            item {
                SleepPlate(stringResource(R.string.sl_ink)) {
                    Choice(stringResource(R.string.sl_ink_label), listOf(stringResource(R.string.sl_ink_paper) to SleepInk.Paper, stringResource(R.string.sl_ink_inverted) to SleepInk.Inverted), spec.ink) { v -> edit { it.copy(ink = v) } }
                    Text(stringResource(R.string.sl_accent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.s))
                    Spacer(Modifier.height(Space.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        SleepAccent.entries.forEach { a -> Swatch(a, stringResource(accentName(a)), spec.accent == a) { edit { it.copy(accent = a) } } }
                    }
                    Text(stringResource(R.string.sl_accent_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                }
                Spacer(Modifier.height(Space.l))
            }
            item {
                SleepPlate(stringResource(R.string.sl_refresh)) {
                    Choice(
                        stringResource(R.string.sl_refresh_label),
                        SleepFaceSpec.INTERVALS.map { pluralStringResource(R.plurals.sl_minutes, it, it) to it },
                        spec.intervalMin,
                    ) { v -> edit { it.copy(intervalMin = v) } }
                    Prose(stringResource(R.string.sl_refresh_explain), color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
                }
                Spacer(Modifier.height(Space.l))
            }

            item {
                SleepPlate(stringResource(R.string.sl_boox)) {
                    if (spec.mode == SleepMode.Image) {
                        Prose(stringResource(R.string.sl_boox_image), modifier = Modifier.padding(vertical = Space.s))
                    } else {
                        Prose(stringResource(R.string.sl_boox_overlay_intro), modifier = Modifier.padding(vertical = Space.s))
                        listOf(R.string.sl_step_1, R.string.sl_step_2, R.string.sl_step_3, R.string.sl_step_4).forEachIndexed { i, res ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Text("${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
                                Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(Space.s))
                        Key(stringResource(R.string.sl_prepare), enabled = !busy, onClick = { act(context.getString(R.string.sl_prepared)) { SleepStudio.prepareSticker(context) } })
                    }
                    Spacer(Modifier.height(Space.s))
                    Prose(stringResource(R.string.sl_boox_overlay_settings), color = Ink.Legend)
                    Spacer(Modifier.height(Space.s))
                    Key(stringResource(R.string.sl_open_boox), onClick = { SleepPublisher.openScreensaverSettings(context) })
                    Text(stringResource(R.string.sl_limits), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val paneWidth = maxWidth * 0.34f
        val sheetWidth = maxWidth * 0.25f
        Column(Modifier.fillMaxSize()) {
            editing?.let { field ->
                EditPanel(field, spec, onCancel = { editing = null }) { next -> edit { next }; editing = null }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(paneWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.l),
                    ) {
                        head()
                        sheet(Modifier.fillMaxWidth())
                        legend()
                        Spacer(Modifier.height(Space.m))
                        keys()
                    }
                    VerticalDivider(thickness = Lines.hairline, color = Ink.Rule)
                    controls(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.m), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(sheetWidth)) { sheet(Modifier.fillMaxWidth()) }
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f)) {
                        head()
                        keys()
                        Spacer(Modifier.height(Space.s))
                        legend()
                    }
                }
                HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
                controls(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

// ---------- Pieces ----------

/**
 * Live updates while asleep: the switch and its choices, then what it needs with a lamp each, then how the last
 * sleep went. Everything re-reads when the page comes back to the front, so returning from Android's settings shows
 * the new state without a manual refresh.
 */
@Composable
private fun LivePlate(spec: SleepFaceSpec, shell: Boolean, readKey: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(app.booxultimatum.core.sleep.LivePrefs.load(context)) }
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val a11y = remember(tick, readKey) { app.booxultimatum.core.sleep.LiveSleep.accessibilityOn(context) }
    val running = remember(tick, readKey) { app.booxultimatum.core.sleep.LiveSleepService.running }
    val background = remember(tick, readKey) { app.booxultimatum.core.sleep.LiveSleep.backgroundAllowed(context) }
    val exact = remember(tick, readKey) { app.booxultimatum.core.sleep.LiveSleep.exactAlarms(context) }
    val secure = remember(tick, readKey) { context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED }
    val record = remember(tick, readKey) { app.booxultimatum.core.sleep.LiveSleep.record(context) }
    var note by remember { mutableStateOf<String?>(null) }

    fun save(next: app.booxultimatum.core.sleep.LivePrefs) {
        prefs = next
        app.booxultimatum.core.sleep.LivePrefs.save(context, next)
        if (!next.enabled) app.booxultimatum.core.sleep.LiveSleep.cancel(context)
    }

    SleepPlate(stringResource(R.string.sl_live)) {
        if (spec.face.live && !prefs.enabled) Prose(stringResource(R.string.sl_live_suggest), modifier = Modifier.padding(top = Space.s))
        Prose(stringResource(R.string.sl_live_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        when {
            spec.mode != SleepMode.Image -> Prose(stringResource(R.string.sl_live_needs_image), modifier = Modifier.padding(bottom = Space.s))
            !spec.active -> Prose(stringResource(R.string.sl_live_needs_apply), modifier = Modifier.padding(bottom = Space.s))
        }
        ToggleRow(stringResource(R.string.sl_live), null, prefs.enabled && spec.active && spec.mode == SleepMode.Image) {
            Key(stringResource(if (prefs.enabled) R.string.sl_live_turn_off else R.string.sl_live_turn_on), primary = !prefs.enabled, onClick = { save(prefs.copy(enabled = !prefs.enabled)) })
        }
        Choice(
            stringResource(R.string.sl_live_step),
            app.booxultimatum.core.sleep.LivePrefs.STEPS.map { pluralStringResource(R.plurals.sl_minutes, it, it) to it },
            prefs.stepMin,
        ) { v -> save(prefs.copy(stepMin = v)) }
        ToggleRow(stringResource(R.string.sl_live_charging), stringResource(R.string.sl_live_charging_detail), prefs.onlyCharging) {
            Key(stringResource(if (prefs.onlyCharging) R.string.sl_live_on else R.string.sl_live_off), onClick = { save(prefs.copy(onlyCharging = !prefs.onlyCharging)) })
        }
        val from = "%02d:00".format(prefs.quietFrom)
        val to = "%02d:00".format(prefs.quietTo)
        ToggleRow(stringResource(R.string.sl_live_quiet), stringResource(R.string.sl_live_quiet_detail, from, to), prefs.quiet) {
            Key(stringResource(if (prefs.quiet) R.string.sl_live_on else R.string.sl_live_off), onClick = { save(prefs.copy(quiet = !prefs.quiet)) })
        }

        Spacer(Modifier.height(Space.m))
        Text(stringResource(R.string.sl_live_setup), style = MaterialTheme.typography.titleSmall)
        ToggleRow(stringResource(R.string.sl_live_a11y), stringResource(R.string.sl_live_a11y_detail), a11y) {
            when {
                a11y && secure -> Key(stringResource(R.string.sl_live_a11y_off), onClick = { app.booxultimatum.core.sleep.LiveSleep.disableAccessibility(context); tick++ })
                a11y -> Key(stringResource(R.string.sl_live_a11y_open), onClick = { app.booxultimatum.core.sleep.LiveSleep.openAccessibility(context) })
                secure -> Key(stringResource(R.string.sl_live_a11y_direct), primary = true, onClick = { app.booxultimatum.core.sleep.LiveSleep.enableAccessibility(context); scope.launch { delay(1_500); tick++ } })
                else -> Key(stringResource(R.string.sl_live_a11y_open), primary = true, onClick = { app.booxultimatum.core.sleep.LiveSleep.openAccessibility(context) })
            }
        }
        if (a11y && !running) Text(stringResource(R.string.sl_live_waiting), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.xs))
        ToggleRow(stringResource(R.string.sl_live_bg), stringResource(R.string.sl_live_bg_detail), background) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (shell) Key(stringResource(R.string.sl_live_bg_register), onClick = {
                    scope.launch {
                        val ok = app.booxultimatum.kit.core.AppWork.scope.async { app.booxultimatum.core.sleep.LiveSleep.registerWithBoox(context) }.await()
                        note = if (ok) context.getString(R.string.sl_live_registered) else null
                        tick++
                    }
                })
                Key(stringResource(R.string.sl_live_bg_open), primary = !background, onClick = { openBatteryPage(context, context.packageName) })
            }
        }
        ToggleRow(stringResource(R.string.sl_live_exact), stringResource(R.string.sl_live_exact_detail), exact) {
            if (!exact) Key(stringResource(R.string.sl_live_exact_open), onClick = { app.booxultimatum.core.sleep.LiveSleep.openExactAlarms(context) })
            else Text(stringResource(R.string.sl_live_allowed), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
        }

        Spacer(Modifier.height(Space.s))
        val last = when {
            record == null -> stringResource(R.string.sl_live_none)
            record.updates > 0 -> pluralStringResource(R.plurals.sl_live_last, record.updates, record.updates, Format.clock(context, record.lastUpdate))
            else -> stringResource(R.string.sl_live_last_none)
        }
        Text(last, style = MaterialTheme.typography.bodyMedium)
        if (record?.missed == true) Prose(stringResource(R.string.sl_live_missed), color = Ink.Alert, modifier = Modifier.padding(top = Space.xs))
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs)) }
    }
}

/** A compact section for this page: a small title over the engraved rule. The shared Plate stays as it is elsewhere. */
@Composable
private fun SleepPlate(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Space.xs + 2.dp).semantics { heading() })
        HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        Spacer(Modifier.height(Space.xs))
        content()
    }
}

@Composable
private fun Prose(text: String, modifier: Modifier = Modifier, color: Color = Ink.Black) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.widthIn(max = 640.dp))
}
/** The sheet in a device rim. Over Transparent it sits on a mock page; dashes mark the Boox clock when room is left for it. */
@Composable
private fun SheetPreview(bitmap: ImageBitmap?, landscape: Boolean, overlay: Boolean, clock: Boolean, modifier: Modifier) {
    val description = stringResource(R.string.sl_preview_description)
    Box(
        modifier.aspectRatio(if (landscape) 2480f / 1860f else 1860f / 2480f)
            .clip(RoundedCornerShape(14.dp)).background(Ink.Black).padding(7.dp)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Ink.Paper)) {
            if (overlay) MockPage()
            if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                val st = Stroke(1.5.dp.toPx(), pathEffect = dash)
                if (clock) drawRect(Ink.Rule, Offset(size.width * 0.22f, size.height * 0.03f), Size(size.width * 0.56f, size.height * 0.18f), style = st)
            }
        }
    }
}

/** A stand-in for whatever the Transparent style will snapshot: a page of grey lines. */
@Composable
private fun MockPage() {
    Canvas(Modifier.fillMaxSize()) {
        val line = size.height * 0.018f
        val gap = size.height * 0.034f
        var y = size.height * 0.08f
        drawRect(Color(0xFFBDBDBD), Offset(size.width * 0.08f, y), Size(size.width * 0.5f, line * 1.8f))
        y += gap * 2
        var i = 0
        while (y < size.height * 0.9f) {
            val w = if (i % 7 == 6) 0.55f else 0.84f - (i % 3) * 0.03f
            drawRect(Color(0xFFD6D6D6), Offset(size.width * 0.08f, y), Size(size.width * w, line))
            y += if (i % 7 == 6) gap * 1.8f else gap
            i++
        }
    }
}

@Composable
private fun FaceTile(name: String, thumb: ImageBitmap?, landscape: Boolean, selected: Boolean, modifier: Modifier, overlay: Boolean = false, live: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (landscape) 2480f / 1860f else 1860f / 2480f).clip(shape)
                .border(if (selected) 3.dp else Lines.hairline, if (selected) Ink.Black else Ink.Rule, shape)
                .padding(if (selected) 3.dp else 1.dp).background(Ink.Paper),
        ) {
            if (overlay) MockPage()
            if (thumb != null) Image(thumb, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            // Faces made for updates while asleep carry a small black tab, like a Braun function label.
            if (live) Text(
                stringResource(R.string.sl_live_badge),
                style = MaterialTheme.typography.labelSmall, color = Ink.Paper,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Ink.Black).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) { Lamp(true, size = 10.dp); Spacer(Modifier.size(6.dp)) }
            Text(name, style = MaterialTheme.typography.labelMedium, color = if (selected) Ink.Black else Ink.Legend, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ModeRow(title: String, detail: String, tier: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Lamp(selected, Modifier.padding(top = 5.dp))
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
            }
            Tag(tier, strong = selected)
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun ToggleRow(title: String, detail: String?, on: Boolean, keys: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Lamp(on)
            Column(Modifier.weight(1f).padding(start = Space.m, end = Space.m)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.Legend, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            keys()
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, current: T, onChange: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Space.xs + 2.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            options.forEach { (text, v) -> Key(text, primary = v == current, onClick = { onChange(v) }) }
        }
    }
}

@Composable
private fun Swatch(accent: SleepAccent, name: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 64.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .border(if (selected) 4.dp else Lines.rim, Ink.Black, CircleShape)
                .padding(if (selected) 6.dp else 4.dp).clip(CircleShape)
                .background(if (accent == SleepAccent.Ink) Ink.Black else Color(accent.argb)),
        )
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = if (selected) Ink.Black else Ink.Legend)
    }
}

/** On or off, when it last published, and what that cost: the numbers that keep the feature honest. */
@Composable
private fun StateLine(spec: SleepFaceSpec, status: SleepStatus?, previewMs: Long?, fullMs: Long?) {
    val context = LocalContext.current
    val mode = stringResource(if (spec.mode == SleepMode.Image) R.string.sl_mode_image else R.string.sl_mode_overlay)
    Row(verticalAlignment = Alignment.Top) {
        Lamp(spec.active, Modifier.padding(top = 5.dp))
        Spacer(Modifier.size(Space.m))
        Column {
            Text(
                if (spec.active) stringResource(R.string.sl_state_on, mode, pluralStringResource(R.plurals.sl_minutes, spec.intervalMin, spec.intervalMin))
                else stringResource(R.string.sl_state_off),
                style = MaterialTheme.typography.titleSmall,
            )
            val line = status?.let { st ->
                val at = Format.clock(context, st.at)
                when {
                    st.error != null -> stringResource(R.string.sl_status_error, at, st.error)
                    st.skipped -> stringResource(R.string.sl_status_skipped, at)
                    else -> stringResource(
                        R.string.sl_status, at, st.renderMs, st.encodeMs, Formatter.formatShortFileSize(context, st.bytes),
                        st.file.removePrefix(android.os.Environment.getExternalStorageDirectory().path + "/"),
                    )
                }
            }
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = if (status?.error != null) Ink.Alert else Ink.Legend)
            val live = remember(spec) { app.booxultimatum.core.sleep.LivePrefs.load(context) }
            if (spec.active && spec.mode == SleepMode.Image && live.enabled) {
                Text(pluralStringResource(R.plurals.sl_state_live, live.stepMin, live.stepMin), style = MaterialTheme.typography.bodySmall)
            }
            if (previewMs != null) Text(stringResource(R.string.sl_preview_ms, previewMs), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
            if (fullMs != null) Text(stringResource(R.string.sl_full_ms, fullMs), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        }
    }
}

/** Typing happens here, at the top of the screen, where the keyboard can't cover it. */
@Composable
private fun EditPanel(field: SleepField, spec: SleepFaceSpec, onCancel: () -> Unit, onDone: (SleepFaceSpec) -> Unit) {
    var a by rememberSaveable(field) {
        mutableStateOf(
            when (field) {
                SleepField.Note -> spec.note
                SleepField.Owner -> spec.ownerName
                SleepField.Quote -> spec.ownQuote
                SleepField.Caption -> spec.caption
            },
        )
    }
    var b by rememberSaveable(field) { mutableStateOf(if (field == SleepField.Owner) spec.ownerContact else spec.ownQuoteAuthor) }
    var c by rememberSaveable(field) { mutableStateOf(spec.reward) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(field) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().background(Ink.Paper).padding(horizontal = Space.l, vertical = Space.m)) {
        Text(
            stringResource(
                when (field) {
                    SleepField.Note -> R.string.sl_edit_note
                    SleepField.Owner -> R.string.sl_edit_owner
                    SleepField.Quote -> R.string.sl_edit_quote
                    SleepField.Caption -> R.string.sl_edit_caption
                },
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(Space.s))
        when (field) {
            SleepField.Note -> Field(stringResource(R.string.sl_el_note), a, { a = it.take(400) }, lines = 4, modifier = Modifier.focusRequester(focus))
            SleepField.Caption -> Field(stringResource(R.string.sl_caption), a, { a = it.take(80) }, lines = 1, modifier = Modifier.focusRequester(focus))
            SleepField.Quote -> {
                Field(stringResource(R.string.sl_quote_text), a, { a = it.take(300) }, lines = 3, modifier = Modifier.focusRequester(focus))
                Field(stringResource(R.string.sl_quote_author), b, { b = it.take(60) }, lines = 1)
            }
            SleepField.Owner -> {
                Field(stringResource(R.string.sl_owner_name), a, { a = it.take(60) }, lines = 1, modifier = Modifier.focusRequester(focus))
                Field(stringResource(R.string.sl_owner_contact), b, { b = it.take(160) }, lines = 2)
                Field(stringResource(R.string.sl_owner_reward), c, { c = it.take(120) }, lines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            Key(stringResource(R.string.sl_cancel), onClick = onCancel)
            Key(stringResource(R.string.sl_done), primary = true, onClick = {
                onDone(
                    when (field) {
                        SleepField.Note -> spec.copy(note = a.trim())
                        SleepField.Caption -> spec.copy(caption = a.trim())
                        SleepField.Quote -> spec.copy(ownQuote = a.trim(), ownQuoteAuthor = b.trim(), useOwnQuote = a.isNotBlank())
                        SleepField.Owner -> spec.copy(ownerName = a.trim(), ownerContact = b.trim(), reward = c.trim())
                    },
                )
            })
        }
    }
    HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, lines: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else lines + 2,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black),
            cursorBrush = SolidColor(Ink.Black),
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label }
                .border(Lines.rim, Ink.Black, shape).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

// ---------- Labels ----------

private fun fieldFor(e: SleepElement) = when (e) {
    SleepElement.Owner -> SleepField.Owner
    SleepElement.Quote -> SleepField.Quote
    else -> SleepField.Note
}

private fun elementLabel(e: SleepElement) = when (e) {
    SleepElement.Date -> R.string.sl_el_date
    SleepElement.Battery -> R.string.sl_el_battery
    SleepElement.PutDown -> R.string.sl_el_put_down
    SleepElement.Agenda -> R.string.sl_el_agenda
    SleepElement.Note -> R.string.sl_el_note
    SleepElement.Owner -> R.string.sl_el_owner
    SleepElement.Quote -> R.string.sl_el_quote
    SleepElement.Asleep -> R.string.sl_element_asleep
}

@Composable
private fun elementDetail(e: SleepElement, spec: SleepFaceSpec): String? = when (e) {
    SleepElement.PutDown -> stringResource(R.string.sl_el_put_down_detail)
    SleepElement.Agenda -> stringResource(R.string.sl_el_agenda_detail)
    SleepElement.Note -> spec.note.ifBlank { stringResource(R.string.sl_note_none) }
    SleepElement.Owner -> ownerDetail(spec)
    SleepElement.Quote -> if (spec.useOwnQuote && spec.ownQuote.isNotBlank()) spec.ownQuote else stringResource(R.string.sl_quote_daily_detail)
    SleepElement.Asleep -> stringResource(R.string.sl_element_asleep_detail)
    else -> null
}

@Composable
private fun ownerDetail(spec: SleepFaceSpec): String =
    listOf(spec.ownerName, spec.ownerContact).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { stringResource(R.string.sl_owner_none) }

private fun faceNote(spec: SleepFaceSpec) = if (spec.mode == SleepMode.Overlay) R.string.sl_note_overlay else when (spec.face) {
    SleepFace.Dial -> R.string.sl_note_dial
    SleepFace.Clock -> R.string.sl_note_clock
    SleepFace.Monitor -> R.string.sl_note_monitor
    SleepFace.Cube -> R.string.sl_note_cube
    SleepFace.Flip -> R.string.sl_note_flip
    SleepFace.Dashboard -> R.string.sl_note_dashboard
    SleepFace.WordClock -> R.string.sl_note_words
    SleepFace.DayRing -> R.string.sl_note_ring
    SleepFace.Timeline -> R.string.sl_note_timeline
    SleepFace.Lcd -> R.string.sl_note_lcd
    SleepFace.Sky -> R.string.sl_note_sky
    SleepFace.Broadsheet -> R.string.sl_note_broadsheet
    SleepFace.Year -> R.string.sl_note_year
    SleepFace.Almanac -> R.string.sl_note_almanac
    SleepFace.Instrument -> R.string.sl_note_instrument
    SleepFace.Poster -> R.string.sl_note_poster
    SleepFace.UnderClock -> R.string.sl_note_under_clock
    SleepFace.Photo -> R.string.sl_note_photo
    SleepFace.Note -> R.string.sl_note_note
    SleepFace.ReturnCard -> R.string.sl_note_return
    SleepFace.Minimal -> R.string.sl_note_minimal
}

private fun weightName(w: Int) = when (w) {
    400 -> R.string.sl_w_regular
    500 -> R.string.sl_w_medium
    600 -> R.string.sl_w_semibold
    700 -> R.string.sl_w_bold
    800 -> R.string.sl_w_heavy
    else -> R.string.sl_w_black
}

private fun accentName(a: SleepAccent) = when (a) {
    SleepAccent.Green -> R.string.sl_ac_green
    SleepAccent.Red -> R.string.sl_ac_red
    SleepAccent.Blue -> R.string.sl_ac_blue
    SleepAccent.Orange -> R.string.sl_ac_orange
    SleepAccent.Yellow -> R.string.sl_ac_yellow
    SleepAccent.Ink -> R.string.sl_ac_ink
}
