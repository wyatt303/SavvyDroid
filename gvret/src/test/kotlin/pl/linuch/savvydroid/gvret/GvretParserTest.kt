package pl.linuch.savvydroid.gvret

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte fixtures built independently from [GvretParser]'s own code, from
 * the wire layout documented in docs/PROTOCOL.md (mirrors
 * `commbuffer.cpp`'s `sendFrameToBuffer()` on the firmware side) — not
 * by round-tripping through the parser itself.
 */
private fun leBytes(v: UInt): ByteArray = byteArrayOf(
    (v and 0xFFu).toByte(),
    ((v shr 8) and 0xFFu).toByte(),
    ((v shr 16) and 0xFFu).toByte(),
    ((v shr 24) and 0xFFu).toByte(),
)

private fun rawFrame(
    timestampUs: UInt,
    rawId: UInt, // already includes the extended bit if desired
    bus: Int,
    data: ByteArray,
    checksum: Byte = 0x00,
): ByteArray {
    val out = ArrayList<Byte>()
    out.add(0xF1.toByte())
    out.add(0x00)
    out.addAll(leBytes(timestampUs).toList())
    out.addAll(leBytes(rawId).toList())
    out.add(((data.size and 0x0F) or ((bus and 0x0F) shl 4)).toByte())
    out.addAll(data.toList())
    out.add(checksum)
    return out.toByteArray()
}

private fun keepAliveReplyBytes(): ByteArray =
    byteArrayOf(0xF1.toByte(), 0x09, 0xDE.toByte(), 0xAD.toByte())

class GvretParserTest {

    @Test
    fun `decodes a single standard-id frame delivered in one chunk`() {
        val bytes = rawFrame(
            timestampUs = 39747828u,
            rawId = 0x5EBu,
            bus = 0,
            data = byteArrayOf(0xE8.toByte(), 0x45, 0x85.toByte(), 0x4B, 0x4A, 0x28, 0x36, 0x69),
        )
        val events = GvretParser().feed(bytes)
        assertEquals(1, events.size)
        val f = (events[0] as GvretEvent.Frame).frame
        assertEquals(39747828L, f.timestampUs)
        assertEquals(0x5EB, f.id)
        assertEquals(false, f.extended)
        assertEquals(0, f.bus)
        assertEquals(8, f.data.size)
        assertEquals(0xE8.toByte(), f.data[0])
        assertEquals(0x69.toByte(), f.data[7])
    }

    @Test
    fun `decodes an extended-id frame and masks off the flag bit`() {
        val rawId = 0x1ABCDEFu or (1u shl 31)
        val bytes = rawFrame(timestampUs = 100u, rawId = rawId, bus = 1, data = byteArrayOf(0x01, 0x02))
        val events = GvretParser().feed(bytes)
        val f = (events[0] as GvretEvent.Frame).frame
        assertEquals(0x1ABCDEF, f.id)
        assertTrue(f.extended)
        assertEquals(1, f.bus)
        assertEquals(listOf<Byte>(0x01, 0x02), f.data.toList())
    }

    @Test
    fun `decodes a zero-length-data frame`() {
        val bytes = rawFrame(timestampUs = 1u, rawId = 0x100u, bus = 0, data = ByteArray(0))
        val events = GvretParser().feed(bytes)
        val f = (events[0] as GvretEvent.Frame).frame
        assertEquals(0, f.data.size)
    }

    @Test
    fun `checksum byte value is not validated but is still consumed`() {
        // Two frames back to back, the first with a garbage (non-zero)
        // checksum byte -- must not desync the second frame.
        val f1 = rawFrame(timestampUs = 1u, rawId = 0x111u, bus = 0, data = byteArrayOf(0x01), checksum = 0xFF.toByte())
        val f2 = rawFrame(timestampUs = 2u, rawId = 0x222u, bus = 0, data = byteArrayOf(0x02))
        val events = GvretParser().feed(f1 + f2)
        assertEquals(2, events.size)
        assertEquals(0x111, (events[0] as GvretEvent.Frame).frame.id)
        assertEquals(0x222, (events[1] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `a checksum byte that happens to equal the 0xF1 prefix does not corrupt the next frame`() {
        // The checksum byte's VALUE must never influence parsing -- only
        // that exactly one byte is consumed there. 0xF1 is the one value
        // that would silently "look like" a valid resync if the consume
        // step were accidentally skipped, so it's the case worth pinning.
        val f1 = rawFrame(timestampUs = 1u, rawId = 0x111u, bus = 0, data = byteArrayOf(0x01), checksum = 0xF1.toByte())
        val f2 = rawFrame(timestampUs = 2u, rawId = 0x222u, bus = 0, data = byteArrayOf(0x02))
        val events = GvretParser().feed(f1 + f2)
        assertEquals(2, events.size)
        assertEquals(0x111, (events[0] as GvretEvent.Frame).frame.id)
        assertEquals(0x222, (events[1] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `decodes multiple frames concatenated in one chunk`() {
        val f1 = rawFrame(timestampUs = 1u, rawId = 0x10u, bus = 0, data = byteArrayOf(1))
        val f2 = rawFrame(timestampUs = 2u, rawId = 0x20u, bus = 0, data = byteArrayOf(2, 3))
        val f3 = rawFrame(timestampUs = 3u, rawId = 0x30u, bus = 0, data = ByteArray(0))
        val events = GvretParser().feed(f1 + f2 + f3)
        assertEquals(3, events.size)
        assertEquals(0x10, (events[0] as GvretEvent.Frame).frame.id)
        assertEquals(0x20, (events[1] as GvretEvent.Frame).frame.id)
        assertEquals(0x30, (events[2] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `survives TCP fragmentation - one byte at a time`() {
        val bytes = rawFrame(timestampUs = 555u, rawId = 0x7AAu, bus = 2, data = byteArrayOf(9, 8, 7))
        val parser = GvretParser()
        val events = ArrayList<GvretEvent>()
        for (b in bytes) {
            events.addAll(parser.feed(byteArrayOf(b)))
        }
        assertEquals(1, events.size)
        val f = (events[0] as GvretEvent.Frame).frame
        assertEquals(0x7AA, f.id)
        assertEquals(2, f.bus)
        assertEquals(listOf<Byte>(9, 8, 7), f.data.toList())
    }

    @Test
    fun `survives a frame split across two arbitrary chunk boundaries`() {
        val bytes = rawFrame(timestampUs = 42u, rawId = 0x333u, bus = 0, data = byteArrayOf(1, 2, 3, 4))
        val parser = GvretParser()
        // split mid-timestamp, mid-id, mid-data -- three separate feeds
        val events = ArrayList<GvretEvent>()
        events.addAll(parser.feed(bytes.copyOfRange(0, 3)))   // 0xF1, cmd, 1 ts byte
        events.addAll(parser.feed(bytes.copyOfRange(3, 10)))  // rest of ts, all of id, lenbus
        events.addAll(parser.feed(bytes.copyOfRange(10, bytes.size))) // data + checksum
        assertEquals(1, events.size)
        assertEquals(0x333, (events[0] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `garbage bytes before a valid frame are skipped, not fatal`() {
        val garbage = byteArrayOf(0x00, 0xAB.toByte(), 0xCD.toByte(), 0xF1.toByte() /* looks like a prefix but isn't followed by a valid cmd */, 0x99.toByte())
        val real = rawFrame(timestampUs = 7u, rawId = 0x44u, bus = 0, data = byteArrayOf(1))
        val events = GvretParser().feed(garbage + real)
        assertEquals(1, events.size)
        assertEquals(0x44, (events[0] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `unrecognized command id is skipped and the parser resyncs on the next prefix`() {
        // 0xF1 0x07 = GET_DEV_INFO -- out of MVP scope, unknown length.
        // We only know it starts with 0xF1 0x07; the parser must not try
        // to interpret what follows as a frame. It resyncs when it next
        // sees 0xF1 that DOES start a real command-0 frame.
        val unknownCmd = byteArrayOf(0xF1.toByte(), 0x07, 0x01, 0x02, 0x03)
        val real = rawFrame(timestampUs = 9u, rawId = 0x55u, bus = 0, data = byteArrayOf(1, 2))
        val events = GvretParser().feed(unknownCmd + real)
        assertEquals(1, events.size)
        assertEquals(0x55, (events[0] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `a data length nibble above 8 is treated as corrupt and resynced`() {
        // Hand-craft a lenbus byte with length=12 (invalid for classic CAN).
        val corrupt = byteArrayOf(
            0xF1.toByte(), 0x00,
            0, 0, 0, 0,       // timestamp
            0xAA.toByte(), 0, 0, 0, // id
            0x0C,             // lenbus: length=12 (invalid)
        )
        val real = rawFrame(timestampUs = 3u, rawId = 0x66u, bus = 0, data = byteArrayOf(5))
        val events = GvretParser().feed(corrupt + real)
        assertEquals(1, events.size)
        assertEquals(0x66, (events[0] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `keepalive reply is decoded as its own event, not a frame`() {
        val bytes = keepAliveReplyBytes()
        val events = GvretParser().feed(bytes)
        assertEquals(1, events.size)
        assertEquals(GvretEvent.KeepAliveReply, events[0])
    }

    @Test
    fun `keepalive reply between two frames does not desync either`() {
        val f1 = rawFrame(timestampUs = 1u, rawId = 0x11u, bus = 0, data = byteArrayOf(1))
        val ka = keepAliveReplyBytes()
        val f2 = rawFrame(timestampUs = 2u, rawId = 0x22u, bus = 0, data = byteArrayOf(2))
        val events = GvretParser().feed(f1 + ka + f2)
        assertEquals(3, events.size)
        assertEquals(0x11, (events[0] as GvretEvent.Frame).frame.id)
        assertEquals(GvretEvent.KeepAliveReply, events[1])
        assertEquals(0x22, (events[2] as GvretEvent.Frame).frame.id)
    }

    @Test
    fun `feed with no bytes returns no events and does not throw`() {
        val events = GvretParser().feed(ByteArray(0))
        assertEquals(0, events.size)
    }

    @Test
    fun `errorCount tracks garbage bytes, unrecognized commands, and invalid length, not valid frames`() {
        val parser = GvretParser()
        assertEquals(0L, parser.errorCount)

        parser.feed(byteArrayOf(0x00, 0xAB.toByte())) // 2 garbage bytes outside any command
        assertEquals(2L, parser.errorCount)

        parser.feed(byteArrayOf(0xF1.toByte(), 0x07)) // unrecognized command id
        assertEquals(3L, parser.errorCount)

        val real = rawFrame(timestampUs = 1u, rawId = 0x10u, bus = 0, data = byteArrayOf(1))
        parser.feed(real)
        assertEquals(3L, parser.errorCount) // a valid frame must not bump the error count

        val corruptLenBus = byteArrayOf(0xF1.toByte(), 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x0F)
        parser.feed(corruptLenBus)
        assertEquals(4L, parser.errorCount)
    }
}
