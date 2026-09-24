package pl.linuch.savvydroid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import pl.linuch.savvydroid.capture.CaptureService
import pl.linuch.savvydroid.databinding.ActivityMainBinding
import pl.linuch.savvydroid.gvret.ConnectionState
import pl.linuch.savvydroid.gvret.DeviceDiscovery

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = LiveFrameAdapter()

    private var service: CaptureService? = null
    private var bound = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as CaptureService.LocalBinder).service
            bound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.liveList.layoutManager = LinearLayoutManager(this)
        binding.liveList.adapter = adapter

        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.recordButton.setOnClickListener { onRecordClicked() }
        binding.markerButton.setOnClickListener { service?.addMarker() }

        requestNotificationPermissionIfNeeded()
        startAndBindService()
        observeDiscovery()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startAndBindService() {
        val intent = CaptureService.startIntent(this)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun onConnectClicked() {
        val svc = service ?: return
        if (svc.connectionState.value == ConnectionState.Disconnected) {
            val host = binding.ipInput.text?.toString()?.trim().orEmpty()
            if (host.isEmpty()) return
            svc.connect(host)
        } else {
            svc.disconnect()
        }
    }

    private fun onRecordClicked() {
        val svc = service ?: return
        if (svc.isCurrentlyRecording()) {
            lifecycleScope.launch {
                val file = svc.stopRecording()
                if (file != null) shareFile(file)
            }
        } else {
            svc.startRecording()
        }
    }

    private fun shareFile(file: java.io.File) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.app_name)))
    }

    private fun observeService() {
        val svc = service ?: return

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.connectionState.collect { state ->
                    binding.statusText.text = when (state) {
                        ConnectionState.Connected -> getString(R.string.status_connected)
                        ConnectionState.Connecting -> getString(R.string.status_connecting)
                        is ConnectionState.Reconnecting -> getString(R.string.status_reconnecting)
                        ConnectionState.Disconnected -> getString(R.string.status_disconnected)
                    }
                    binding.connectButton.text = if (state == ConnectionState.Disconnected) {
                        getString(R.string.connect)
                    } else {
                        getString(R.string.disconnect)
                    }
                    binding.recordButton.isEnabled = state == ConnectionState.Connected
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.liveRows.collect { rows -> adapter.submitList(rows) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.stats.collect { stats ->
                    binding.fpsCounter.text = getString(R.string.fps_format, stats.fps)
                    binding.droppedCounter.text = getString(R.string.dropped_format, stats.parseErrorCount)
                    val recording = svc.isCurrentlyRecording()
                    binding.markerButton.isEnabled = recording
                    binding.recordButton.text = if (recording) {
                        getString(R.string.stop_recording)
                    } else {
                        getString(R.string.start_recording)
                    }
                    binding.recordingTimeText.text = if (recording) {
                        getString(R.string.recording_time_format, formatElapsed(stats.recordingElapsedMs))
                    } else {
                        ""
                    }
                }
            }
        }
    }

    private fun observeDiscovery() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val found = LinkedHashSet<String>()
                DeviceDiscovery().listen().collect { addr ->
                    if (found.add(addr.hostAddress ?: addr.toString())) {
                        binding.discoveredText.text = "Discovered: ${found.joinToString(", ")}"
                    }
                }
            }
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
    }
}
