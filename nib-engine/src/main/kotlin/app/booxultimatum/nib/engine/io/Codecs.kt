package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.brush.Preview
import app.booxultimatum.nib.engine.brush.PreviewColor
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.history.AddLayer
import app.booxultimatum.nib.engine.history.AddStroke
import app.booxultimatum.nib.engine.history.Batch
import app.booxultimatum.nib.engine.history.Clear
import app.booxultimatum.nib.engine.history.Command
import app.booxultimatum.nib.engine.history.InsertStrokes
import app.booxultimatum.nib.engine.history.MergeDown
import app.booxultimatum.nib.engine.history.MoveLayer
import app.booxultimatum.nib.engine.history.MoveStrokes
import app.booxultimatum.nib.engine.history.PlacedStroke
import app.booxultimatum.nib.engine.history.RemoveLayer
import app.booxultimatum.nib.engine.history.RemoveStrokes
import app.booxultimatum.nib.engine.history.ReplaceStrokes
import app.booxultimatum.nib.engine.history.SetLayerProps
import app.booxultimatum.nib.engine.history.TransformStrokes
import app.booxultimatum.nib.engine.input.PressureCurve

/**
 * Binary encodings shared by `.nib` files and the journal. Every record is a list of length-prefixed fields, so
 * readers skip fields they don't know; field numbers are part of the format and are never reused.
 */
internal object Codecs {
    // region Brushes

    fun writeBrush(w: ByteWriter, b: BrushSpec) {
        w.fieldString(1, b.kind.id)
        w.fieldFloat(2, b.width)
        w.field(3) {
            fieldFloat(1, b.curve.exponent)
            fieldFloat(2, b.curve.floor)
            fieldFloat(3, b.curve.ceiling)
        }
        w.fieldFloat(4, b.smoothing)
        w.fieldFloat(5, b.opacity)
        w.fieldVarint(6, b.blend.code.toLong())
        w.field(7) {
            fieldVarint(1, b.preview.style.code.toLong())
            fieldFloat(2, b.preview.widthFactor)
            fieldFloat(3, b.preview.minWidthPx)
            fieldVarint(4, previewColorCode(b.preview.color).toLong())
            fieldVarint(5, b.preview.alpha.toLong())
        }
        w.fieldFloat(8, b.spacing)
        w.fieldFloat(9, b.flow)
        w.fieldFloat(10, b.grain)
        w.fieldFloat(11, b.jitter)
        w.fieldFloat(12, b.pressureFlow)
        w.fieldFloat(13, b.nibAngle)
        w.fieldBool(14, b.nibFromOrientation)
        w.fieldFloat(15, b.minRatio)
        w.fieldFloat(16, b.speedInfluence)
        w.fieldFloat(17, b.taper)
        w.fieldFloat(18, b.dashOn)
        w.fieldFloat(19, b.dashOff)
    }

    /** Reads a brush; a brush kind this version doesn't know becomes a fineliner so its ink still shows. */
    fun readBrush(r: ByteReader): BrushSpec {
        var kind: BrushKind? = null
        var width: Float? = null
        var curve: PressureCurve? = null
        var smoothing: Float? = null
        var opacity: Float? = null
        var blend: Blend? = null
        var preview: Preview? = null
        var spacing: Float? = null
        var flow: Float? = null
        var grain: Float? = null
        var jitter: Float? = null
        var pressureFlow: Float? = null
        var nibAngle: Float? = null
        var nibFromOrientation: Boolean? = null
        var minRatio: Float? = null
        var speedInfluence: Float? = null
        var taper: Float? = null
        var dashOn: Float? = null
        var dashOff: Float? = null
        r.fields { tag, f ->
            when (tag) {
                1 -> kind = BrushKind.fromId(f.string()) ?: BrushKind.Fineliner
                2 -> width = f.float()
                3 -> curve = readCurve(f)
                4 -> smoothing = f.float()
                5 -> opacity = f.float()
                6 -> blend = Blend.fromCode(f.count())
                7 -> preview = readPreview(f)
                8 -> spacing = f.float()
                9 -> flow = f.float()
                10 -> grain = f.float()
                11 -> jitter = f.float()
                12 -> pressureFlow = f.float()
                13 -> nibAngle = f.float()
                14 -> nibFromOrientation = f.bool()
                15 -> minRatio = f.float()
                16 -> speedInfluence = f.float()
                17 -> taper = f.float()
                18 -> dashOn = f.float()
                19 -> dashOff = f.float()
            }
        }
        val base = BrushSpec.defaults(kind ?: BrushKind.Fineliner)
        return base.copy(
            width = width ?: base.width,
            curve = curve ?: base.curve,
            smoothing = smoothing ?: base.smoothing,
            opacity = opacity ?: base.opacity,
            blend = blend ?: base.blend,
            preview = preview ?: base.preview,
            spacing = spacing ?: base.spacing,
            flow = flow ?: base.flow,
            grain = grain ?: base.grain,
            jitter = jitter ?: base.jitter,
            pressureFlow = pressureFlow ?: base.pressureFlow,
            nibAngle = nibAngle ?: base.nibAngle,
            nibFromOrientation = nibFromOrientation ?: base.nibFromOrientation,
            minRatio = minRatio ?: base.minRatio,
            speedInfluence = speedInfluence ?: base.speedInfluence,
            taper = taper ?: base.taper,
            dashOn = dashOn ?: base.dashOn,
            dashOff = dashOff ?: base.dashOff,
        )
    }

