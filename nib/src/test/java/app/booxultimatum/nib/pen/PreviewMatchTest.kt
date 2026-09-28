package app.booxultimatum.nib.pen

import android.content.SharedPreferences
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
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

    @Test fun aPressureBrushIsPreviewedAtItsUsualWidthNotItsFullWidth() {
        val full = 10f
        val sent = match.width(fountain, 1, full)
        assertEquals(full * fountain.curve.factor(PreviewMatch.DEFAULT_PRESSURE), sent, 0.001f)
        assertTrue(sent < full, "the preview used to be sent at the full width and looked too wide")
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
}
