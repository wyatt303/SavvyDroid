package pl.linuch.savvydroid.gvret

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * A single GVRET TCP connection attempt: connect, handshake (binary
 * mode + bus setup), then stream decoded events until the socket closes
 * or a keepalive timeout fires. One [GvretClient] is good for exactly
 * one connection — reconnection with backoff is [ReconnectingGvretClient]'s job.
 *
 * Deliberately built on plain `java.net.Socket` (not an Android-specific
 * networking API) so this whole layer — including handshake and
 * keepalive timing — runs as fast JVM unit tests against
 * [pl.linuch.savvydroid.gvret.testutil.FakeGvretServer], no emulator or
 * hardware needed.
 */
class GvretClient(
    private val host: String,
    private val port: Int = 23,
    private val busSpeed: GvretCommands.BusSpeed = GvretCommands.BusSpeed.SPEED_500K,
    private val listenOnly: Boolean = true,
    private val connectTimeoutMs: Int = 5000,
    private val keepAliveIntervalMs: Long = 3000,
    private val keepAliveMissThreshold: Int = 2,
    private val socketFactory: () -> Socket = { Socket() },
) {

    class KeepAliveTimeoutException : IOException("no keepalive reply received in time")

    /**
     * Connects, runs the handshake, and returns a cold [Flow] of decoded
     * events. Collecting drives the connection; cancelling the collector
     * (or the flow completing/throwing) closes the socket and stops the
     * keepalive loop.
     */
    fun run(): Flow<GvretEvent> = callbackFlow {
        val sock = socketFactory()
        try {
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            close(e)
            return@callbackFlow
        }

        val out = sock.getOutputStream()
        val input = sock.getInputStream()
        val parser = GvretParser()
        val missedKeepAlives = AtomicInteger(0)

        try {
            out.write(GvretCommands.binaryModeEnable())
            out.write(GvretCommands.setupCanBus(busSpeed, listenOnly))
            out.flush()
        } catch (e: IOException) {
            runCatching { sock.close() }
            close(e)
            return@callbackFlow
        }

        val keepAliveJob = launch {
            while (isActive) {
                delay(keepAliveIntervalMs)
                if (missedKeepAlives.incrementAndGet() > keepAliveMissThreshold) {
                    close(KeepAliveTimeoutException())
                    return@launch
                }
                try {
                    out.write(GvretCommands.keepAliveRequest())
                    out.flush()
                } catch (e: IOException) {
                    close(e)
                    return@launch
                }
            }
        }

        val readJob = launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            var lastReportedErrors = 0L
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) {
                        close()
                        return@launch
                    }
                    val decoded = parser.feed(buf.copyOf(n))
                    // Report an error-count bump before the events from this
                    // same chunk, since (within the wire format) any
                    // discarded bytes that caused it necessarily occurred
                    // earlier in the stream than a frame completed afterward.
                    if (parser.errorCount != lastReportedErrors) {
                        lastReportedErrors = parser.errorCount
                        trySend(GvretEvent.ParserErrors(lastReportedErrors))
                    }
                    for (event in decoded) {
                        if (event is GvretEvent.KeepAliveReply) missedKeepAlives.set(0)
                        trySend(event)
                    }
                }
            } catch (e: IOException) {
                close(e)
            }
        }

        awaitClose {
            keepAliveJob.cancel()
            readJob.cancel()
            runCatching { sock.close() }
        }
    }
}
