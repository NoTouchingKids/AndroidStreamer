package com.example.android_streamer.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val FU_HEADER_SIZE = 3 // H.265 FU header (PayloadHdr + FU Header)
    private val MAX_FRAGMENT_PAYLOAD = MTU - RTP_HEADER_SIZE - FU_HEADER_SIZE

    private val packetBuffer = ByteArray(MTU)
    private val packet = DatagramPacket(packetBuffer, packetBuffer.size)

    // Temp buffer for frame copy (reused, pre-allocated)
    // 1MB handles 1080p@60fps keyframes (~300-500KB), ready to scale to 4MB for 4K
    private val frameBuffer = ByteArray(1 * 1024 * 1024) // 1MB max frame

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
            // Force IPv4 resolution for server address
            serverAddress = Inet4Address.getByName(serverIp) as Inet4Address
            Log.i(TAG, "Server address resolved to IPv4: ${serverAddress?.hostAddress}")

            // Bind to specific client port if specified (required for RTSP publishing)
            socket = if (clientPort > 0) {
                Log.i(TAG, "Binding to client port $clientPort (as declared in RTSP SETUP)")
                // Force IPv4 binding to avoid IPv6 issues with MediaMTX
                val ipv4Wildcard = Inet4Address.getByName("0.0.0.0") as Inet4Address
                val bindAddress = InetSocketAddress(ipv4Wildcard, clientPort)
                val sock = DatagramSocket(bindAddress)
                sock.reuseAddress = true
                Log.i(TAG, "✓ Socket bound to IPv4: ${sock.localAddress}:${sock.localPort}")
                sock
            } else {
                val sock = DatagramSocket()
                Log.i(TAG, "✓ Socket bound: ${sock.localAddress}:${sock.localPort}")
                sock
            }

            // Optimize for local network
            socket?.sendBufferSize = 512 * 1024 // 512KB for burst traffic
            socket?.trafficClass = 0x10 // IPTOS_LOWDELAY

            Log.i(TAG, "✓ RTP sender ready: SSRC=0x${ssrc.toString(16)}, MTU=$MTU")
            Log.i(TAG, "✓ Will send: ${socket?.localSocketAddress} -> ${serverAddress?.hostAddress}:$serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to start RTP sender", e)
            throw e
        }
    }

    /**
     * Update destination IP/port (for RTSP server mode when client connects).
     */
    fun updateDestination(ip: String, port: Int) {
        try {
            Log.i(TAG, "Updating RTP destination: $serverIp:$serverPort -> $ip:$port")
            this.serverIp = ip
            this.serverPort = port
            // Force IPv4 resolution
            this.serverAddress = Inet4Address.getByName(ip) as Inet4Address
            Log.i(TAG, "✓ RTP destination updated: ${serverAddress?.hostAddress}:$port (IPv4)")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to update destination", e)
        }
    }

    /**
     * Send H.265 frame to MediaMTX over RTP.
     *
     * H.265 keyframes may contain multiple NAL units (VPS + SPS + PPS + IDR).
     * This function parses start codes and sends each NAL unit separately.
     *
     * @param buffer MediaCodec output buffer (Annex B format with start codes)
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

        // Copy frame to temp buffer
        val originalPosition = buffer.position()
        buffer.get(frameBuffer, 0, frameSize)
        buffer.position(originalPosition)

        // Convert timestamp to RTP (90kHz clock)
        val rtpTimestamp = (timestampUs * 90 / 1000).toInt()

        // Parse and send each NAL unit separately
        val nalUnits = parseNalUnits(frameBuffer, frameSize)

        if (nalUnits.isEmpty()) {
            Log.w(TAG, "No NAL units found in frame")
            return
        }

        // Send each NAL unit
        for (i in nalUnits.indices) {
            val nal = nalUnits[i]
            val isLastNal = (i == nalUnits.size - 1)

            // Copy NAL to beginning of frameBuffer for sending
            System.arraycopy(frameBuffer, nal.offset, frameBuffer, 0, nal.size)

            // Send NAL (fragment if necessary)
            if (nal.size <= MTU - RTP_HEADER_SIZE) {
                // Small NAL - send as single packet (marker bit on last NAL only)
                sendSinglePacket(sock, addr, nal.size, rtpTimestamp, isLastNal)
            } else {
                // Large NAL - fragment it (marker bit set on last fragment automatically)
                sendFragmentedNal(sock, addr, nal.size, rtpTimestamp, isLastNal)
            }
        }
    }

    /**
     * Parse H.265 NAL units from Annex B format buffer.
     * Returns list of NAL unit offsets and sizes.
     */
    private fun parseNalUnits(buffer: ByteArray, bufferSize: Int): List<NalUnit> {
        val nalUnits = mutableListOf<NalUnit>()
        var offset = 0

        while (offset < bufferSize) {
            // Find start code (0x00000001 or 0x000001)
            val startCodeSize = when {
                offset + 3 < bufferSize &&
                buffer[offset] == 0.toByte() &&
                buffer[offset + 1] == 0.toByte() &&
                buffer[offset + 2] == 0.toByte() &&
                buffer[offset + 3] == 1.toByte() -> 4

                offset + 2 < bufferSize &&
                buffer[offset] == 0.toByte() &&
                buffer[offset + 1] == 0.toByte() &&
                buffer[offset + 2] == 1.toByte() -> 3

                else -> 0
            }

            if (startCodeSize == 0) {
                offset++
                continue
            }

            // Found start code, skip it
            val nalStart = offset + startCodeSize

            // Find next start code or end of buffer
            var nalEnd = nalStart + 1
            while (nalEnd < bufferSize) {
                if ((nalEnd + 3 < bufferSize &&
                    buffer[nalEnd] == 0.toByte() &&
                    buffer[nalEnd + 1] == 0.toByte() &&
                    buffer[nalEnd + 2] == 0.toByte() &&
                    buffer[nalEnd + 3] == 1.toByte()) ||
                    (nalEnd + 2 < bufferSize &&
                    buffer[nalEnd] == 0.toByte() &&
                    buffer[nalEnd + 1] == 0.toByte() &&
                    buffer[nalEnd + 2] == 1.toByte())) {
                    break
                }
                nalEnd++
            }

            val nalSize = nalEnd - nalStart
            if (nalSize > 0) {
                nalUnits.add(NalUnit(nalStart, nalSize))
            }

            offset = nalEnd
        }

        return nalUnits
    }

    private data class NalUnit(val offset: Int, val size: Int)

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

            // Log first packet for debugging
            if (packetsSent == 0L) {
                Log.i(TAG, "✓ FIRST PACKET SENT: ${addr.hostAddress}:$serverPort (${packet.length} bytes)")
            }

            packetsSent++
            bytesSent += packet.length
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        } catch (e: IOException) {
            Log.w(TAG, "Send failed: ${e.message}")
        }
    }

    /**
     * Fragment large NAL unit into multiple RTP packets using H.265 FU.
     *
     * Zero-allocation: reuses packet buffer for each fragment.
     */
    private fun sendFragmentedNal(
        sock: DatagramSocket,
        addr: InetAddress,
        nalSize: Int,
        rtpTimestamp: Int,
        isLastNal: Boolean
    ) {
        // H.265 NAL unit header is first 2 bytes
        val nalHeader1 = frameBuffer[0]
        val nalHeader2 = frameBuffer[1]
        val nalType = (nalHeader1.toInt() shr 1) and 0x3F // Bits 1-6

        // Calculate number of fragments
        val nalPayloadSize = nalSize - 2 // Exclude 2-byte NAL header
        val numFragments = (nalPayloadSize + MAX_FRAGMENT_PAYLOAD - 1) / MAX_FRAGMENT_PAYLOAD

        fragmentedFrames++

        var payloadOffset = 2 // Skip 2-byte NAL header
        var fragmentIndex = 0

        while (payloadOffset < nalSize) {
            val isFirst = fragmentIndex == 0
            val isLast = payloadOffset + MAX_FRAGMENT_PAYLOAD >= nalSize
            val fragmentSize = minOf(MAX_FRAGMENT_PAYLOAD, nalSize - payloadOffset)

            try {
                var offset = 0

                // RTP header (marker bit only on LAST fragment of LAST NAL)
                buildRtpHeader(packetBuffer, offset, isLast && isLastNal, rtpTimestamp)
                offset += RTP_HEADER_SIZE

                // H.265 FU header (3 bytes)
                // Byte 0-1: PayloadHdr (type=49 for FU)
                // Preserve LayerId MSB (bit 0 of nalHeader1) to avoid packet corruption
                val layerIdMsb = nalHeader1.toInt() and 0x01
                packetBuffer[offset++] = ((49 shl 1) or layerIdMsb).toByte() // Type = 49 (FU)
                packetBuffer[offset++] = nalHeader2

                // Byte 2: FU header (S, E, R, FuType)
                var fuHeader = nalType
                if (isFirst) fuHeader = fuHeader or 0x80 // S bit (start)
                if (isLast) fuHeader = fuHeader or 0x40  // E bit (end)
                packetBuffer[offset++] = fuHeader.toByte()

                // Copy fragment payload
                System.arraycopy(frameBuffer, payloadOffset, packetBuffer, offset, fragmentSize)

                // Send fragment
                packet.address = addr
                packet.port = serverPort
                packet.length = offset + fragmentSize
                sock.send(packet)

                // Log first fragment for debugging
                if (packetsSent == 0L && fragmentIndex == 0) {
                    Log.i(TAG, "✓ FIRST FRAGMENT SENT: ${addr.hostAddress}:$serverPort (${packet.length} bytes)")
                }

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
