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
import com.example.android_streamer.network.RTSPClient

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: Camera2Controller
    private lateinit var encoder: H265Encoder
    private var rtpSender: RTPSender? = null
    private var rtspClient: RTSPClient? = null

    private val handler = Handler(Looper.getMainLooper())

    // Track surface availability
    private var previewSurfaceReady = false
    private var encoderSurfaceReady = false
    private var encoderSurface: android.view.Surface? = null

    // Track capture state
    private var isCapturing = false

    // Video configuration (4K@60fps for Samsung Note 10+)
    private val targetWidth = 3840   // 4K UHD
    private val targetHeight = 2160  // 4K UHD
    private var targetFps = 60       // Will be validated based on device support

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
         *
         * Standard RTSP Client Mode (Android = Client, MediaMTX = Server):
         *   Android connects TO MediaMTX and publishes stream
         *   MediaMTX server has stable IP (e.g., 192.168.0.2)
         *   Android connects to: rtsp://MEDIAMTX_IP:8554/android
         *
         *   MediaMTX Configuration (simple!):
         *   # No paths config needed! Just defaults:
         *   rtspAddress: :8554
         *
         *   MediaMTX automatically accepts published streams.
         *
         * View stream: rtsp://MEDIAMTX_IP:8554/android
         *
         * See MEDIAMTX_SETUP.md for detailed setup instructions.
         */
        private const val ENABLE_NETWORK_STREAMING = true

        // MediaMTX server configuration (RTSP client mode)
        private const val MEDIAMTX_SERVER_IP = "192.168.0.2" // Your MediaMTX server IP
        private const val MEDIAMTX_RTSP_PORT = 8554          // MediaMTX RTSP port
        private const val STREAM_PATH = "/android"           // Stream path on server

        // RTP port for data (client side)
        private const val RTP_PORT = 5004
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
            // RTSP client mode: Android connects TO MediaMTX
            rtspClient = RTSPClient(MEDIAMTX_SERVER_IP, MEDIAMTX_RTSP_PORT, STREAM_PATH)
            // Create RTPSender with placeholder port (will be updated after RTSP SETUP)
            // Don't start() it yet - wait for RTSP connection to complete
            rtpSender = RTPSender(MEDIAMTX_SERVER_IP, 0, rtspClient!!.getClientRtpPort())

            // Set callback for when RTSP session is ready
            rtspClient?.onReadyToStream = { serverIp, serverPort ->
                Log.i(TAG, "RTSP session established! Sending RTP to $serverIp:$serverPort")
                rtpSender?.updateDestination(serverIp, serverPort)

                // NOW start the RTP sender with correct destination
                rtpSender?.let {
                    try {
                        it.start()
                        Log.i(TAG, "RTP sender started with correct destination: $serverIp:$serverPort")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start RTP sender", e)
                    }
                }

                // Show connection status
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Connected to MediaMTX!\nStreaming to $serverIp:$serverPort",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateStatus("Streaming to MediaMTX ($MEDIAMTX_SERVER_IP)")
                }
            }

            Log.i(TAG, "RTSP client mode: Will connect to rtsp://$MEDIAMTX_SERVER_IP:$MEDIAMTX_RTSP_PORT$STREAM_PATH")
            Toast.makeText(
                this,
                "MediaMTX Server: $MEDIAMTX_SERVER_IP\nWill publish to: rtsp://$MEDIAMTX_SERVER_IP:$MEDIAMTX_RTSP_PORT$STREAM_PATH",
                Toast.LENGTH_LONG
            ).show()
        }

        // Initialize encoder with detected FPS
        encoder = H265Encoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = 100_000_000, // 100 Mbps for 4K@60fps high quality (complex scenes)
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
        // Set callback to receive codec data when it's ready
        encoder.onCodecDataReady = { vps, sps, pps ->
            Log.i(TAG, "Codec data received: VPS=${vps?.size ?: 0}B, SPS=${sps.size}B, PPS=${pps.size}B")

            // Now we can set stream parameters and connect RTSP client
            rtspClient?.let { client ->
                Log.i(TAG, "Setting codec data in RTSP client: SPS=${sps.size}B, PPS=${pps.size}B")
                client.setStreamParameters(targetWidth, targetHeight, targetFps, sps, pps)

                // Connect RTSP client to MediaMTX
                try {
                    client.connect()
                    Log.i(TAG, "RTSP client connecting to ${client.getServerUrl()}")
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Connecting to MediaMTX...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect RTSP client", e)
                    runOnUiThread {
                        Toast.makeText(this, "Failed to connect to MediaMTX: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Create encoder and get input surface
        encoderSurface = encoder.start()
        encoderSurfaceReady = true
        Log.i(TAG, "Encoder initialized, surface ready")

        // NOTE: RTP sender is started AFTER RTSP connection completes (in onReadyToStream callback)
        // This ensures we send to the correct server port from RTSP SETUP response

        // Update UI status
        val statusMsg = if (rtspClient != null) {
            "Ready - Waiting for codec data..."
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

        // Disconnect RTSP client
        rtspClient?.disconnect()

        isCapturing = false

        // Reinitialize network components if enabled
        if (ENABLE_NETWORK_STREAMING) {
            rtspClient = RTSPClient(MEDIAMTX_SERVER_IP, MEDIAMTX_RTSP_PORT, STREAM_PATH)
            rtpSender = RTPSender(MEDIAMTX_SERVER_IP, 0, rtspClient!!.getClientRtpPort())

            rtspClient?.onReadyToStream = { serverIp, serverPort ->
                Log.i(TAG, "RTSP reconnected! Sending RTP to $serverIp:$serverPort")
                rtpSender?.updateDestination(serverIp, serverPort)

                // Start RTP sender with correct destination
                rtpSender?.let {
                    try {
                        it.start()
                        Log.i(TAG, "RTP sender restarted with correct destination: $serverIp:$serverPort")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart RTP sender", e)
                    }
                }

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Reconnected to MediaMTX!",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateStatus("Streaming to MediaMTX ($MEDIAMTX_SERVER_IP)")
                }
            }
        }

        // Reinitialize encoder for next session
        encoder = H265Encoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = 100_000_000, // 100 Mbps for 4K@60fps high quality (complex scenes)
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
        rtspClient?.disconnect()
        cameraController.release()
        handler.removeCallbacksAndMessages(null)
    }

}
