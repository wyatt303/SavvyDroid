package pl.linuch.savvydroid.gvret

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveFrameTableTest {

    private fun frame(id: Int, vararg data: Int) =
        GvretFrame(timestampUs = 0, id = id, extended = false, bus = 0, data = data.map { it.toByte() }.toByteArray())

    @Test
    fun `first frame for an id has every present byte marked changed`() {
        val table = LiveFrameTable()
        val row = table.update(frame(0x10, 1, 2, 3), nowMs = 0)
        assertEquals(0b111, row.changedBytesMask)
    }

    @Test
    fun `unchanged bytes are not marked, changed bytes are`() {
        val table = LiveFrameTable()
        table.update(frame(0x10, 1, 2, 3, 4), nowMs = 0)
        val row = table.update(frame(0x10, 1, 99, 3, 4), nowMs = 10)
        assertEquals(0b0010, row.changedBytesMask) // only byte index 1 changed
    }

    @Test
    fun `a frame growing longer marks the new trailing bytes as changed`() {
        val table = LiveFrameTable()
        table.update(frame(0x10, 1, 2), nowMs = 0)
        val row = table.update(frame(0x10, 1, 2, 3, 4), nowMs = 10)
        assertEquals(0b1100, row.changedBytesMask) // bytes 2,3 are new
    }

    @Test
    fun `different ids get independent rows`() {
        val table = LiveFrameTable()
        table.update(frame(0x10, 1), nowMs = 0)
        table.update(frame(0x20, 9), nowMs = 0)
        val snapshot = table.snapshot()
        assertEquals(listOf(0x10, 0x20), snapshot.map { it.frame.id })
    }

    @Test
    fun `snapshot reflects only the latest frame per id, overwrite semantics`() {
        val table = LiveFrameTable()
        table.update(frame(0x10, 1), nowMs = 0)
        table.update(frame(0x10, 2), nowMs = 10)
        val snapshot = table.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(2.toByte(), snapshot[0].frame.data[0])
    }

    @Test
    fun `clear empties the table`() {
        val table = LiveFrameTable()
        table.update(frame(0x10, 1), nowMs = 0)
        table.clear()
        assertEquals(0, table.snapshot().size)
    }
}
