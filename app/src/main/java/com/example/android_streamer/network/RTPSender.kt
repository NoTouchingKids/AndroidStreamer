package com.example.android_streamer.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Simple RTP sender for H.265/HEVC to MediaMTX server (LOCAL NETWORK ONLY).
 *
 * Zero-copy, zero-allocation design:
 * - Pre-allocated packet buffer (reused for all sends)
 * - Direct ByteBuffer operations (no array copying)
 * - Fire-and-forget UDP (no retries, no RTSP handshake)
 * - Proper RTP headers for MediaMTX compatibility
 *
 * MediaMTX Configuration:
 * Configure MediaMTX to accept RTP on the specified port:
 *
 * paths:
 *   mystream:
 *     source: rtp://0.0.0.0:5004
 *     sourceProtocol: rtp
 *
 * Then view with: rtsp://server:8554/mystream
 *
 * @param serverIp IP address of MediaMTX server (e.g., "192.168.1.100")
 * @param serverPort RTP port (e.g., 5004)
 */
class RTPSender(
    private val serverIp: String,
    private val serverPort: Int
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    // Pre-allocated packet buffer (max 64KB for local network)
    // MediaMTX can handle large RTP packets on local network
    private val packetBuffer = ByteArray(65507) // Max UDP payload size
    private val packet = DatagramPacket(packetBuffer, packetBuffer.size)

    // RTP state
    private var sequenceNumber: Int = 1 // Start at 1
    private val ssrc: Int = 0x12345678 // Fixed SSRC for this session

    // RTP constants
    private val RTP_VERSION = 2
    private val RTP_PAYLOAD_TYPE = 96 // H.265

    // Stats
    @Volatile
    var packetsSent = 0L
        private set

    @Volatile
    var bytesSent = 0L
        private set

    /**
     * Initialize RTP sender for MediaMTX.
     */
    fun start() {
        Log.i(TAG, "Starting RTP sender to MediaMTX at $serverIp:$serverPort")

        try {
            serverAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket()

            // Optimize for local network
            socket?.sendBufferSize = 256 * 1024 // 256KB send buffer
            socket?.trafficClass = 0x10 // IPTOS_LOWDELAY

            Log.i(TAG, "RTP sender started: SSRC=0x${ssrc.toString(16)}, seq=$sequenceNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTP sender", e)
            throw e
        }
    }

    /**
     * Send H.265 frame to MediaMTX over RTP.
     *
     * Zero-copy, zero-allocation hot path:
     * - Reuses pre-allocated packet buffer
     * - Direct ByteBuffer.get() to array
     * - No intermediate allocations
     * - Proper RTP header for MediaMTX
     *
     * Packet format:
     * [12 bytes] RTP header (standard RFC 3550)
     * [N bytes]  H.265 NAL unit data
     *
     * For local network, we send entire frame in one packet.
     * MediaMTX will handle this fine on gigabit LAN.
     *
     * @param buffer MediaCodec output buffer (positioned at frame data)
     * @param timestampUs Presentation timestamp in microseconds
     * @param isKeyFrame Whether this is a keyframe
     */
    fun sendFrame(buffer: ByteBuffer, timestampUs: Long, isKeyFrame: Boolean) {
        val sock = socket ?: return
        val addr = serverAddress ?: return

        val frameSize = buffer.remaining()

        // Check if frame fits (should be fine for local network at 8Mbps)
        val headerSize = 12 // RTP header
        if (frameSize + headerSize > packetBuffer.size) {
            Log.w(TAG, "Frame too large: $frameSize bytes (dropping)")
            return
        }

        try {
            // Build RTP header (12 bytes, NO ALLOCATIONS)
            var offset = 0

            // Byte 0: V(2)=2, P(1)=0, X(1)=0, CC(4)=0
            packetBuffer[offset++] = (RTP_VERSION shl 6).toByte()

            // Byte 1: M(1)=1, PT(7)=96 (H.265)
            // Marker bit set on every packet (indicates frame boundary)
            packetBuffer[offset++] = (0x80 or RTP_PAYLOAD_TYPE).toByte()

            // Bytes 2-3: Sequence number (big-endian, 16-bit)
            packetBuffer[offset++] = (sequenceNumber shr 8).toByte()
            packetBuffer[offset++] = (sequenceNumber and 0xFF).toByte()

            // Bytes 4-7: Timestamp (90kHz clock for video)
            val rtpTimestamp = (timestampUs * 90 / 1000).toInt()
            packetBuffer[offset++] = (rtpTimestamp shr 24).toByte()
            packetBuffer[offset++] = (rtpTimestamp shr 16).toByte()
            packetBuffer[offset++] = (rtpTimestamp shr 8).toByte()
            packetBuffer[offset++] = (rtpTimestamp and 0xFF).toByte()

            // Bytes 8-11: SSRC (big-endian)
            packetBuffer[offset++] = (ssrc shr 24).toByte()
            packetBuffer[offset++] = (ssrc shr 16).toByte()
            packetBuffer[offset++] = (ssrc shr 8).toByte()
            packetBuffer[offset++] = (ssrc and 0xFF).toByte()

            // Copy H.265 data directly from ByteBuffer
            buffer.get(packetBuffer, offset, frameSize)
            buffer.rewind()

            // Send packet (fire and forget!)
            packet.address = addr
            packet.port = serverPort
            packet.length = offset + frameSize
            sock.send(packet)

            // Update stats and sequence
            packetsSent++
            bytesSent += packet.length
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF // Wrap at 65535

            if (isKeyFrame) {
                Log.d(TAG, "Sent keyframe: $frameSize bytes, seq=$sequenceNumber, ts=$rtpTimestamp")
            }

        } catch (e: IOException) {
            // Fire and forget - just log and continue
            Log.w(TAG, "Send failed (seq=$sequenceNumber): ${e.message}")
        }
    }

    /**
     * Stop RTP sender.
     */
    fun stop() {
        Log.i(TAG, "Stopping RTP sender. Stats: packets=$packetsSent, bytes=${bytesSent / 1024}KB")
        socket?.close()
        socket = null
        serverAddress = null
    }

    companion object {
        private const val TAG = "RTPSender"
    }
}
