package pl.linuch.savvydroid.gvret.testutil

import java.io.Closeable
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import pl.linuch.savvydroid.gvret.GvretFrame

/**
 * A minimal TCP server that plays the ESP32RET side of the GVRET
 * protocol for tests — lets the connection layer (handshake, keepalive,
 * reconnect) be exercised without real hardware. Deliberately encodes
 * frames independently of [pl.linuch.savvydroid.gvret.GvretParser]'s own
 * code (from docs/PROTOCOL.md directly) so a bug shared between encoder
 * and decoder wouldn't hide behind a passing test.
 */
class FakeGvretServer : Closeable {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    /** Blocks until a client connects. */
    fun accept(): FakeGvretConnection = FakeGvretConnection(serverSocket.accept())

    override fun close() {
        runCatching { serverSocket.close() }
    }
}

class FakeGvretConnection(private val socket: Socket) : Closeable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    /** Blocking read of exactly [n] bytes (for asserting on the handshake). */
    fun readNBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("socket closed after $off/$n bytes")
            off += r
        }
        return buf
    }

    fun sendFrame(frame: GvretFrame) {
        output.write(encodeFrame(frame))
        output.flush()
    }

    fun sendKeepAliveReply() {
        output.write(byteArrayOf(0xF1.toByte(), 0x09, 0xDE.toByte(), 0xAD.toByte()))
        output.flush()
    }

    fun sendRaw(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun encodeFrame(frame: GvretFrame): ByteArray {
        val out = ArrayList<Byte>(12 + frame.data.size)
        out.add(0xF1.toByte())
        out.add(0x00)
        var rawId = frame.id.toUInt()
        if (frame.extended) rawId = rawId or (1u shl 31)
        appendLe(out, frame.timestampUs.toUInt())
        appendLe(out, rawId)
        out.add(((frame.data.size and 0x0F) or ((frame.bus and 0x0F) shl 4)).toByte())
        for (b in frame.data) out.add(b)
        out.add(0x00) // checksum, value doesn't matter -- parser discards it
        return out.toByteArray()
    }

    private fun appendLe(out: MutableList<Byte>, v: UInt) {
        for (i in 0 until 4) out.add(((v shr (8 * i)) and 0xFFu).toByte())
    }
}
