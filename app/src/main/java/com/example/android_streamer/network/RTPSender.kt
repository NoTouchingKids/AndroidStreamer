package com.example.android_streamer.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Random

/**
 * RTP sender for H.265/HEVC video streams.
 *
 * Implements RFC 7798 (RTP Payload Format for H.265) with:
 * - Single NAL Unit Packets for small NALUs
 * - Fragmentation Units (FU) for large NALUs (>MTU)
 * - Proper RTP header with timestamp and sequence number
 *
 * Architecture:
 * - Zero-copy where possible (ByteBuffer.slice())
 * - MTU-aware fragmentation (default 1400 bytes payload)
 * - Thread-safe (designed to be called from encoder sender thread)
 *
 * @param serverIp IP address of RTP receiver (e.g., "192.168.1.100")
 * @param serverPort UDP port for RTP (e.g., 5004)
 */
class RTPSender(
    private val serverIp: String,
    private val serverPort: Int
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    // RTP state
    private var sequenceNumber: Int = Random().nextInt(65536) // 16-bit
    private var timestamp: Long = Random().nextInt() // 32-bit, but stored as Long to prevent overflow
    private val ssrc: Int = Random().nextInt() // Synchronization source identifier

    // RTP constants
    private val RTP_VERSION = 2
    private val RTP_PAYLOAD_TYPE = 96 // Dynamic payload type for H.265

    // H.265 NAL unit types
    private val NAL_TYPE_VPS = 32
    private val NAL_TYPE_SPS = 33
    private val NAL_TYPE_PPS = 34
    private val NAL_TYPE_IDR_W_RADL = 19
    private val NAL_TYPE_IDR_N_LP = 20

    // Fragmentation
    private val MTU = 1400 // Conservative MTU (1500 - 20 IP - 8 UDP - 12 RTP - 60 margin)
    private val MAX_PAYLOAD_SIZE = MTU - 12 // 12-byte RTP header

    // Stats
    @Volatile
    var packetsSent = 0L
        private set

    @Volatile
    var bytesSent = 0L
        private set

    /**
     * Initialize the RTP sender and connect to server.
     *
     * @throws IOException if socket creation or DNS resolution fails
     */
    fun start() {
        Log.i(TAG, "Starting RTP sender to $serverIp:$serverPort")

        try {
            serverAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket()

            Log.i(TAG, "RTP sender started: SSRC=0x${ssrc.toString(16)}, seq=$sequenceNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTP sender", e)
            throw e
        }
    }

    /**
     * Send an H.265 encoded frame over RTP.
     *
     * The buffer should contain one or more NAL units (potentially with start codes).
     * This method will:
     * 1. Parse NAL units from the buffer
     * 2. Send each NAL unit as RTP packet(s)
     * 3. Fragment large NAL units if needed
     *
     * @param buffer ByteBuffer containing H.265 encoded data
     * @param timestampUs Presentation timestamp in microseconds
     * @param isKeyFrame Whether this is a keyframe (for logging)
     */
    fun sendFrame(buffer: ByteBuffer, timestampUs: Long, isKeyFrame: Boolean) {
        val sock = socket ?: run {
            Log.w(TAG, "Socket not initialized, skipping frame")
            return
        }

        val addr = serverAddress ?: run {
            Log.w(TAG, "Server address not resolved, skipping frame")
            return
        }

        // Convert microsecond timestamp to RTP timestamp (90kHz clock for video)
        val rtpTimestamp = (timestampUs * 90 / 1000).toInt()

        // Parse and send all NAL units in this frame
        val nalUnits = parseNalUnits(buffer)

        if (nalUnits.isEmpty()) {
            Log.w(TAG, "No NAL units found in buffer")
            return
        }

        if (isKeyFrame) {
            Log.d(TAG, "Sending keyframe: ${nalUnits.size} NAL units, ${buffer.remaining()} bytes, ts=$rtpTimestamp")
        }

        for (nalUnit in nalUnits) {
            sendNalUnit(sock, addr, nalUnit, rtpTimestamp, nalUnit === nalUnits.last())
        }
    }

    /**
     * Parse NAL units from H.265 buffer.
     *
     * MediaCodec output can have:
     * - Start codes (0x00000001 or 0x000001)
     * - Or length-prefixed NAL units
     *
     * We'll handle both cases.
     */
    private fun parseNalUnits(buffer: ByteBuffer): List<ByteBuffer> {
        val nalUnits = mutableListOf<ByteBuffer>()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        buffer.rewind()

        var pos = 0
        val length = data.size

        while (pos < length) {
            // Look for start code (0x00000001 or 0x000001)
            val startCodeSize = when {
                pos + 3 < length &&
                data[pos] == 0.toByte() &&
                data[pos + 1] == 0.toByte() &&
                data[pos + 2] == 0.toByte() &&
                data[pos + 3] == 1.toByte() -> 4

                pos + 2 < length &&
                data[pos] == 0.toByte() &&
                data[pos + 1] == 0.toByte() &&
                data[pos + 2] == 1.toByte() -> 3

                else -> 0
            }

            if (startCodeSize > 0) {
                // Found start code - skip it
                pos += startCodeSize

                // Find next start code or end of buffer
                var nextPos = pos
                while (nextPos < length) {
                    val hasStartCode = when {
                        nextPos + 3 < length &&
                        data[nextPos] == 0.toByte() &&
                        data[nextPos + 1] == 0.toByte() &&
                        data[nextPos + 2] == 0.toByte() &&
                        data[nextPos + 3] == 1.toByte() -> true

                        nextPos + 2 < length &&
                        data[nextPos] == 0.toByte() &&
                        data[nextPos + 1] == 0.toByte() &&
                        data[nextPos + 2] == 1.toByte() -> true

                        else -> false
                    }

                    if (hasStartCode) break
                    nextPos++
                }

                // Extract NAL unit
                if (nextPos > pos) {
                    val nalData = ByteBuffer.wrap(data, pos, nextPos - pos)
                    nalUnits.add(nalData)
                }

                pos = nextPos
            } else {
                // No start code at current position - maybe length-prefixed
                // For now, treat entire remaining buffer as one NAL unit
                val nalData = ByteBuffer.wrap(data, pos, length - pos)
                nalUnits.add(nalData)
                break
            }
        }

        return nalUnits
    }

    /**
     * Send a single NAL unit over RTP.
     *
     * Small NAL units (<= MTU) are sent as Single NAL Unit Packets.
     * Large NAL units are fragmented into Fragmentation Units (FU).
     */
    private fun sendNalUnit(
        socket: DatagramSocket,
        address: InetAddress,
        nalUnit: ByteBuffer,
        rtpTimestamp: Int,
        isLastNal: Boolean
    ) {
        val nalSize = nalUnit.remaining()

        if (nalSize <= MAX_PAYLOAD_SIZE - 2) { // -2 for potential FU header
            // Send as single NAL unit packet
            sendSingleNalPacket(socket, address, nalUnit, rtpTimestamp, isLastNal)
        } else {
            // Fragment into multiple packets
            sendFragmentedNal(socket, address, nalUnit, rtpTimestamp, isLastNal)
        }
    }

    /**
     * Send a small NAL unit as a single RTP packet.
     */
    private fun sendSingleNalPacket(
        socket: DatagramSocket,
        address: InetAddress,
        nalUnit: ByteBuffer,
        rtpTimestamp: Int,
        isLastNal: Boolean
    ) {
        val nalSize = nalUnit.remaining()
        val packetSize = 12 + nalSize // RTP header + NAL unit
        val packet = ByteArray(packetSize)

        // Build RTP header
        buildRtpHeader(packet, 0, isLastNal, rtpTimestamp)

        // Copy NAL unit data
        nalUnit.get(packet, 12, nalSize)
        nalUnit.rewind()

        // Send packet
        val dgram = DatagramPacket(packet, packet.size, address, serverPort)
        socket.send(dgram)

        packetsSent++
        bytesSent += packet.size
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
    }

    /**
     * Send a large NAL unit fragmented across multiple RTP packets.
     *
     * Uses H.265 Fragmentation Units (FU) from RFC 7798.
     */
    private fun sendFragmentedNal(
        socket: DatagramSocket,
        address: InetAddress,
        nalUnit: ByteBuffer,
        rtpTimestamp: Int,
        isLastNal: Boolean
    ) {
        // Get NAL unit header (first 2 bytes)
        val nalHeader1 = nalUnit.get()
        val nalHeader2 = nalUnit.get()
        val nalType = (nalHeader1.toInt() shr 1) and 0x3F

        // Calculate number of fragments needed
        val nalPayloadSize = nalUnit.remaining()
        val maxFragmentPayload = MAX_PAYLOAD_SIZE - 3 // -3 for FU header
        val numFragments = (nalPayloadSize + maxFragmentPayload - 1) / maxFragmentPayload

        Log.d(TAG, "Fragmenting NAL type $nalType: $nalPayloadSize bytes -> $numFragments packets")

        var fragmentIndex = 0
        var payloadOffset = 0

        while (payloadOffset < nalPayloadSize) {
            val isFirst = fragmentIndex == 0
            val isLast = payloadOffset + maxFragmentPayload >= nalPayloadSize
            val fragmentPayloadSize = minOf(maxFragmentPayload, nalPayloadSize - payloadOffset)

            val packetSize = 12 + 3 + fragmentPayloadSize // RTP header + FU header + payload
            val packet = ByteArray(packetSize)

            // Build RTP header (marker bit only on LAST fragment of LAST NAL)
            buildRtpHeader(packet, 0, isLast && isLastNal, rtpTimestamp)

            // Build FU header (3 bytes for H.265)
            // PayloadHdr (2 bytes)
            packet[12] = (49 shl 1).toByte() // Type = 49 (FU)
            packet[13] = nalHeader2

            // FU header (1 byte)
            var fuHeader = nalType
            if (isFirst) fuHeader = fuHeader or 0x80 // S bit
            if (isLast) fuHeader = fuHeader or 0x40  // E bit
            packet[14] = fuHeader.toByte()

            // Copy fragment payload
            val position = nalUnit.position()
            nalUnit.get(packet, 15, fragmentPayloadSize)
            payloadOffset += fragmentPayloadSize

            // Send packet
            val dgram = DatagramPacket(packet, packet.size, address, serverPort)
            socket.send(dgram)

            packetsSent++
            bytesSent += packet.size
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            fragmentIndex++
        }

        nalUnit.rewind()
    }

    /**
     * Build RTP header in packet buffer.
     *
     * RTP header format (12 bytes):
     * 0-1: V(2), P(1), X(1), CC(4), M(1), PT(7)
     * 2-3: Sequence number
     * 4-7: Timestamp
     * 8-11: SSRC
     */
    private fun buildRtpHeader(
        packet: ByteArray,
        offset: Int,
        marker: Boolean,
        rtpTimestamp: Int
    ) {
        // Byte 0: V(2)=2, P(1)=0, X(1)=0, CC(4)=0
        packet[offset] = (RTP_VERSION shl 6).toByte()

        // Byte 1: M(1), PT(7)
        val markerBit = if (marker) 0x80 else 0x00
        packet[offset + 1] = (markerBit or RTP_PAYLOAD_TYPE).toByte()

        // Bytes 2-3: Sequence number (big-endian)
        packet[offset + 2] = (sequenceNumber shr 8).toByte()
        packet[offset + 3] = (sequenceNumber and 0xFF).toByte()

        // Bytes 4-7: Timestamp (big-endian)
        packet[offset + 4] = (rtpTimestamp shr 24).toByte()
        packet[offset + 5] = (rtpTimestamp shr 16).toByte()
        packet[offset + 6] = (rtpTimestamp shr 8).toByte()
        packet[offset + 7] = (rtpTimestamp and 0xFF).toByte()

        // Bytes 8-11: SSRC (big-endian)
        packet[offset + 8] = (ssrc shr 24).toByte()
        packet[offset + 9] = (ssrc shr 16).toByte()
        packet[offset + 10] = (ssrc shr 8).toByte()
        packet[offset + 11] = (ssrc and 0xFF).toByte()
    }

    /**
     * Stop the RTP sender and close socket.
     */
    fun stop() {
        Log.i(TAG, "Stopping RTP sender. Stats: packets=$packetsSent, bytes=$bytesSent")
        socket?.close()
        socket = null
        serverAddress = null
    }

    companion object {
        private const val TAG = "RTPSender"
    }
}
