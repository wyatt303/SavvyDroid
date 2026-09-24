package pl.linuch.savvydroid.gvret

/**
 * Streaming, byte-at-a-time decoder for the GVRET binary protocol
 * (device -> client direction only — this app never needs to parse its
 * own outgoing commands). See docs/PROTOCOL.md for the exact wire
 * format this mirrors.
 *
 * Robust against TCP fragmentation (call [feed] with whatever chunk
 * size arrives, including 1 byte at a time — every call carries state
 * over via internal fields, nothing is assumed about chunk boundaries)
 * and against garbage/corruption in the stream (any byte that doesn't
 * fit the expected shape drops the parser back to scanning for the next
 * 0xF1 prefix, it never throws and never gets stuck).
 *
 * Not thread-safe — feed from a single reader coroutine/thread.
 */
class GvretParser {

    private enum class State {
        IDLE,           // scanning for 0xF1
        GOT_PREFIX,     // read 0xF1, waiting for the command id byte
        FRAME_TS,       // command 0: collecting the 4 timestamp bytes
        FRAME_ID,       // command 0: collecting the 4 id bytes
        FRAME_LENBUS,   // command 0: collecting the 1 length|bus byte
        FRAME_DATA,     // command 0: collecting `dataLen` data bytes
        FRAME_CHECKSUM, // command 0: collecting the 1 trailing checksum byte (value discarded)
        KEEPALIVE_TAIL, // command 9: collecting the fixed 2-byte reply tail
    }

    private var state = State.IDLE

    /**
     * Count of bytes/commands the parser had to discard to stay
     * resynced: a garbage byte seen outside any command, an
     * unrecognized (out-of-MVP-scope) command id, or an invalid
     * length|bus byte. Exposed for the UI's "dropped frames/parse
     * errors" counter (see the MVP spec's "Live view" requirement) --
     * NOT incremented for anything else, e.g. a successfully decoded
     * frame with a non-zero checksum byte is not an error.
     */
    var errorCount: Long = 0
        private set

    // Scratch accumulated while decoding a command-0 frame.
    private val scratch = ByteArray(8 + 4 + 1) // ts(4) + id(4) + lenbus(1) — enough for the fixed prefix
    private var scratchPos = 0
    private var dataLen = 0
    private val dataBuf = ByteArray(8)
    private var dataPos = 0
    private var keepaliveTailNeeded = 0

    /**
     * Feed the next chunk of bytes received from the socket. Returns
     * every event ([GvretEvent.Frame] / [GvretEvent.KeepAliveReply])
     * completed by this call, in the order they completed. May return
     * an empty list if the chunk didn't complete anything yet.
     */
    fun feed(bytes: ByteArray): List<GvretEvent> {
        val out = ArrayList<GvretEvent>()
        for (b in bytes) {
            step(b, out)
        }
        return out
    }

    private fun step(b: Byte, out: MutableList<GvretEvent>) {
        val u = b.toInt() and 0xFF
        when (state) {
            State.IDLE -> {
                if (u == 0xF1) {
                    state = State.GOT_PREFIX
                } else {
                    errorCount++ // garbage byte outside any command
                }
            }

            State.GOT_PREFIX -> {
                when (u) {
                    0x00 -> {
                        scratchPos = 0
                        state = State.FRAME_TS
                    }
                    0x09 -> {
                        keepaliveTailNeeded = 2
                        state = State.KEEPALIVE_TAIL
                    }
                    else -> {
                        // Recognized-but-out-of-MVP-scope command (1/2/3/6/7/
                        // 12/13/20/22/...) or plain garbage: we don't know
                        // its length, so we can't skip it safely. Drop back
                        // to IDLE and resync on the next 0xF1 — see
                        // docs/PROTOCOL.md "Resync".
                        errorCount++
                        state = State.IDLE
                    }
                }
            }

            State.FRAME_TS -> {
                scratch[scratchPos++] = b
                if (scratchPos == 4) state = State.FRAME_ID
            }

            State.FRAME_ID -> {
                scratch[scratchPos++] = b
                if (scratchPos == 8) state = State.FRAME_LENBUS
            }

            State.FRAME_LENBUS -> {
                scratch[scratchPos++] = b
                val lenBus = u
                dataLen = lenBus and 0x0F
                if (dataLen > 8) {
                    // Nibble allows up to 15 but real CAN data is 0..8;
                    // treat anything larger as a desynced stream rather
                    // than trust it and read garbage as "data".
                    errorCount++
                    state = State.IDLE
                    return
                }
                dataPos = 0
                state = if (dataLen == 0) State.FRAME_CHECKSUM else State.FRAME_DATA
            }

            State.FRAME_DATA -> {
                dataBuf[dataPos++] = b
                if (dataPos == dataLen) state = State.FRAME_CHECKSUM
            }

            State.FRAME_CHECKSUM -> {
                // Value intentionally discarded — see docs/PROTOCOL.md:
                // shipped firmware hardcodes this byte to 0x00, and even
                // SavvyCAN's own reference client doesn't validate it. We
                // still must consume exactly one byte here or every frame
                // after this one is misaligned.
                out.add(GvretEvent.Frame(buildFrame()))
                state = State.IDLE
            }

            State.KEEPALIVE_TAIL -> {
                keepaliveTailNeeded--
                if (keepaliveTailNeeded == 0) {
                    out.add(GvretEvent.KeepAliveReply)
                    state = State.IDLE
                }
            }
        }
    }

    private fun buildFrame(): GvretFrame {
        val ts = leUInt(scratch, 0)
        val rawId = leUInt(scratch, 4)
        val extended = (rawId and 0x80000000u) != 0u
        val id = (rawId and 0x1FFFFFFFu).toInt()
        val bus = (scratch[8].toInt() and 0xF0) ushr 4
        val data = dataBuf.copyOf(dataLen)
        return GvretFrame(
            timestampUs = ts.toLong(),
            id = id,
            extended = extended,
            bus = bus,
            data = data,
        )
    }

    private fun leUInt(buf: ByteArray, offset: Int): UInt {
        var v = 0u
        for (i in 0 until 4) {
            v = v or ((buf[offset + i].toInt() and 0xFF).toUInt() shl (8 * i))
        }
        return v
    }
}
