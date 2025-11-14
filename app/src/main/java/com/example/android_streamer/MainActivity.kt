package com.example.android_streamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.android_streamer.databinding.ActivityMainBinding

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

        try {
            val remoteHost = binding.etRemoteHost.text.toString()
            val remotePort = binding.etRemotePort.text.toString().toIntOrNull() ?: 5004

            if (remoteHost.isEmpty()) {
                Toast.makeText(this, "Please enter remote host", Toast.LENGTH_SHORT).show()
                return
            }

            Log.i(TAG, "Starting streaming to $remoteHost:$remotePort")

            // Create streaming configuration
            val config = StreamingPipeline.StreamingConfig(
                resolution = StreamingPipeline.Resolution.HD_1080P, // Start with 1080p
                frameRate = 60,
                remoteHost = remoteHost,
                remotePort = remotePort
            )

            // Create and start pipeline
            streamingPipeline = StreamingPipeline(this, config).apply {
                start()
            }

            isStreaming = true
            binding.btnStartStop.text = "Stop Streaming"
            binding.tvStatus.text = "Status: Streaming"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_light))

            Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            stopStreaming()
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
                        "UDP Sent: %d (%d KB)",
                        stats.udpPackets,
                        stats.udpBytes / 1024
                    )

                    binding.tvErrors.text = "Errors: ${stats.udpErrors}"

                    // Update error text color
                    if (stats.udpErrors > 0) {
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
