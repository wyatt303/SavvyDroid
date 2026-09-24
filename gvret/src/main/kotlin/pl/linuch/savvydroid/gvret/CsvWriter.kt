package pl.linuch.savvydroid.gvret

/**
 * Formats frames in SavvyCAN's own "native" CSV format (see
 * docs/PROTOCOL.md) — the exact shape `framefileio.cpp`'s
 * `saveNativeCSVFile()` writes and reads back, so files this produces
 * open in SavvyCAN with no conversion step.
 *
 * Pure formatting only — no file I/O here, so it's trivially testable
 * and reusable from the (Android-only) recording service, which owns
 * buffering/flushing.
 */
object CsvWriter {

    const val HEADER = "Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8"

    /** Reserved ID for a SavvyDroid marker row — see docs/PROTOCOL.md. */
    const val MARKER_ID = 0

    fun formatRow(frame: GvretFrame, dir: String = "Rx"): String {
        val sb = StringBuilder(64)
        sb.append(frame.timestampUs).append(',')
        sb.append(String.format("%08X", frame.id)).append(',')
        sb.append(if (frame.extended) "true" else "false").append(',')
        sb.append(dir).append(',')
        sb.append(frame.bus).append(',')
        sb.append(frame.data.size)
        for (i in 0 until 8) {
            sb.append(',')
            if (i < frame.data.size) {
                sb.append(String.format("%02X", frame.data[i].toInt() and 0xFF))
            } else {
                sb.append("00")
            }
        }
        return sb.toString()
    }

    /** A marker row at [timestampUs] — see docs/PROTOCOL.md "Marker encoding". */
    fun formatMarkerRow(timestampUs: Long): String =
        formatRow(GvretFrame(timestampUs = timestampUs, id = MARKER_ID, extended = false, bus = 0, data = ByteArray(0)))
}
