package pl.linuch.savvydroid.gvret

import kotlin.test.Test
import kotlin.test.assertEquals

class GvretCommandsTest {

    @Test
    fun `binary mode enable is two 0xE7 bytes`() {
        assertEquals(listOf(0xE7.toByte(), 0xE7.toByte()), GvretCommands.binaryModeEnable().toList())
    }

    @Test
    fun `keepalive request is F1 09`() {
        assertEquals(listOf(0xF1.toByte(), 0x09), GvretCommands.keepAliveRequest().toList())
    }

    @Test
    fun `setup canbus command starts with the F1 05 prefix and is 10 bytes total`() {
        val bytes = GvretCommands.setupCanBus(GvretCommands.BusSpeed.SPEED_500K, listenOnly = true)
        assertEquals(10, bytes.size)
        assertEquals(0xF1.toByte(), bytes[0])
        assertEquals(0x05.toByte(), bytes[1])
    }

    private fun bus0Word(bytes: ByteArray): UInt =
        (0 until 4).fold(0u) { acc, i -> acc or ((bytes[2 + i].toUInt() and 0xFFu) shl (8 * i)) }

    // Bit layout as read by ESP32RET's gvret_comm.cpp SETUP_CANBUS:
    // bit31 = extended status present, bit30 = enabled, bit29 = listen-only, low 20 bits = speed.
    @Test
    fun `setup canbus 500k listen-only sets bits 31 30 29 and the speed`() {
        val word = bus0Word(GvretCommands.setupCanBus(GvretCommands.BusSpeed.SPEED_500K, listenOnly = true))
        assertEquals(0xE0000000u or 500_000u, word)
    }

    @Test
    fun `setup canbus without listen-only leaves bit 29 clear`() {
        val word = bus0Word(GvretCommands.setupCanBus(GvretCommands.BusSpeed.SPEED_500K, listenOnly = false))
        assertEquals(0xC0000000u or 500_000u, word)
    }

    @Test
    fun `setup canbus speeds fit the firmware's 20-bit speed field`() {
        for (speed in GvretCommands.BusSpeed.values()) {
            val word = bus0Word(GvretCommands.setupCanBus(speed, listenOnly = true))
            assertEquals(speed.bps.toUInt(), word and 0xFFFFFu)
        }
    }

    @Test
    fun `setup canbus bus1 field is always disabled (all zero)`() {
        val bytes = GvretCommands.setupCanBus(GvretCommands.BusSpeed.SPEED_125K, listenOnly = false)
        assertEquals(listOf<Byte>(0, 0, 0, 0), bytes.slice(6..9))
    }
}
