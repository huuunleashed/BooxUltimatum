package app.booxultimatum.core.sleep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.core.graphics.createBitmap
import app.booxultimatum.R
import app.booxultimatum.core.Journal
import app.booxultimatum.core.exec.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** The user's picture for the Photo face, kept in app files with its EXIF rotation applied. */
object SleepPhoto {
    fun file(context: Context) = File(File(context.filesDir, "sleep").apply { mkdirs() }, "photo.jpg")

    fun exists(context: Context) = file(context).length() > 0

    fun clear(context: Context) { file(context).delete() }

    /** Blocking. Stores the picture with its long side at most the panel's long side; false if it can't be read. */
    // The framework ExifInterface is reliable from API 25, and minSdk is 30, so the androidx copy would add nothing.
    @android.annotation.SuppressLint("ExifInterface")
    fun import(context: Context, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2480) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)!!.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        val long = maxOf(decoded.width, decoded.height)
        val scale = if (long > 2480) 2480f / long else 1f
        val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val bmp = if (degrees == 0f && scale == 1f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { decoded.recycle() }
        val tmp = File(file(context).parentFile, "photo.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        check(tmp.renameTo(file(context)))
    }.isSuccess
}

/**
 * The studio's engine: renders the chosen face at the panel's current orientation, skips the work when nothing shown
 * would change, and publishes through [SleepPublisher]. Renders run on [Dispatchers.Default], one at a time. The
 * full-size bitmap and decoded photo are reused across a burst of renders and released a minute after the last, so
 * the home screen's process doesn't carry 18 MB between refreshes.
 */
object SleepStudio {
    private const val SCREEN_JOURNAL = "sleep.screen"
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private var canvasBitmap: Bitmap? = null
    private var photo: Pair<Long, Bitmap>? = null
    private val release = Runnable { synchronized(this) { canvasBitmap = null; photo = null } }

    data class Rendered(val bitmap: Bitmap, val data: SleepData, val renderMs: Long, val key: String)

    /** The panel in its current rotation: Onyx centre-crops the picture to exactly this, so we render at it. */
    fun panelSize(context: Context): Pair<Int, Int> {
        val d = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d?.mode
        var pw = mode?.physicalWidth ?: 1860
        var ph = mode?.physicalHeight ?: 2480
        if (pw > ph) { val t = pw; pw = ph; ph = t }
        val rotated = d?.rotation == Surface.ROTATION_90 || d?.rotation == Surface.ROTATION_270
        return if (rotated) ph to pw else pw to ph
    }

    private fun photoBitmap(context: Context): Bitmap? = synchronized(this) {
        val f = SleepPhoto.file(context)
        if (f.length() == 0L) return null
        photo?.takeIf { it.first == f.lastModified() }?.let { return it.second }
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        photo = f.lastModified() to b
        b
    }

    /** The shared full-size sheet. Portrait and landscape hold the same number of pixels, so one allocation serves both. */
    private fun canvasFor(w: Int, h: Int): Bitmap = synchronized(this) {
        canvasBitmap?.let { b ->
            if (b.isMutable && b.width == w && b.height == h) return b
            if (b.isMutable && b.allocationByteCount >= w * h * 4) { b.reconfigure(w, h, Bitmap.Config.ARGB_8888); return b }
        }
        createBitmap(w, h).also { canvasBitmap = it }
    }

    // ---------- Both orientations, ready ahead ----------

    /** One encoded picture per panel size, so a rotation only has to swap a file instead of rendering. */
    private class Cached(val bytes: ByteArray, val format: SleepPublisher.Format, val key: String)

    private val publishLock = Mutex()

    private fun sizeKey(w: Int, h: Int) = "${w}x$h"

    private fun specHash(spec: SleepFaceSpec) = sha(spec.copy(active = false).toJson().toString())

    private fun cacheFile(context: Context, w: Int, h: Int) = File(File(context.noBackupFilesDir, "sleep-cache").apply { mkdirs() }, "${sizeKey(w, h)}.bin")

