package pl.linuch.savvydroid.gvret

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import pl.linuch.savvydroid.gvret.testutil.FakeGvretServer

class GvretClientTest {

    private val ioPool = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @Test
    fun `connect sends binary mode enable then the bus setup command`() = runBlocking {
        FakeGvretServer().use { server ->
            val client = GvretClient(host = "127.0.0.1", port = server.port)

            val serverConn = async(ioPool) { server.accept() }
            val collectJob = launch { client.run().collect { } }
            try {
                val conn = withTimeout(5000) { serverConn.await() }
                conn.use {
                    val binaryMode = it.readNBytes(2)
                    assertEquals(listOf(0xE7.toByte(), 0xE7.toByte()), binaryMode.toList())

                    val setup = it.readNBytes(10)
                    assertEquals(GvretCommands.setupCanBus(GvretCommands.BusSpeed.SPEED_500K, true).toList(), setup.toList())
                }
            } finally {
                collectJob.cancel()
            }
        }
    }

    @Test
    fun `frames sent by the device arrive as decoded events`() = runBlocking {
        FakeGvretServer().use { server ->
            val client = GvretClient(host = "127.0.0.1", port = server.port)
            val serverConn = async(ioPool) {
                val conn = server.accept()
                conn.readNBytes(2)  // binary mode
                conn.readNBytes(10) // setup
                conn.sendFrame(GvretFrame(timestampUs = 42L, id = 0x123, extended = false, bus = 0, data = byteArrayOf(1, 2, 3)))
                conn
            }

            val events = withTimeout(5000) { client.run().take(1).toList() }
            val f = (events[0] as GvretEvent.Frame).frame
            assertEquals(0x123, f.id)
            assertEquals(listOf<Byte>(1, 2, 3), f.data.toList())

            serverConn.await().close()
        }
    }

    @Test
    fun `garbage bytes from the device surface as a ParserErrors event`() = runBlocking {
        FakeGvretServer().use { server ->
            val client = GvretClient(host = "127.0.0.1", port = server.port)
            val serverConn = async(ioPool) {
                val conn = server.accept()
                conn.readNBytes(2)
                conn.readNBytes(10)
                conn.sendRaw(byteArrayOf(0x00, 0xAB.toByte())) // 2 garbage bytes -> errorCount=2
                conn.sendFrame(GvretFrame(timestampUs = 1L, id = 0x10, extended = false, bus = 0, data = byteArrayOf(1)))
                conn
            }

            val events = withTimeout(5000) { client.run().take(2).toList() }
            assertTrue(events[0] is GvretEvent.ParserErrors)
            assertEquals(2L, (events[0] as GvretEvent.ParserErrors).totalErrorCount)
            assertTrue(events[1] is GvretEvent.Frame)

            serverConn.await().close()
        }
    }

    @Test
    fun `keepalive replies keep the connection alive across several intervals`() = runBlocking {
        FakeGvretServer().use { server ->
            val client = GvretClient(
                host = "127.0.0.1",
                port = server.port,
                keepAliveIntervalMs = 40,
                keepAliveMissThreshold = 2,
            )

            val serverJob = launch(ioPool) {
                val conn = server.accept()
                conn.readNBytes(2)
                conn.readNBytes(10)
                // Reply to every keepalive request as it arrives, for a
                // while -- long enough to span several intervals.
                repeat(4) {
                    conn.readNBytes(2) // F1 09 request
                    conn.sendKeepAliveReply()
                }
                conn
            }

            val received = withTimeout(5000) { client.run().take(4).toList() }
            assertEquals(4, received.count { it == GvretEvent.KeepAliveReply })
            serverJob.join()
        }
    }

    @Test
    fun `no keepalive replies eventually closes the flow with a timeout`() = runBlocking {
        FakeGvretServer().use { server ->
            val client = GvretClient(
                host = "127.0.0.1",
                port = server.port,
                keepAliveIntervalMs = 30,
                keepAliveMissThreshold = 1,
            )

            val serverJob = async(ioPool) {
                val conn = server.accept()
                conn.readNBytes(2)
                conn.readNBytes(10)
                conn // never replies to keepalives
            }

            val ex = assertFailsWith<GvretClient.KeepAliveTimeoutException> {
                withTimeout(5000) { client.run().toList() }
            }
            assertTrue(ex.message!!.contains("keepalive"))
            serverJob.await().close()
        }
    }

    @Test
    fun `device closing the socket completes the flow without error`() = runBlocking {
        FakeGvretServer().use { server ->
            val client = GvretClient(host = "127.0.0.1", port = server.port)
            launch(ioPool) {
                val conn = server.accept()
                conn.readNBytes(2)
                conn.readNBytes(10)
                conn.close()
            }
            // Should complete normally (empty event list), not throw.
            val events = withTimeout(5000) { client.run().toList() }
            assertEquals(emptyList(), events)
        }
    }
}
