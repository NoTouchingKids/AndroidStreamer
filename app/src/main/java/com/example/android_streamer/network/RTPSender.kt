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
    private val frameBuffer = ByteArray(1 * 1024 * 1024)

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

            socket?.sendBufferSize = 512 * 1024
            socket?.trafficClass = 0x10
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
        val sock = socket ?: return
        val addr = serverAddress ?: return

        val frameSize = buffer.remaining()

        if (frameSize > frameBuffer.size) {
            Log.e(TAG, "Frame too large: $frameSize bytes")
            return
        }

        val originalPosition = buffer.position()
        buffer.get(frameBuffer, 0, frameSize)
        buffer.position(originalPosition)

        val rtpTimestamp = (timestampUs * 90 / 1000).toInt()
        val nalUnits = parseNalUnits(frameBuffer, frameSize)

        if (nalUnits.isEmpty()) {
            return
        }

        for (i in nalUnits.indices) {
            val nal = nalUnits[i]
            val isLastNal = (i == nalUnits.size - 1)

            System.arraycopy(frameBuffer, nal.offset, frameBuffer, 0, nal.size)

            if (nal.size <= MTU - RTP_HEADER_SIZE) {
                sendSinglePacket(sock, addr, nal.size, rtpTimestamp, isLastNal)
            } else {
                sendFragmentedNal(sock, addr, nal.size, rtpTimestamp, isLastNal)
            }
        }
    }

    private fun parseNalUnits(buffer: ByteArray, bufferSize: Int): List<NalUnit> {
        val nalUnits = mutableListOf<NalUnit>()
        var offset = 0

        while (offset < bufferSize) {
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

            val nalStart = offset + startCodeSize
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

    private fun sendSinglePacket(
        sock: DatagramSocket,
        addr: InetAddress,
        frameSize: Int,
        rtpTimestamp: Int,
        markerBit: Boolean
    ) {
        try {
            var offset = 0

            buildRtpHeader(packetBuffer, offset, markerBit, rtpTimestamp)
            offset += RTP_HEADER_SIZE

            System.arraycopy(frameBuffer, 0, packetBuffer, offset, frameSize)

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

    private fun sendFragmentedNal(
        sock: DatagramSocket,
        addr: InetAddress,
        nalSize: Int,
        rtpTimestamp: Int,
        isLastNal: Boolean
    ) {
        val nalHeader1 = frameBuffer[0]
        val nalHeader2 = frameBuffer[1]
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

                System.arraycopy(frameBuffer, payloadOffset, packetBuffer, offset, fragmentSize)

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

    fun stop() {
        socket?.close()
        socket = null
        serverAddress = null
    }

    companion object {
        private const val TAG = "RTPSender"
    }
}
