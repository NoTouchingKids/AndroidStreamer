package com.example.android_streamer.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * RTP sender for H.265/HEVC to MediaMTX server (LOCAL NETWORK ONLY).
 *
 * Zero-allocation design with automatic fragmentation:
 * - Pre-allocated packet buffer for single packets
 * - Automatic fragmentation for frames > MTU
 * - Proper H.265 FU (Fragmentation Unit) for large keyframes
 * - Fire-and-forget UDP (no retries)
 *
 * MediaMTX Configuration:
 * Configure MediaMTX to accept RTP on UDP port (shown in MediaMTX logs):
 *
 * paths:
 *   android:
 *     source: rtp://0.0.0.0:8000
 *     sourceProtocol: rtp
 *
 * Then view with: rtsp://server:8554/android
 *
 * @param serverIp IP address of MediaMTX server (e.g., "192.168.1.100")
 * @param serverPort RTP port from MediaMTX (e.g., 8000)
 * @param clientPort Local source port to bind to (must match RTSP SETUP client_port)
 */
class RTPSender(
    private var serverIp: String,
    private var serverPort: Int,
    private val clientPort: Int = 0
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    // Pre-allocated packet buffer (MTU-sized for fragmentation)
    private val MTU = 1400 // Conservative for local network
    private val RTP_HEADER_SIZE = 12
    private val FU_HEADER_SIZE = 3 // H.265 FU header
    private val MAX_FRAGMENT_PAYLOAD = MTU - RTP_HEADER_SIZE - FU_HEADER_SIZE

    private val packetBuffer = ByteArray(MTU)
    private val packet = DatagramPacket(packetBuffer, packetBuffer.size)

    // Temp buffer for frame copy (reused, pre-allocated)
    private val frameBuffer = ByteArray(256 * 1024) // 256KB max frame

    // RTP state
    private var sequenceNumber: Int = 1
    private val ssrc: Int = 0x12345678

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

    @Volatile
    var fragmentedFrames = 0L
        private set

    /**
     * Initialize RTP sender for MediaMTX.
     */
    fun start() {
        Log.i(TAG, "Starting RTP sender to MediaMTX at $serverIp:$serverPort (with fragmentation)")

        try {
            serverAddress = InetAddress.getByName(serverIp)
            // Bind to specific client port if specified (required for RTSP publishing)
            socket = if (clientPort > 0) {
                Log.i(TAG, "Binding to client port $clientPort (as declared in RTSP SETUP)")
                DatagramSocket(clientPort)
            } else {
                DatagramSocket()
            }

            // Optimize for local network
            socket?.sendBufferSize = 512 * 1024 // 512KB for burst traffic
            socket?.trafficClass = 0x10 // IPTOS_LOWDELAY

            Log.i(TAG, "RTP sender started: SSRC=0x${ssrc.toString(16)}, MTU=$MTU")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTP sender", e)
            throw e
        }
    }

    /**
     * Update destination IP/port (for RTSP server mode when client connects).
     */
    fun updateDestination(ip: String, port: Int) {
        try {
            this.serverIp = ip
            this.serverPort = port
            this.serverAddress = InetAddress.getByName(ip)
            Log.i(TAG, "Updated RTP destination: $ip:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update destination", e)
        }
    }

    /**
     * Send H.265 frame to MediaMTX over RTP.
     *
     * Automatically fragments frames > MTU using H.265 FU packets.
     * Small frames sent as single packets, large frames fragmented.
     *
     * @param buffer MediaCodec output buffer
     * @param timestampUs Presentation timestamp in microseconds
     * @param isKeyFrame Whether this is a keyframe
     */
    fun sendFrame(buffer: ByteBuffer, timestampUs: Long, isKeyFrame: Boolean) {
        val sock = socket ?: return
        val addr = serverAddress ?: return

        val frameSize = buffer.remaining()

        // Check if frame exceeds our buffer
        if (frameSize > frameBuffer.size) {
            Log.e(TAG, "Frame too large: $frameSize bytes (max ${frameBuffer.size})")
            return
        }

        // Copy frame to temp buffer (needed for fragmentation)
        val originalPosition = buffer.position()
        buffer.get(frameBuffer, 0, frameSize)
        buffer.position(originalPosition) // Reset for potential retry

        // Convert timestamp to RTP (90kHz clock)
        val rtpTimestamp = (timestampUs * 90 / 1000).toInt()

        // Decide: single packet or fragment
        if (frameSize <= MTU - RTP_HEADER_SIZE) {
            // Small frame - send as single packet
            sendSinglePacket(sock, addr, frameSize, rtpTimestamp, true)
        } else {
            // Large frame - fragment it
            sendFragmented(sock, addr, frameSize, rtpTimestamp, isKeyFrame)
        }
    }

    /**
     * Send small frame as single RTP packet.
     */
    private fun sendSinglePacket(
        sock: DatagramSocket,
        addr: InetAddress,
        frameSize: Int,
        rtpTimestamp: Int,
        markerBit: Boolean
    ) {
        try {
            var offset = 0

            // RTP header
            buildRtpHeader(packetBuffer, offset, markerBit, rtpTimestamp)
            offset += RTP_HEADER_SIZE

            // Copy frame data
            System.arraycopy(frameBuffer, 0, packetBuffer, offset, frameSize)

            // Send
            packet.address = addr
            packet.port = serverPort
            packet.length = offset + frameSize
            sock.send(packet)

            packetsSent++
            bytesSent += packet.length
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        } catch (e: IOException) {
            Log.w(TAG, "Send failed: ${e.message}")
        }
    }

    /**
     * Fragment large frame into multiple RTP packets using H.265 FU.
     *
     * Zero-allocation: reuses packet buffer for each fragment.
     */
    private fun sendFragmented(
        sock: DatagramSocket,
        addr: InetAddress,
        frameSize: Int,
        rtpTimestamp: Int,
        isKeyFrame: Boolean
    ) {
        // H.265 NAL unit header is first 2 bytes
        val nalHeader1 = frameBuffer[0]
        val nalHeader2 = frameBuffer[1]
        val nalType = (nalHeader1.toInt() shr 1) and 0x3F

        // Calculate number of fragments
        val nalPayloadSize = frameSize - 2 // Exclude NAL header
        val numFragments = (nalPayloadSize + MAX_FRAGMENT_PAYLOAD - 1) / MAX_FRAGMENT_PAYLOAD

        if (isKeyFrame) {
            Log.d(TAG, "Fragmenting keyframe: $frameSize bytes -> $numFragments packets")
        }

        fragmentedFrames++

        var payloadOffset = 2 // Skip NAL header
        var fragmentIndex = 0

        while (payloadOffset < frameSize) {
            val isFirst = fragmentIndex == 0
            val isLast = payloadOffset + MAX_FRAGMENT_PAYLOAD >= frameSize
            val fragmentSize = minOf(MAX_FRAGMENT_PAYLOAD, frameSize - payloadOffset)

            try {
                var offset = 0

                // RTP header (marker bit only on LAST fragment)
                buildRtpHeader(packetBuffer, offset, isLast, rtpTimestamp)
                offset += RTP_HEADER_SIZE

                // H.265 FU header (3 bytes)
                // Byte 0-1: PayloadHdr (type=49 for FU)
                packetBuffer[offset++] = (49 shl 1).toByte() // Type = 49 (FU)
                packetBuffer[offset++] = nalHeader2

                // Byte 2: FU header
                var fuHeader = nalType
                if (isFirst) fuHeader = fuHeader or 0x80 // S bit
                if (isLast) fuHeader = fuHeader or 0x40  // E bit
                packetBuffer[offset++] = fuHeader.toByte()

                // Copy fragment payload
                System.arraycopy(frameBuffer, payloadOffset, packetBuffer, offset, fragmentSize)

                // Send fragment
                packet.address = addr
                packet.port = serverPort
                packet.length = offset + fragmentSize
                sock.send(packet)

                packetsSent++
                bytesSent += packet.length
                sequenceNumber = (sequenceNumber + 1) and 0xFFFF

                payloadOffset += fragmentSize
                fragmentIndex++

            } catch (e: IOException) {
                Log.w(TAG, "Fragment send failed: ${e.message}")
                break
            }
        }
    }

    /**
     * Build RTP header in buffer.
     */
    private fun buildRtpHeader(
        buffer: ByteArray,
        offset: Int,
        marker: Boolean,
        rtpTimestamp: Int
    ) {
        // Byte 0: V(2)=2, P(1)=0, X(1)=0, CC(4)=0
        buffer[offset] = (RTP_VERSION shl 6).toByte()

        // Byte 1: M(1), PT(7)=96
        val markerBit = if (marker) 0x80 else 0x00
        buffer[offset + 1] = (markerBit or RTP_PAYLOAD_TYPE).toByte()

        // Bytes 2-3: Sequence number
        buffer[offset + 2] = (sequenceNumber shr 8).toByte()
        buffer[offset + 3] = (sequenceNumber and 0xFF).toByte()

        // Bytes 4-7: Timestamp
        buffer[offset + 4] = (rtpTimestamp shr 24).toByte()
        buffer[offset + 5] = (rtpTimestamp shr 16).toByte()
        buffer[offset + 6] = (rtpTimestamp shr 8).toByte()
        buffer[offset + 7] = (rtpTimestamp and 0xFF).toByte()

        // Bytes 8-11: SSRC
        buffer[offset + 8] = (ssrc shr 24).toByte()
        buffer[offset + 9] = (ssrc shr 16).toByte()
        buffer[offset + 10] = (ssrc shr 8).toByte()
        buffer[offset + 11] = (ssrc and 0xFF).toByte()
    }

    /**
     * Stop RTP sender.
     */
    fun stop() {
        Log.i(TAG, "Stopping RTP sender. Stats: packets=$packetsSent, bytes=${bytesSent / 1024}KB, fragmented=$fragmentedFrames")
        socket?.close()
        socket = null
        serverAddress = null
    }

    companion object {
        private const val TAG = "RTPSender"
    }
}
