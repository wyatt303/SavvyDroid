package pl.linuch.savvydroid.gvret

/** Bytes the client sends TO the device — see docs/PROTOCOL.md. */
object GvretCommands {

    /** Switches the link from ASCII/text mode into binary GVRET mode. */
    fun binaryModeEnable(): ByteArray = byteArrayOf(0xE7.toByte(), 0xE7.toByte())

    /** Command 9: request the device echo back its keepalive reply. */
    fun keepAliveRequest(): ByteArray = byteArrayOf(0xF1.toByte(), 0x09)

    enum class BusSpeed(val bps: Int) {
        SPEED_125K(125_000),
        SPEED_250K(250_000),
        SPEED_500K(500_000),
        SPEED_1M(1_000_000),
    }

    /**
     * Command 5 (SETUP_CANBUS): configure bus 0's speed and listen-only
     * mode; bus 1 is sent disabled (this app is single-bus for MVP).
     *
     * NOT-YET-VERIFIED against source (see docs/PROTOCOL.md) — deliberately
     * isolated in this one function so the byte layout can be corrected
     * later without touching the parser, connection layer, or anything
     * downstream of it.
     */
    fun setupCanBus(speed: BusSpeed, listenOnly: Boolean): ByteArray {
        var bus0 = speed.bps.toUInt() and 0x1FFFFFFFu
        if (listenOnly) bus0 = bus0 or (1u shl 31)
        bus0 = bus0 or (1u shl 30) // bus enabled

        val out = ByteArray(2 + 4 + 4)
        out[0] = 0xF1.toByte()
        out[1] = 0x05
        writeLeUInt(out, 2, bus0)
        writeLeUInt(out, 6, 0u) // bus 1: disabled
        return out
    }

    private fun writeLeUInt(buf: ByteArray, offset: Int, v: UInt) {
        for (i in 0 until 4) {
            buf[offset + i] = ((v shr (8 * i)) and 0xFFu).toByte()
        }
    }
}
