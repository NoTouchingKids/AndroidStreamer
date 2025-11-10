package com.example.android_streamer.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal RTSP server for H.265 streaming to MediaMTX.
 *
 * MediaMTX connects to this server (pull model) instead of Android pushing to MediaMTX.
 * This is the standard way to stream to MediaMTX.
 *
 * Architecture:
 * 1. MediaMTX connects via TCP to RTSP port (8554)
 * 2. RTSP handshake: DESCRIBE -> SETUP -> PLAY
 * 3. Server responds with SDP containing H.265 codec info
 * 4. Video data flows via RTP/UDP (using existing RTPSender)
 *
 * MediaMTX Configuration:
 * paths:
 *   android:
 *     source: rtsp://192.168.1.50:8554/live
 *     sourceProtocol: tcp
 *
 * @param rtspPort RTSP control port (default 8554)
 * @param rtpPort RTP data port for video (default 5004)
 * @param deviceIp Local IP address of Android device
 */
class RTSPServer(
    private val rtspPort: Int = 8554,
    private val rtpPort: Int = 5004,
    private val deviceIp: String
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var clientSocket: Socket? = null

    // Stream parameters (set by H265Encoder)
    private var width: Int = 1920
    private var height: Int = 1080
    private var fps: Int = 30
    private var sps: String? = null
    private var pps: String? = null

    // RTSP session
    private var cseq = 0
    private val sessionId = "12345678"

    // Client connection info (for RTP destination)
    private var clientIp: String? = null
    private var clientRtpPort: Int = 0

    // Callback when client connects and PLAY is requested
    var onClientReady: ((clientIp: String, clientRtpPort: Int) -> Unit)? = null

    /**
     * Start RTSP server listening for MediaMTX connections.
     */
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "RTSP server already running")
            return
        }

        try {
            serverSocket = ServerSocket(rtspPort, 1, InetAddress.getByName("0.0.0.0"))
            isRunning.set(true)

            Log.i(TAG, "RTSP server started on port $rtspPort")
            Log.i(TAG, "MediaMTX should connect to: rtsp://$deviceIp:$rtspPort/live")

            // Accept connections in background thread
            serverThread = thread(name = "RTSP-Server") {
                acceptConnections()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server", e)
            throw e
        }
    }

    /**
     * Set stream parameters from encoder (SPS/PPS for H.265).
     */
    fun setStreamParameters(width: Int, height: Int, fps: Int, sps: ByteArray?, pps: ByteArray?) {
        this.width = width
        this.height = height
        this.fps = fps
        this.sps = sps?.toBase64()
        this.pps = pps?.toBase64()

        Log.i(TAG, "Stream parameters set: ${width}x${height}@${fps}fps")
        if (sps != null && pps != null) {
            Log.d(TAG, "SPS/PPS configured (${sps.size}/${pps.size} bytes)")
        }
    }

    /**
     * Accept and handle RTSP client connections.
     */
    private fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                clientSocket = socket

                val clientAddr = socket.inetAddress.hostAddress
                Log.i(TAG, "RTSP client connected: $clientAddr")

                // Handle this client's requests
                handleClient(socket)

            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Socket error", e)
                }
                // Normal during shutdown
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting connection", e)
            }
        }
    }

    /**
     * Handle RTSP requests from client (MediaMTX).
     */
    private fun handleClient(socket: Socket) {
        try {
            // Capture client IP for RTP destination
            clientIp = socket.inetAddress.hostAddress
            Log.i(TAG, "Client IP captured: $clientIp")

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

            while (isRunning.get()) {
                // Read RTSP request
                val requestLine = reader.readLine() ?: break
                Log.d(TAG, "RTSP Request: $requestLine")

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break

                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        headers[parts[0].trim()] = parts[1].trim()
                    }
                }

                // Extract CSeq
                cseq = headers["CSeq"]?.toIntOrNull() ?: 0

                // Parse request method
                val method = requestLine.split(" ").getOrNull(0) ?: ""

                // Handle RTSP methods
                val response = when (method) {
                    "OPTIONS" -> handleOptions()
                    "DESCRIBE" -> handleDescribe()
                    "SETUP" -> handleSetup(headers)
                    "PLAY" -> handlePlay()
                    "TEARDOWN" -> handleTeardown()
                    else -> {
                        Log.w(TAG, "Unsupported method: $method")
                        buildResponse(501, "Not Implemented")
                    }
                }

                // Send response
                writer.print(response)
                writer.flush()
                Log.d(TAG, "RTSP Response: ${response.lines().firstOrNull()}")

                if (method == "TEARDOWN") break
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            socket.close()
            clientSocket = null
        }
    }

    /**
     * Handle OPTIONS request.
     */
    private fun handleOptions(): String {
        return buildResponse(
            200, "OK",
            "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"
        )
    }

    /**
     * Handle DESCRIBE request - send SDP.
     */
    private fun handleDescribe(): String {
        val sdp = buildSDP()

        return buildResponse(
            200, "OK",
            "Content-Type" to "application/sdp",
            "Content-Length" to sdp.length.toString()
        ) + sdp
    }

    /**
     * Handle SETUP request - configure RTP transport.
     */
    private fun handleSetup(headers: Map<String, String>): String {
        // Parse Transport header to extract client RTP port
        val transport = headers["Transport"] ?: ""
        Log.d(TAG, "Transport header: $transport")

        // Extract client_port from Transport header
        // Example: Transport: RTP/AVP;unicast;client_port=5004-5005
        val clientPortMatch = Regex("client_port=(\\d+)").find(transport)
        if (clientPortMatch != null) {
            clientRtpPort = clientPortMatch.groupValues[1].toInt()
            Log.i(TAG, "Client RTP port: $clientRtpPort")
        } else {
            // Default to our RTP port if not specified
            clientRtpPort = rtpPort
            Log.w(TAG, "No client_port in Transport, using default: $rtpPort")
        }

        return buildResponse(
            200, "OK",
            "Transport" to "RTP/AVP;unicast;client_port=$clientRtpPort-${clientRtpPort + 1};server_port=$rtpPort-${rtpPort + 1}",
            "Session" to sessionId
        )
    }

    /**
     * Handle PLAY request - start streaming.
     */
    private fun handlePlay(): String {
        Log.i(TAG, "MediaMTX requested PLAY - stream should start")

        // Notify that client is ready to receive RTP
        val ip = clientIp
        val port = clientRtpPort
        if (ip != null && port > 0) {
            Log.i(TAG, "Notifying RTP sender: send to $ip:$port")
            onClientReady?.invoke(ip, port)
        } else {
            Log.w(TAG, "Client IP or port not available for RTP")
        }

        return buildResponse(
            200, "OK",
            "Session" to sessionId,
            "Range" to "npt=0.000-"
        )
    }

    /**
     * Handle TEARDOWN request - stop streaming.
     */
    private fun handleTeardown(): String {
        Log.i(TAG, "MediaMTX sent TEARDOWN - stopping stream")

        return buildResponse(
            200, "OK",
            "Session" to sessionId
        )
    }

    /**
     * Build SDP (Session Description Protocol) for H.265 stream.
     */
    private fun buildSDP(): String {
        // Note: MediaMTX needs proper H.265 SDP with sprop-vps, sprop-sps, sprop-pps
        // For now, we'll use a generic H.265 SDP
        val spsParam = sps ?: ""
        val ppsParam = pps ?: ""

        return """
v=0
o=- 0 0 IN IP4 $deviceIp
s=AndroidStreamer H.265 Live Stream
c=IN IP4 $deviceIp
t=0 0
a=tool:AndroidStreamer
a=type:broadcast
a=control:*
a=range:npt=0-
m=video $rtpPort RTP/AVP 96
a=rtpmap:96 H265/90000
a=fmtp:96 sprop-sps=$spsParam;sprop-pps=$ppsParam
a=control:track0
a=framerate:$fps
a=cliprect:0,0,$height,$width

""".trimIndent()
    }

    /**
     * Build RTSP response.
     */
    private fun buildResponse(code: Int, status: String, vararg headers: Pair<String, String>): String {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $code $status\r\n")
        sb.append("CSeq: $cseq\r\n")

        for ((key, value) in headers) {
            sb.append("$key: $value\r\n")
        }

        sb.append("\r\n")
        return sb.toString()
    }

    /**
     * Stop RTSP server.
     */
    fun stop() {
        Log.i(TAG, "Stopping RTSP server")
        isRunning.set(false)

        clientSocket?.close()
        serverSocket?.close()

        serverThread?.interrupt()
        serverThread = null

        clientSocket = null
        serverSocket = null
    }

    /**
     * Get server status.
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * Get device IP for MediaMTX connection.
     */
    fun getConnectionUrl(): String = "rtsp://$deviceIp:$rtspPort/live"

    companion object {
        private const val TAG = "RTSPServer"

        /**
         * Convert byte array to Base64 string for SDP.
         */
        private fun ByteArray.toBase64(): String {
            return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
        }
    }
}
