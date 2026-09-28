package app.booxultimatum.core.sleep

import androidx.annotation.StringRes
import app.booxultimatum.R

/** One value a face option can take: its id as stored, and its label on the Sleep page. */
data class FaceChoice(val id: String, @param:StringRes val label: Int)

/**
 * A setting that belongs to one face, stored in [SleepFaceSpec.faceOptions] under [key]. A [multi] option holds a set
 * of ids, comma-separated in declaration order, and is offered as keys that switch on and off; any other option is
 * offered as one choice among its [values]. Unknown or missing values fall back to [default], so specs saved by older
 * versions keep working.
 */
data class FaceOption(
    val key: String,
    @param:StringRes val label: Int,
    val values: List<FaceChoice>,
    val default: String,
    val multi: Boolean = false,
    @param:StringRes val note: Int? = null,
)

/** Every face's own options, declared once; the Sleep page lists them for the selected face and the faces read them. */
object FaceOptions {
    private fun c(id: String, @StringRes label: Int) = FaceChoice(id, label)
    private val hours = listOf(c("system", R.string.so_hours_system), c("12", R.string.so_hours_12), c("24", R.string.so_hours_24))
    private val onOff = listOf(c("on", R.string.so_on), c("off", R.string.so_off))

    val DIAL_STYLE = FaceOption(
        "dial.style", R.string.so_dial_style,
        listOf(c("braun", R.string.so_dial_braun), c("railway", R.string.so_dial_railway), c("numerals", R.string.so_dial_numerals), c("h24", R.string.so_dial_24)),
        "braun", note = R.string.so_dial_note,
    )
    val CLOCK_HOURS = FaceOption("clock.hours", R.string.so_hours, hours, "system")
    val CUBE_SHADE = FaceOption("cube.shade", R.string.so_cube_shade, listOf(c("dots", R.string.so_shade_dots), c("lines", R.string.so_shade_lines)), "dots")
    val CUBE_HOURS = FaceOption("cube.hours", R.string.so_hours, hours, "system")
    val FLIP_HOURS = FaceOption("flip.hours", R.string.so_hours, hours, "system")
    val DASH_TILES = FaceOption(
        "dash.tiles", R.string.so_dash_tiles,
        listOf(
            c("battery", R.string.so_tile_battery), c("asleep", R.string.so_tile_asleep), c("alarm", R.string.so_tile_alarm),
            c("agenda", R.string.so_tile_agenda), c("day", R.string.so_tile_day), c("moon", R.string.so_tile_moon),
            c("weather", R.string.so_tile_weather), c("year", R.string.so_tile_year),
        ),
        "battery,asleep,alarm,agenda,day,moon,weather", multi = true, note = R.string.so_dash_note,
    )
    val DASH_HOURS = FaceOption("dash.hours", R.string.so_hours, hours, "system")
    val WORD_STYLE = FaceOption("word.style", R.string.so_word_style, listOf(c("grid", R.string.so_word_grid), c("words", R.string.so_word_alone)), "grid")
    val WORD_DOTS = FaceOption("word.dots", R.string.so_word_dots, onOff, "on", note = R.string.so_word_note)
    val RING_TOP = FaceOption("ring.top", R.string.so_ring_top, listOf(c("noon", R.string.so_ring_noon), c("midnight", R.string.so_ring_midnight)), "noon")
    val RING_NIGHT = FaceOption("ring.night", R.string.so_ring_night, onOff, "on", note = R.string.so_sun_note)
    val LINE_SPAN = FaceOption(
        "timeline.span", R.string.so_line_span,
        listOf(c("around", R.string.so_line_around), c("waking", R.string.so_line_waking), c("day", R.string.so_line_day)),
        "around",
    )
    val YEAR_LAYOUT = FaceOption(
        "year.layout", R.string.so_year_layout,
        listOf(c("months", R.string.so_year_months), c("rows", R.string.so_year_rows), c("weeks", R.string.so_year_weeks)),
        "months",
    )
    val LCD_SLANT = FaceOption("lcd.slant", R.string.so_lcd_slant, listOf(c("slanted", R.string.so_lcd_slanted), c("upright", R.string.so_lcd_upright)), "slanted")
    val LCD_GHOST = FaceOption("lcd.ghost", R.string.so_lcd_ghost, onOff, "on")
    val LCD_HOURS = FaceOption("lcd.hours", R.string.so_hours, hours, "system")
    val PAPER_LEAD = FaceOption("paper.lead", R.string.so_paper_lead, listOf(c("agenda", R.string.so_paper_agenda), c("quote", R.string.so_paper_quote)), "agenda", note = R.string.so_paper_note)

    fun of(face: SleepFace): List<FaceOption> = when (face) {
        SleepFace.Dial -> listOf(DIAL_STYLE)
        SleepFace.Clock -> listOf(CLOCK_HOURS)
        SleepFace.Cube -> listOf(CUBE_SHADE, CUBE_HOURS)
        SleepFace.Flip -> listOf(FLIP_HOURS)
        SleepFace.Dashboard -> listOf(DASH_TILES, DASH_HOURS)
        SleepFace.WordClock -> listOf(WORD_STYLE, WORD_DOTS)
        SleepFace.DayRing -> listOf(RING_TOP, RING_NIGHT)
        SleepFace.Timeline -> listOf(LINE_SPAN)
        SleepFace.Lcd -> listOf(LCD_SLANT, LCD_GHOST, LCD_HOURS)
        SleepFace.Broadsheet -> listOf(PAPER_LEAD)
        SleepFace.Year -> listOf(YEAR_LAYOUT)
        else -> emptyList()
    }
}
