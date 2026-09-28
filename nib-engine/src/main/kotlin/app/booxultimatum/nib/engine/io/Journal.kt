package app.booxultimatum.nib.engine.io

import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.history.Command
import app.booxultimatum.nib.engine.history.EditResult
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Why [Journal.replay] stopped. */
enum class ReplayStop {
    /** Every record was applied. */
    End,

    /** The last record was cut short (the app died while writing it). */
    Truncated,

    /** A record failed its checksum or couldn't be decoded. */
    Corrupt,

    /** A record was refused by the document, so the journal doesn't belong to it. */
    Rejected,

    /** The header is missing or damaged; nothing was applied. */
    BadHeader,

    /** The journal was written after a different snapshot; nothing was applied. */
    TokenMismatch,
}

/**
 * The result of [Journal.replay]: the [document] with [applied] records applied. [validLength] is the byte length of
 * the intact part of the journal; truncate the file to it before appending again ([Journal.openForAppend] does).
 */
data class ReplayResult(
    val document: Document,
    val applied: Int,
    val validLength: Long,
    val stop: ReplayStop,
    val token: Long?,
)

/**
 * An append-only log of commands, written after each edit so a crash loses at most the edit in flight.
 *
 * Layout: a header (`NIBJ`, version, the snapshot's token, CRC32), then records of
 * `[length varint][type byte][payload][crc32 of type and payload, 4 bytes]`. Undo and redo are recorded as the
 * commands they effectively applied ([app.booxultimatum.nib.engine.history.HistoryEvent.effective]), so replay
 * reproduces the final state without knowing about history.
 *
 * Compaction: the app saves a fresh `.nib` snapshot with a new token (via [NibFile.writeAtomically]), then starts a
 * new journal with [create] and that token. If it dies in between, the old journal carries the old token and
 * [replay] skips it ([ReplayStop.TokenMismatch]), because the snapshot already holds its edits.
 */
class Journal private constructor(private val out: OutputStream, private val fos: FileOutputStream?) : Closeable {
    private val record = ByteWriter(1024)
    private val body = ByteWriter(1024)
    private val crc = CRC32()

    /** Records [command]. Call [sync] to make it durable. */
    fun append(command: Command) {
        body.reset()
        Codecs.writeCommand(body, command)
        crc.reset()
        crc.update(body.bytes(), 0, body.size)
        record.reset()
        record.varint(body.size)
        record.raw(body.bytes(), 0, body.size)
        record.int32(crc.value.toInt())
        record.writeTo(out)
    }

    /** Flushes, and forces the bytes to disk when writing to a file. */
    fun sync() {
        out.flush()
        fos?.fd?.sync()
    }

    override fun close() {
        out.flush()
        out.close()
    }

