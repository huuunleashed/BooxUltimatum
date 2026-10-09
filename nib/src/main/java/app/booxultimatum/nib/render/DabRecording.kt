package app.booxultimatum.nib.render

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.render.Cap
import app.booxultimatum.nib.engine.render.RenderSink
import app.booxultimatum.nib.engine.render.Texture

/**
 * Records one dab or stipple stroke's calls so each tile can replay only the stamps that land on it. A textured stroke is a
 * thousand dabs or more, and a stipple stroke as many stamps; played whole into each of the tiles it crosses, it cost
 * tens of milliseconds per commit (a long pencil stroke, 150 ms on the Note Air6 C). Stipple is replayed as stamps, so
 * each tile still works its dots out on its own pixels.
 * Anything but dabs inside at most one group marks the recording [general], and the caller falls back to replaying
 * the whole stroke. Single use, one thread.
 */
class DabRecording : RenderSink {
    /** The stroke made calls other than dabs in one group; replay it whole instead. */
    var general = false
        private set

    private var depth = 0
    private var grouped = false
    private var groupAlpha = 1f
    private var groupBlend = Blend.Normal

    // A stroke has one brush, so one dot size for all its stamps.
    private var stippleDot = 1f

    private var n = 0
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var rs = FloatArray(256)
    private var angles = FloatArray(256)
    private var alphas = FloatArray(256)
    private var colors = IntArray(256)
    private var stippled = BooleanArray(256)
    private val textures = ArrayList<Texture>(256)
    private val blends = ArrayList<Blend>(256)

    val size: Int get() = n

    override fun beginGroup(alpha: Float, blend: Blend) {
        if (grouped || n > 0) general = true
        depth++
        grouped = true
        groupAlpha = alpha
        groupBlend = blend
    }

    override fun endGroup() {
        depth--
    }

    override fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend) {
        general = true
    }

    override fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend) {
        general = true
    }

    override fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend) {
        general = true
    }

    override fun stipple(x: Float, y: Float, radius: Float, density: Float, color: Int, blend: Blend, dot: Float) {
        stippleDot = dot
        record(x, y, radius, 0f, color, density, Texture.Solid, blend, stipple = true)
    }

    override fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        record(x, y, radius, angle, color, alpha, texture, blend, stipple = false)
    }

    private fun record(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend, stipple: Boolean) {
        if (depth == 0 && grouped) general = true
        if (n == xs.size) grow()
        xs[n] = x
        ys[n] = y
        rs[n] = radius
        angles[n] = angle
        alphas[n] = alpha
        colors[n] = color
        stippled[n] = stipple
        textures.add(texture)
        blends.add(blend)
        n++
    }

    /** Replays, in order, the dabs that reach [box] (document pixels, with an antialiasing margin) into [sink]. */
    fun replay(sink: RenderSink, box: Box): Int {
        var played = 0
        var open = false
        // Consecutive stipple stamps of one colour and blend go to the sink as one run, as the stroke itself sends them.
        var run = 0
        var runColor = 0
        var runBlend = Blend.Normal
        fun flush() {
            if (run > 0) sink.stippleRun(runXs, runYs, runRs, runDs, run, runColor, runBlend, stippleDot)
            run = 0
        }
        for (i in 0 until n) {
            val r = rs[i] + 1f
            if (xs[i] + r < box.left || xs[i] - r > box.right || ys[i] + r < box.top || ys[i] - r > box.bottom) continue
            if (grouped && !open) {
                sink.beginGroup(groupAlpha, groupBlend)
                open = true
            }
            if (stippled[i]) {
                if (run > 0 && (colors[i] != runColor || blends[i] != runBlend)) flush()
                if (run == runXs.size) growRun()
                runXs[run] = xs[i]
                runYs[run] = ys[i]
                runRs[run] = rs[i]
                runDs[run] = alphas[i]
                runColor = colors[i]
                runBlend = blends[i]
                run++
            } else {
                flush()
                sink.dab(xs[i], ys[i], rs[i], angles[i], colors[i], alphas[i], textures[i], blends[i])
            }
            played++
        }
        flush()
        if (open) sink.endGroup()
        return played
    }

    private var runXs = FloatArray(0)
    private var runYs = FloatArray(0)
    private var runRs = FloatArray(0)
    private var runDs = FloatArray(0)

    private fun growRun() {
        val s = maxOf(64, runXs.size * 2)
        runXs = runXs.copyOf(s)
        runYs = runYs.copyOf(s)
        runRs = runRs.copyOf(s)
        runDs = runDs.copyOf(s)
    }

    private fun grow() {
        val s = xs.size * 2
        xs = xs.copyOf(s)
        ys = ys.copyOf(s)
        rs = rs.copyOf(s)
        angles = angles.copyOf(s)
        alphas = alphas.copyOf(s)
        colors = colors.copyOf(s)
        stippled = stippled.copyOf(s)
    }
}
