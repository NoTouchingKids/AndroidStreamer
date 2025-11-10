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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: Camera2Controller
    private lateinit var encoder: H265Encoder
    private var rtpSender: RTPSender? = null

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
         * ENABLE_NETWORK_STREAMING: Set to true to send encoded video over RTP to MediaMTX
         * RTP_SERVER_IP: IP address of MediaMTX server (LOCAL NETWORK ONLY!)
         * RTP_SERVER_PORT: UDP port for RTP stream (default 8000, check MediaMTX logs)
         *
         * MediaMTX shows the RTP port in its logs:
         *   "INF [RTSP] listener opened on :8554 (TCP), :8000 (UDP/RTP), :8001 (UDP/RTCP)"
         *                                              ^^^^^ Use this port
         *
         * MediaMTX Configuration (mediamtx.yml):
         *   paths:
         *     android:
         *       source: rtp://0.0.0.0:8000
         *       sourceProtocol: rtp
         *
         * View stream: rtsp://server:8554/android
         *
         * See MEDIAMTX_SETUP.md for detailed setup instructions.
         */
        private const val ENABLE_NETWORK_STREAMING = true
        private const val RTP_SERVER_IP = "192.168.1.100" // Change to your MediaMTX server IP
        private const val RTP_SERVER_PORT = 8000          // MediaMTX default RTP port
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

        // Initialize RTP sender if network streaming is enabled
        if (ENABLE_NETWORK_STREAMING) {
            rtpSender = RTPSender(RTP_SERVER_IP, RTP_SERVER_PORT)
            Log.i(TAG, "Network streaming enabled: $RTP_SERVER_IP:$RTP_SERVER_PORT")
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
        val statusMsg = if (rtpSender != null) {
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

        isCapturing = false

        // Reinitialize RTP sender if network streaming is enabled
        if (ENABLE_NETWORK_STREAMING) {
            rtpSender = RTPSender(RTP_SERVER_IP, RTP_SERVER_PORT)
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
        cameraController.release()
        handler.removeCallbacksAndMessages(null)
    }

}
