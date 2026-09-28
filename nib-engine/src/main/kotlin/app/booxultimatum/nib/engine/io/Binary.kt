package app.booxultimatum.nib.engine.io

import java.io.IOException
import java.io.OutputStream

/** Malformed binary data. */
internal class FormatException(message: String) : IOException(message)

/**
 * Appends little-endian binary values: LEB128 varints, zigzag varints, fixed 32-bit values and length-prefixed
 * fields (`tag`, `length`, payload) that readers can skip when they don't know the tag.
 */
internal class ByteWriter(capacity: Int = 256) {
    private var buf = ByteArray(maxOf(16, capacity))

    var size: Int = 0
        private set

    fun byte(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun raw(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    /** An unsigned LEB128 varint of the 64 bits of [v]. */
    fun varint(v: Long) {
        ensure(10)
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            buf[size++] = ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        buf[size++] = x.toByte()
    }

    fun varint(v: Int) = varint(v.toLong() and 0xFFFFFFFFL)

    /** A zigzag varint, compact for small negative numbers. */
    fun svarint(v: Long) = varint((v shl 1) xor (v shr 63))

    fun int32(v: Int) {
        ensure(4)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 24).toByte()
    }

    fun int64(v: Long) {
        int32(v.toInt())
        int32((v ushr 32).toInt())
    }

    fun float(v: Float) = int32(java.lang.Float.floatToRawIntBits(v))

    fun blob(b: ByteArray) {
        varint(b.size)
        raw(b)
    }

    fun string(s: String) = blob(s.toByteArray(Charsets.UTF_8))

    /** A field holding whatever [write] produces. */
    inline fun field(tag: Int, write: ByteWriter.() -> Unit) {
        val child = ByteWriter()
        child.write()
        varint(tag)
        varint(child.size)
        raw(child.bytes(), 0, child.size)
    }

    fun fieldVarint(tag: Int, v: Long) = field(tag) { varint(v) }

    fun fieldBool(tag: Int, v: Boolean) = field(tag) { varint(if (v) 1L else 0L) }

    fun fieldFloat(tag: Int, v: Float) = field(tag) { float(v) }

    fun fieldInt32(tag: Int, v: Int) = field(tag) { int32(v) }

    fun fieldString(tag: Int, s: String) = field(tag) { string(s) }

    fun fieldBytes(tag: Int, b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        varint(tag)
        varint(len)
        raw(b, off, len)
    }

    /** The internal buffer; only the first [size] bytes are meaningful. */
    fun bytes(): ByteArray = buf

    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun writeTo(out: OutputStream) = out.write(buf, 0, size)

    fun reset() {
        size = 0
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var n = buf.size * 2
        while (n < size + extra) n *= 2
        buf = buf.copyOf(n)
    }
}

/** Reads what [ByteWriter] writes, from [start] until [end]. Throws [FormatException] on anything malformed. */
internal class ByteReader(private val buf: ByteArray, start: Int = 0, val end: Int = buf.size) {
    var pos: Int = start
        private set

    init {
        if (start < 0 || end > buf.size || start > end) throw FormatException("bad slice")
    }

    val remaining: Int get() = end - pos
    val hasMore: Boolean get() = pos < end

    fun byte(): Int {
        if (pos >= end) throw FormatException("unexpected end of data")
        return buf[pos++].toInt() and 0xFF
    }

    fun raw(len: Int): ByteArray {
        if (len < 0 || len > remaining) throw FormatException("length $len out of range")
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            if (shift == 63 && b > 1) throw FormatException("varint overflow")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw FormatException("varint too long")
        }
    }

    /** A varint that must fit a non-negative Int. */
    fun count(): Int {
        val v = varint()
        if (v < 0 || v > Int.MAX_VALUE) throw FormatException("count $v out of range")
        return v.toInt()
    }

    fun svarint(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    fun int32(): Int {
        if (remaining < 4) throw FormatException("unexpected end of data")
        val v = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun int64(): Long {
        val lo = int32().toLong() and 0xFFFFFFFFL
        val hi = int32().toLong()
        return lo or (hi shl 32)
    }

    fun float(): Float = java.lang.Float.intBitsToFloat(int32())

    fun blob(): ByteArray = raw(count())

    fun string(): String = String(blob(), Charsets.UTF_8)

    fun bool(): Boolean = varint() != 0L

    /** A reader over the next [len] bytes; this reader skips past them. */
    fun sub(len: Int): ByteReader {
        if (len < 0 || len > remaining) throw FormatException("length $len out of range")
        val r = ByteReader(buf, pos, pos + len)
        pos += len
        return r
    }

    /** Calls [block] for every field until the end; unknown tags are simply not read. */
    inline fun fields(block: (tag: Int, field: ByteReader) -> Unit) {
        while (hasMore) {
            val tag = varint()
            if (tag < 0 || tag > Int.MAX_VALUE) throw FormatException("bad tag")
            val len = count()
            block(tag.toInt(), sub(len))
        }
    }
}
