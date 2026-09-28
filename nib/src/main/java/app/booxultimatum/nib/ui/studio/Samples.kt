package app.booxultimatum.nib.ui.studio

import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.createBitmap
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.render.CanvasSink
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pictures drawn by Nib's own engine for the panels: brush samples, the pens on the rail, layer thumbnails, the
 * library's empty page. Rendered on one low-priority thread and kept in a small cache, so opening a panel never waits
 * on the main thread and a sample is drawn once per brush, width and colour.
 */
object Samples {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nib-samples").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val cache = LruCache<Any, ImageBitmap>(120)
    private val sinks = ThreadLocal.withInitial { CanvasSink() }

    fun peek(key: Any): ImageBitmap? = cache.get(key)

    /** Renders once for [key] into a [w] by [h] pixel bitmap, off the main thread. */
    suspend fun render(key: Any, w: Int, h: Int, draw: (Canvas, CanvasSink) -> Unit): ImageBitmap = withContext(dispatcher) {
        cache.get(key) ?: run {
            val bmp = createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1))
            draw(Canvas(bmp), sinks.get()!!)
            bmp.asImageBitmap().also { cache.put(key, it) }
        }
    }

    /**
     * The sample stroke: an S across a [w] by [h] pixel box with the pressure rising and falling, drawn with [brush]
     * at its own width (one document pixel to one screen pixel), capped at [maxWidth] so broad brushes still fit.
     */
    fun sCurve(brush: BrushSpec, color: Int, w: Float, h: Float, maxWidth: Float = h * 0.42f): Stroke {
        val width = min(brush.width, maxWidth)
        val b = StrokeBuilder(brush.copy(width = width), color, SAMPLE_ID)
        val margin = max(width * 0.7f + 4f, w * 0.07f)
        val amp = max(2f, h / 2f - margin * 0.9f) * 0.8f
        val n = 72
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = margin + t * (w - 2 * margin)
            val y = h / 2f + amp * sin(t * 2f * PI.toFloat())
            b.add(InputSample(x, y, 0.12f + 0.88f * sin(t * PI.toFloat()), timeNanos = i * 7_000_000L))
        }
        return b.finish()
    }

    /** A shaky hand's line across a [w] by [h] box, as raw samples: the smoothing sample's input. */
    fun wobble(w: Float, h: Float): List<InputSample> {
        val n = 56
        return (0..n).map { i ->
            val t = i / n.toFloat()
            // A tremor from sample to sample over a slow wander: what smoothing is for.
            val shake = sin(i * 2.1f) * 0.55f + sin(i * 0.63f + 1.3f) * 0.45f
            InputSample(w * 0.06f + t * w * 0.88f, h / 2f + h * 0.2f * shake, 0.7f, timeNanos = i * 6_000_000L)
        }
    }

    /** [samples] drawn through a stroke of [brush], smoothed as it smooths. */
    fun through(brush: BrushSpec, color: Int, samples: List<InputSample>): Stroke {
        val b = StrokeBuilder(brush, color, SAMPLE_ID)
        b.addAll(samples)
        return b.finish()
    }

    fun draw(stroke: Stroke, canvas: Canvas, sink: CanvasSink) {
        StrokeRenderer.render(stroke, sink.on(canvas), StrokeRenderer.DEFAULT_TOLERANCE)
    }

    private const val SAMPLE_ID = 7L
}

/**
 * An engine-drawn picture filling its space: rendered off the main thread at the exact pixel size, the previous one
 * kept on screen until the new one is ready, so nothing flashes blank.
 */
@Composable
fun EnginePicture(key: Any, modifier: Modifier = Modifier, draw: (Canvas, CanvasSink, Int, Int) -> Unit) {
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return@BoxWithConstraints
        val full = Triple(key, w, h)
        val shown = remember { mutableStateOf(Samples.peek(full)) }
        LaunchedEffect(full) {
            val cached = Samples.peek(full)
            shown.value = cached ?: Samples.render(full, w, h) { c, s -> draw(c, s, w, h) }
        }
        shown.value?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
        }
    }
}
