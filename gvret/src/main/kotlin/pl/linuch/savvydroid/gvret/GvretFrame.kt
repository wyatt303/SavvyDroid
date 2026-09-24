package pl.linuch.savvydroid.gvret

/**
 * One decoded CAN frame received over the GVRET link (command 0,
 * BUILD_CAN_FRAME — see docs/PROTOCOL.md).
 *
 * @param timestampUs device-clock microseconds, as sent on the wire
 *   (NOT wall-clock — the ESP32's own free-running counter). Widened to
 *   [Long] because the wire value is an unsigned 32-bit int.
 * @param id the CAN identifier with the extended-frame flag bit already
 *   masked off — always in `0..0x1FFFFFFF`.
 * @param extended true for a 29-bit extended ID, false for 11-bit standard.
 * @param bus the bus number the frame arrived on (upper nibble of the
 *   length|bus byte).
 * @param data the payload, 0..8 bytes.
 */
data class GvretFrame(
    val timestampUs: Long,
    val id: Int,
    val extended: Boolean,
    val bus: Int,
    val data: ByteArray,
) {
    init {
        require(data.size in 0..8) { "data length ${data.size} out of range 0..8" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GvretFrame) return false
        return timestampUs == other.timestampUs &&
            id == other.id &&
            extended == other.extended &&
            bus == other.bus &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestampUs.hashCode()
        result = 31 * result + id
        result = 31 * result + extended.hashCode()
        result = 31 * result + bus
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Something the parser produced from the byte stream. */
sealed interface GvretEvent {
    data class Frame(val frame: GvretFrame) : GvretEvent
    data object KeepAliveReply : GvretEvent
}
