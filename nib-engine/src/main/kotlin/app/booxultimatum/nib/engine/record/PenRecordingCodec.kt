package app.booxultimatum.nib.engine.record

import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.Tool
import app.booxultimatum.nib.engine.io.ByteReader
import app.booxultimatum.nib.engine.io.ByteWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Why a pen recording couldn't be read. */
class PenRecordingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streams events to [out] as they happen, so a recording survives a crash up to the last flush. Call [finish] to
 * write the end marker; the stream stays open.
 */
class PenRecordingWriter(private val out: OutputStream, header: PenHeader) : Closeable {
    private val w = ByteWriter(256)
    private val payload = ByteWriter(64)
    private var lastTime = 0L
    private var finished = false

    init {
        w.raw(PenRecordingCodec.MAGIC)
        w.varint(PenRecordingCodec.VERSION)
        w.field(1) {
            fieldString(1, header.device)
            fieldVarint(2, header.panelWidth.toLong())
            fieldVarint(3, header.panelHeight.toLong())
            fieldFloat(4, header.pressureMax)
            fieldString(5, header.sampleRateNote)
        }
        flushBuffer()
    }

    fun write(event: PenEvent) {
        check(!finished) { "recording finished" }
        payload.reset()
        val delta = event.timeNanos - lastTime
        lastTime = event.timeNanos
        when (event) {
            is PenEvent.Sample -> {
                payload.byte(event.action.code)
                payload.byte(event.tool.ordinal)
                val s = event.sample
                payload.float(s.x)
                payload.float(s.y)
                payload.float(s.pressure)
                payload.float(s.tilt)
                payload.float(s.orientation)
                payload.svarint(delta)
                w.byte(PenRecordingCodec.SAMPLE)
            }
            is PenEvent.Marker -> {
                payload.string(event.name)
                payload.string(event.value)
                payload.svarint(delta)
                w.byte(PenRecordingCodec.MARKER)
            }
        }
        w.varint(payload.size)
        w.raw(payload.bytes(), 0, payload.size)
        if (w.size >= 1 shl 14) flushBuffer()
    }

    fun flush() {
        flushBuffer()
        out.flush()
    }

    /** Writes the end marker and flushes. */
    fun finish() {
        if (finished) return
        w.byte(PenRecordingCodec.END)
        finished = true
        flush()
    }

    override fun close() = finish()

    private fun flushBuffer() {
        w.writeTo(out)
        w.reset()
    }
}

/** The binary format for [PenRecording]s: magic `PENREC1`, a version, a header record, then length-prefixed events. */
object PenRecordingCodec {
    internal val MAGIC = "PENREC1".toByteArray(Charsets.US_ASCII)
    internal const val VERSION = 1
    internal const val END = 0
    internal const val SAMPLE = 1
    internal const val MARKER = 2

    fun write(recording: PenRecording, out: OutputStream) {
        val writer = PenRecordingWriter(out, recording.header)
        for (e in recording.events) writer.write(e)
        writer.finish()
    }

    /**
     * Reads a recording. A recording cut short (no end marker) is an error unless [lenient], in which case the
     * events read so far are returned.
     */
    fun read(input: InputStream, lenient: Boolean = false): PenRecording {
        val bytes = input.readBytes()
        if (bytes.size < MAGIC.size || MAGIC.indices.any { bytes[it] != MAGIC[it] }) {
            throw PenRecordingException("not a pen recording")
        }
        val r = ByteReader(bytes, MAGIC.size)
        val events = ArrayList<PenEvent>()
        var header: PenHeader? = null
        try {
            val version = r.count()
            if (version > VERSION) throw PenRecordingException("recording version $version is newer than $VERSION")
            val tag = r.count()
            val h = r.sub(r.count())
            if (tag != 1) throw PenRecordingException("no header")
            header = readHeader(h)
            var time = 0L
            while (true) {
                val kind = r.byte()
                if (kind == END) break
                val p = r.sub(r.count())
                when (kind) {
                    SAMPLE -> {
                        val action = PenAction.fromCode(p.byte()) ?: throw PenRecordingException("bad action")
                        val tool = Tool.entries.getOrNull(p.byte()) ?: Tool.Pen
                        val x = p.float()
                        val y = p.float()
                        val pressure = p.float()
                        val tilt = p.float()
                        val orientation = p.float()
                        time += p.svarint()
                        events.add(PenEvent.Sample(action, tool, InputSample(x, y, pressure, tilt, orientation, time)))
                    }
                    MARKER -> {
                        val name = p.string()
                        val value = p.string()
                        time += p.svarint()
                        events.add(PenEvent.Marker(name, value, time))
                    }
                    else -> Unit
                }
            }
        } catch (e: PenRecordingException) {
            if (!lenient || header == null) throw e
        } catch (e: IOException) {
            if (!lenient || header == null) throw PenRecordingException("recording cut short or damaged: ${e.message}", e)
        }
        return PenRecording(header ?: throw PenRecordingException("no header"), events)
    }

    private fun readHeader(r: ByteReader): PenHeader {
        var device = ""
        var w = 0
        var h = 0
        var pressureMax = 0f
        var note = ""
        r.fields { tag, f ->
            when (tag) {
                1 -> device = f.string()
                2 -> w = f.count()
                3 -> h = f.count()
                4 -> pressureMax = f.float()
                5 -> note = f.string()
            }
        }
        return PenHeader(device, w, h, pressureMax, note)
    }
}
