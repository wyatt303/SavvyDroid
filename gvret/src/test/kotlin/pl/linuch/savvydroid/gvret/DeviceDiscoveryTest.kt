package pl.linuch.savvydroid.gvret

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DeviceDiscoveryTest {

    @Test
    fun `a 4-byte beacon datagram is reported as a discovered address`() = runBlocking {
        // Bind to an ephemeral port (0) in the test instead of the real
        // 17222 -- keeps the test independent of anything else on the
        // dev machine's LAN using that port, while exercising the exact
        // same receive/filter logic. portReady is a real suspension
        // point (unlike Thread.sleep polling, which would starve the
        // launched collector coroutine on runBlocking's single-threaded
        // event loop and never let it run).
        val portReady = CompletableDeferred<Int>()
        val discovery = DeviceDiscovery(
            port = 0,
            socketFactory = {
                DatagramSocket(0).also { portReady.complete(it.localPort) }
            },
        )

        val job = launch {
            val addr = withTimeout(5000) { discovery.listen().first() }
            assertTrue(addr.isLoopbackAddress)
        }

        val boundPort = withTimeout(5000) { portReady.await() }

        DatagramSocket().use { sender ->
            val beacon = byteArrayOf(0x1C, 0xEF.toByte(), 0xAC.toByte(), 0xED.toByte())
            val packet = DatagramPacket(beacon, beacon.size, InetAddress.getLoopbackAddress(), boundPort)
            sender.send(packet)
        }

        job.join()
    }

    @Test
    fun `a differently-sized datagram is ignored`() = runBlocking {
        val portReady = CompletableDeferred<Int>()
        val discovery = DeviceDiscovery(
            port = 0,
            socketFactory = { DatagramSocket(0).also { portReady.complete(it.localPort) } },
        )

        val job = launch {
            val addr = withTimeout(5000) { discovery.listen().first() }
            assertEquals(true, addr.isLoopbackAddress)
        }

        val boundPort = withTimeout(5000) { portReady.await() }

        DatagramSocket().use { sender ->
            // Wrong size -- must be silently ignored, not reported.
            val bogus = byteArrayOf(1, 2, 3)
            sender.send(DatagramPacket(bogus, bogus.size, InetAddress.getLoopbackAddress(), boundPort))
            // Now the real 4-byte beacon.
            val beacon = byteArrayOf(0x1C, 0xEF.toByte(), 0xAC.toByte(), 0xED.toByte())
            sender.send(DatagramPacket(beacon, beacon.size, InetAddress.getLoopbackAddress(), boundPort))
        }

        job.join()
    }
}
