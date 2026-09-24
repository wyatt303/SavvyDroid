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
     * Layout verified against ESP32RET's `gvret_comm.cpp` `SETUP_CANBUS`
     * state: each bus is a little-endian uint32 where the low 20 bits are
     * the speed in bps, bit 31 says "the enabled/listen-only bits below
     * are present" (without it the firmware just enables the bus and
     * ignores listen-only), bit 30 = bus enabled, bit 29 = listen-only.
     * (An earlier version put listen-only on bit 31, which the firmware
     * read as "extended status present" -- so listen-only was never set.)
     */
    fun setupCanBus(speed: BusSpeed, listenOnly: Boolean): ByteArray {
        var bus0 = speed.bps.toUInt() and 0xFFFFFu
        bus0 = bus0 or (1u shl 31) // enabled/listen-only bits are present
        bus0 = bus0 or (1u shl 30) // bus enabled
        if (listenOnly) bus0 = bus0 or (1u shl 29)

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
