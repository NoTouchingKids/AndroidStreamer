package com.example.android_streamer.network

import android.util.Base64
import android.util.Log
import java.net.InetAddress

/**
 * Generates SDP (Session Description Protocol) for RTSP ANNOUNCE
 * Describes H.265/HEVC video stream parameters
 */
object SDPGenerator {
    private const val TAG = "SDPGenerator"

    /**
     * Generate SDP for H.265 stream
     *
     * @param clientAddress Our IP address (for o= and c= lines)
     * @param rtpPort RTP data port (client port)
     * @param width Video width
     * @param height Video height
     * @param frameRate Video frame rate
     * @param payloadType RTP payload type (96 for dynamic)
     * @param vps VPS (Video Parameter Set) from encoder (optional)
     * @param sps SPS (Sequence Parameter Set) from encoder (optional)
     * @param pps PPS (Picture Parameter Set) from encoder (optional)
     */
    fun generateH265SDP(
        clientAddress: String = getLocalAddress(),
        rtpPort: Int,
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Int = 60,
        payloadType: Int = 96,
        vps: ByteArray? = null,
        sps: ByteArray? = null,
        pps: ByteArray? = null,
        rtspUrl: String? = null  // Optional RTSP URL for absolute control URLs
    ): String {
        val sessionId = System.currentTimeMillis()
        val sessionVersion = sessionId

        Log.d(TAG, "Generating SDP with client address: $clientAddress")

        val sdp = buildString {
            // Session description
            append("v=0\r\n")
            append("o=- $sessionId $sessionVersion IN IP4 $clientAddress\r\n")
            append("s=Android H.265 Stream\r\n")
            append("c=IN IP4 $clientAddress\r\n")
            append("t=0 0\r\n")
            append("a=tool:AndroidStreamer\r\n")
            append("a=type:broadcast\r\n")

            // Control URL - absolute if rtspUrl provided, otherwise relative
            if (rtspUrl != null) {
                append("a=control:$rtspUrl\r\n")
            } else {
                append("a=control:*\r\n")
            }

            // Media description
            append("m=video $rtpPort RTP/AVP $payloadType\r\n")
            append("b=AS:4000\r\n")  // 4 Mbps bandwidth

            // RTP map for H.265
            append("a=rtpmap:$payloadType H265/90000\r\n")

            // Format parameters
            val fmtp = buildString {
                append("a=fmtp:$payloadType")

                // Add parameter sets if available
                if (vps != null && sps != null && pps != null) {
                    append(" sprop-vps=")
                    append(Base64.encodeToString(vps, Base64.NO_WRAP))
                    append(";sprop-sps=")
                    append(Base64.encodeToString(sps, Base64.NO_WRAP))
                    append(";sprop-pps=")
                    append(Base64.encodeToString(pps, Base64.NO_WRAP))
                }

                // Profile/level/tier (Main profile, Level 5.1, Main tier)
                if (vps != null && sps != null && pps != null) {
                    append(";")
                } else {
                    append(" ")
                }
                append("profile-id=1")
                append(";level-id=153")  // Level 5.1
                append(";tier-flag=0")   // Main tier
            }
            append(fmtp)
            append("\r\n")

            // Video attributes
            append("a=framerate:$frameRate\r\n")

            // Note: No track-level control URL for RTSP publishing
            // For ANNOUNCE/RECORD, the session-level control URL is sufficient
            // MediaMTX uses the session path for SETUP, not per-track URLs

            // Note: No direction attribute (sendonly/recvonly) for RTSP publishing
            // MediaMTX rejects "sendonly" as it interprets it as a back channel
        }

        Log.d(TAG, "Generated SDP:\n$sdp")
        return sdp
    }

    /**
     * Get local IP address (Android-compatible)
     * Searches network interfaces for non-loopback IPv4 address
     */
    private fun getLocalAddress(): String {
        return try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    /**
     * Parse parameter sets from MediaCodec CSD-0 buffer
     * CSD-0 contains VPS, SPS, PPS in Annex B format (start codes 0x00 0x00 0x00 0x01)
     */
    fun parseParameterSets(csd0: ByteArray): Triple<ByteArray?, ByteArray?, ByteArray?> {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        try {
            val nalUnits = splitNALUnits(csd0)

            for (nal in nalUnits) {
                if (nal.isEmpty()) continue

                val nalType = (nal[0].toInt() shr 1) and 0x3F

                when (nalType) {
                    32 -> vps = nal  // VPS
                    33 -> sps = nal  // SPS
                    34 -> pps = nal  // PPS
                }
            }
        } catch (e: Exception) {
            // Parameter sets are optional, don't crash
        }

        return Triple(vps, sps, pps)
    }

    /**
     * Split Annex B format into individual NAL units
     * Start codes: 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01
     */
    private fun splitNALUnits(data: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var start = 0

        for (i in 0 until data.size - 3) {
            // Check for 4-byte start code
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                if (start > 0) {
                    nalUnits.add(data.copyOfRange(start, i))
                }
                start = i + 4
            }
            // Check for 3-byte start code
            else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                if (start > 0) {
                    nalUnits.add(data.copyOfRange(start, i))
                }
                start = i + 3
            }
        }

        // Add last NAL unit
        if (start < data.size) {
            nalUnits.add(data.copyOfRange(start, data.size))
        }

        return nalUnits
    }
}
