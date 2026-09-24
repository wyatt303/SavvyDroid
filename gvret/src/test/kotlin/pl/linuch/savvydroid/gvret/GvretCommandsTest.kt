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

    @Test
    fun `setup canbus bus1 field is always disabled (all zero)`() {
        val bytes = GvretCommands.setupCanBus(GvretCommands.BusSpeed.SPEED_125K, listenOnly = false)
        assertEquals(listOf<Byte>(0, 0, 0, 0), bytes.slice(6..9))
    }
}
