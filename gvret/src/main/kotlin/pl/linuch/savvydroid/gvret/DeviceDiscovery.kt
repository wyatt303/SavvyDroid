package pl.linuch.savvydroid.gvret

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens for ESP32RET's one-way UDP discovery beacon (4 bytes,
 * broadcast to port 17222 roughly once a second — see docs/PROTOCOL.md
 * "Transport"). There is no request/reply here: the firmware sends
 * unsolicited, so this only ever listens.
 *
 * A discovered [InetAddress] is a *candidate* only — the caller must
 * still complete a real TCP connect + handshake on port 23 before
 * treating it as a usable device (see docs/PROTOCOL.md's discovery
 * spoofing caveat).
 */
class DeviceDiscovery(
    private val port: Int = 17222,
    private val socketFactory: () -> DatagramSocket = {
        DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    },
) {
    fun listen(): Flow<InetAddress> = callbackFlow {
        val sock = try {
            socketFactory()
        } catch (e: IOException) {
            close(e)
            return@callbackFlow
        }

        val job = launch(Dispatchers.IO) {
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (isActive) {
                    sock.receive(packet)
                    if (packet.length == 4) {
                        trySend(packet.address)
                    }
                }
            } catch (e: IOException) {
                close(e)
            }
        }

        awaitClose {
            job.cancel()
            runCatching { sock.close() }
        }
    }
}
