package app.booxultimatum.nib.diag

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabTest {
    @Test fun everyLabProbeAsksSomethingOrShowsIt() {
        for (p in LabProbe.entries) {
            if (p != LabProbe.Geometry) assertTrue(p.questions.isNotEmpty(), p.id)
            for (q in p.questions) {
                assertTrue(q.answers.size >= 2, "${p.id} ${q.key}")
                assertEquals(q.answers.size, q.answers.distinct().size, "${p.id} ${q.key}")
            }
            assertEquals(p.questions.size, p.questions.map { it.key }.distinct().size, p.id)
            p.surface?.let { assertTrue(it.lab, "${p.id} draws on a Lab surface") }
        }
        assertEquals(Probe.entries.filter { it.lab }.toSet(), LabProbe.entries.mapNotNull { it.surface }.toSet(), "every Lab surface has its page")
    }

    @Test fun theTabletsMatrixInvertsToTheMeasuredMapping() {
        // Portrait on the Note Air6 C: panel = (y, 1860 - x).
        val m = floatArrayOf(0f, -1f, 1860f, 1f, 0f, 0f, 0f, 0f, 1f, 0.119f, 0.119f)
        val inv = assertNotNull(PanelGeometry.invert(m))
        assertContentEquals(floatArrayOf(2480f, 1860f), PanelGeometry.apply(inv, 0f, 2480f))
        assertContentEquals(floatArrayOf(0f, 1860f), PanelGeometry.apply(inv, 0f, 0f))
        assertContentEquals(floatArrayOf(0f, 0f), PanelGeometry.apply(inv, 1860f, 0f))
        val back = PanelGeometry.apply(m, 100f, 200f)
        assertTrue(PanelGeometry.agree(PanelGeometry.apply(inv, back[0], back[1]), floatArrayOf(100f, 200f)))
    }

    @Test fun aFlatMatrixDoesNotInvert() {
        assertNull(PanelGeometry.invert(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)))
        assertNull(PanelGeometry.invert(floatArrayOf(1f, 0f)))
    }

    @Test fun cornersGoRoundTheScreen() {
        val c = PanelGeometry.corners(1860f, 2480f)
        assertEquals(listOf(listOf(0f, 0f), listOf(1860f, 0f), listOf(1860f, 2480f), listOf(0f, 2480f)), c.map { it.toList() })
        assertFalse(PanelGeometry.agree(null, floatArrayOf(0f, 0f)))
        assertFalse(PanelGeometry.agree(floatArrayOf(0f, 0f), floatArrayOf(3f, 0f)))
    }
}
