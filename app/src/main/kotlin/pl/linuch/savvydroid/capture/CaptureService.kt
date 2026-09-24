package pl.linuch.savvydroid.capture

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service placeholder -- filled in at the "foreground
 * service" step of the MVP's order of work (after the connection layer
 * and CSV writer are done and tested).
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
