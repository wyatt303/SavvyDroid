package pl.linuch.savvydroid.gvret

/** One row of the live "overwrite" view -- the most recent frame for one CAN ID. */
data class LiveRow(
    val frame: GvretFrame,
    /** Bit i set => data byte i differs from the previous frame with this ID (or is newly present). */
    val changedBytesMask: Int,
    val fps: Int,
)

/**
 * MVP live view model: one row per CAN ID, overwritten as new frames
 * arrive, tracking per-ID fps and which data bytes changed since the
 * previous frame with that ID (for UI highlighting). Pure/testable --
 * no Android dependency, no I/O.
 *
 * Not thread-safe -- feed from a single collector coroutine.
 */
class LiveFrameTable {
    private class RowState(var row: LiveRow, val fpsCounter: FpsCounter)

    // LinkedHashMap: iteration order = first-seen order, stable for a UI list.
    private val rows = LinkedHashMap<Int, RowState>()

    fun update(frame: GvretFrame, nowMs: Long): LiveRow {
        val state = rows[frame.id]
        val mask = if (state != null) changedMask(state.row.frame.data, frame.data) else allBytesMask(frame.data.size)
        val fpsCounter = state?.fpsCounter ?: FpsCounter()
        val fps = fpsCounter.record(nowMs)
        val newRow = LiveRow(frame, mask, fps)
        if (state != null) {
            state.row = newRow
        } else {
            rows[frame.id] = RowState(newRow, fpsCounter)
        }
        return newRow
    }

    fun snapshot(): List<LiveRow> = rows.values.map { it.row }

    fun clear() = rows.clear()

    companion object {
        internal fun allBytesMask(size: Int): Int = (1 shl size.coerceIn(0, 8)) - 1

        internal fun changedMask(old: ByteArray, new: ByteArray): Int {
            var mask = 0
            val common = minOf(old.size, new.size, 8)
            for (i in 0 until common) {
                if (old[i] != new[i]) mask = mask or (1 shl i)
            }
            for (i in common until minOf(new.size, 8)) {
                mask = mask or (1 shl i) // byte newly present relative to the old, shorter frame
            }
            return mask
        }
    }
}
