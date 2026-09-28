package app.booxultimatum.nib.render

import android.graphics.Canvas
import android.graphics.Paint
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.store.Guides
import app.booxultimatum.nib.store.Paper
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The page's guides, drawn under the ink in document pixels on a canvas already mapped to the document. Only the
 * guides inside [visible] are drawn, so a zoomed-in view costs no more than a fitted one.
 */
object GuidesPainter {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Draws [paper]'s guides over a [docW] by [docH] page. [lineWidth] is in document pixels; 0 draws hairlines one
     * screen pixel wide at any zoom.
     */
    fun draw(canvas: Canvas, paper: Paper, docW: Int, docH: Int, visible: Box, lineWidth: Float) {
        if (paper.guides == Guides.None) return
        val step = paper.spacing.coerceIn(Paper.SPACING_RANGE.start, Paper.SPACING_RANGE.endInclusive)
        val area = visible.intersect(Box(0f, 0f, docW.toFloat(), docH.toFloat()))
        if (area.isEmpty) return
        val x0 = max(1, ceil(area.left / step).toInt())
        val x1 = min(floor((docW - 0.01f) / step).toInt(), floor(area.right / step).toInt())
        val y0 = max(1, ceil(area.top / step).toInt())
        val y1 = min(floor((docH - 0.01f) / step).toInt(), floor(area.bottom / step).toInt())
        synchronized(this) {
            line.color = paper.guideColour
            line.strokeWidth = lineWidth
            dot.color = paper.guideColour
            when (paper.guides) {
                Guides.Lines -> for (j in y0..y1) canvas.drawLine(0f, j * step, docW.toFloat(), j * step, line)
                Guides.Grid -> {
                    for (j in y0..y1) canvas.drawLine(0f, j * step, docW.toFloat(), j * step, line)
                    for (i in x0..x1) canvas.drawLine(i * step, 0f, i * step, docH.toFloat(), line)
                }
                Guides.Dots -> {
                    if (x1 < x0 || y1 < y0) return
                    dot.strokeWidth = max(lineWidth * 2.5f, step / 18f)
                    val pts = FloatArray((x1 - x0 + 1) * 2)
                    for (j in y0..y1) {
                        var k = 0
                        for (i in x0..x1) {
                            pts[k++] = i * step
                            pts[k++] = j * step
                        }
                        canvas.drawPoints(pts, 0, k, dot)
                    }
                }
                Guides.None -> Unit
            }
        }
    }
}