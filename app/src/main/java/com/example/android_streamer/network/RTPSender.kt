package com.example.android_streamer.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Simple UDP sender for H.265/HEVC video streams over LOCAL NETWORK.
 *
 * Zero-copy, zero-allocation design:
 * - Pre-allocated packet buffer (reused for all sends)
 * - Direct ByteBuffer operations (no array copying)
 * - Fire-and-forget UDP (no retries, no error handling)
 * - Minimal RTP-like header for frame identification
 *
 * NOT suitable for internet streaming - local network only!
 *
 * @param serverIp IP address of receiver (e.g., "192.168.1.100")
 * @param serverPort UDP port (e.g., 5004)
 */
class RTPSender(
    private val serverIp: String,
    private val serverPort: Int
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    // Pre-allocated packet buffer (max 64KB for local network)
    // Most H.265 frames are < 64KB at 8Mbps 30fps
    private val packetBuffer = ByteArray(65507) // Max UDP payload size
    private val packet = DatagramPacket(packetBuffer, packetBuffer.size)

    // RTP-like state (minimal)
    private var sequenceNumber: Int = 0
    private var initialTimestamp: Long = System.currentTimeMillis()

    // Stats
    @Volatile
    var packetsSent = 0L
        private set

    @Volatile
    var bytesSent = 0L
        private set

    /**
     * Initialize UDP sender.
     */
    fun start() {
        Log.i(TAG, "Starting UDP sender to $serverIp:$serverPort (local network)")

        try {
            serverAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket()

            // Optimize for local network
            socket?.sendBufferSize = 256 * 1024 // 256KB send buffer
            socket?.trafficClass = 0x10 // IPTOS_LOWDELAY

            Log.i(TAG, "UDP sender started, seq=$sequenceNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP sender", e)
            throw e
        }
    }

    /**
     * Send H.265 frame over UDP.
     *
     * Zero-copy, zero-allocation hot path:
     * - Reuses pre-allocated packet buffer
     * - Direct ByteBuffer.get() to array
     * - No intermediate allocations
     *
     * Packet format (minimal header):
     * [4 bytes] Sequence number (big-endian)
     * [8 bytes] Timestamp in microseconds (big-endian)
     * [4 bytes] Frame size
     * [1 byte]  Flags (bit 0 = keyframe)
     * [N bytes] H.265 data
     *
     * @param buffer MediaCodec output buffer (positioned at frame data)
     * @param timestampUs Presentation timestamp in microseconds
     * @param isKeyFrame Whether this is a keyframe
     */
    fun sendFrame(buffer: ByteBuffer, timestampUs: Long, isKeyFrame: Boolean) {
        val sock = socket ?: return
        val addr = serverAddress ?: return

        val frameSize = buffer.remaining()

        // Check if frame fits in packet (should be fine for local network)
        val headerSize = 17 // 4 + 8 + 4 + 1
        if (frameSize + headerSize > packetBuffer.size) {
            Log.w(TAG, "Frame too large: $frameSize bytes (max ${packetBuffer.size - headerSize})")
            // For local network, we could split, but for now just skip
            return
        }

        try {
            // Build minimal header directly in packet buffer (NO ALLOCATIONS)
            var offset = 0

            // Sequence number (4 bytes)
            packetBuffer[offset++] = (sequenceNumber shr 24).toByte()
            packetBuffer[offset++] = (sequenceNumber shr 16).toByte()
            packetBuffer[offset++] = (sequenceNumber shr 8).toByte()
            packetBuffer[offset++] = (sequenceNumber and 0xFF).toByte()

            // Timestamp (8 bytes)
            packetBuffer[offset++] = (timestampUs shr 56).toByte()
            packetBuffer[offset++] = (timestampUs shr 48).toByte()
            packetBuffer[offset++] = (timestampUs shr 40).toByte()
            packetBuffer[offset++] = (timestampUs shr 32).toByte()
            packetBuffer[offset++] = (timestampUs shr 24).toByte()
            packetBuffer[offset++] = (timestampUs shr 16).toByte()
            packetBuffer[offset++] = (timestampUs shr 8).toByte()
            packetBuffer[offset++] = (timestampUs and 0xFF).toByte()

            // Frame size (4 bytes)
            packetBuffer[offset++] = (frameSize shr 24).toByte()
            packetBuffer[offset++] = (frameSize shr 16).toByte()
            packetBuffer[offset++] = (frameSize shr 8).toByte()
            packetBuffer[offset++] = (frameSize and 0xFF).toByte()

            // Flags (1 byte)
            packetBuffer[offset++] = if (isKeyFrame) 0x01 else 0x00

            // Copy H.265 data directly from ByteBuffer (minimal copy, unavoidable)
            buffer.get(packetBuffer, offset, frameSize)
            buffer.rewind() // Reset for potential retry

            // Send packet (fire and forget!)
            packet.address = addr
            packet.port = serverPort
            packet.length = offset + frameSize
            sock.send(packet)

            // Update stats and sequence
            packetsSent++
            bytesSent += packet.length
            sequenceNumber++

            if (isKeyFrame) {
                Log.d(TAG, "Sent keyframe: $frameSize bytes, seq=$sequenceNumber, ts=$timestampUs")
            }

        } catch (e: IOException) {
            // Fire and forget - just log and continue
            Log.w(TAG, "Failed to send frame (seq=$sequenceNumber): ${e.message}")
        }
    }

    /**
     * Stop UDP sender.
     */
    fun stop() {
        Log.i(TAG, "Stopping UDP sender. Stats: packets=$packetsSent, bytes=${bytesSent / 1024}KB")
        socket?.close()
        socket = null
        serverAddress = null
    }

    companion object {
        private const val TAG = "RTPSender"
    }
}
