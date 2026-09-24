package pl.linuch.savvydroid.gvret

import kotlin.test.Test
import kotlin.test.assertEquals

class FpsCounterTest {

    @Test
    fun `counts events within the first second and reports fps once the window closes`() {
        val c = FpsCounter()
        assertEquals(0, c.record(0))
        for (i in 1 until 9) c.record(i * 10L) // 8 more calls, 9 total so far
        val fps = c.record(1000) // 10th call closes the [0,1000) window
        assertEquals(10, fps)
    }

    @Test
    fun `resets the window after reporting`() {
        val c = FpsCounter()
        for (t in listOf(0L, 200L, 400L, 600L, 800L, 1000L)) c.record(t) // 6 events in [0,1000] -> 6fps
        assertEquals(6, c.lastFps)
        c.record(1001)
        // new window has started; fps unchanged until the next window closes
        assertEquals(6, c.lastFps)
    }
}
