package pl.linuch.savvydroid.gvret

/**
 * Simple 1-second sliding-window frames-per-second counter. Not
 * thread-safe by itself -- callers (e.g. [LiveFrameTable]) are
 * responsible for confining access to one thread/coroutine.
 */
class FpsCounter {
    private var windowStartMs = -1L
    private var countInWindow = 0

    var lastFps: Int = 0
        private set

    /** Record one occurrence at [nowMs] (epoch or any monotonic ms clock). Returns the current fps estimate. */
    fun record(nowMs: Long): Int {
        if (windowStartMs < 0) windowStartMs = nowMs
        countInWindow++
        val elapsed = nowMs - windowStartMs
        if (elapsed >= 1000) {
            lastFps = ((countInWindow.toLong() * 1000L) / elapsed).toInt()
            countInWindow = 0
            windowStartMs = nowMs
        }
        return lastFps
    }
}
