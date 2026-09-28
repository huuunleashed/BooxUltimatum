package app.booxultimatum.nib.editor

/** A growable list of floats, reused between strokes so the eraser paths allocate nothing per sample. */
class FloatList(capacity: Int = 256) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    val points: Int get() = size / 2

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 2))
        data[size++] = x
        data[size++] = y
    }
}
