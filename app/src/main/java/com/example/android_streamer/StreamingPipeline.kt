package com.example.android_streamer

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.example.android_streamer.camera.Camera2Controller
import com.example.android_streamer.encoder.HEVCEncoder
import com.example.android_streamer.network.RTPPacketizer
import com.example.android_streamer.network.UDPSender
import java.nio.ByteBuffer

/**
 * Complete streaming pipeline integrating:
 * - Camera2 (1080p@60fps or 4K@60fps)
 * - MediaCodec HEVC encoder (hardware-accelerated, zero-copy)
 * - RTP packetizer (RFC 7798)
 * - UDP sender (DatagramChannel with direct buffers)
 *
 * Optimized for Android 12+ with aggressive performance tuning.
 */
@RequiresApi(Build.VERSION_CODES.S)
class StreamingPipeline(
    private val context: Context,
    private val config: StreamingConfig
) {
    // Components
    private var camera: Camera2Controller? = null
    private var encoder: HEVCEncoder? = null
    private var packetizer: RTPPacketizer? = null
    private var sender: UDPSender? = null

    // State
    private var isRunning = false

    /**
     * Start the streaming pipeline.
     *
     * @param previewSurface Optional surface for camera preview
     */
    fun start(previewSurface: Surface? = null) {
        if (isRunning) {
            Log.w(TAG, "Pipeline already running")
            return
        }

        try {
            Log.i(TAG, "Starting streaming pipeline: ${config.resolution}@${config.frameRate}fps -> ${config.remoteHost}:${config.remotePort}")

            // 1. Check hardware capabilities
            if (!HEVCEncoder.isHardwareHEVCAvailable()) {
                throw IllegalStateException("Hardware HEVC encoder not available")
            }

            // 2. Create UDP sender
            sender = UDPSender(config.remoteHost, config.remotePort).apply {
                start()
            }

            // 3. Create RTP packetizer
            packetizer = RTPPacketizer()

            // 4. Create encoder with callback
            encoder = when (config.resolution) {
                Resolution.HD_1080P -> HEVCEncoder.createFor1080p60 { buffer, info ->
                    handleEncodedData(buffer, info)
                }
                Resolution.UHD_4K -> HEVCEncoder.createFor4K60 { buffer, info ->
                    handleEncodedData(buffer, info)
                }
            }

            // 5. Start encoder and get input surface
            val encoderSurface = encoder!!.start()

            // 6. Create and start camera
            camera = Camera2Controller(context).apply {
                val (width, height) = config.resolution.getDimensions()
                startCamera(
                    targetSurface = encoderSurface,
                    previewSurface = previewSurface,
                    width = width,
                    height = height,
                    fps = config.frameRate
                )
            }

            isRunning = true
            Log.i(TAG, "Streaming pipeline started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming pipeline", e)
            stop()
            throw e
        }
    }

    /**
     * Stop the streaming pipeline.
     */
    fun stop() {
        if (!isRunning) {
            return
        }

        Log.i(TAG, "Stopping streaming pipeline...")

        // Stop in reverse order
        camera?.stopCamera()
        encoder?.stop()
        sender?.stop()

        // Print statistics
        printStatistics()

        // Clear references
        camera = null
        encoder = null
        packetizer = null
        sender = null

        isRunning = false
        Log.i(TAG, "Streaming pipeline stopped")
    }

    /**
     * Request I-frame (keyframe) immediately.
     */
    fun requestKeyframe() {
        encoder?.requestSyncFrame()
    }

    /**
     * Adjust bitrate dynamically (for network adaptation).
     */
    fun setBitrate(bitrateMbps: Int) {
        encoder?.setBitrate(bitrateMbps * 1_000_000)
    }

    /**
     * Handle encoded data from MediaCodec.
     * Called on encoder callback thread.
     */
    private fun handleEncodedData(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val pkt = packetizer ?: return
        val snd = sender ?: return

        try {
            // Packetize H.265 NAL units into RTP packets
            pkt.packetize(buffer, info) { rtpPacket ->
                // Send RTP packet over UDP
                val packetCopy = rtpPacket.duplicate() // Duplicate for async send
                if (!snd.sendPacket(packetCopy)) {
                    Log.w(TAG, "Failed to send RTP packet (queue full)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing encoded data", e)
        }
    }

    /**
     * Print pipeline statistics.
     */
    private fun printStatistics() {
        packetizer?.let { pkt ->
            val stats = pkt.getStats()
            Log.i(TAG, "RTP Packetizer: ${stats.totalPackets} packets, ${stats.totalBytes / 1024}KB")
        }

        sender?.let { snd ->
            val stats = snd.getStats()
            Log.i(TAG, "UDP Sender: ${stats.packetsSent} packets, ${stats.bytesSent / 1024}KB, " +
                    "Dropped: ${stats.packetsDropped}, Errors: ${stats.sendErrors}")

            if (!snd.isHealthy()) {
                Log.w(TAG, "WARNING: UDP sender health check failed - high drop/error rate")
            }
        }
    }

    /**
     * Get current pipeline statistics.
     */
    fun getStatistics(): PipelineStats {
        val packetizerStats = packetizer?.getStats()
        val senderStats = sender?.getStats()

        return PipelineStats(
            rtpPackets = packetizerStats?.totalPackets ?: 0,
            rtpBytes = packetizerStats?.totalBytes ?: 0,
            udpPackets = senderStats?.packetsSent ?: 0,
            udpBytes = senderStats?.bytesSent ?: 0,
            udpErrors = senderStats?.sendErrors ?: 0,
            udpDropped = senderStats?.packetsDropped ?: 0,
            queueOccupancy = senderStats?.queueOccupancy ?: 0
        )
    }

    /**
     * Check if pipeline is healthy (low error/drop rates).
     */
    fun isHealthy(): Boolean {
        return sender?.isHealthy() ?: false
    }

    /**
     * Streaming configuration.
     */
    data class StreamingConfig(
        val resolution: Resolution,
        val frameRate: Int = 60,
        val remoteHost: String,
        val remotePort: Int
    )

    /**
     * Supported resolutions.
     */
    enum class Resolution {
        HD_1080P,
        UHD_4K;

        fun getDimensions(): Pair<Int, Int> = when (this) {
            HD_1080P -> Pair(1920, 1080)
            UHD_4K -> Pair(3840, 2160)
        }
    }

    /**
     * Pipeline statistics.
     */
    data class PipelineStats(
        val rtpPackets: Long,
        val rtpBytes: Long,
        val udpPackets: Long,
        val udpBytes: Long,
        val udpErrors: Long,
        val udpDropped: Long,
        val queueOccupancy: Int
    )

    companion object {
        private const val TAG = "StreamingPipeline"
    }
}
