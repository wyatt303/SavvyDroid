package pl.linuch.savvydroid.capture

/** Global counters shown in the UI -- see the MVP spec's "Live view" section. */
data class CaptureStats(
    val fps: Int = 0,
    val totalFrames: Long = 0,
    val parseErrorCount: Long = 0,
    val isRecording: Boolean = false,
    val recordingElapsedMs: Long = 0,
    val recordingFileName: String? = null,
)
