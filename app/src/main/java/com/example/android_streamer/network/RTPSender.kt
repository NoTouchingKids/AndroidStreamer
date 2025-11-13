package com.example.android_streamer.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP sender for H.265/HEVC streaming.
 * Zero-allocation design with automatic fragmentation for large NAL units.
 */
class RTPSender(
    private var serverIp: String,
    private var serverPort: Int,
    private val clientPort: Int = 0
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    private val MTU = 1400
    private val RTP_HEADER_SIZE = 12
    private val FU_HEADER_SIZE = 3
    private val MAX_FRAGMENT_PAYLOAD = MTU - RTP_HEADER_SIZE - FU_HEADER_SIZE

    private val packetBuffer = ByteArray(MTU)
    private val packet = DatagramPacket(packetBuffer, packetBuffer.size)

    // Preallocated NAL unit storage (reused every frame, zero allocations)
    private val maxNalUnits = 64  // Increased for high complexity encoding
    private val nalOffsets = IntArray(maxNalUnits)
    private val nalSizes = IntArray(maxNalUnits)
    private var nalCount = 0

    // Dedicated sender thread for parallel packet transmission
    // Changed to enqueue raw frame data - parsing happens on sender thread to unblock EncoderSender
    private data class RawFrame(
        val frameData: ByteArray,  // Copied frame data (safe to use after MediaCodec release)
        val rtpTimestamp: Int,
        val isKeyFrame: Boolean
    )

    private val sendQueue = ArrayBlockingQueue<RawFrame>(32)  // Buffer up to 32 raw frames for burst tolerance
    private var senderThread: Thread? = null
    private val senderRunning = AtomicBoolean(false)

    private var sequenceNumber: Int = 1
    private val ssrc: Int = 0x12345678

    private val RTP_VERSION = 2
    private val RTP_PAYLOAD_TYPE = 96

    @Volatile
    var packetsSent = 0L
        private set

    @Volatile
    var bytesSent = 0L
        private set

    @Volatile
    var fragmentedFrames = 0L
        private set

    @Volatile
    var sendErrors = 0L
        private set

    fun start() {
        try {
            serverAddress = Inet4Address.getByName(serverIp) as Inet4Address

            socket = if (clientPort > 0) {
                val ipv4Wildcard = Inet4Address.getByName("0.0.0.0") as Inet4Address
                val bindAddress = InetSocketAddress(ipv4Wildcard, clientPort)
                val sock = DatagramSocket(bindAddress)
                sock.reuseAddress = true
                sock
            } else {
                DatagramSocket()
            }

            socket?.sendBufferSize = 2 * 1024 * 1024  // 2MB for smoother burst handling
            socket?.trafficClass = 0x10

            // Start dedicated sender thread
            Log.i(TAG, "Starting sender thread setup...")
            senderRunning.set(true)
            Log.i(TAG, "senderRunning set to true")
            senderThread = Thread(SenderRunnable(), "RTP-Sender").apply {
                Log.i(TAG, "Thread object created, setting priority and starting...")
                priority = Thread.MAX_PRIORITY
                start()
                Log.i(TAG, "Thread.start() called")
            }
            Log.i(TAG, "RTP sender thread started successfully, thread=$senderThread")
        } catch (e: Exception) {
            Log.e(TAG, "RTP start failed", e)
            throw e
        }
    }

    fun updateDestination(ip: String, port: Int) {
        try {
            this.serverIp = ip
            this.serverPort = port
            this.serverAddress = Inet4Address.getByName(ip) as Inet4Address
        } catch (e: Exception) {
            Log.e(TAG, "Update destination failed", e)
        }
    }

    fun sendFrame(buffer: ByteBuffer, timestampUs: Long, isKeyFrame: Boolean) {
        val frameStartTime = System.nanoTime()
        val rtpTimestamp = (timestampUs * 90 / 1000).toInt()

        // Copy frame data ONLY on caller thread (EncoderSender) - parsing moved to sender thread
        val copyStart = System.nanoTime()
        val frameSize = buffer.remaining()
        val frameDataCopy = ByteArray(frameSize)
        buffer.get(frameDataCopy)
        val copyTimeUs = (System.nanoTime() - copyStart) / 1000  // Microseconds for precision

        // Enqueue raw frame for async parsing and sending (non-blocking)
        val enqueueStart = System.nanoTime()
        val rawFrame = RawFrame(frameDataCopy, rtpTimestamp, isKeyFrame)
        val queueSizeBefore = sendQueue.size
        val enqueued = sendQueue.offer(rawFrame)
        val enqueueTimeUs = (System.nanoTime() - enqueueStart) / 1000

        val totalTimeUs = (System.nanoTime() - frameStartTime) / 1000

        if (!enqueued) {
            // Queue full - drop frame (should be rare with 32-frame buffer)
            Log.w(TAG, "Send queue full (${sendQueue.size}), dropped frame (${frameSize} bytes)")
        } else if (packetsSent < 1200 || totalTimeUs > 2000) {  // Log first 20 frames OR if >2ms
            Log.d(TAG, "Enqueued: ${frameSize}b, copy=${copyTimeUs}µs, enqueue=${enqueueTimeUs}µs, total=${totalTimeUs}µs, queue=${queueSizeBefore}→${sendQueue.size}")
        }
    }

    /**
     * Parse NAL units from H.265 frame ByteArray into preallocated arrays.
     * Fast scanning directly on ByteArray (no ByteBuffer.get() overhead).
     */
    private fun parseNalUnits(frameData: ByteArray) {
        nalCount = 0
        val frameSize = frameData.size

        if (frameSize == 0) return

        var offset = 0

        while (offset < frameSize && nalCount < maxNalUnits) {
            // Scan for start code: 0x00 00 00 01 (4-byte) or 0x00 00 01 (3-byte)
            val startCodeSize = when {
                offset + 3 < frameSize &&
                frameData[offset] == 0.toByte() &&
                frameData[offset + 1] == 0.toByte() &&
                frameData[offset + 2] == 0.toByte() &&
                frameData[offset + 3] == 1.toByte() -> 4

                offset + 2 < frameSize &&
                frameData[offset] == 0.toByte() &&
                frameData[offset + 1] == 0.toByte() &&
                frameData[offset + 2] == 1.toByte() -> 3

                else -> 0
            }

            if (startCodeSize == 0) {
                offset++
                continue
            }

            val nalStart = offset + startCodeSize
            var nalEnd = nalStart + 1

            // Find end of NAL (next start code or end of buffer)
            while (nalEnd < frameSize) {
                if ((nalEnd + 3 < frameSize &&
                    frameData[nalEnd] == 0.toByte() &&
                    frameData[nalEnd + 1] == 0.toByte() &&
                    frameData[nalEnd + 2] == 0.toByte() &&
                    frameData[nalEnd + 3] == 1.toByte()) ||
                    (nalEnd + 2 < frameSize &&
                    frameData[nalEnd] == 0.toByte() &&
                    frameData[nalEnd + 1] == 0.toByte() &&
                    frameData[nalEnd + 2] == 1.toByte())) {
                    break
                }
                nalEnd++
            }

            val nalSize = nalEnd - nalStart
            if (nalSize > 0) {
                nalOffsets[nalCount] = nalStart  // Relative offset within frameData
                nalSizes[nalCount] = nalSize
                nalCount++
            }

            offset = nalEnd
        }

        // Warn if we're truncating NAL units (indicates buffer too small)
        if (nalCount >= maxNalUnits && offset < frameSize) {
            Log.w(TAG, "NAL limit reached! Found $nalCount NALs but more data remains. Increase maxNalUnits.")
        }
    }

    private fun sendSinglePacket(
        sock: DatagramSocket,
        addr: InetAddress,
        frameData: ByteArray,
        nalOffset: Int,
        nalSize: Int,
        rtpTimestamp: Int,
        markerBit: Boolean
    ) {
        try {
            var offset = 0

            buildRtpHeader(packetBuffer, offset, markerBit, rtpTimestamp)
            offset += RTP_HEADER_SIZE

            // Copy NAL unit from frame data
            System.arraycopy(frameData, nalOffset, packetBuffer, offset, nalSize)

            packet.address = addr
            packet.port = serverPort
            packet.length = offset + nalSize
            sock.send(packet)

            packetsSent++
            bytesSent += packet.length
            sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        } catch (e: IOException) {
            sendErrors++
            if (sendErrors % 100 == 1L) {  // Log first error and every 100th
                Log.w(TAG, "Send failed: ${e.message} (total errors: $sendErrors)")
            }
        }
    }

    private fun sendFragmentedNal(
        sock: DatagramSocket,
        addr: InetAddress,
        frameData: ByteArray,
        nalOffset: Int,
        nalSize: Int,
        rtpTimestamp: Int,
        isLastNal: Boolean
    ) {
        val nalHeader1 = frameData[nalOffset]
        val nalHeader2 = frameData[nalOffset + 1]
        val nalType = (nalHeader1.toInt() shr 1) and 0x3F

        fragmentedFrames++

        var payloadOffset = 2
        var fragmentIndex = 0

        while (payloadOffset < nalSize) {
            val isFirst = fragmentIndex == 0
            val isLast = payloadOffset + MAX_FRAGMENT_PAYLOAD >= nalSize
            val fragmentSize = minOf(MAX_FRAGMENT_PAYLOAD, nalSize - payloadOffset)

            try {
                var offset = 0

                buildRtpHeader(packetBuffer, offset, isLast && isLastNal, rtpTimestamp)
                offset += RTP_HEADER_SIZE

                val layerIdMsb = nalHeader1.toInt() and 0x01
                packetBuffer[offset++] = ((49 shl 1) or layerIdMsb).toByte()
                packetBuffer[offset++] = nalHeader2

                var fuHeader = nalType
                if (isFirst) fuHeader = fuHeader or 0x80
                if (isLast) fuHeader = fuHeader or 0x40
                packetBuffer[offset++] = fuHeader.toByte()

                // Copy fragment from frame data
                System.arraycopy(frameData, nalOffset + payloadOffset, packetBuffer, offset, fragmentSize)

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
                sendErrors++
                if (sendErrors % 100 == 1L) {  // Log first error and every 100th
                    Log.w(TAG, "Fragment send failed: ${e.message} (total errors: $sendErrors)")
                }
                break
            }
        }
    }

    private fun buildRtpHeader(
        buffer: ByteArray,
        offset: Int,
        marker: Boolean,
        rtpTimestamp: Int
    ) {
        buffer[offset] = (RTP_VERSION shl 6).toByte()

        val markerBit = if (marker) 0x80 else 0x00
        buffer[offset + 1] = (markerBit or RTP_PAYLOAD_TYPE).toByte()

        buffer[offset + 2] = (sequenceNumber shr 8).toByte()
        buffer[offset + 3] = (sequenceNumber and 0xFF).toByte()

        buffer[offset + 4] = (rtpTimestamp shr 24).toByte()
        buffer[offset + 5] = (rtpTimestamp shr 16).toByte()
        buffer[offset + 6] = (rtpTimestamp shr 8).toByte()
        buffer[offset + 7] = (rtpTimestamp and 0xFF).toByte()

        buffer[offset + 8] = (ssrc shr 24).toByte()
        buffer[offset + 9] = (ssrc shr 16).toByte()
        buffer[offset + 10] = (ssrc shr 8).toByte()
        buffer[offset + 11] = (ssrc and 0xFF).toByte()
    }

    /**
     * Dedicated sender thread - pulls raw frames, parses NALs, and sends packets.
     * Parsing on this thread keeps EncoderSender thread fast (<1ms per frame).
     */
    private inner class SenderRunnable : Runnable {
        override fun run() {
            Log.i(TAG, "Sender thread started, senderRunning=${senderRunning.get()}")
            var framesSent = 0
            var pollAttempts = 0

            while (senderRunning.get() || !sendQueue.isEmpty()) {
                try {
                    pollAttempts++
                    if (pollAttempts <= 5) {
                        Log.d(TAG, "Poll attempt $pollAttempts, queue size=${sendQueue.size}, socket=$socket, serverAddress=$serverAddress")
                    }

                    // Wait for raw frame (blocking with timeout)
                    val rawFrame = sendQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (rawFrame == null) {
                        // Log every 50 empty polls to detect if we're waiting
                        if (pollAttempts % 50 == 1) {
                            Log.d(TAG, "Sender waiting, queue empty (poll attempt $pollAttempts, sent $framesSent frames so far)")
                        }
                        continue
                    }

                    Log.d(TAG, "Got frame from queue! size=${rawFrame.frameData.size}, checking socket/address...")

                    val sock = socket
                    if (sock == null) {
                        Log.e(TAG, "Socket is null! Breaking out of sender loop")
                        break
                    }

                    val addr = serverAddress
                    if (addr == null) {
                        Log.e(TAG, "ServerAddress is null! Breaking out of sender loop")
                        break
                    }

                    Log.d(TAG, "Dequeued frame ${framesSent + 1}, size=${rawFrame.frameData.size}, queue=${sendQueue.size}")

                    val startTime = System.nanoTime()
                    val packetsBefore = packetsSent

                    // Parse NAL units on sender thread (keeps EncoderSender fast)
                    val parseStart = System.nanoTime()
                    parseNalUnits(rawFrame.frameData)
                    val parseTime = (System.nanoTime() - parseStart) / 1_000_000

                    Log.d(TAG, "Parsed frame ${framesSent + 1}: ${nalCount} NALs in ${parseTime}ms")

                    if (nalCount == 0) {
                        Log.w(TAG, "Frame has no NAL units, skipping")
                        continue
                    }

                    // Send all NAL units for this frame
                    for (i in 0 until nalCount) {
                        val isLastNal = (i == nalCount - 1)
                        val nalOffset = nalOffsets[i]
                        val nalSize = nalSizes[i]

                        if (nalSize <= MTU - RTP_HEADER_SIZE) {
                            sendSinglePacket(sock, addr, rawFrame.frameData, nalOffset, nalSize, rawFrame.rtpTimestamp, isLastNal)
                        } else {
                            sendFragmentedNal(sock, addr, rawFrame.frameData, nalOffset, nalSize, rawFrame.rtpTimestamp, isLastNal)
                        }
                    }

                    framesSent++
                    val totalTimeMs = (System.nanoTime() - startTime) / 1_000_000
                    val packetsThisFrame = packetsSent - packetsBefore

                    Log.d(TAG, "Sent frame $framesSent: ${rawFrame.frameData.size}b, parse=${parseTime}ms, total=${totalTimeMs}ms, ${packetsThisFrame}pkts, queue=${sendQueue.size}, errors=$sendErrors")
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Sender thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Sender thread error", e)
                }
            }

            Log.i(TAG, "Sender thread stopped (sent $framesSent frames)")
        }
    }

    fun stop() {
        // Stop sender thread
        senderRunning.set(false)
        senderThread?.interrupt()
        senderThread?.join(1000)
        senderThread = null

        // Clear queue
        sendQueue.clear()

        socket?.close()
        socket = null
        serverAddress = null
    }

    companion object {
        private const val TAG = "RTPSender"
    }
}
