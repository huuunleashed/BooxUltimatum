package app.booxultimatum.nib.engine.record

import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.input.Tool

/** Rebuilds strokes from a recording, the way the app builds them live. */
object PenReplay {
    /**
     * One stroke per Down..Up run of a tool in [tools], built with [brush] and [color] and numbered from [firstId]
     * without gaps. Cancelled runs are dropped; a run still open at the end of the recording is finished. [transform]
     * maps the recorded coordinates to document pixels.
     */
    fun strokes(
        recording: PenRecording,
        brush: BrushSpec,
        color: Int,
        firstId: Long = 1L,
        tools: Set<Tool> = setOf(Tool.Pen),
        transform: Affine = Affine.IDENTITY,
    ): List<Stroke> {
        val out = ArrayList<Stroke>()
        var id = firstId
        var builder: StrokeBuilder? = null
        for (e in recording.events) {
            if (e !is PenEvent.Sample || e.tool !in tools) continue
            val s = if (transform.isIdentity) e.sample else mapped(e.sample, transform)
            when (e.action) {
                PenAction.Down -> {
                    builder?.let {
                        out.add(it.finish())
                        id++
                    }
                    builder = StrokeBuilder(brush, color, id).also { it.add(s) }
                }
                PenAction.Move -> builder?.add(s)
                PenAction.Up -> {
                    builder?.let {
                        it.add(s)
                        out.add(it.finish())
                        id++
                    }
                    builder = null
                }
                PenAction.Cancel -> builder = null
                PenAction.HoverEnter, PenAction.HoverMove, PenAction.HoverExit -> Unit
            }
        }
        builder?.let { out.add(it.finish()) }
        return out
    }

    private fun mapped(s: InputSample, t: Affine): InputSample = s.copy(x = t.mapX(s.x, s.y), y = t.mapY(s.x, s.y))
}
