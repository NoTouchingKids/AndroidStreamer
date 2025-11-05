package com.example.android_streamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.android_streamer.buffer.RingBuffer
import com.example.android_streamer.camera.CameraController
import com.example.android_streamer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController

    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameTime = 0L
    private var frameCountForFps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize camera controller
        cameraController = CameraController(this, this)

        // Check and request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
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

    private fun startCamera() {
        cameraController.startCamera(binding.previewView) { readSlot ->
            // Frame callback - this is where we'd send to encoder/network
            processFrame(readSlot)
        }
        Log.i(TAG, "Camera started")
    }

    private fun processFrame(readSlot: RingBuffer.ReadSlot) {
        // Calculate FPS
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime == 0L) {
            lastFrameTime = currentTime
        }

        frameCountForFps++

        // Update FPS every second
        val elapsed = currentTime - lastFrameTime
        if (elapsed >= 1000) {
            val fps = (frameCountForFps * 1000.0) / elapsed
            lastFrameTime = currentTime
            frameCountForFps = 0

            // Update UI on main thread
            handler.post {
                binding.tvFps.text = String.format("FPS: %.1f", fps)
            }
        }

        // TODO: Send frame to encoder/RTP packetizer
        Log.v(TAG, "Frame received: ${readSlot.frameSize} bytes, timestamp: ${readSlot.timestampNs}")
    }

    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val stats = cameraController.getStats()
                val dropRate = if (stats.totalFrames > 0) {
                    (stats.droppedFrames.toFloat() / stats.totalFrames) * 100
                } else {
                    0f
                }

                binding.tvFrameCount.text = "Frames: ${stats.totalFrames}"
                binding.tvDroppedFrames.text = String.format(
                    "Dropped: %d (%.2f%%)",
                    stats.droppedFrames,
                    dropRate
                )
                binding.tvBufferOccupancy.text = "Buffer: ${stats.bufferOccupancy}/20"

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
                startCamera()
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
        cameraController.stopCamera()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