    private fun readCurve(r: ByteReader): PressureCurve? {
        var e = 1f
        var lo = 0f
        var hi = 1f
        r.fields { tag, f ->
            when (tag) {
                1 -> e = f.float()
                2 -> lo = f.float()
                3 -> hi = f.float()
            }
        }
        val valid = e > 0f && e.isFinite() && lo >= 0f && hi >= 0f && lo.isFinite() && hi.isFinite()
        return if (valid) PressureCurve(e, lo, hi) else null
    }

    private fun readPreview(r: ByteReader): Preview? {
        var style = HardwareStyle.Fountain
        var factor = 1f
        var min = Preview.DEFAULT_MIN_WIDTH_PX
        var color = PreviewColor.Brush
        var alpha = -1
        r.fields { tag, f ->
            when (tag) {
                1 -> style = HardwareStyle.fromCode(f.count()) ?: HardwareStyle.Fountain
                2 -> factor = f.float()
                3 -> min = f.float()
                4 -> color = previewColor(f.count())
                5 -> alpha = f.count()
            }
        }
        if (!(factor >= 0f) || !(min >= 0f)) return null
        val a = when {
            style != HardwareStyle.Marker -> 255
            alpha in 0..255 -> alpha
            else -> Preview.MARKER_ALPHA
        }
        return Preview(style, factor, min, color, a)
    }

    private fun previewColorCode(c: PreviewColor): Int = when (c) {
        PreviewColor.Black -> 0
        PreviewColor.Brush -> 1
        PreviewColor.White -> 2
    }

    private fun previewColor(code: Int): PreviewColor = when (code) {
        0 -> PreviewColor.Black
        2 -> PreviewColor.White
        else -> PreviewColor.Brush
    }

    // endregion

    // region Points and strokes

    private const val HAS_TILT = 1
    private const val HAS_ORIENTATION = 2
    private const val HAS_TIME = 4

    fun writePoints(w: ByteWriter, p: PackedPoints) {
        val n = p.size
        var flags = 0
        for (i in 0 until n) {
            if (p.rawTilt(i) != 0) flags = flags or HAS_TILT
            if (p.rawOrientation(i) != 0) flags = flags or HAS_ORIENTATION
            if (p.deltaMillis(i) != 0) flags = flags or HAS_TIME
        }
        w.varint(n)
        w.varint(flags)
        var px = 0
        var py = 0
        for (i in 0 until n) {
            val x = p.rawX(i)
            val y = p.rawY(i)
            w.svarint((x - px).toLong())
            w.svarint((y - py).toLong())
            px = x
            py = y
        }
        var pp = 0
        for (i in 0 until n) {
            val v = p.rawPressure(i)
            w.svarint((v - pp).toLong())
            pp = v
        }
        if (flags and HAS_TILT != 0) for (i in 0 until n) w.byte(p.rawTilt(i))
        if (flags and HAS_ORIENTATION != 0) for (i in 0 until n) w.byte(p.rawOrientation(i))
        if (flags and HAS_TIME != 0) for (i in 0 until n) w.varint(p.deltaMillis(i))
    }

