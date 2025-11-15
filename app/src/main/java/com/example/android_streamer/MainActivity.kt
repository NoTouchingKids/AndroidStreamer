package com.example.android_streamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.android_streamer.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.S)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var streamingPipeline: StreamingPipeline? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup button listener
        binding.btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        // Setup RTSP checkbox listener
        binding.cbUseRtsp.setOnCheckedChangeListener { _, isChecked ->
            binding.etRtspPath.visibility = if (isChecked) View.VISIBLE else View.GONE

            // Update port hint
            if (isChecked) {
                binding.etRemotePort.hint = "RTSP Port (e.g., 8554)"
                if (binding.etRemotePort.text.toString() == "5004") {
                    binding.etRemotePort.setText("8554")
                }
            } else {
                binding.etRemotePort.hint = "RTP Port (e.g., 5004)"
                if (binding.etRemotePort.text.toString() == "8554") {
                    binding.etRemotePort.setText("5004")
                }
            }
        }

        // Check and request camera permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Start stats updater
        startStatsUpdater()
    }

    private fun startStreaming() {
        if (!allPermissionsGranted()) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val remoteHost = binding.etRemoteHost.text.toString()
                val remotePort = binding.etRemotePort.text.toString().toIntOrNull() ?: 5004
                val useRtsp = binding.cbUseRtsp.isChecked
                val rtspPath = binding.etRtspPath.text.toString().ifEmpty { "android" }

                if (remoteHost.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter remote host", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val mode = if (useRtsp) "RTSP" else "Pure RTP/UDP"
                Log.i(TAG, "Starting streaming ($mode) to $remoteHost:$remotePort")

                // Create streaming configuration
                val config = StreamingPipeline.StreamingConfig(
                    resolution = StreamingPipeline.Resolution.HD_1080P, // Start with 1080p
                    frameRate = 60,
                    remoteHost = remoteHost,
                    remotePort = remotePort,
                    useRtsp = useRtsp,
                    rtspPort = if (useRtsp) remotePort else 8554,
                    rtspPath = rtspPath,
                    rtpPort = if (useRtsp) 5004 else remotePort,  // For RTSP, use 5004 for RTP data
                    rtcpPort = 5005
                )

                // Create and start pipeline (suspend function)
                streamingPipeline = StreamingPipeline(this@MainActivity, config).apply {
                    start()  // This is now a suspend function
                }

                isStreaming = true
                binding.btnStartStop.text = "Stop Streaming"
                binding.tvStatus.text = "Status: Streaming ($mode)"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_light))

                Toast.makeText(this@MainActivity, "Streaming started", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
                Toast.makeText(this@MainActivity, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        try {
            streamingPipeline?.stop()
            streamingPipeline = null

            isStreaming = false
            binding.btnStartStop.text = "Start Streaming"
            binding.tvStatus.text = "Status: Stopped"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))

            Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping streaming", e)
        }
    }

    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                streamingPipeline?.let { pipeline ->
                    val stats = pipeline.getStatistics()

                    binding.tvRtpPackets.text = String.format(
                        "RTP Packets: %d (%d KB)",
                        stats.rtpPackets,
                        stats.rtpBytes / 1024
                    )

                    binding.tvUdpPackets.text = String.format(
                        "UDP Sent: %d (%d KB) | Queue: %d/512",
                        stats.udpPackets,
                        stats.udpBytes / 1024,
                        stats.queueOccupancy
                    )

                    binding.tvErrors.text = String.format(
                        "Dropped: %d | Errors: %d",
                        stats.udpDropped,
                        stats.udpErrors
                    )

                    // Update status based on health
                    if (pipeline.isHealthy()) {
                        binding.tvStatus.text = "Status: Streaming (Healthy)"
                        binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    } else {
                        binding.tvStatus.text = "Status: Streaming (Issues Detected)"
                        binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                    }

                    // Update error text color based on drop/error rates
                    if (stats.udpErrors > 0 || stats.udpDropped > stats.udpPackets / 100) {
                        binding.tvErrors.setTextColor(getColor(android.R.color.holo_red_light))
                    } else {
                        binding.tvErrors.setTextColor(getColor(android.R.color.holo_green_light))
                    }
                }

                // Update every 500ms
                handler.postDelayed(this, 500)
            }
        }
        handler.post(updateRunnable)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(
                    this,
                    "Camera permissions are required to use this app",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
