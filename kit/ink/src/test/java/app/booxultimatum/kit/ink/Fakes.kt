package app.booxultimatum.kit.ink

import app.booxultimatum.kit.ink.canvas.InkScheduler
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.session.InkDisplay

/** Records every display call, and plays SurfaceFlinger's part in the pen state and stroke parameters. */
class FakeInkDisplay(var routable: Boolean = true) : InkDisplay {
    val calls = mutableListOf<String>()
    var state = Epd.PenState.STOP
    val params = mutableMapOf(1 to floatArrayOf(0.3f, 0.6f), 4 to floatArrayOf(1f, 3f))

    override fun connect() = routable.also { calls += "connect" }
    override fun penState() = state
    override fun setPenState(state: Int): Boolean { calls += "state $state"; this.state = state; return true }
    override fun setRegionMode(mode: Epd.RegionMode): Boolean { calls += "mode ${mode.name}"; return true }
    override fun setLimit(rects: IntArray, screen: Boolean): Boolean { calls += "limit ${if (screen) "screen" else "panel"} ${rects.joinToString(",")}"; return true }
    override fun setExclude(rects: IntArray): Boolean { calls += "exclude ${rects.joinToString(",").ifEmpty { "none" }}"; return true }
    override fun setStroke(style: Int, widthPx: Float, argb: Int) { calls += "stroke $style $widthPx ${Integer.toHexString(argb)}" }
    override fun strokeParameters(style: Int) = params[style]?.copyOf().also { calls += "get params $style" }
    override fun setStrokeParameters(style: Int, params: FloatArray): Boolean { calls += "params $style ${params.joinToString(",")}"; this.params[style] = params.copyOf(); return true }
    override fun setEraserPreview(on: Boolean, style: Int): Boolean { calls += "eraser $on $style"; return true }
    override fun setPenButtons(rect: IntArray, buttons: List<Epd.ButtonStroke>): Boolean {
        calls += "buttons ${rect.joinToString(",")} ${buttons.joinToString(";") { "${it.button}:${it.style}" }.ifEmpty { "none" }}"; return true
    }
    override fun enablePost(on: Boolean): Boolean { calls += "post $on"; return true }
    override fun release() { calls += "release"; state = Epd.PenState.STOP }

    fun clear() = calls.clear()
    val posts get() = calls.filter { it.startsWith("post") }
}

/** A clock and scheduler under the test's control. */
class FakeTime : InkScheduler {
    var now = 0L
    private class Task(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()

    override fun post(delayMs: Long, block: () -> Unit): () -> Unit {
        val t = Task(now + delayMs, block)
        tasks += t
        return { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = next.at
            next.block()
        }
        now = end
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}
