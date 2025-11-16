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
import com.example.android_streamer.network.RTCPSender
import com.example.android_streamer.network.RTSPClient
import com.example.android_streamer.network.SDPGenerator
import com.example.android_streamer.network.UDPSender
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Complete streaming pipeline integrating:
 * - Camera2 (1080p@60fps or 4K@60fps)
 * - MediaCodec HEVC encoder (hardware-accelerated, zero-copy)
 * - RTP packetizer (RFC 7798)
 * - UDP sender (DatagramChannel with direct buffers, lock-free SPSC queue)
 *
 * RTSP Support (optional):
 * - RTSP control channel (async TCP) - ANNOUNCE, SETUP, RECORD
 * - RTCP Sender Reports (async UDP) - statistics feedback
 * - RTP data path remains unchanged (lock-free, low-latency)
 *
 * Optimized for Android 12+ with aggressive performance tuning.
 */
@RequiresApi(Build.VERSION_CODES.S)
class StreamingPipeline(
    private val context: Context,
    private val config: StreamingConfig
) {
    // Data plane (fast path - unchanged)
    private var camera: Camera2Controller? = null
    private var encoder: HEVCEncoder? = null
    private var packetizer: RTPPacketizer? = null
    private var sender: UDPSender? = null

    // Control plane (async)
    private var rtspClient: RTSPClient? = null
    private var rtcpSender: RTCPSender? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State
    private var isRunning = false

    /**
     * Start the streaming pipeline.
     *
     * @param previewSurface Optional surface for camera preview
     */
    suspend fun start(previewSurface: Surface? = null) {
        if (isRunning) {
            Log.w(TAG, "Pipeline already running")
            return
        }

        try {
            val mode = if (config.useRtsp) "RTSP" else "Pure RTP/UDP"
            Log.i(TAG, "Starting streaming pipeline ($mode): ${config.resolution}@${config.frameRate}fps -> ${config.remoteHost}:${config.remotePort}")

            // 1. Check hardware capabilities
            if (!HEVCEncoder.isHardwareHEVCAvailable()) {
                throw IllegalStateException("Hardware HEVC encoder not available on this device")
            }

            // 2. Create RTP packetizer (needed for SSRC)
            packetizer = RTPPacketizer()

            // 3. Create encoder with format callback for VPS/SPS/PPS
            val csd0Deferred = CompletableDeferred<ByteArray?>()
            encoder = when (config.resolution) {
                Resolution.HD_1080P -> HEVCEncoder.createFor1080p60(
                    onEncodedData = { buffer, info -> handleEncodedData(buffer, info) },
                    onFormatChanged = { csd0 -> csd0Deferred.complete(csd0) }
                )
                Resolution.UHD_4K -> HEVCEncoder.createFor4K60(
                    onEncodedData = { buffer, info -> handleEncodedData(buffer, info) },
                    onFormatChanged = { csd0 -> csd0Deferred.complete(csd0) }
                )
            }

            // 4. Start encoder and get input surface
            val encoderSurface = encoder!!.start()

            // 5. Start camera FIRST so encoder gets frames and triggers format callback
            camera = Camera2Controller(context).apply {
                val (width, height) = config.resolution.getDimensions()
                try {
                    Log.i(TAG, "Starting camera ${width}x${height}@${config.frameRate}fps")
                    startCamera(
                        targetSurface = encoderSurface,
                        previewSurface = previewSurface,
                        width = width,
                        height = height,
                        fps = config.frameRate
                    )
                } catch (e: IllegalStateException) {
                    // Provide helpful error message
                    val suggestion = if (config.resolution == Resolution.UHD_4K) {
                        "Try 1080p instead of 4K, or reduce frame rate"
                    } else {
                        "Try reducing frame rate to 30fps"
                    }
                    throw IllegalStateException(
                        "Camera doesn't support ${width}x${height}@${config.frameRate}fps. $suggestion",
                        e
                    )
                }
            }

            // 6. Wait for encoder format (contains VPS/SPS/PPS) - now that camera is feeding frames
            Log.i(TAG, "Waiting for encoder format callback (CSD-0)...")
            val csd0 = withTimeoutOrNull(5000) {
                Log.d(TAG, "CSD-0 deferred is awaiting...")
                csd0Deferred.await()
            }

            if (csd0 != null) {
                Log.i(TAG, "Received CSD-0: ${csd0.size} bytes")
            } else {
                Log.w(TAG, "CSD-0 timeout - format callback not triggered yet")
            }

            // 7. RTSP handshake (async control plane) - if enabled
            if (config.useRtsp) {
                Log.i(TAG, "Performing RTSP handshake...")

                val (width, height) = config.resolution.getDimensions()
                val rtspUrl = "rtsp://${config.remoteHost}:${config.rtspPort}/${config.rtspPath}"

                // Parse VPS/SPS/PPS from CSD-0 if available
                val (vps, sps, pps) = if (csd0 != null) {
                    Log.i(TAG, "Parsing VPS/SPS/PPS from CSD-0 (${csd0.size} bytes)")
                    SDPGenerator.parseParameterSets(csd0)
                } else {
                    Log.w(TAG, "No CSD-0 data available, SDP will not include parameter sets")
                    Triple(null, null, null)
                }

                val sdp = SDPGenerator.generateH265SDP(
                    // clientAddress will be auto-detected (local device IP)
                    rtpPort = config.rtpPort,
                    width = width,
                    height = height,
                    frameRate = config.frameRate,
                    rtspUrl = rtspUrl,
                    vps = vps,
                    sps = sps,
                    pps = pps
                )

                rtspClient = RTSPClient(
                    host = config.remoteHost,
                    port = config.rtspPort,
                    path = config.rtspPath,
                    rtpPort = config.rtpPort,
                    rtcpPort = config.rtcpPort
                )

                val connected = rtspClient!!.connect(sdp)
                if (!connected) {
                    throw IllegalStateException("RTSP handshake failed - check MediaMTX is running")
                }

                // Start RTSP keep-alive
                rtspClient!!.startKeepAlive()

                Log.i(TAG, "RTSP session established")
            }

            // 8. Create UDP sender (RTP data plane - unchanged)
            sender = UDPSender(config.remoteHost, config.rtpPort).apply {
                start()
            }

            // 9. Create RTCP sender (async control plane) - if RTSP enabled
            if (config.useRtsp) {
                rtcpSender = RTCPSender(
                    remoteHost = config.remoteHost,
                    remotePort = config.rtcpPort,
                    ssrc = packetizer!!.ssrc.toLong()
                )
                rtcpSender!!.start()
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

        // Stop data plane (in reverse order)
        camera?.stopCamera()
        encoder?.stop()
        sender?.stop()

        // Stop control plane (async)
        scope.launch {
            rtspClient?.teardown()
        }
        rtcpSender?.stop()

        // Print statistics
        printStatistics()

        // Clear references
        camera = null
        encoder = null
        packetizer = null
        sender = null
        rtspClient = null
        rtcpSender = null

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
     * Called on encoder callback thread (RTP data plane - unchanged).
     */
    private fun handleEncodedData(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val pkt = packetizer ?: return
        val snd = sender ?: return

        try {
            // Packetize H.265 NAL units into RTP packets
            pkt.packetize(buffer, info) { rtpPacket ->
                // Send RTP packet over UDP (lock-free SPSC queue)
                val packetCopy = rtpPacket.duplicate() // Duplicate for async send
                if (!snd.sendPacket(packetCopy)) {
                    Log.w(TAG, "Failed to send RTP packet (queue full)")
                }
            }

            // Update RTCP statistics (async control plane)
            rtcpSender?.let { rtcp ->
                val stats = snd.getStats()
                rtcp.packetCount = stats.packetsSent
                rtcp.byteCount = stats.bytesSent
                rtcp.lastRtpTimestamp = pkt.getStats().currentTimestamp
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
        val remotePort: Int,  // For backward compatibility (RTP port if pure RTP, or RTSP port if RTSP)

        // RTSP configuration (optional)
        val useRtsp: Boolean = false,
        val rtspPort: Int = 8554,        // MediaMTX RTSP port
        val rtspPath: String = "android", // Stream path (rtsp://host:8554/android)
        val rtpPort: Int = 5004,          // RTP data port (client port)
        val rtcpPort: Int = 5005          // RTCP control port (client port)
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
