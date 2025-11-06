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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: Camera2Controller
    private lateinit var encoder: H265Encoder

    private val handler = Handler(Looper.getMainLooper())

    // Track surface availability
    private var previewSurfaceReady = false
    private var encoderSurfaceReady = false

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize encoder (1080p@60fps, 8 Mbps)
        encoder = H265Encoder(
            width = 1920,
            height = 1080,
            bitrate = 8_000_000, // 8 Mbps
            frameRate = 60
        )

        // Initialize Camera2 controller
        cameraController = Camera2Controller(this)

        // Check camera capabilities
        if (!cameraController.checkCapabilities(1920, 1080, 60)) {
            Toast.makeText(
                this,
                "Device doesn't support 1080p@60fps",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Device doesn't support target configuration, trying anyway...")
        }

        // Setup preview surface callback
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface created")
                previewSurfaceReady = true
                tryStartCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Preview surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Preview surface destroyed")
                previewSurfaceReady = false
            }
        })

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
        // Start encoder and get input surface
        encoder.start()
        encoderSurfaceReady = true
        Log.i(TAG, "Encoder started, surface ready")

        tryStartCamera()
    }

    private fun tryStartCamera() {
        // For encoder-only mode, only wait for encoder surface
        // For dual-surface mode, wait for both surfaces
        val surfacesReady = if (ENABLE_PREVIEW_DURING_CAPTURE) {
            previewSurfaceReady && encoderSurfaceReady
        } else {
            encoderSurfaceReady
        }

        if (!surfacesReady) {
            Log.d(TAG, "Waiting for surfaces: preview=$previewSurfaceReady, encoder=$encoderSurfaceReady")
            return
        }

        val encoderSurface = encoder.start() // Get encoder surface

        // Conditionally add preview surface
        val previewSurface = if (ENABLE_PREVIEW_DURING_CAPTURE && previewSurfaceReady) {
            binding.previewSurface.holder.surface
        } else {
            null
        }

        val mode = if (previewSurface != null) "dual-surface (debug)" else "encoder-only (production)"
        Log.i(TAG, "Starting Camera2 in $mode mode")

        // Start camera (encoder-only or dual-surface)
        cameraController.start(
            encoderSurface = encoderSurface,
            previewSurface = previewSurface,
            width = 1920,
            height = 1080,
            fps = 60
        )

        Log.i(TAG, "Camera2 started: TRUE ZERO-COPY PIPELINE ACTIVE ($mode)")
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
        cameraController.stop()
        encoder.stop()
        handler.removeCallbacksAndMessages(null)
    }

}