    private fun storeCache(context: Context, w: Int, h: Int, spec: SleepFaceSpec, bytes: ByteArray, format: SleepPublisher.Format, key: String) {
        val f = cacheFile(context, w, h)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.delete(); return }
        SleepStore.put(context, "cache_${sizeKey(w, h)}", JSONObject().put("key", key).put("spec", specHash(spec)).put("format", format.name).put("install", installStamp(context)).toString())
    }

    /** The picture rendered ahead for [w] × [h], if it was made from the face as it is set now, by this install. */
    private fun cached(context: Context, w: Int, h: Int, spec: SleepFaceSpec): Cached? = runCatching {
        val meta = JSONObject(SleepStore.get(context, "cache_${sizeKey(w, h)}") ?: return null)
        if (meta.getString("spec") != specHash(spec) || meta.optLong("install") != installStamp(context)) return null
        val f = cacheFile(context, w, h)
        if (f.length() == 0L) return null
        Cached(f.readBytes(), SleepPublisher.Format.valueOf(meta.getString("format")), meta.getString("key"))
    }.getOrNull()

    /**
     * Renders the face for the other orientation and keeps it encoded, unless the one kept is already current. Onyx
     * centre-crops the picture to the rotation the tablet sleeps in, so the second orientation has to be ready before
     * anyone turns the tablet; rendering it only after the turn left seconds in which a sleep showed a cropped face.
     */
    private fun prepareOther(context: Context, spec: SleepFaceSpec, exact: Boolean, w: Int, h: Int) {
        runCatching {
            val alt = job(context, spec, exact, 1f, size = h to w)
            if (cached(context, alt.w, alt.h, spec)?.key == alt.key) return
            val r = draw(context, alt, null, shared = true)
            val (bytes, format) = encode(r.bitmap, spec)
            storeCache(context, alt.w, alt.h, spec, bytes, format, alt.key)
        }
    }

    /** Writes an already-encoded picture where Onyx reads it. Callers hold [publishLock]. */
    private suspend fun publish(context: Context, spec: SleepFaceSpec, bytes: ByteArray, format: SleepPublisher.Format, announce: Boolean): String = when (spec.mode) {
        SleepMode.Image -> {
            val p = SleepPublisher.writeImage(context, bytes, format)
            if (announce || p != SleepStore.get(context, "broadcast_path")) {
                SleepPublisher.broadcastImage(context, p)
                SleepStore.put(context, "broadcast_path", p)
            }
            p
        }
        SleepMode.Overlay -> SleepPublisher.writeSticker(context, bytes, redetect = announce).getOrThrow()
    }

    /** Whether the picture Onyx will show was made for the panel's rotation right now. */
    fun matchesRotation(context: Context): Boolean {
        val (w, h) = panelSize(context)
        return SleepStore.get(context, "published_size") == sizeKey(w, h)
    }

    /**
     * Puts the picture for the current rotation in place straight away, from the one rendered ahead: a file write,
     * not a render. False when there is none yet, so the caller renders instead. Safe to call at any moment.
     */
    suspend fun matchRotation(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val spec = SleepStore.load(app)
        if (!spec.active || matchesRotation(app)) return@withContext true
        runCatching {
            publishLock.withLock {
                val (w, h) = panelSize(app)
                if (SleepStore.get(app, "published_size") == sizeKey(w, h)) return@withLock true
                val c = cached(app, w, h, spec) ?: return@withLock false
                publish(app, spec, c.bytes, c.format, announce = false)
                SleepStore.put(app, "published_size", sizeKey(w, h))
                SleepStore.put(app, "hash", c.key)
                true
            }
        }.getOrDefault(false)
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Job(val spec: SleepFaceSpec, val data: SleepData, val fonts: SleepTypefaces, val w: Int, val h: Int, val usesPhoto: Boolean, val key: String)

    /** Gathers what the face will show and its fingerprint, without drawing anything. Blocking. */
    private fun job(context: Context, spec: SleepFaceSpec, exact: Boolean, scale: Float, size: Pair<Int, Int>? = null): Job {
        val (pw, ph) = size ?: panelSize(context)
        val w = (pw * scale).toInt().coerceAtLeast(16)
        val h = (ph * scale).toInt().coerceAtLeast(16)
        val data = SleepData.gather(context, spec, exact)
        val fonts = SleepTypefaces.load(context, spec)
        val usesPhoto = spec.mode == SleepMode.Image && spec.face == SleepFace.Photo
        val photoStamp = if (usesPhoto) SleepPhoto.file(context).lastModified() else 0L
        val key = sha(spec.copy(active = false).toJson().toString() + "|" + data.renderKey() + "|${w}x$h|" + fonts.stamp + "|" + photoStamp + "|" + installStamp(context))
        return Job(spec, data, fonts, w, h, usesPhoto, key)
    }

    /** Changes with every install, so a new version redraws once with its own layout instead of keeping the old picture. */
    @Volatile private var installed: Long = 0L

    private fun installStamp(context: Context): Long {
        if (installed == 0L) installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(1L)
        return installed
    }

    private fun draw(context: Context, j: Job, into: Bitmap?, shared: Boolean): Rendered {
        val target = into?.takeIf { it.width == j.w && it.height == j.h && it.isMutable }
            ?: if (shared) canvasFor(j.w, j.h) else createBitmap(j.w, j.h)
        val t0 = SystemClock.elapsedRealtime()
        SleepRenderer.render(target, j.spec, j.data, j.fonts, if (j.usesPhoto) photoBitmap(context) else null)
        return Rendered(target, j.data, SystemClock.elapsedRealtime() - t0, j.key)
    }

    /**
     * Renders [spec] into [into] or a new bitmap, for previews and thumbnails. Blocking; call on a background
     * dispatcher. [scale] shrinks the sheet; the type scales with it, since every size is a fraction of the short side.
     */
    fun render(context: Context, spec: SleepFaceSpec, exact: Boolean = false, scale: Float = 1f, into: Bitmap? = null): Rendered =
        draw(context, job(context, spec, exact, scale), into, shared = false)

    /** Photos without dither go out as JPEG (smaller, no visible loss); everything else as PNG, JPEG only above the limit. */
    private fun encode(b: Bitmap, spec: SleepFaceSpec): Pair<ByteArray, SleepPublisher.Format> {
        fun jpeg(q: Int) = ByteArrayOutputStream(1 shl 20).also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray() to SleepPublisher.Format.Jpeg
        val photo = spec.mode == SleepMode.Image && spec.face == SleepFace.Photo
        if (photo && !spec.dither) return jpeg(95)
        val png = ByteArrayOutputStream(1 shl 20).also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return if (png.size > SleepPublisher.LIMIT && spec.mode == SleepMode.Image) jpeg(92) else png to SleepPublisher.Format.Png
    }

    /**
     * Renders and publishes if anything shown changed. [force] ignores the unchanged check (Apply, Sleep now);
     * [announce] sends the Onyx style switch even when the path is unchanged.
     */
    suspend fun refresh(context: Context, reason: String, force: Boolean = false, exact: Boolean = false, announce: Boolean = false): Result<SleepStatus> =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                val app = context.applicationContext
                val spec = SleepStore.load(app)
                runCatching {
                    check(spec.active || force) { "inactive" }
                    val j = job(app, spec, exact, 1f)
                    val live = when (spec.mode) {
                        SleepMode.Image -> SleepPublisher.imageLive(app)
                        SleepMode.Overlay -> SleepStore.get(app, "sticker_path") != null
                    }
                    if (!force && live && j.key == SleepStore.get(app, "hash")) {
                        val prev = SleepStore.status(app)
                        prepareOther(app, spec, exact, j.w, j.h)
                        return@runCatching SleepStatus(
                            System.currentTimeMillis(), 0, 0, prev?.bytes ?: 0, prev?.file.orEmpty(), spec.mode, reason, true, null,
                        ).also { SleepStore.setStatus(app, it) }
                    }
                    val r = draw(app, j, null, shared = true)
                    val t1 = SystemClock.elapsedRealtime()
                    val (bytes, format) = encode(r.bitmap, spec)
                    val encodeMs = SystemClock.elapsedRealtime() - t1
                    storeCache(app, j.w, j.h, spec, bytes, format, r.key)
                    val path = publishLock.withLock {
                        // The tablet may have turned while this rendered: publishing now would put the wrong shape in place.
                        if (panelSize(app) != j.w to j.h) null
                        else publish(app, spec, bytes, format, announce).also {
                            SleepStore.put(app, "hash", r.key)
                            SleepStore.put(app, "published_size", sizeKey(j.w, j.h))
                        }
                    }
                    if (path == null) {
                        matchRotation(app)
                        SleepScheduler.request(app, "rotation", 1_500)
                    }
                    // Sleep now puts the tablet down right after this; the other shape can wait for the next refresh.
                    if (reason != "sleep_now") prepareOther(app, spec, exact, j.w, j.h)
                    SleepStatus(System.currentTimeMillis(), r.renderMs, encodeMs, bytes.size.toLong(), path ?: SleepStore.status(app)?.file.orEmpty(), spec.mode, reason, false, null)
                        .also { SleepStore.setStatus(app, it) }
                }.onFailure { e ->
                    if (e.message != "inactive") {
                        SleepStore.setStatus(app, SleepStatus(System.currentTimeMillis(), 0, 0, 0, SleepStore.status(app)?.file.orEmpty(), spec.mode, reason, false, e.message ?: e.toString()))
                    }
                }.also {
                    main.removeCallbacks(release)
                    main.postDelayed(release, 60_000)
                }
            }
        }

    fun faceName(context: Context, spec: SleepFaceSpec): String = context.getString(
        if (spec.mode == SleepMode.Image) faceLabel(spec.face) else overlayLabel(spec.overlay),
    )

    fun faceLabel(f: SleepFace) = when (f) {
        SleepFace.Almanac -> R.string.sl_face_almanac
        SleepFace.Instrument -> R.string.sl_face_instrument
        SleepFace.Poster -> R.string.sl_face_poster
        SleepFace.UnderClock -> R.string.sl_face_under_clock
        SleepFace.Photo -> R.string.sl_face_photo
        SleepFace.Note -> R.string.sl_face_note
        SleepFace.ReturnCard -> R.string.sl_face_return
        SleepFace.Minimal -> R.string.sl_face_minimal
    }

    fun overlayLabel(o: SleepOverlay) = when (o) {
        SleepOverlay.BottomBand -> R.string.sl_ov_bottom
        SleepOverlay.TopBand -> R.string.sl_ov_top
        SleepOverlay.Corner -> R.string.sl_ov_corner
        SleepOverlay.Centre -> R.string.sl_ov_centre
    }

    /** Makes the face the sleep screen and keeps it current. [exact] stamps the minute instead of rounding it. */
    suspend fun apply(context: Context, exact: Boolean = false): Result<SleepStatus> {
        val app = context.applicationContext
        val spec = SleepStore.load(app)
        if (spec.mode == SleepMode.Overlay && !Privileged.ready()) return Result.failure(IllegalStateException(app.getString(R.string.sl_err_needs_shizuku)))
        SleepStore.save(app, spec.copy(active = true))
        // Onyx keeps its style in private storage we can't read, so there is no prior value to record, only the fact.
        Journal.rememberOriginal(app, SCREEN_JOURNAL, JSONObject().put("boox", "unreadable"))
        val r = refresh(app, if (exact) "sleep_now" else "apply", force = true, exact = exact, announce = true)
        val name = faceName(app, spec)
        Journal.log(
            app, "sleep-screen",
            app.getString(if (spec.mode == SleepMode.Image) R.string.sl_journal_image else R.string.sl_journal_overlay, name),
            r.getOrNull()?.file ?: r.exceptionOrNull()?.message.orEmpty(),
            r.isSuccess,
        )
        if (r.isFailure) SleepStore.save(app, SleepStore.load(app).copy(active = spec.active))
        else SleepScheduler.watch(app)
        return r
    }

    /** Stamps the exact minute, publishes, then puts the tablet to sleep through the shell (T2). */
    suspend fun sleepNow(context: Context): Result<Unit> {
        val app = context.applicationContext
        if (!Privileged.ready()) return Result.failure(IllegalStateException(app.getString(R.string.sl_err_needs_shizuku)))
        apply(app, exact = true).onFailure { return Result.failure(it) }
        val r = Privileged.sh("input keyevent KEYCODE_SLEEP")
        return if (r.ok) Result.success(Unit) else Result.failure(IllegalStateException(r.message))
    }

    /** Places the current overlay in the gallery's Sticker album, for the one-time pick in Boox. T0 (our own picture). */
    suspend fun prepareSticker(context: Context): Result<String> = mutex.withLock {
        withContext(Dispatchers.Default) {
            runCatching {
                val app = context.applicationContext
                val spec = SleepStore.load(app).copy(mode = SleepMode.Overlay)
                val r = render(app, spec)
                val bytes = ByteArrayOutputStream().also { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                SleepPublisher.placeStickerPick(app, bytes)
            }
        }
    }

    /**
     * Hands the sleep screen back to Boox: the Boox default picture through the same broadcast, Boox's own sticker
     * copied back, our pictures removed and refreshes stopped. The style the user had before can't be read, so the
     * screen offers Boox's screensaver settings next to this.
     */
    suspend fun restore(context: Context): Result<Unit> = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        val r = runCatching {
            SleepStore.save(app, SleepStore.load(app).copy(active = false))
            SleepScheduler.cancel(app)
            SleepPublisher.broadcastImage(app, SleepPublisher.BOOX_DEFAULT)
            val sticker = SleepPublisher.restoreSticker(app)
            SleepPublisher.deleteOurPictures(app)
            listOf("hash", "broadcast_path", "published_size", "reannounce").forEach { SleepStore.put(app, it, null) }
            File(app.noBackupFilesDir, "sleep-cache").deleteRecursively()
            Journal.forget(app, SCREEN_JOURNAL)
            sticker.getOrThrow()
        }
        Journal.log(app, "sleep-screen", app.getString(R.string.sl_journal_restore), r.exceptionOrNull()?.message.orEmpty(), r.isSuccess)
        r
    }

    fun changed(context: Context) = Journal.original(context, SCREEN_JOURNAL) != null || SleepPublisher.stickerChanged(context)
}