    fun readPoints(r: ByteReader): PackedPoints {
        val n = r.count()
        // Every point takes at least three bytes, which bounds what a corrupt count can allocate.
        if (n > r.remaining) throw FormatException("point count $n exceeds the data")
        val flags = r.count()
        val xs = IntArray(n)
        val ys = IntArray(n)
        var x = 0L
        var y = 0L
        for (i in 0 until n) {
            x += r.svarint()
            y += r.svarint()
            if (x !in Int.MIN_VALUE..Int.MAX_VALUE || y !in Int.MIN_VALUE..Int.MAX_VALUE) throw FormatException("bad coordinate")
            xs[i] = x.toInt()
            ys[i] = y.toInt()
        }
        val ps = IntArray(n)
        var p = 0L
        for (i in 0 until n) {
            p += r.svarint()
            if (p !in 0L..65535L) throw FormatException("bad pressure")
            ps[i] = p.toInt()
        }
        val ts = IntArray(n)
        if (flags and HAS_TILT != 0) for (i in 0 until n) ts[i] = r.byte()
        val os = IntArray(n)
        if (flags and HAS_ORIENTATION != 0) for (i in 0 until n) os[i] = r.byte().toByte().toInt()
        val ds = IntArray(n)
        if (flags and HAS_TIME != 0) for (i in 0 until n) ds[i] = r.count().coerceAtMost(65535)
        return PackedPoints.fromRaw(xs, ys, ps, ts, os, ds)
    }

    /** Writes a stroke, either pointing at [brushIndex] in a table or with its brush inline when it is -1. */
    fun writeStroke(w: ByteWriter, s: Stroke, brushIndex: Int = -1) {
        w.fieldVarint(1, s.id)
        w.fieldInt32(2, s.color)
        if (brushIndex >= 0) w.fieldVarint(3, brushIndex.toLong()) else w.field(5) { writeBrush(this, s.brush) }
        w.field(4) { writePoints(this, s.points) }
    }

    fun readStroke(r: ByteReader, brushes: List<BrushSpec> = emptyList()): Stroke {
        var id: Long? = null
        var color = 0xFF000000.toInt()
        var brush: BrushSpec? = null
        var points: PackedPoints? = null
        r.fields { tag, f ->
            when (tag) {
                1 -> id = f.varint()
                2 -> color = f.int32()
                3 -> brush = brushes.getOrNull(f.count()) ?: throw FormatException("unknown brush")
                4 -> points = readPoints(f)
                5 -> brush = readBrush(f)
            }
        }
        return Stroke(
            id ?: throw FormatException("stroke without id"),
            brush ?: throw FormatException("stroke without brush"),
            color,
            points ?: PackedPoints.EMPTY,
        )
    }

    // endregion

    // region Layers

    fun writeLayerProps(w: ByteWriter, l: Layer) {
        w.fieldVarint(1, l.id)
        w.fieldString(2, l.name)
        w.fieldBool(3, l.visible)
        w.fieldBool(4, l.locked)
        w.fieldFloat(5, l.opacity)
        w.fieldVarint(6, l.blend.code.toLong())
        w.fieldBool(7, l.alphaLock)
    }

    /** A layer's properties from its fields; [extra] sees the fields that aren't properties (tags 8 and up). */
    inline fun readLayerProps(r: ByteReader, extra: (tag: Int, f: ByteReader) -> Unit): Layer {
        var id: Long? = null
        var name = ""
        var visible = true
        var locked = false
        var opacity = 1f
        var blend = Blend.Normal
        var alphaLock = false
        r.fields { tag, f ->
            when (tag) {
                1 -> id = f.varint()
                2 -> name = f.string()
                3 -> visible = f.bool()
                4 -> locked = f.bool()
                5 -> opacity = f.float().let { if (it.isNaN()) 1f else it.coerceIn(0f, 1f) }
                6 -> blend = Blend.fromCode(f.count())
                7 -> alphaLock = f.bool()
                else -> extra(tag, f)
            }
        }
        return Layer(id ?: throw FormatException("layer without id"), name, visible, locked, opacity, blend, alphaLock)
    }

