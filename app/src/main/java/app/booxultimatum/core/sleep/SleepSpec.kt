package app.booxultimatum.core.sleep

import android.content.Context
import androidx.core.content.edit
import app.booxultimatum.core.Tier
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the face reaches the sleep screen. [Image] hands Onyx a file of ours through its own screensaver broadcast (the
 * app sends it, the shell repeats it when Shizuku runs). [Overlay] rewrites the Lockscreen Sticker that the Boox
 * Transparent style lays over its snapshot of the screen, which only the shell uid may write.
 */
enum class SleepMode(val tier: Tier) { Image(Tier.T0), Overlay(Tier.T2) }

/** What a face may show besides its own subject. Each face lists the ones it can typeset well. */
enum class SleepElement { Date, Battery, PutDown, Agenda, Note, Owner, Quote, Asleep }

/**
 * Full-screen faces for [SleepMode.Image]. Every one has its own portrait and landscape composition. The live ones
 * come first ([live]): they read the time now, how long the tablet has slept and what the battery has done since, and
 * are made for updates while asleep. Without live updates they show the moment the tablet was put down. Settings that
 * belong to one face only are declared in [FaceOptions].
 */
enum class SleepFace(val options: Set<SleepElement>, val live: Boolean = false) {
    Dial(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Clock(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Monitor(setOf(SleepElement.Agenda, SleepElement.Owner), live = true),
    Cube(setOf(SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Flip(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Dashboard(emptySet(), live = true),
    WordClock(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep), live = true),
    DayRing(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Timeline(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Lcd(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda), live = true),
    Sky(setOf(SleepElement.Date), live = true),
    Broadsheet(setOf(SleepElement.Battery, SleepElement.Asleep, SleepElement.Agenda, SleepElement.Quote), live = true),
    Almanac(setOf(SleepElement.PutDown, SleepElement.Battery, SleepElement.Agenda)),
    Year(setOf(SleepElement.PutDown, SleepElement.Battery)),
    Instrument(setOf(SleepElement.PutDown, SleepElement.Agenda, SleepElement.Owner)),
    Poster(setOf(SleepElement.PutDown, SleepElement.Battery, SleepElement.Quote)),
    UnderClock(setOf(SleepElement.Battery, SleepElement.PutDown, SleepElement.Agenda, SleepElement.Note, SleepElement.Owner)),
    Photo(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.PutDown)),
    Note(setOf(SleepElement.Date, SleepElement.PutDown, SleepElement.Battery)),
    ReturnCard(setOf(SleepElement.Date, SleepElement.PutDown)),
    Minimal(setOf(SleepElement.Battery, SleepElement.PutDown)),
    ;

    /** The Boox clock sits over the top of the image; this face is composed around it and always leaves it free. */
    val keepsClockRoom get() = this == UnderClock

    /** Reads the calendar, with or without the Next events element (the Dashboard chooses its tiles itself). */
    val usesCalendar get() = SleepElement.Agenda in options || this == Dashboard

    /** Draws today as a whole: the events already past as well as those to come. */
    val drawsDay get() = this == DayRing || this == Timeline || this == Broadsheet

    /** Shows the launcher's cached weather, the sun or the moon. */
    val readsSky get() = this == Dashboard || this == DayRing || this == Timeline || this == Sky || this == Broadsheet
}

/** Plates for [SleepMode.Overlay]: paper on a transparent sheet, legible over any screenshot. */
enum class SleepOverlay(val options: Set<SleepElement>) {
    BottomBand(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.PutDown, SleepElement.Agenda, SleepElement.Owner)),
    TopBand(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.PutDown, SleepElement.Agenda, SleepElement.Owner)),
    Corner(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.PutDown, SleepElement.Agenda)),
    Centre(setOf(SleepElement.Date, SleepElement.Battery, SleepElement.PutDown, SleepElement.Agenda, SleepElement.Note, SleepElement.Owner, SleepElement.Quote)),
}

enum class SleepInk { Paper, Inverted }

/** Kaleido shows saturated areas best, so the accent fills shapes (lamp, today, needle head) and never carries text. */
enum class SleepAccent(val argb: Long) {
    Green(0xFF00A651), Red(0xFFE53935), Blue(0xFF1E63D6), Orange(0xFFF57C00), Yellow(0xFFFFC400), Ink(0),
}

enum class SleepFont { System, Archivo, File }

enum class PhotoFit { Fill, Fit }

/** Everything the user chose. Persisted as JSON so it survives rotation, process death and updates. */
data class SleepFaceSpec(
    val active: Boolean = false,
    val mode: SleepMode = SleepMode.Image,
    val face: SleepFace = SleepFace.Dial,
    val overlay: SleepOverlay = SleepOverlay.BottomBand,
    val elements: Set<SleepElement> = setOf(SleepElement.Date, SleepElement.Battery, SleepElement.PutDown, SleepElement.Agenda, SleepElement.Quote, SleepElement.Asleep),
    val font: SleepFont = SleepFont.System,
    val fontFile: String? = null,
    val displayWeight: Int = 800,
    val bodyWeight: Int = 500,
    val ink: SleepInk = SleepInk.Paper,
    val accent: SleepAccent = SleepAccent.Green,
    val intervalMin: Int = 5,
    val clockRoom: Boolean = false,
    val note: String = "",
    val ownerName: String = "",
    val ownerContact: String = "",
    val reward: String = "",
    val useOwnQuote: Boolean = false,
    val ownQuote: String = "",
    val ownQuoteAuthor: String = "",
    val photoFit: PhotoFit = PhotoFit.Fill,
    val dither: Boolean = false,
    val caption: String = "",
    /** Settings that belong to one face, keyed like "dial.style"; see [FaceOptions]. A missing key means the default. */
    val faceOptions: Map<String, String> = emptyMap(),
) {
    fun shows(e: SleepElement): Boolean = e in elements && e in options

    /** The value of a face's own option, or its default when unset or no longer offered. */
    fun option(o: FaceOption): String = faceOptions[o.key]?.takeIf { v -> o.multi || o.values.any { it.id == v } } ?: o.default

    /** The ids switched on in a [FaceOption.multi] option. */
    fun optionSet(o: FaceOption): Set<String> = option(o).split(',').filter { id -> o.values.any { it.id == id } }.toSet()

    fun withOption(o: FaceOption, value: String) = copy(faceOptions = faceOptions + (o.key to value))

    /** Switches one id of a [FaceOption.multi] option, keeping the declared order. */
    fun toggleOption(o: FaceOption, id: String): SleepFaceSpec {
        val on = optionSet(o).let { if (id in it) it - id else it + id }
        return withOption(o, o.values.map { it.id }.filter { it in on }.joinToString(","))
    }

    /** The elements the current face or plate can show; the UI offers toggles for these only. */
    val options: Set<SleepElement> get() = if (mode == SleepMode.Image) face.options else overlay.options

    /** The Boox clock zone is kept free: always for the face built around it, otherwise when the user asks. */
    val leavesClockRoom: Boolean get() = clockRoom || (mode == SleepMode.Image && face.keepsClockRoom)

    fun toJson(): JSONObject = JSONObject()
        .put("v", 3)
        .put("active", active).put("mode", mode.name).put("face", face.name).put("overlay", overlay.name)
        .put("elements", JSONArray(elements.map { it.name }))
        .put("font", font.name).put("fontFile", fontFile ?: "")
        .put("displayWeight", displayWeight).put("bodyWeight", bodyWeight)
        .put("ink", ink.name).put("accent", accent.name).put("intervalMin", intervalMin).put("clockRoom", clockRoom)
        .put("note", note).put("ownerName", ownerName).put("ownerContact", ownerContact).put("reward", reward)
        .put("useOwnQuote", useOwnQuote).put("ownQuote", ownQuote).put("ownQuoteAuthor", ownQuoteAuthor)
        .put("photoFit", photoFit.name).put("dither", dither).put("caption", caption)
        // Sorted, so the same settings always give the same fingerprint.
        .put("faceOptions", JSONObject(faceOptions.toSortedMap() as Map<*, *>))

    companion object {
        val INTERVALS = listOf(1, 5, 15, 30)
        val DISPLAY_WEIGHTS = listOf(600, 700, 800, 900)
        val BODY_WEIGHTS = listOf(400, 500, 600)

        private inline fun <reified E : Enum<E>> JSONObject.enum(key: String, default: E): E =
            runCatching { enumValueOf<E>(getString(key)) }.getOrDefault(default)

        fun fromJson(o: JSONObject): SleepFaceSpec {
            val d = SleepFaceSpec()
            val els = o.optJSONArray("elements")?.let { a ->
                (0 until a.length()).mapNotNull { i -> runCatching { SleepElement.valueOf(a.getString(i)) }.getOrNull() }.toSet()
            } ?: d.elements
            return SleepFaceSpec(
                active = o.optBoolean("active", d.active),
                mode = o.enum("mode", d.mode),
                face = o.enum("face", d.face),
                overlay = o.enum("overlay", d.overlay),
                // Faces saved before the live elements existed get them on, as a new install would.
                elements = if (o.has("v")) els else els + SleepElement.Asleep,
                font = o.enum("font", d.font),
                fontFile = o.optString("fontFile").ifEmpty { null },
                displayWeight = o.optInt("displayWeight", d.displayWeight).coerceIn(600, 900),
                bodyWeight = o.optInt("bodyWeight", d.bodyWeight).coerceIn(400, 600),
                ink = o.enum("ink", d.ink),
                accent = o.enum("accent", d.accent),
                intervalMin = o.optInt("intervalMin", d.intervalMin).takeIf { it in INTERVALS } ?: d.intervalMin,
                clockRoom = o.optBoolean("clockRoom", d.clockRoom),
                note = o.optString("note"),
                ownerName = o.optString("ownerName"),
                ownerContact = o.optString("ownerContact"),
                reward = o.optString("reward"),
                useOwnQuote = o.optBoolean("useOwnQuote"),
                ownQuote = o.optString("ownQuote"),
                ownQuoteAuthor = o.optString("ownQuoteAuthor"),
                photoFit = o.enum("photoFit", d.photoFit),
                dither = o.optBoolean("dither"),
                caption = o.optString("caption"),
                // Specs saved before version 3 have none: every face then starts from its defaults.
                faceOptions = o.optJSONObject("faceOptions")?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: emptyMap(),
            )
        }
    }
}

/** The last publish, for the status line. [skipped] means the inputs were unchanged, so nothing was encoded or written. */
data class SleepStatus(
    val at: Long,
    val renderMs: Long,
    val encodeMs: Long,
    val bytes: Long,
    val file: String,
    val mode: SleepMode,
    val reason: String,
    val skipped: Boolean,
    val error: String?,
)

/** Private preferences for the studio: the spec, where our files are, and the last publish. */
object SleepStore {
    private fun prefs(c: Context) = c.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    fun load(c: Context): SleepFaceSpec =
        prefs(c).getString("spec", null)?.let { runCatching { SleepFaceSpec.fromJson(JSONObject(it)) }.getOrNull() } ?: SleepFaceSpec()

    fun save(c: Context, spec: SleepFaceSpec) {
        prefs(c).edit { putString("spec", spec.toJson().toString()) }
    }

    fun get(c: Context, key: String): String? = prefs(c).getString(key, null)

    fun put(c: Context, key: String, value: String?) {
        prefs(c).edit { if (value == null) remove(key) else putString(key, value) }
    }

    fun status(c: Context): SleepStatus? {
        val o = prefs(c).getString("status", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return SleepStatus(
            o.optLong("at"), o.optLong("renderMs"), o.optLong("encodeMs"), o.optLong("bytes"), o.optString("file"),
            runCatching { SleepMode.valueOf(o.optString("mode")) }.getOrDefault(SleepMode.Image),
            o.optString("reason"), o.optBoolean("skipped"), o.optString("error").ifEmpty { null },
        )
    }

    fun setStatus(c: Context, s: SleepStatus) {
        val o = JSONObject().put("at", s.at).put("renderMs", s.renderMs).put("encodeMs", s.encodeMs).put("bytes", s.bytes)
            .put("file", s.file).put("mode", s.mode.name).put("reason", s.reason).put("skipped", s.skipped).put("error", s.error ?: "")
        prefs(c).edit { putString("status", o.toString()) }
    }
}
