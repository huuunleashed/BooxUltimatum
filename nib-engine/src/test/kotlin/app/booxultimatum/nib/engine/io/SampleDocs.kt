package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.brush.Preview
import app.booxultimatum.nib.engine.brush.PreviewColor
import app.booxultimatum.nib.engine.brush.TiltResponse
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.nib.engine.scribble
import kotlin.random.Random

/** Documents with every kind of content, for the file tests. */
internal object SampleDocs {
    fun randomBrush(rnd: Random, kind: BrushKind): BrushSpec {
        val base = BrushSpec.defaults(kind)
        val style = HardwareStyle.entries[rnd.nextInt(HardwareStyle.entries.size)]
        return base.copy(
            width = base.widthRange.start + rnd.nextFloat() * (base.widthRange.endInclusive - base.widthRange.start),
            curve = PressureCurve(0.2f + rnd.nextFloat() * 3f, rnd.nextFloat() * 0.5f, 0.5f + rnd.nextFloat()),
            smoothing = rnd.nextFloat(),
            opacity = rnd.nextFloat(),
            blend = Blend.entries[rnd.nextInt(Blend.entries.size)],
            preview = Preview(
                style,
                rnd.nextFloat() * 2f,
                rnd.nextFloat() * 3f,
                PreviewColor.entries[rnd.nextInt(3)],
                if (style == HardwareStyle.Marker) rnd.nextInt(256) else 255,
            ),
            spacing = rnd.nextFloat(),
            flow = rnd.nextFloat(),
            grain = rnd.nextFloat(),
            jitter = rnd.nextFloat(),
            pressureFlow = rnd.nextFloat(),
            nibAngle = rnd.nextFloat() * 6f - 3f,
            nibFromOrientation = rnd.nextBoolean(),
            minRatio = rnd.nextFloat(),
            speedInfluence = rnd.nextFloat(),
            taper = rnd.nextFloat() * 3f,
            dashOn = rnd.nextFloat() * 5f,
            dashOff = rnd.nextFloat() * 5f,
            tiltScale = if (rnd.nextBoolean()) BrushSpec.NO_TILT else 1f + rnd.nextFloat() * 4f,
            tiltResponse = TiltResponse.entries[rnd.nextInt(TiltResponse.entries.size)],
            speedDamping = if (rnd.nextBoolean()) 0f else rnd.nextFloat() * 0.2f,
            minWidth = if (rnd.nextBoolean()) 0f else rnd.nextFloat() * 4f,
        )
    }

    fun rich(seed: Int, layers: Int = 6, strokesPerLayer: Int = 20): Document {
        val rnd = Random(seed)
        var id = 1L
        val list = (0 until layers).map {
            val layerId = id++
            val strokes = (0 until strokesPerLayer).map {
                val kind = BrushKind.entries[rnd.nextInt(16)]
                val s = scribble(rnd, id++, kind, 1 + rnd.nextInt(80), -50f, -50f, 1900f, 2500f, color = rnd.nextInt())
                if (rnd.nextBoolean()) s.copy(brush = randomBrush(rnd, kind)) else s
            }
            Layer(
                layerId,
                name = "Layer ${layerId} \u00e9\u00e0 \u270e",
                visible = rnd.nextBoolean(),
                locked = rnd.nextBoolean(),
                opacity = rnd.nextFloat(),
                blend = Blend.entries[rnd.nextInt(Blend.entries.size)],
                alphaLock = rnd.nextBoolean(),
                strokes = strokes,
            )
        }
        return Document("doc-$seed", 1860, 2480, background = rnd.nextInt(), layers = list, nextId = id + 5)
    }

    fun longStroke(id: Long, points: Int): Stroke =
        scribble(Random(id), id, BrushKind.Fountain, points, 0f, 0f, 1860f, 2480f, step = 2f)
}
