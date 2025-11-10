package com.example.android_streamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.android_streamer.camera.Camera2Controller
import com.example.android_streamer.databinding.ActivityMainBinding
import com.example.android_streamer.encoder.H265Encoder
import com.example.android_streamer.network.RTPSender
import com.example.android_streamer.network.RTSPServer
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: Camera2Controller
    private lateinit var encoder: H265Encoder
    private var rtpSender: RTPSender? = null
    private var rtspServer: RTSPServer? = null

    private val handler = Handler(Looper.getMainLooper())

    // Track surface availability
    private var previewSurfaceReady = false
    private var encoderSurfaceReady = false
    private var encoderSurface: android.view.Surface? = null

    // Track capture state
    private var isCapturing = false

    // Video configuration (detected from device capabilities)
    private val targetWidth = 1920
    private val targetHeight = 1080
    private var targetFps = 30 // Will be updated based on device support

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        /**
         * Enable preview during capture (for debug/setup only).
         *
         * FALSE (recommended): Encoder-only mode - saves GPU bandwidth, lower latency
         * TRUE: Dual-surface mode - preview visible during capture (debug only)
         */
        private const val ENABLE_PREVIEW_DURING_CAPTURE = false

        /**
         * Network streaming configuration for MediaMTX server.
         *
         * ENABLE_NETWORK_STREAMING: Set to true to enable streaming to MediaMTX
         * USE_RTSP_SERVER_MODE: TRUE (recommended) - Android runs RTSP server, MediaMTX connects to Android
         *                       FALSE - Android pushes raw RTP to MediaMTX (may not work)
         *
         * RTSP Server Mode (USE_RTSP_SERVER_MODE = true):
         *   Android device runs RTSP server on port 8554
         *   MediaMTX connects to: rtsp://ANDROID_IP:8554/live
         *
         *   MediaMTX Configuration (mediamtx.yml):
         *   paths:
         *     android:
         *       source: rtsp://192.168.1.50:8554/live  # Your Android device IP
         *       sourceProtocol: tcp
         *
         * Raw RTP Mode (USE_RTSP_SERVER_MODE = false - NOT RECOMMENDED):
         *   Android pushes RTP to MediaMTX (may not work reliably)
         *   Requires RTP_SERVER_IP and RTP_SERVER_PORT configuration
         *
         * View stream: rtsp://MEDIAMTX_IP:8554/android
         *
         * See MEDIAMTX_SETUP.md for detailed setup instructions.
         */
        private const val ENABLE_NETWORK_STREAMING = true
        private const val USE_RTSP_SERVER_MODE = true     // Recommended!
        private const val RTSP_SERVER_PORT = 8554         // RTSP control port
        private const val RTP_PORT = 5004                 // RTP data port

        // Only used in non-RTSP mode (not recommended)
        private const val RTP_SERVER_IP = "192.168.1.100" // MediaMTX server IP
        private const val RTP_SERVER_PORT = 8000          // MediaMTX RTP port
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Camera2 controller FIRST (need it for capability detection)
        cameraController = Camera2Controller(this)

        // Detect maximum supported FPS for target resolution
        targetFps = cameraController.getMaxSupportedFps(targetWidth, targetHeight)
        Log.i(TAG, "Using configuration: ${targetWidth}x${targetHeight}@${targetFps}fps")

        // Show user the detected configuration
        Toast.makeText(
            this,
            "Detected: ${targetWidth}x${targetHeight}@${targetFps}fps",
            Toast.LENGTH_SHORT
        ).show()

        // Initialize network streaming if enabled
        if (ENABLE_NETWORK_STREAMING) {
            if (USE_RTSP_SERVER_MODE) {
                // RTSP server mode: Android runs RTSP server, MediaMTX connects
                val deviceIp = getLocalIpAddress()
                if (deviceIp != null) {
                    rtpSender = RTPSender(deviceIp, RTP_PORT) // Initial placeholder, will be updated when client connects
                    rtspServer = RTSPServer(RTSP_SERVER_PORT, RTP_PORT, deviceIp)

                    // Set callback to update RTP destination when MediaMTX connects
                    rtspServer?.onClientReady = { clientIp, clientPort ->
                        Log.i(TAG, "MediaMTX connected! Sending RTP to $clientIp:$clientPort")
                        rtpSender?.updateDestination(clientIp, clientPort)
                    }

                    Log.i(TAG, "RTSP server mode enabled. MediaMTX should connect to: rtsp://$deviceIp:$RTSP_SERVER_PORT/live")
                    Toast.makeText(
                        this,
                        "RTSP: rtsp://$deviceIp:$RTSP_SERVER_PORT/live",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.e(TAG, "Cannot get device IP address for RTSP server")
                    Toast.makeText(this, "Error: Cannot get device IP", Toast.LENGTH_LONG).show()
                }
            } else {
                // Raw RTP mode: Android pushes to MediaMTX
                rtpSender = RTPSender(RTP_SERVER_IP, RTP_SERVER_PORT)
                Log.i(TAG, "Raw RTP mode enabled: $RTP_SERVER_IP:$RTP_SERVER_PORT")
            }
        }

        // Initialize encoder with detected FPS
        encoder = H265Encoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = 8_000_000, // 8 Mbps
            frameRate = targetFps,
            rtpSender = rtpSender
        )

        // Setup preview surface callback
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface created")
                previewSurfaceReady = true
                // Don't auto-start - wait for user button press
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Preview surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface destroyed")
                previewSurfaceReady = false
            }
        })

        // Setup button handlers
        binding.btnStart.setOnClickListener {
            startCapture()
        }

        binding.btnStop.setOnClickListener {
            stopCapture()
        }

        // Check and request camera permissions
        if (allPermissionsGranted()) {
            initializeEncoder()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Start stats update loop
        startStatsUpdater()
    }

    private fun initializeEncoder() {
        // Start RTSP server if configured
        rtspServer?.let {
            try {
                it.start()
                Log.i(TAG, "RTSP server started: ${it.getConnectionUrl()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RTSP server", e)
                Toast.makeText(this, "Failed to start RTSP server: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Start RTP sender if configured
        rtpSender?.let {
            try {
                it.start()
                Log.i(TAG, "RTP sender started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RTP sender", e)
                Toast.makeText(this, "Failed to start network sender: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Create encoder and get input surface
        encoderSurface = encoder.start()
        encoderSurfaceReady = true
        Log.i(TAG, "Encoder initialized, surface ready")

        // Update UI status
        val statusMsg = if (USE_RTSP_SERVER_MODE && rtspServer != null) {
            "Ready (RTSP: ${rtspServer?.getConnectionUrl()})"
        } else if (rtpSender != null) {
            "Ready to capture (Network: $RTP_SERVER_IP:$RTP_SERVER_PORT)"
        } else {
            "Ready to capture (Local mode)"
        }
        updateStatus(statusMsg)
    }

    private fun startCapture() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing!")
            return
        }

        // Check surfaces are ready
        val surfacesReady = if (ENABLE_PREVIEW_DURING_CAPTURE) {
            previewSurfaceReady && encoderSurfaceReady
        } else {
            encoderSurfaceReady
        }

        if (!surfacesReady) {
            Log.e(TAG, "Surfaces not ready: preview=$previewSurfaceReady, encoder=$encoderSurfaceReady")
            updateStatus("Error: Surfaces not ready")
            return
        }

        // Get encoder surface
        val encoderSurface = this.encoderSurface ?: run {
            Log.e(TAG, "Encoder surface is null!")
            updateStatus("Error: Encoder not initialized")
            return
        }

        // Verify surface is valid
        if (!encoderSurface.isValid) {
            Log.e(TAG, "Encoder surface is INVALID! Surface was released or destroyed.")
            updateStatus("Error: Encoder surface invalid")
            return
        }
        Log.i(TAG, "Encoder surface is VALID (isValid=true)")

        // Conditionally add preview surface
        val previewSurface = if (ENABLE_PREVIEW_DURING_CAPTURE && previewSurfaceReady) {
            binding.previewSurface.holder.surface
        } else {
            null
        }

        val mode = if (previewSurface != null) "dual-surface (debug)" else "encoder-only (production)"
        Log.i(TAG, "Starting Camera2 in $mode mode")

        // Start camera with detected configuration
        cameraController.start(
            encoderSurface = encoderSurface,
            previewSurface = previewSurface,
            width = targetWidth,
            height = targetHeight,
            fps = targetFps
        )

        isCapturing = true

        // Update UI
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        updateStatus("Capturing...")

        Log.i(TAG, "Camera2 started: TRUE ZERO-COPY PIPELINE ACTIVE ($mode)")
    }

    private fun stopCapture() {
        if (!isCapturing) {
            Log.w(TAG, "Not capturing!")
            return
        }

        Log.i(TAG, "Stopping capture...")

        // Stop camera
        cameraController.stop()

        // Stop encoder
        encoder.stop()

        // Stop RTP sender
        rtpSender?.stop()

        // Stop RTSP server
        rtspServer?.stop()

        isCapturing = false

        // Reinitialize network components if enabled
        if (ENABLE_NETWORK_STREAMING) {
            if (USE_RTSP_SERVER_MODE) {
                val deviceIp = getLocalIpAddress()
                if (deviceIp != null) {
                    rtpSender = RTPSender(deviceIp, RTP_PORT)
                    rtspServer = RTSPServer(RTSP_SERVER_PORT, RTP_PORT, deviceIp)
                    rtspServer?.onClientReady = { clientIp, clientPort ->
                        Log.i(TAG, "MediaMTX reconnected! Sending RTP to $clientIp:$clientPort")
                        rtpSender?.updateDestination(clientIp, clientPort)
                    }
                }
            } else {
                rtpSender = RTPSender(RTP_SERVER_IP, RTP_SERVER_PORT)
            }
        }

        // Reinitialize encoder for next session
        encoder = H265Encoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = 8_000_000,
            frameRate = targetFps,  // Use detected FPS, not hardcoded 60
            rtpSender = rtpSender
        )
        initializeEncoder()

        // Update UI
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        updateStatus("Ready to capture")

        Log.i(TAG, "Capture stopped, ready for next session")
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.tvStatus.text = "Status: $status"
        }
    }

    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val encoderStats = encoder.getStats()
                val cameraStats = cameraController.getStats()

                // Calculate drop rate
                val totalFrames = encoderStats.encodedFrames + encoderStats.droppedFrames
                val dropRate = if (totalFrames > 0) {
                    (encoderStats.droppedFrames.toFloat() / totalFrames) * 100
                } else {
                    0f
                }

                // Update UI
                binding.tvFrameCount.text = "Encoded: ${encoderStats.encodedFrames} (${encoderStats.keyFrames} I-frames)"
                binding.tvDroppedFrames.text = String.format(
                    "Dropped: %d (%.2f%%)",
                    encoderStats.droppedFrames,
                    dropRate
                )
                binding.tvBufferOccupancy.text = "Ring: ${encoderStats.ringOccupancy}/32"
                binding.tvFps.text = "Camera: ${cameraStats.capturedFrames} frames"

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
            if (allPermissionsGranted()) {
                initializeEncoder()
            } else {
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
        if (isCapturing) {
            stopCapture()
        }
        rtpSender?.stop()
        rtspServer?.stop()
        cameraController.release()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Get local IP address of device for RTSP server.
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Filter: IPv4, not loopback
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        Log.d(TAG, "Found IP address: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return null
    }

}
