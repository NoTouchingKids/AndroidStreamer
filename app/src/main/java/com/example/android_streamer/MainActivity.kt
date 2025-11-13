package com.example.android_streamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private var encoderSurfaceReady = false
    private var encoderSurface: android.view.Surface? = null
    private var isCapturing = false

    private val targetWidth = 3840
    private val targetHeight = 2160
    private var targetFps = 60
    private var targetBitrate = 150_000_000  // Default 150 Mbps for 4k60

    companion object {
        private const val TAG = "AndroidStreamer"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private const val MEDIAMTX_SERVER_IP = "192.168.0.2"
        private const val MEDIAMTX_RTSP_PORT = 8554
        private const val STREAM_PATH = "/android"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraController = Camera2Controller(this)
        targetFps = cameraController.getMaxSupportedFps(targetWidth, targetHeight)

        rtspClient = RTSPClient(MEDIAMTX_SERVER_IP, MEDIAMTX_RTSP_PORT, STREAM_PATH)
        rtpSender = RTPSender(MEDIAMTX_SERVER_IP, 0, rtspClient!!.getClientRtpPort())

        // Start RTP sender thread immediately so it's ready when frames arrive
        rtpSender?.let {
            if (!it.start()) {
                Log.e(TAG, "Failed to start RTP sender, streaming will not work")
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Failed to start RTP sender", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        rtspClient?.onReadyToStream = { serverIp, serverPort ->
            // Update destination when RTSP negotiation completes
            rtpSender?.updateDestination(serverIp, serverPort)
            runOnUiThread { updateStatus("Streaming to $serverIp:$serverPort") }
        }

        encoder = H265Encoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = targetBitrate,
            frameRate = targetFps,
            rtpSender = rtpSender
        )

        // Bitrate control
        binding.seekBarBitrate.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                targetBitrate = progress * 1_000_000  // Convert Mbps to bps
                binding.tvBitrate.text = "Bitrate: $progress Mbps"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnStart.setOnClickListener { startCapture() }
        binding.btnStop.setOnClickListener { stopCapture() }

        if (allPermissionsGranted()) {
            initializeEncoder()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        startStatsUpdater()
    }

    private fun initializeEncoder() {
        encoder.onCodecDataReady = { vps, sps, pps ->
            rtspClient?.let { client ->
                client.setStreamParameters(targetWidth, targetHeight, targetFps, sps, pps)
                try {
                    client.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "RTSP connect failed", e)
                    runOnUiThread {
                        updateStatus("Connection failed")
                        android.widget.Toast.makeText(this@MainActivity, "Failed to connect to server", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        encoderSurface = encoder.start()
        encoderSurfaceReady = true
        updateStatus("Ready to stream")
    }

    private fun startCapture() {
        if (isCapturing) return

        val surface = encoderSurface ?: run {
            Log.e(TAG, "Encoder surface null")
            return
        }

        if (!surface.isValid) {
            Log.e(TAG, "Encoder surface invalid")
            return
        }

        cameraController.start(
            encoderSurface = surface,
            width = targetWidth,
            height = targetHeight,
            fps = targetFps
        )

        isCapturing = true
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        binding.seekBarBitrate.isEnabled = false
        updateStatus("Connecting to server...")
    }

    private fun stopCapture() {
        if (!isCapturing) return

        cameraController.stop()
        encoder.stop()
        rtpSender?.stop()
        rtspClient?.disconnect()

        isCapturing = false

        rtspClient = RTSPClient(MEDIAMTX_SERVER_IP, MEDIAMTX_RTSP_PORT, STREAM_PATH)
        rtpSender = RTPSender(MEDIAMTX_SERVER_IP, 0, rtspClient!!.getClientRtpPort())

        // Start RTP sender thread immediately so it's ready when frames arrive
        rtpSender?.let {
            if (!it.start()) {
                Log.e(TAG, "Failed to restart RTP sender")
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Failed to restart RTP sender", android.widget.Toast.LENGTH_SHORT).show()
                    updateStatus("RTP sender failed")
                }
            }
        }

        rtspClient?.onReadyToStream = { serverIp, serverPort ->
            // Update destination when RTSP negotiation completes
            rtpSender?.updateDestination(serverIp, serverPort)
            runOnUiThread { updateStatus("Streaming to $serverIp:$serverPort") }
        }

        encoder = H265Encoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = 75_000_000,  // Increased from 50 Mbps for better quality
            frameRate = targetFps,
            rtpSender = rtpSender
        )
        initializeEncoder()

        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        binding.seekBarBitrate.isEnabled = true
        updateStatus("Stopped")
    }

    private fun updateStatus(status: String) {
        runOnUiThread { binding.tvStatus.text = "Status: $status (4K60)" }
    }

    private fun startStatsUpdater() {
        val updateRunnable = object : Runnable {
            override fun run() {
                val stats = encoder.getStats()
                val totalFrames = stats.encodedFrames + stats.droppedFrames
                val dropRate = if (totalFrames > 0) {
                    (stats.droppedFrames.toFloat() / totalFrames) * 100
                } else {
                    0f
                }

                binding.tvFrameCount.text = "Encoded: ${stats.encodedFrames}"
                binding.tvDroppedFrames.text = String.format("Dropped: %d (%.1f%%)", stats.droppedFrames, dropRate)
                binding.tvBufferOccupancy.text = "Ring: ${stats.ringOccupancy}/32"
                binding.tvFps.text = "FPS: ${cameraController.getStats().capturedFrames}"

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