    /** A whole layer with its strokes inline, for the journal. */
    fun writeLayer(w: ByteWriter, l: Layer) {
        writeLayerProps(w, l)
        for (s in l.strokes) w.field(20) { writeStroke(this, s) }
    }

    fun readLayer(r: ByteReader): Layer {
        val strokes = ArrayList<Stroke>()
        val layer = readLayerProps(r) { tag, f -> if (tag == 20) strokes.add(readStroke(f)) }
        return layer.copy(strokes = strokes)
    }

    // endregion

    // region Commands

    private const val ADD_STROKE = 1
    private const val INSERT_STROKES = 2
    private const val REMOVE_STROKES = 3
    private const val REPLACE_STROKES = 4
    private const val TRANSFORM_STROKES = 5
    private const val MOVE_STROKES = 6
    private const val ADD_LAYER = 7
    private const val REMOVE_LAYER = 8
    private const val MOVE_LAYER = 9
    private const val SET_LAYER_PROPS = 10
    private const val MERGE_DOWN = 11
    private const val CLEAR = 12
    private const val BATCH = 13

    /** Writes the command's type byte and then its fields. */
    fun writeCommand(w: ByteWriter, c: Command) {
        when (c) {
            is AddStroke -> {
                w.byte(ADD_STROKE)
                w.fieldVarint(1, c.layerId)
                w.field(2) { writeStroke(this, c.stroke) }
            }
            is InsertStrokes -> {
                w.byte(INSERT_STROKES)
                w.fieldVarint(1, c.layerId)
                for (p in c.strokes) {
                    w.field(2) {
                        fieldVarint(1, p.index.toLong())
                        field(2) { writeStroke(this, p.stroke) }
                    }
                }
            }
            is RemoveStrokes -> {
                w.byte(REMOVE_STROKES)
                w.fieldVarint(1, c.layerId)
                w.field(2) { writeIds(this, c.ids) }
            }
            is ReplaceStrokes -> {
                w.byte(REPLACE_STROKES)
                w.fieldVarint(1, c.layerId)
                for (s in c.strokes) w.field(2) { writeStroke(this, s) }
            }
            is TransformStrokes -> {
                w.byte(TRANSFORM_STROKES)
                w.fieldVarint(1, c.layerId)
                w.field(2) { writeIds(this, c.ids) }
                w.field(3) {
                    val a = c.affine
                    for (v in floatArrayOf(a.scaleX, a.skewX, a.transX, a.skewY, a.scaleY, a.transY)) float(v)
                }
            }
            is MoveStrokes -> {
                w.byte(MOVE_STROKES)
                w.fieldVarint(1, c.fromLayer)
                w.fieldVarint(2, c.toLayer)
                w.field(3) { writeIds(this, c.ids) }
            }
            is AddLayer -> {
                w.byte(ADD_LAYER)
                w.field(1) { writeLayer(this, c.layer) }
                w.fieldVarint(2, c.index.toLong())
            }
            is RemoveLayer -> {
                w.byte(REMOVE_LAYER)
                w.fieldVarint(1, c.layerId)
                w.fieldBool(2, c.evenIfLocked)
            }
            is MoveLayer -> {
                w.byte(MOVE_LAYER)
                w.fieldVarint(1, c.layerId)
                w.fieldVarint(2, c.toIndex.toLong())
            }
            is SetLayerProps -> {
                w.byte(SET_LAYER_PROPS)
                w.fieldVarint(1, c.layerId)
                c.name?.let { w.fieldString(2, it) }
                c.visible?.let { w.fieldBool(3, it) }
                c.locked?.let { w.fieldBool(4, it) }
                c.opacity?.let { w.fieldFloat(5, it) }
                c.blend?.let { w.fieldVarint(6, it.code.toLong()) }
                c.alphaLock?.let { w.fieldBool(7, it) }
            }
            is MergeDown -> {
                w.byte(MERGE_DOWN)
                w.fieldVarint(1, c.layerId)
            }
            is Clear -> {
                w.byte(CLEAR)
                w.fieldVarint(1, c.layerId)
            }
            is Batch -> {
                w.byte(BATCH)
                for (sub in c.commands) w.field(1) { writeCommand(this, sub) }
            }
        }
    }

