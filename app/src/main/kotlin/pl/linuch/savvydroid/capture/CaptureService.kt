package pl.linuch.savvydroid.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import pl.linuch.savvydroid.MainActivity
import pl.linuch.savvydroid.R
import pl.linuch.savvydroid.gvret.ConnectionState
import pl.linuch.savvydroid.gvret.CsvWriter
import pl.linuch.savvydroid.gvret.GvretClient
import pl.linuch.savvydroid.gvret.GvretCommands
import pl.linuch.savvydroid.gvret.GvretEvent
import pl.linuch.savvydroid.gvret.LiveFrameTable
import pl.linuch.savvydroid.gvret.LiveRow
import pl.linuch.savvydroid.gvret.ReconnectingGvretClient

/**
 * Foreground service owning the whole capture lifecycle: connection
 * (with reconnect-with-backoff), the live per-ID frame table, and
 * (optionally) recording to a SavvyCAN-compatible CSV file. Runs with
 * the screen off -- nothing here depends on any UI being visible.
 */
class CaptureService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private val binder = LocalBinder()

    private val liveTable = LiveFrameTable()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _liveRows = MutableStateFlow<List<LiveRow>>(emptyList())
    val liveRows: StateFlow<List<LiveRow>> = _liveRows.asStateFlow()

    private val _stats = MutableStateFlow(CaptureStats())
    val stats: StateFlow<CaptureStats> = _stats.asStateFlow()

    private var reconnectingClient: ReconnectingGvretClient? = null
    private var connectionJob: Job? = null

    // Recording state -- CSV rows go through a channel to a single
    // writer coroutine so the event-collector coroutine never blocks on
    // file I/O and there's exactly one writer, no shared-buffer races.
    private var csvChannel: Channel<String>? = null
    private var writerJob: Job? = null
    private var recordingTickerJob: Job? = null
    private var recordingFile: File? = null
    private var recordingStartMs: Long = 0
    @Volatile private var lastFrameTimestampUs: Long = 0
    @Volatile private var isRecording: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // -- Connection ------------------------------------------------------

    fun connect(host: String, busSpeed: GvretCommands.BusSpeed = GvretCommands.BusSpeed.SPEED_500K) {
        disconnect()
        val client = ReconnectingGvretClient(clientFactory = { GvretClient(host = host, busSpeed = busSpeed) })
        reconnectingClient = client

        connectionJob = lifecycleScope.launch(Dispatchers.IO) {
            client.events().collect { event -> handleEvent(event) }
        }
        client.state.onEach { s ->
            _connectionState.value = s
            updateNotification()
        }.launchIn(lifecycleScope)
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        reconnectingClient = null
        _connectionState.value = ConnectionState.Disconnected
        liveTable.clear()
        _liveRows.value = emptyList()
        updateNotification()
    }

    private fun handleEvent(event: GvretEvent) {
        when (event) {
            is GvretEvent.Frame -> {
                val now = System.currentTimeMillis()
                lastFrameTimestampUs = event.frame.timestampUs
                liveTable.update(event.frame, now)
                _liveRows.value = liveTable.snapshot()
                _stats.value = _stats.value.copy(
                    totalFrames = _stats.value.totalFrames + 1,
                    fps = liveTable.snapshot().maxOfOrNull { it.fps } ?: 0,
                )
                if (isRecording) {
                    csvChannel?.trySend(CsvWriter.formatRow(event.frame))
                }
            }
            is GvretEvent.ParserErrors -> {
                _stats.value = _stats.value.copy(parseErrorCount = event.totalErrorCount)
            }
            GvretEvent.KeepAliveReply -> {
                // Purely a liveness signal, already consumed by GvretClient
                // internally -- nothing for the UI to do with it.
            }
        }
    }

    // -- Recording ---------------------------------------------------------

    /** Starts recording to a new file. No-op if already recording. */
    fun startRecording(): Boolean {
        if (isRecording) return false

        val dir = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val name = "savvydroid-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"
        val file = File(dir, name)

        val channel = Channel<String>(capacity = Channel.UNLIMITED)
        csvChannel = channel
        recordingFile = file
        recordingStartMs = System.currentTimeMillis()
        isRecording = true
        _stats.value = _stats.value.copy(isRecording = true, recordingElapsedMs = 0, recordingFileName = name)

        recordingTickerJob = lifecycleScope.launch {
            while (isRecording) {
                _stats.value = _stats.value.copy(recordingElapsedMs = System.currentTimeMillis() - recordingStartMs)
                kotlinx.coroutines.delay(1000)
            }
        }

        writerJob = lifecycleScope.launch(Dispatchers.IO) {
            val writer: BufferedWriter = BufferedWriter(FileWriter(file))
            writer.write(CsvWriter.HEADER)
            writer.newLine()
            var lastFlush = System.currentTimeMillis()
            try {
                // Consumes rows as they arrive; flushes at most once a
                // second rather than after every single row, and never
                // buffers more than "since the last flush" in memory --
                // each row is written to the OS-buffered writer
                // immediately, only the explicit flush() is throttled.
                for (row in channel) {
                    writer.write(row)
                    writer.newLine()
                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= 1000) {
                        writer.flush()
                        lastFlush = now
                    }
                }
            } finally {
                writer.flush()
                writer.close()
            }
        }

        updateNotification()
        return true
    }

    /** Stops recording and returns the finished file, or null if nothing was recording. */
    suspend fun stopRecording(): File? {
        if (!isRecording) return null
        isRecording = false
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        csvChannel?.close()
        writerJob?.join()
        writerJob = null
        csvChannel = null
        val file = recordingFile
        recordingFile = null
        _stats.value = _stats.value.copy(isRecording = false, recordingElapsedMs = 0, recordingFileName = null)
        updateNotification()
        return file
    }

    /** Inserts a marker row at (approximately) the current moment -- see docs/PROTOCOL.md "Marker encoding". */
    fun addMarker() {
        if (isRecording) {
            csvChannel?.trySend(CsvWriter.formatMarkerRow(lastFrameTimestampUs))
        }
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    // -- Notification --------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = when (val s = _connectionState.value) {
            is ConnectionState.Connected -> getString(R.string.status_connected)
            is ConnectionState.Connecting -> getString(R.string.status_connecting)
            is ConnectionState.Reconnecting -> getString(R.string.status_reconnecting)
            ConnectionState.Disconnected -> getString(R.string.status_disconnected)
        }
        val recordingSuffix = if (isRecording) " • REC" else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText + recordingSuffix)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context): Intent = Intent(context, CaptureService::class.java)
    }
}
