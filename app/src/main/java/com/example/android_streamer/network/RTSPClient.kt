package com.example.android_streamer.network

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RTSP client for publishing H.265 stream to MediaMTX server.
 *
 * This is the STANDARD approach where:
 * - MediaMTX = RTSP Server (stable IP, always listening)
 * - Android = RTSP Client (connects and publishes stream)
 *
 * Flow:
 * 1. Connect to MediaMTX via TCP
 * 2. ANNOUNCE - Tell server about our H.265 stream (with SDP)
 * 3. SETUP - Negotiate RTP ports
 * 4. RECORD - Start publishing (instead of PLAY for viewing)
 * 5. Send RTP packets via UDP
 *
 * MediaMTX Configuration (simple!):
 * # No paths config needed! Just defaults:
 * rtspAddress: :8554
 *
 * Android publishes to: rtsp://MEDIAMTX_IP:8554/android
 *
 * @param serverIp MediaMTX server IP (e.g., "192.168.0.2")
 * @param serverPort MediaMTX RTSP port (default 8554)
 * @param streamPath Stream path on server (e.g., "/android")
 */
class RTSPClient(
    private val serverIp: String,
    private val serverPort: Int = 8554,
    private val streamPath: String = "/android"
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private val isConnected = AtomicBoolean(false)

    private var cseq = 1
    private var sessionId: String? = null

    // Keepalive mechanism to prevent session timeout
    private var keepaliveThread: HandlerThread? = null
    private var keepaliveHandler: Handler? = null
    private val keepaliveInterval = 30000L // 30 seconds

    // Stream parameters (set before connecting)
    private var width: Int = 1920
    private var height: Int = 1080
    private var fps: Int = 30
    private var sps: String? = null
    private var pps: String? = null

    // RTP ports negotiated with server
    private var clientRtpPort: Int = 5004
    private var serverRtpPort: Int = 0

    // Callback when RECORD is accepted (ready to send RTP)
    var onReadyToStream: ((serverIp: String, serverRtpPort: Int) -> Unit)? = null

    // Pre-allocated keepalive message (zero-allocation at runtime)
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            sendGetParameter()
            // Reschedule if still connected
            if (isConnected.get()) {
                keepaliveHandler?.postDelayed(this, keepaliveInterval)
            }
        }
    }

    /**
     * Set stream parameters (must call before connect).
     */
    fun setStreamParameters(width: Int, height: Int, fps: Int, sps: ByteArray?, pps: ByteArray?) {
        this.width = width
        this.height = height
        this.fps = fps
        this.sps = sps?.toBase64()
        this.pps = pps?.toBase64()

        Log.i(TAG, "Stream parameters set: ${width}x${height}@${fps}fps")
    }

    /**
     * Connect to MediaMTX and start RTSP publishing session.
     */
    fun connect() {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected")
            return
        }

        thread(name = "RTSP-Client") {
            try {
                Log.i(TAG, "Connecting to MediaMTX at $serverIp:$serverPort$streamPath")

                // Connect TCP socket
                socket = Socket(serverIp, serverPort)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream()), true)

                isConnected.set(true)
                Log.i(TAG, "TCP connection established")

                // RTSP handshake
                if (!sendOptions()) {
                    Log.e(TAG, "OPTIONS failed")
                    disconnect()
                    return@thread
                }

                if (!sendAnnounce()) {
                    Log.e(TAG, "ANNOUNCE failed")
                    disconnect()
                    return@thread
                }

                if (!sendSetup()) {
                    Log.e(TAG, "SETUP failed")
                    disconnect()
                    return@thread
                }

                if (!sendRecord()) {
                    Log.e(TAG, "RECORD failed")
                    disconnect()
                    return@thread
                }

                Log.i(TAG, "✓ RTSP session established! Ready to stream.")

                // Start keepalive to prevent session timeout
                startKeepalive()

                // Notify that we're ready to send RTP
                onReadyToStream?.invoke(serverIp, serverRtpPort)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                disconnect()
            }
        }
    }

    /**
     * Send OPTIONS request.
     */
    private fun sendOptions(): Boolean {
        val request = "OPTIONS rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "\r\n"

        return sendRequestAndCheckResponse(request, "OPTIONS")
    }

    /**
     * Send ANNOUNCE request with SDP.
     */
    private fun sendAnnounce(): Boolean {
        val sdp = buildSDP()

        val request = "ANNOUNCE rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "Content-Type: application/sdp\r\n" +
                      "Content-Length: ${sdp.length}\r\n" +
                      "\r\n" +
                      sdp

        return sendRequestAndCheckResponse(request, "ANNOUNCE")
    }

    /**
     * Send SETUP request to negotiate RTP transport.
     */
    private fun sendSetup(): Boolean {
        // Note: Using RTP/AVP/UDP for explicit UDP transport
        val request = "SETUP rtsp://$serverIp:$serverPort$streamPath/track0 RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "Transport: RTP/AVP/UDP;unicast;client_port=$clientRtpPort-${clientRtpPort + 1};mode=record\r\n" +
                      "\r\n"

        val response = sendRequestAndGetResponse(request, "SETUP") ?: return false

        // Extract session ID
        val sessionMatch = Regex("Session: ([^;\r\n]+)").find(response)
        if (sessionMatch != null) {
            sessionId = sessionMatch.groupValues[1].trim()
            Log.i(TAG, "✓ Session ID: $sessionId")
        } else {
            Log.e(TAG, "✗ No Session ID in response!")
            return false
        }

        // Extract server RTP port from Transport header
        // MediaMTX should return something like: Transport: RTP/AVP;unicast;client_port=5004-5005;server_port=8000-8001
        val transportLine = response.lines().find { it.startsWith("Transport:", ignoreCase = true) }

        if (transportLine != null) {
            // Try multiple patterns for server_port
            val serverPortMatch = Regex("server_port=(\\d+)").find(transportLine)
            if (serverPortMatch != null) {
                serverRtpPort = serverPortMatch.groupValues[1].toInt()
                Log.i(TAG, "✓ Extracted server RTP port: $serverRtpPort")
            } else {
                Log.e(TAG, "✗ CRITICAL: No server_port in Transport header!")
                Log.e(TAG, "  MediaMTX must specify where to send RTP packets")
                Log.e(TAG, "  Transport: $transportLine")
                // For publishing mode, this is a protocol error - we don't know where to send!
                return false
            }
        } else {
            Log.e(TAG, "✗ No Transport header in response!")
            return false
        }

        Log.i(TAG, "✓ SETUP complete: client_port=$clientRtpPort -> server_port=$serverRtpPort")
        return true
    }

    /**
     * Send RECORD request to start publishing.
     */
    private fun sendRecord(): Boolean {
        val session = sessionId ?: run {
            Log.e(TAG, "No session ID for RECORD")
            return false
        }

        val request = "RECORD rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "Session: $session\r\n" +
                      "Range: npt=0.000-\r\n" +
                      "\r\n"

        return sendRequestAndCheckResponse(request, "RECORD")
    }

    /**
     * Send GET_PARAMETER to keep session alive (prevents 60s timeout).
     */
    private fun sendGetParameter() {
        val session = sessionId ?: return

        try {
            val request = "GET_PARAMETER rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                          "CSeq: ${cseq++}\r\n" +
                          "Session: $session\r\n" +
                          "\r\n"

            writer?.print(request)
            writer?.flush()

            // Don't wait for response - keepalive is fire-and-forget
            Log.d(TAG, "Keepalive sent (GET_PARAMETER)")
        } catch (e: Exception) {
            Log.w(TAG, "Keepalive failed: ${e.message}")
        }
    }

    /**
     * Start keepalive timer to prevent session timeout.
     */
    private fun startKeepalive() {
        // Create background thread for keepalive (low priority, minimal overhead)
        keepaliveThread = HandlerThread("RTSP-Keepalive").apply {
            start()
            keepaliveHandler = Handler(looper)
        }

        // Schedule first keepalive
        keepaliveHandler?.postDelayed(keepaliveRunnable, keepaliveInterval)
        Log.i(TAG, "Keepalive started (30s interval)")
    }

    /**
     * Stop keepalive timer.
     */
    private fun stopKeepalive() {
        keepaliveHandler?.removeCallbacks(keepaliveRunnable)
        keepaliveThread?.quitSafely()
        keepaliveThread = null
        keepaliveHandler = null
        Log.d(TAG, "Keepalive stopped")
    }

    /**
     * Send RTSP request and check for 200 OK response.
     */
    private fun sendRequestAndCheckResponse(request: String, method: String): Boolean {
        val response = sendRequestAndGetResponse(request, method)
        return response != null && response.contains("200 OK")
    }

    /**
     * Send RTSP request and return full response.
     */
    private fun sendRequestAndGetResponse(request: String, method: String): String? {
        try {
            Log.d(TAG, "→ $method")
            writer?.print(request)
            writer?.flush()

            // Read response
            val response = StringBuilder()
            val statusLine = reader?.readLine() ?: return null
            response.append(statusLine).append("\n")

            Log.d(TAG, "← $statusLine")

            // Read headers
            var contentLength = 0
            while (true) {
                val line = reader?.readLine() ?: break
                if (line.isEmpty()) break

                response.append(line).append("\n")

                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // Read body if present
            if (contentLength > 0) {
                val body = CharArray(contentLength)
                reader?.read(body, 0, contentLength)
                response.append(String(body))
            }

            val responseStr = response.toString()

            if (!statusLine.contains("200 OK")) {
                Log.w(TAG, "Non-OK response: $statusLine")
            }

            return responseStr

        } catch (e: Exception) {
            Log.e(TAG, "Error sending $method", e)
            return null
        }
    }

    /**
     * Build SDP for H.265 stream.
     */
    private fun buildSDP(): String {
        val spsParam = sps ?: ""
        val ppsParam = pps ?: ""

        // H.265 uses sprop-sps and sprop-pps as separate parameters
        return "v=0\r\n" +
               "o=- 0 0 IN IP4 127.0.0.1\r\n" +
               "s=Android H.265 Stream\r\n" +
               "c=IN IP4 $serverIp\r\n" +
               "t=0 0\r\n" +
               "a=tool:AndroidStreamer\r\n" +
               "a=type:broadcast\r\n" +
               "a=control:*\r\n" +
               "m=video $clientRtpPort RTP/AVP 96\r\n" +
               "a=rtpmap:96 H265/90000\r\n" +
               "a=fmtp:96 sprop-sps=$spsParam;sprop-pps=$ppsParam\r\n" +
               "a=control:track0\r\n"
    }

    /**
     * Disconnect from MediaMTX (sends TEARDOWN if session active).
     */
    fun disconnect() {
        if (!isConnected.get()) return

        // Stop keepalive first
        stopKeepalive()

        // Perform network operations on background thread to avoid NetworkOnMainThreadException
        thread {
            try {
                // Send TEARDOWN if we have a session
                sessionId?.let { session ->
                    val request = "TEARDOWN rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                                  "CSeq: ${cseq++}\r\n" +
                                  "Session: $session\r\n" +
                                  "\r\n"

                    writer?.print(request)
                    writer?.flush()
                    Log.i(TAG, "Sent TEARDOWN")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error sending TEARDOWN", e)
            }

            // Close connections
            try {
                reader?.close()
                writer?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing connections", e)
            }

            isConnected.set(false)
            sessionId = null

            Log.i(TAG, "Disconnected from MediaMTX")
        }
    }

    /**
     * Check if connected to MediaMTX.
     */
    fun isConnected(): Boolean = isConnected.get()

    /**
     * Get connection URL for display.
     */
    fun getServerUrl(): String = "rtsp://$serverIp:$serverPort$streamPath"

    /**
     * Get the client RTP port that was declared in SETUP.
     * RTPSender must bind to this port for MediaMTX to accept packets.
     */
    fun getClientRtpPort(): Int = clientRtpPort

    companion object {
        private const val TAG = "RTSPClient"

        /**
         * Convert byte array to Base64 for SDP.
         */
        private fun ByteArray.toBase64(): String {
            return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
        }
    }
}