    fun readCommand(r: ByteReader): Command {
        val type = r.byte()
        var layerId: Long? = null
        return when (type) {
            ADD_STROKE -> {
                var stroke: Stroke? = null
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> stroke = readStroke(f)
                    }
                }
                AddStroke(need(layerId), need(stroke))
            }
            INSERT_STROKES -> {
                val placed = ArrayList<PlacedStroke>()
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> {
                            var index: Int? = null
                            var stroke: Stroke? = null
                            f.fields { t, g ->
                                when (t) {
                                    1 -> index = g.count()
                                    2 -> stroke = readStroke(g)
                                }
                            }
                            placed.add(PlacedStroke(need(index), need(stroke)))
                        }
                    }
                }
                InsertStrokes(need(layerId), placed)
            }
            REMOVE_STROKES -> {
                var ids: List<Long> = emptyList()
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> ids = readIds(f)
                    }
                }
                RemoveStrokes(need(layerId), ids)
            }
            REPLACE_STROKES -> {
                val strokes = ArrayList<Stroke>()
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> strokes.add(readStroke(f))
                    }
                }
                ReplaceStrokes(need(layerId), strokes)
            }
            TRANSFORM_STROKES -> {
                var ids: List<Long> = emptyList()
                var affine: Affine? = null
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> ids = readIds(f)
                        3 -> affine = Affine(f.float(), f.float(), f.float(), f.float(), f.float(), f.float())
                    }
                }
                TransformStrokes(need(layerId), ids, need(affine))
            }
            MOVE_STROKES -> {
                var to: Long? = null
                var ids: List<Long> = emptyList()
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> to = f.varint()
                        3 -> ids = readIds(f)
                    }
                }
                MoveStrokes(need(layerId), need(to), ids)
            }
            ADD_LAYER -> {
                var layer: Layer? = null
                var index: Int? = null
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layer = readLayer(f)
                        2 -> index = f.count()
                    }
                }
                AddLayer(need(layer), need(index))
            }
            REMOVE_LAYER -> {
                var force = false
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> force = f.bool()
                    }
                }
                RemoveLayer(need(layerId), force)
            }
            MOVE_LAYER -> {
                var index: Int? = null
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> index = f.count()
                    }
                }
                MoveLayer(need(layerId), need(index))
            }
            SET_LAYER_PROPS -> {
                var name: String? = null
                var visible: Boolean? = null
                var locked: Boolean? = null
                var opacity: Float? = null
                var blend: Blend? = null
                var alphaLock: Boolean? = null
                r.fields { tag, f ->
                    when (tag) {
                        1 -> layerId = f.varint()
                        2 -> name = f.string()
                        3 -> visible = f.bool()
                        4 -> locked = f.bool()
                        5 -> opacity = f.float()
                        6 -> blend = Blend.fromCode(f.count())
                        7 -> alphaLock = f.bool()
                    }
                }
                SetLayerProps(need(layerId), name, visible, locked, opacity, blend, alphaLock)
            }
            MERGE_DOWN, CLEAR -> {
                r.fields { tag, f -> if (tag == 1) layerId = f.varint() }
                if (type == MERGE_DOWN) MergeDown(need(layerId)) else Clear(need(layerId))
            }
            BATCH -> {
                val commands = ArrayList<Command>()
                r.fields { tag, f -> if (tag == 1) commands.add(readCommand(f)) }
                Batch(commands)
            }
            else -> throw FormatException("unknown command type $type")
        }
    }

    private fun writeIds(w: ByteWriter, ids: List<Long>) {
        w.varint(ids.size)
        var prev = 0L
        for (id in ids) {
            w.svarint(id - prev)
            prev = id
        }
    }

    private fun readIds(r: ByteReader): List<Long> {
        val n = r.count()
        if (n > r.remaining) throw FormatException("id count $n exceeds the data")
        val out = ArrayList<Long>(n)
        var prev = 0L
        for (i in 0 until n) {
            prev += r.svarint()
            out.add(prev)
        }
        return out
    }

    private fun <T : Any> need(v: T?): T = v ?: throw FormatException("missing field")

    // endregion
}
