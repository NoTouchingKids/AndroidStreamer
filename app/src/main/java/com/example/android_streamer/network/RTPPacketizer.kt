package com.example.android_streamer.network

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

/**
 * RTP packetizer for H.265/HEVC video according to RFC 7798.
 *
 * Threading model:
 * - This class is designed for SINGLE-THREADED use
 * - Should only be called from MediaCodec encoder callback thread
 * - NOT thread-safe for concurrent calls to packetize()
 * - Reuses internal buffer (packetBuffer) across calls for zero-allocation
 *
 * MediaMTX compatibility:
 * - Generates RFC 7798 compliant RTP packets for H.265
 * - Supports NAL unit fragmentation for MTU compliance
 * - Uses 90kHz RTP timestamp clock (standard for video)
 * - Payload type 96 (dynamic) - configure MediaMTX accordingly
 *
 * Supports:
 * - Single NAL Unit Packets (small NALUs)
 * - Fragmentation Units (FU) for large NALUs (>1400 bytes)
 * - Proper RTP sequencing and timestamping
 */
class RTPPacketizer(
    val ssrc: Int = DEFAULT_SSRC, // Synchronization source identifier (exposed for RTCP)
    private val payloadType: Int = 96 // Dynamic payload type for H.265
) {
    // RTP state (mutable - not thread-safe)
    @Volatile  // For visibility when reading stats from other threads
    private var sequenceNumber: Int = (0..0xFFFF).random()

    @Volatile
    private var timestamp: Long = 0L

    // MTU - Maximum Transmission Unit (typical Ethernet MTU minus IP/UDP headers)
    private val mtu = 1400 // Conservative MTU for UDP over Ethernet

    // RTP header size (12 bytes) + FU header (3 bytes for H.265)
    private val rtpHeaderSize = 12
    private val fuHeaderSize = 3
    private val maxPayloadSize = mtu - rtpHeaderSize - fuHeaderSize

    // Preallocated buffer for RTP packets (zero-allocation)
    private val packetBuffer = ByteBuffer.allocateDirect(mtu)

    // Statistics
    private var totalPackets = 0L
    private var totalBytes = 0L

    /**
     * Packetize encoded H.265 NAL units into RTP packets.
     *
     * @param encodedData ByteBuffer containing H.265 encoded data
     * @param bufferInfo MediaCodec buffer info with presentation timestamp
     * @param onPacket Callback invoked for each RTP packet
     */
    fun packetize(
        encodedData: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        onPacket: (ByteBuffer) -> Unit
    ) {
        // Convert presentation time (microseconds) to RTP timestamp (90kHz clock)
        timestamp = (bufferInfo.presentationTimeUs * 90) / 1000

        // Position buffer at the start of data
        encodedData.position(bufferInfo.offset)
        encodedData.limit(bufferInfo.offset + bufferInfo.size)

        // Parse NAL units from the encoded data
        parseNALUnits(encodedData) { nalUnit, isLastNAL ->
            packetizeNALUnit(nalUnit, isLastNAL, onPacket)
        }
    }

    /**
     * Parse H.265 NAL units from encoded data.
     * H.265 uses start codes: 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01
     */
    private fun parseNALUnits(
        buffer: ByteBuffer,
        onNALUnit: (ByteBuffer, Boolean) -> Unit
    ) {
        val startPosition = buffer.position()
        val endPosition = buffer.limit()

        var nalStart = -1
        var i = startPosition

        while (i < endPosition - 3) {
            // Look for start code: 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01
            if (buffer[i] == 0.toByte() &&
                buffer[i + 1] == 0.toByte() &&
                (buffer[i + 2] == 1.toByte() ||
                 (buffer[i + 2] == 0.toByte() && i < endPosition - 4 && buffer[i + 3] == 1.toByte()))
            ) {
                val startCodeLength = if (buffer[i + 2] == 1.toByte()) 3 else 4

                if (nalStart != -1) {
                    // Extract previous NAL unit
                    val nalBuffer = buffer.duplicate()
                    nalBuffer.position(nalStart)
                    nalBuffer.limit(i)
                    onNALUnit(nalBuffer, false)
                }

                nalStart = i + startCodeLength
                i += startCodeLength
            } else {
                i++
            }
        }

        // Handle last NAL unit
        if (nalStart != -1 && nalStart < endPosition) {
            val nalBuffer = buffer.duplicate()
            nalBuffer.position(nalStart)
            nalBuffer.limit(endPosition)
            onNALUnit(nalBuffer, true)
        }
    }

    /**
     * Packetize a single NAL unit into RTP packets.
     */
    private fun packetizeNALUnit(
        nalUnit: ByteBuffer,
        isLastNAL: Boolean,
        onPacket: (ByteBuffer) -> Unit
    ) {
        val nalSize = nalUnit.remaining()

        if (nalSize == 0) return

        // Read NAL unit header (2 bytes in H.265)
        val nalHeader1 = nalUnit.get().toInt() and 0xFF
        val nalHeader2 = nalUnit.get().toInt() and 0xFF
        val nalType = (nalHeader1 shr 1) and 0x3F

        if (nalSize <= maxPayloadSize) {
            // Single NAL Unit Packet (small NAL)
            sendSingleNALPacket(nalHeader1, nalHeader2, nalUnit, isLastNAL, onPacket)
        } else {
            // Fragmentation Unit (large NAL)
            sendFragmentedNALPackets(nalHeader1, nalHeader2, nalUnit, isLastNAL, onPacket)
        }
    }

    /**
     * Send a single NAL unit as one RTP packet.
     */
    private fun sendSingleNALPacket(
        nalHeader1: Int,
        nalHeader2: Int,
        nalUnit: ByteBuffer,
        isLastNAL: Boolean,
        onPacket: (ByteBuffer) -> Unit
    ) {
        packetBuffer.clear()

        // Write RTP header
        writeRTPHeader(packetBuffer, isLastNAL)

        // Write NAL header + payload
        packetBuffer.put(nalHeader1.toByte())
        packetBuffer.put(nalHeader2.toByte())
        packetBuffer.put(nalUnit)

        // Prepare for reading
        packetBuffer.flip()

        // Send packet
        onPacket(packetBuffer)

        // Update stats
        totalPackets++
        totalBytes += packetBuffer.remaining()

        // Increment sequence number
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
    }

    /**
     * Fragment large NAL unit into multiple RTP packets using FU (Fragmentation Unit).
     */
    private fun sendFragmentedNALPackets(
        nalHeader1: Int,
        nalHeader2: Int,
        nalUnit: ByteBuffer,
        isLastNAL: Boolean,
        onPacket: (ByteBuffer) -> Unit
    ) {
        val nalType = (nalHeader1 shr 1) and 0x3F
        val layerId = ((nalHeader1 and 0x01) shl 5) or ((nalHeader2 shr 3) and 0x1F)
        val tid = nalHeader2 and 0x07

        var isFirstFragment = true
        val nalPayloadSize = nalUnit.remaining()
        var offset = 0

        while (offset < nalPayloadSize) {
            val fragmentSize = minOf(maxPayloadSize, nalPayloadSize - offset)
            val isLastFragment = (offset + fragmentSize >= nalPayloadSize)

            packetBuffer.clear()

            // Write RTP header
            writeRTPHeader(packetBuffer, isLastNAL && isLastFragment)

            // Write FU header (3 bytes for H.265)
            // PayloadHdr (2 bytes): Type=49 (FU), LayerId, TID
            val fuType = 49 // Fragmentation Unit type
            val fuHeader1 = (fuType shl 1) or (layerId shr 5)
            val fuHeader2 = ((layerId and 0x1F) shl 3) or tid

            packetBuffer.put(fuHeader1.toByte())
            packetBuffer.put(fuHeader2.toByte())

            // FU header (1 byte): S, E, FuType
            val sFlag = if (isFirstFragment) 0x80 else 0x00
            val eFlag = if (isLastFragment) 0x40 else 0x00
            val fuHeader3 = sFlag or eFlag or nalType

            packetBuffer.put(fuHeader3.toByte())

            // Write fragment payload
            val fragmentStart = nalUnit.position() + offset
            val fragmentLimit = fragmentStart + fragmentSize
            val fragmentBuffer = nalUnit.duplicate()
            fragmentBuffer.position(fragmentStart)
            fragmentBuffer.limit(fragmentLimit)
            packetBuffer.put(fragmentBuffer)

            // Prepare for reading
            packetBuffer.flip()

            // Send packet
            onPacket(packetBuffer)

            // Update state
            totalPackets++
            totalBytes += packetBuffer.remaining()
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF
            offset += fragmentSize
            isFirstFragment = false
        }
    }

    /**
     * Write RTP header (12 bytes) to buffer.
     *
     * RTP Header Format (RFC 3550):
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                           timestamp                           |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |           synchronization source (SSRC) identifier            |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     */
    private fun writeRTPHeader(buffer: ByteBuffer, marker: Boolean) {
        // Byte 0: V=2, P=0, X=0, CC=0
        buffer.put(0x80.toByte())

        // Byte 1: M (marker), PT (payload type)
        val mFlag = if (marker) 0x80 else 0x00
        buffer.put((mFlag or payloadType).toByte())

        // Bytes 2-3: Sequence number (16-bit)
        buffer.put((sequenceNumber shr 8).toByte())
        buffer.put((sequenceNumber and 0xFF).toByte())

        // Bytes 4-7: Timestamp (32-bit)
        buffer.put((timestamp shr 24).toByte())
        buffer.put((timestamp shr 16).toByte())
        buffer.put((timestamp shr 8).toByte())
        buffer.put(timestamp.toByte())

        // Bytes 8-11: SSRC (32-bit)
        buffer.put((ssrc shr 24).toByte())
        buffer.put((ssrc shr 16).toByte())
        buffer.put((ssrc shr 8).toByte())
        buffer.put(ssrc.toByte())
    }

    /**
     * Get packetization statistics.
     */
    fun getStats(): PacketizerStats {
        return PacketizerStats(
            totalPackets = totalPackets,
            totalBytes = totalBytes,
            currentSequence = sequenceNumber,
            currentTimestamp = timestamp
        )
    }

    data class PacketizerStats(
        val totalPackets: Long,
        val totalBytes: Long,
        val currentSequence: Int,
        val currentTimestamp: Long
    )

    companion object {
        private const val TAG = "RTPPacketizer"
        val DEFAULT_SSRC = (0..Int.MAX_VALUE).random()
    }
}
