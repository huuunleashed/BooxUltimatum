package app.booxultimatum.nib.pen

import android.content.SharedPreferences
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.input.PressureCurve
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Just enough SharedPreferences for PreviewMatch. */
private class MemoryPrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { removed += map.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { removed.forEach { map.remove(it) }; map.putAll(pending) }
    }
}

class PreviewMatchTest {
    private val prefs = MemoryPrefs()
    private val match = PreviewMatch(prefs)
    private val fountain = BrushSpec.defaults(BrushKind.Fountain)
    private val fineliner = BrushSpec.defaults(BrushKind.Fineliner)

    @Test fun aConstantWidthPreviewOfAPressureBrushIsSentAtItsUsualWidth() {
        val full = 10f
        val tuned = BrushSpec.defaults(BrushKind.GrainPencil).copy(curve = PressureCurve(1f, 0.35f, 1f))
        val sent = match.width(tuned, 0, full)
        assertEquals(full * tuned.curve.factor(PreviewMatch.DEFAULT_PRESSURE), sent, 0.001f)
        assertTrue(sent < full, "the pencil style can't thin, and at the full width it looked too wide")
        for (kind in listOf(BrushKind.GrainPencil, BrushKind.Graphite)) {
            assertEquals(full, match.width(BrushSpec.defaults(kind), 0, full), 0.001f, "$kind keeps its width whatever the pressure, as BOOX's pencil")
        }
    }

    @Test fun stylesThatApplyPressureThemselvesGetTheFullWidth() {
        assertEquals(10f, match.width(fountain, 1, 10f), 0.001f, "the display thins the fountain by the same law as the engine")
        for (style in listOf(1, 2, 3)) assertTrue(PreviewMatch.followsPressure(style), "style $style")
        for (style in listOf(0, 4, 5, 6, 7)) assertFalse(PreviewMatch.followsPressure(style), "style $style")
        match.setFactor(1, 0.9f)
        assertEquals(9f, match.width(fountain, 1, 10f), 0.001f, "the owner's factor still applies")
        val calligraphy = BrushSpec.defaults(app.booxultimatum.nib.engine.brush.BrushKind.Calligraphy)
        assertEquals(10f * calligraphy.curve.factor(PreviewMatch.DEFAULT_PRESSURE), match.width(calligraphy, 7, 10f), 0.001f, "the square pen's preview doesn't thin, so it gets the usual width")
    }

    @Test fun aConstantWidthBrushKeepsItsWidth() {
        assertEquals(6f, match.width(fineliner, 1, 6f), 0.001f)
    }

    @Test fun itLearnsTheOwnersPressureSlowly() {
        repeat(40) { match.observe(0.9f, 40) }
        assertTrue(match.typicalPressure > 0.85f)
        assertEquals(match.typicalPressure, PreviewMatch(prefs).typicalPressure, "survives a restart")
        assertFalse(match.observe(0.9f, 3), "a dot teaches nothing")
    }

    @Test fun eachStyleCanBeCorrectedAndReset() {
        match.setFactor(1, 0.8f)
        assertEquals(0.8f, match.factor(1))
        assertEquals(1f, match.factor(0))
        assertEquals(10f * fineliner.curve.factor(0.5f) * 0.8f, match.width(fineliner, 1, 10f), 0.001f)
        match.setFactor(1, 9f)
        assertEquals(PreviewMatch.FACTOR_RANGE.endInclusive, match.factor(1), "clamped")
        match.reset()
        assertEquals(1f, match.factor(1))
        assertEquals(PreviewMatch.DEFAULT_PRESSURE, match.typicalPressure)
    }

    @Test fun neverThinnerThanTheBrushsThinnestPreview() {
        match.setFactor(1, 0.3f)
        assertEquals(fineliner.preview.minWidthPx, match.width(fineliner, 1, 0.5f))
    }

    @Test fun theFountainSizeSetBeforeItsInkMatchedStartsAgainOnce() {
        val old = MemoryPrefs()
        old.map["preview_factor_1"] = 0.6f
        old.map["preview_factor_0"] = 0.9f
        old.map["preview_typical_pressure"] = 0.44f
        val upgraded = PreviewMatch(old)
        assertEquals(1f, upgraded.factor(1), "the fountain style's size made up for ink 3 px thinner than its preview")
        assertEquals(0.9f, upgraded.factor(0), "other styles keep the owner's size")
        assertEquals(0.44f, upgraded.typicalPressure, "and the learnt pressure stays")
        upgraded.setFactor(1, 0.8f)
        assertEquals(0.8f, PreviewMatch(old).factor(1), "only once: a size set afterwards stays")
    }
}
