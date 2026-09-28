package app.booxultimatum.nib.engine.render

/** A growable float array, reused between strokes to keep rendering allocation-free. */
internal class FloatBuf(capacity: Int = 256) {
    var data = FloatArray(maxOf(4, capacity))
        private set
    var size = 0
        private set

    /** Points held, when the buffer holds interleaved x, y pairs. */
    val points: Int get() = size / 2

    fun clear() {
        size = 0
    }

    fun ensure(extra: Int) {
        if (size + extra > data.size) {
            var n = data.size * 2
            while (n < size + extra) n *= 2
            data = data.copyOf(n)
        }
    }

    fun add(v: Float) {
        if (size == data.size) ensure(1)
        data[size++] = v
    }

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) ensure(2)
        data[size++] = x
        data[size++] = y
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** splitmix64: a small, well-mixed hash, so dab scatter depends only on the stroke id and the dab's index. */
internal object Hash {
    fun mix(x: Long): Long {
        var z = x + -0x61C8864680B583EBL
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        return z xor (z ushr 31)
    }

    /** A value in [0, 1) for ([seed], [index], [channel]). */
    fun unit(seed: Long, index: Int, channel: Int): Float {
        val h = mix(mix(seed) + index.toLong() * 0x9E3779B1L + channel.toLong() * 0x632BE5ABL)
        return (h ushr 40).toFloat() / (1 shl 24).toFloat()
    }

    /** A value in [0, 1) for a pixel, for textures fixed to the page. */
    fun pixel(x: Int, y: Int, salt: Int): Float {
        val h = mix((x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL) xor (salt.toLong() shl 58))
        return (h ushr 40).toFloat() / (1 shl 24).toFloat()
    }
}