    companion object {
        private val MAGIC = "NIBJ".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1

        /** Bytes in the header. */
        const val HEADER_SIZE = 4 + 1 + 8 + 4
        private const val MAX_RECORD = 1 shl 28

        /** Starts an empty journal for the snapshot with [token], replacing [file]. */
        fun create(file: File, token: Long): Journal {
            val fos = FileOutputStream(file, false)
            val j = Journal(fos.buffered(1 shl 14), fos)
            j.out.write(header(token))
            j.sync()
            return j
        }

        /** Opens [file] to append after replay, first cutting it to [validLength] to drop a damaged tail. */
        fun openForAppend(file: File, validLength: Long): Journal {
            RandomAccessFile(file, "rw").use { it.setLength(validLength) }
            val fos = FileOutputStream(file, true)
            return Journal(fos.buffered(1 shl 14), fos)
        }

        /** A journal over any stream (tests, or a custom store); writes the header first when [writeHeader]. */
        fun over(out: OutputStream, token: Long, writeHeader: Boolean = true): Journal {
            val j = Journal(out, null)
            if (writeHeader) out.write(header(token))
            return j
        }

        fun header(token: Long): ByteArray {
            val w = ByteWriter(HEADER_SIZE)
            w.raw(MAGIC)
            w.byte(VERSION)
            w.int64(token)
            val c = CRC32()
            c.update(w.bytes(), 0, w.size)
            w.int32(c.value.toInt())
            return w.toByteArray()
        }

        /**
         * Applies every intact record in [input] to [document], stopping at the first cut-short, damaged or refused
         * one. With [expectedToken], a journal written for another snapshot is skipped. Never throws.
         */
        fun replay(document: Document, input: InputStream, expectedToken: Long? = null): ReplayResult {
            val src = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 14)
            val head = readFully(src, HEADER_SIZE)
            if (head == null || !validHeader(head)) return ReplayResult(document, 0, 0L, ReplayStop.BadHeader, null)
            val token = ByteReader(head, 5, 13).int64()
            if (expectedToken != null && token != expectedToken) {
                return ReplayResult(document, 0, HEADER_SIZE.toLong(), ReplayStop.TokenMismatch, token)
            }
            var doc = document
            var applied = 0
            var valid = HEADER_SIZE.toLong()
            val crc = CRC32()
            while (true) {
                val stop: ReplayStop? = try {
                    val first = src.read()
                    if (first < 0) {
                        ReplayStop.End
                    } else {
                        var length = 0L
                        var shift = 0
                        var b = first
                        var lengthBytes = 1
                        var bad = false
                        while (true) {
                            length = length or ((b and 0x7F).toLong() shl shift)
                            if (b and 0x80 == 0) break
                            shift += 7
                            if (shift > 28) {
                                bad = true
                                break
                            }
                            b = src.read()
                            if (b < 0) break
                            lengthBytes++
                        }
                        when {
                            bad || length > MAX_RECORD -> ReplayStop.Corrupt
                            b < 0 -> ReplayStop.Truncated
                            length == 0L -> ReplayStop.Corrupt
                            else -> {
                                val payload = readFully(src, length.toInt())
                                val tail = if (payload == null) null else readFully(src, 4)
                                if (payload == null || tail == null) {
                                    ReplayStop.Truncated
                                } else {
                                    crc.reset()
                                    crc.update(payload)
                                    if (ByteReader(tail).int32() != crc.value.toInt()) {
                                        ReplayStop.Corrupt
                                    } else {
                                        val command = try {
                                            Codecs.readCommand(ByteReader(payload))
                                        } catch (_: Exception) {
                                            null
                                        }
                                        when (val r = command?.apply(doc)) {
                                            null -> ReplayStop.Corrupt
                                            is EditResult.Rejected -> ReplayStop.Rejected
                                            is EditResult.Applied -> {
                                                doc = r.document
                                                applied++
                                                valid += lengthBytes + length + 4
                                                null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    ReplayStop.Corrupt
                }
                if (stop != null) return ReplayResult(doc, applied, valid, stop, token)
            }
        }

        /** Replays [file]; a missing file counts as an empty journal with a bad header. */
        fun replay(document: Document, file: File, expectedToken: Long? = null): ReplayResult {
            if (!file.isFile) return ReplayResult(document, 0, 0L, ReplayStop.BadHeader, null)
            return try {
                file.inputStream().use { replay(document, it, expectedToken) }
            } catch (_: Exception) {
                ReplayResult(document, 0, 0L, ReplayStop.BadHeader, null)
            }
        }

        private fun validHeader(h: ByteArray): Boolean {
            for (i in MAGIC.indices) if (h[i] != MAGIC[i]) return false
            if (h[4].toInt() != VERSION) return false
            val c = CRC32()
            c.update(h, 0, 13)
            return ByteReader(h, 13, 17).int32() == c.value.toInt()
        }

        /** Reads exactly [n] bytes, or null at end of stream. Grows as data arrives, so a bogus length can't exhaust memory. */
        private fun readFully(input: InputStream, n: Int): ByteArray? {
            var out = ByteArray(minOf(n, 1 shl 16))
            var off = 0
            while (off < n) {
                if (off == out.size) out = out.copyOf(minOf(n, out.size * 2))
                val r = input.read(out, off, out.size - off)
                if (r < 0) return null
                off += r
            }
            return out
        }
    }
}
