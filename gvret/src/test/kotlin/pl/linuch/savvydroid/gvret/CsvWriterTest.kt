package pl.linuch.savvydroid.gvret

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvWriterTest {

    @Test
    fun `header matches SavvyCAN native format exactly`() {
        assertEquals("Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8", CsvWriter.HEADER)
    }

    @Test
    fun `formats a full 8-byte frame matching the documented example row`() {
        val frame = GvretFrame(
            timestampUs = 39747828L,
            id = 0x5EB,
            extended = false,
            bus = 0,
            data = byteArrayOf(0xE8.toByte(), 0x45, 0x85.toByte(), 0x4B, 0x4A, 0x28, 0x36, 0x69),
        )
        assertEquals("39747828,000005EB,false,Rx,0,8,E8,45,85,4B,4A,28,36,69", CsvWriter.formatRow(frame))
    }

    @Test
    fun `short frames pad unused data columns with 00, not blank`() {
        val frame = GvretFrame(timestampUs = 1L, id = 0x10, extended = false, bus = 0, data = byteArrayOf(0x01, 0x02))
        val row = CsvWriter.formatRow(frame)
        assertEquals("1,00000010,false,Rx,0,2,01,02,00,00,00,00,00,00", row)
    }

    @Test
    fun `zero-length frame still has all 8 data columns present`() {
        val frame = GvretFrame(timestampUs = 0L, id = 0, extended = false, bus = 0, data = ByteArray(0))
        val row = CsvWriter.formatRow(frame)
        assertEquals("0,00000000,false,Rx,0,0,00,00,00,00,00,00,00,00", row)
    }

    @Test
    fun `extended id is formatted as true and id is zero-padded to 8 hex chars`() {
        val frame = GvretFrame(timestampUs = 5L, id = 0x1A, extended = true, bus = 3, data = ByteArray(0))
        val row = CsvWriter.formatRow(frame)
        assertEquals("5,0000001A,true,Rx,3,0,00,00,00,00,00,00,00,00", row)
    }

    @Test
    fun `marker row uses the reserved id and Rx direction`() {
        val row = CsvWriter.formatMarkerRow(123456L)
        assertEquals("123456,00000000,false,Rx,0,0,00,00,00,00,00,00,00,00", row)
    }
}
