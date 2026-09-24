package pl.linuch.savvydroid.gvret

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import pl.linuch.savvydroid.gvret.testutil.FakeGvretServer

class ReconnectingGvretClientTest {

    private val ioPool = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @Test
    fun `reconnects with backoff after the device drops the first connection`() = runBlocking {
        FakeGvretServer().use { server ->
            val serverJob = launch(ioPool) {
                // First connection: handshake then an immediate drop --
                // simulates the ESP losing WiFi / rebooting.
                server.accept().use { conn ->
                    conn.readNBytes(2)
                    conn.readNBytes(10)
                } // .use{} closes it here

                // Second connection: handshake, then a real frame.
                server.accept().use { conn ->
                    conn.readNBytes(2)
                    conn.readNBytes(10)
                    conn.sendFrame(
                        GvretFrame(timestampUs = 1L, id = 0x42, extended = false, bus = 0, data = byteArrayOf(9))
                    )
                    Thread.sleep(200) // keep it open long enough for the test to observe the event
                }
            }

            val reconnecting = ReconnectingGvretClient(
                clientFactory = { GvretClient(host = "127.0.0.1", port = server.port, keepAliveIntervalMs = 10_000) },
                initialBackoffMs = 20,
                maxBackoffMs = 100,
            )

            // Record state transitions concurrently rather than checking
            // state.value after the events flow is cancelled -- first()
            // cancels its upstream the moment it has a value, which (by
            // design) also drives the reconnecting client's state back to
            // Disconnected; asserting on state.value *after* that would
            // race the very cancellation that produced the value.
            val statesSeen = mutableListOf<ConnectionState>()
            val stateJob = launch { reconnecting.state.collect { statesSeen.add(it) } }

            val frame = withTimeout(10_000) { reconnecting.events().first() as GvretEvent.Frame }
            assertEquals(0x42, frame.frame.id)
            assertTrue(statesSeen.contains(ConnectionState.Connected))

            stateJob.cancel()
            serverJob.join()
        }
    }

    @Test
    fun `backoff grows and is capped at maxBackoffMs`() {
        // Pure function check, no I/O -- exercises the private backoff
        // formula's observable contract via reflection-free black box:
        // construct a client that always fails to connect (bad port) and
        // watch the Reconnecting delays it reports.
        runBlocking {
            val reconnecting = ReconnectingGvretClient(
                clientFactory = { GvretClient(host = "127.0.0.1", port = 1, connectTimeoutMs = 50) },
                initialBackoffMs = 10,
                maxBackoffMs = 35,
            )
            val delays = mutableListOf<Long>()
            val job = launch {
                reconnecting.state.collect { s ->
                    if (s is ConnectionState.Reconnecting) {
                        delays.add(s.delayMs)
                        if (delays.size >= 4) throw kotlinx.coroutines.CancellationException("enough samples")
                    }
                }
            }
            val eventsJob = launch { reconnecting.events().collect { } }
            withTimeout(5000) { job.join() }
            eventsJob.cancel()

            assertTrue(delays.size >= 4)
            assertEquals(listOf(10L, 20L, 35L, 35L), delays.take(4))
        }
    }
}
