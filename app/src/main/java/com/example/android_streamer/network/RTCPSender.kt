package com.example.android_streamer.network

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Async RTCP Sender for sending Sender Reports (SR)
 * Runs on coroutine - does NOT block RTP data path
 *
 * RTCP SR Format (RFC 3550):
 * - Sender information (NTP timestamp, RTP timestamp, packet count, byte count)
 * - Sent every ~5 seconds
 */
class RTCPSender(
    private val remoteHost: String,
    private val remotePort: Int,  // RTCP port (typically RTP port + 1)
    private val ssrc: Long        // Same SSRC as RTP sender
) {
    companion object {
        private const val TAG = "RTCPSender"
        private const val SR_INTERVAL_MS = 5000L  // Send SR every 5 seconds
        private const val RTCP_VERSION = 2
        private const val RTCP_SR_TYPE = 200  // Sender Report
    }

    private var channel: DatagramChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val buffer = ByteBuffer.allocateDirect(256)  // RTCP packets are small

    @Volatile
    private var isRunning = false

    // Statistics (updated by RTP sender)
    @Volatile
    var packetCount: Long = 0

    @Volatile
    var byteCount: Long = 0

    @Volatile
    var lastRtpTimestamp: Long = 0

    /**
     * Start RTCP sender (opens UDP channel and starts SR loop)
     */
    fun start() {
        if (isRunning) return

        try {
            // Open UDP channel for RTCP
            channel = DatagramChannel.open().apply {
                configureBlocking(false)
                socket().reuseAddress = true
            }

            val remoteAddress = InetSocketAddress(remoteHost, remotePort)
            channel?.connect(remoteAddress)

            isRunning = true

            Log.i(TAG, "RTCP sender started to $remoteHost:$remotePort")

            // Start periodic SR sending
            startSenderReportLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTCP sender", e)
            stop()
        }
    }

    /**
     * Stop RTCP sender
     */
    fun stop() {
        isRunning = false
        scope.cancel()

        runCatching { channel?.close() }
        channel = null

        Log.i(TAG, "RTCP sender stopped")
    }

    /**
     * Coroutine loop to send Sender Reports
     */
    private fun startSenderReportLoop() {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    sendSenderReport()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send SR", e)
                }

                delay(SR_INTERVAL_MS)
            }
        }
    }

    /**
     * Send RTCP Sender Report (SR)
     *
     * SR Packet Format (RFC 3550 Section 6.4.1):
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |V=2|P|    RC   |   PT=SR=200   |             length            |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                         SSRC of sender                        |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |              NTP timestamp, most significant word             |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |              NTP timestamp, least significant word            |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                         RTP timestamp                         |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                     sender's packet count                     |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                      sender's octet count                     |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     */
    private suspend fun sendSenderReport() = withContext(Dispatchers.IO) {
        buffer.clear()

        // Get current NTP timestamp
        val ntpTimestamp = getNtpTimestamp()

        // Header
        buffer.put(((RTCP_VERSION shl 6) or 0).toByte())  // V=2, P=0, RC=0
        buffer.put(RTCP_SR_TYPE.toByte())                 // PT=200 (SR)
        buffer.putShort(6)                                 // Length (6 words = 28 bytes)

        // SSRC
        buffer.putInt(ssrc.toInt())

        // NTP timestamp (64 bits)
        buffer.putInt((ntpTimestamp shr 32).toInt())  // MSW
        buffer.putInt(ntpTimestamp.toInt())           // LSW

        // RTP timestamp
        buffer.putInt(lastRtpTimestamp.toInt())

        // Sender's packet count
        buffer.putInt(packetCount.toInt())

        // Sender's octet count
        buffer.putInt(byteCount.toInt())

        buffer.flip()

        // Send UDP packet
        try {
            val sent = channel?.send(buffer, channel?.remoteAddress)
            if (sent != null && sent > 0) {
                Log.v(TAG, "Sent SR: packets=$packetCount, bytes=$byteCount, rtpTs=$lastRtpTimestamp")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send SR packet", e)
        }
    }

    /**
     * Get NTP timestamp (64-bit)
     * NTP epoch: January 1, 1900
     * Unix epoch: January 1, 1970
     * Offset: 2208988800 seconds
     */
    private fun getNtpTimestamp(): Long {
        val unixTimeMs = System.currentTimeMillis()
        val ntpSeconds = (unixTimeMs / 1000) + 2208988800L
        val ntpFraction = ((unixTimeMs % 1000) * 0x100000000L) / 1000

        return (ntpSeconds shl 32) or ntpFraction
    }

    /**
     * Get statistics for debugging
     */
    fun getStats(): String {
        return "RTCP SR: $packetCount packets, ${byteCount / 1024} KB sent"
    }
}
