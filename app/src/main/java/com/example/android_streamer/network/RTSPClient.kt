package com.example.android_streamer.network

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Async RTSP client for ANNOUNCE/SETUP/RECORD handshake
 * Control plane runs on coroutines - does NOT touch RTP data path
 *
 * RTSP Flow:
 * 1. ANNOUNCE - send SDP with H.265 details
 * 2. SETUP - negotiate RTP/UDP transport (client ports for RTP/RTCP)
 * 3. RECORD - start recording session
 * 4. TEARDOWN - end session
 */
class RTSPClient(
    private val host: String,
    private val port: Int = 8554,  // MediaMTX default RTSP port
    private val path: String = "android",  // Stream path
    private val rtpPort: Int,      // Our RTP sender port (e.g., 5004)
    private val rtcpPort: Int      // Our RTCP sender port (e.g., 5005)
) {
    companion object {
        private const val TAG = "RTSPClient"
        private const val TIMEOUT_MS = 5000
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val cseq = AtomicInteger(1)
    private var sessionId: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isConnected = false

    /**
     * Async connect and perform RTSP handshake
     * Returns true if session established successfully
     */
    suspend fun connect(sdp: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to rtsp://$host:$port/$path")

            // TCP connection
            socket = Socket(host, port).apply {
                soTimeout = TIMEOUT_MS
                tcpNoDelay = true  // Disable Nagle for control messages
            }

            writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))

            isConnected = true

            // RTSP handshake sequence
            if (!announce(sdp)) {
                Log.e(TAG, "ANNOUNCE failed")
                return@withContext false
            }

            if (!setup()) {
                Log.e(TAG, "SETUP failed")
                return@withContext false
            }

            if (!record()) {
                Log.e(TAG, "RECORD failed")
                return@withContext false
            }

            Log.i(TAG, "RTSP session established: $sessionId")
            true

        } catch (e: Exception) {
            Log.e(TAG, "RTSP connection failed", e)
            disconnect()
            false
        }
    }

    /**
     * ANNOUNCE - send SDP describing H.265 stream
     */
    private suspend fun announce(sdp: String): Boolean = withContext(Dispatchers.IO) {
        val request = buildString {
            append("ANNOUNCE rtsp://$host:$port/$path RTSP/1.0\r\n")
            append("CSeq: ${cseq.getAndIncrement()}\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdp.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n")
            append(sdp)
        }

        Log.d(TAG, "Sending ANNOUNCE:\n$request")
        writer?.print(request)
        writer?.flush()

        val response = readResponse()
        Log.d(TAG, "ANNOUNCE response:\n$response")

        response.startsWith("RTSP/1.0 200")
    }

    /**
     * SETUP - negotiate RTP/UDP transport
     * Tell server our client ports for RTP/RTCP
     */
    private suspend fun setup(): Boolean = withContext(Dispatchers.IO) {
        val request = buildString {
            append("SETUP rtsp://$host:$port/$path/trackID=0 RTSP/1.0\r\n")
            append("CSeq: ${cseq.getAndIncrement()}\r\n")
            append("Transport: RTP/AVP;unicast;client_port=$rtpPort-$rtcpPort\r\n")
            append("\r\n")
        }

        Log.d(TAG, "Sending SETUP:\n$request")
        writer?.print(request)
        writer?.flush()

        val response = readResponse()
        Log.d(TAG, "SETUP response:\n$response")

        if (!response.startsWith("RTSP/1.0 200")) {
            return@withContext false
        }

        // Extract Session ID
        sessionId = response.lines()
            .find { it.startsWith("Session:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.split(";")
            ?.firstOrNull()

        Log.i(TAG, "Session ID: $sessionId")
        sessionId != null
    }

    /**
     * RECORD - start recording/publishing session
     */
    private suspend fun record(): Boolean = withContext(Dispatchers.IO) {
        val request = buildString {
            append("RECORD rtsp://$host:$port/$path RTSP/1.0\r\n")
            append("CSeq: ${cseq.getAndIncrement()}\r\n")
            append("Session: $sessionId\r\n")
            append("Range: npt=0.000-\r\n")
            append("\r\n")
        }

        Log.d(TAG, "Sending RECORD:\n$request")
        writer?.print(request)
        writer?.flush()

        val response = readResponse()
        Log.d(TAG, "RECORD response:\n$response")

        response.startsWith("RTSP/1.0 200")
    }

    /**
     * Send OPTIONS to keep session alive (call periodically)
     */
    suspend fun sendKeepAlive(): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected || sessionId == null) return@withContext false

        try {
            val request = buildString {
                append("OPTIONS rtsp://$host:$port/$path RTSP/1.0\r\n")
                append("CSeq: ${cseq.getAndIncrement()}\r\n")
                append("Session: $sessionId\r\n")
                append("\r\n")
            }

            writer?.print(request)
            writer?.flush()

            val response = readResponse()
            response.startsWith("RTSP/1.0 200")
        } catch (e: Exception) {
            Log.e(TAG, "Keep-alive failed", e)
            false
        }
    }

    /**
     * TEARDOWN - end session gracefully
     */
    suspend fun teardown() = withContext(Dispatchers.IO) {
        if (!isConnected || sessionId == null) return@withContext

        try {
            val request = buildString {
                append("TEARDOWN rtsp://$host:$port/$path RTSP/1.0\r\n")
                append("CSeq: ${cseq.getAndIncrement()}\r\n")
                append("Session: $sessionId\r\n")
                append("\r\n")
            }

            Log.d(TAG, "Sending TEARDOWN")
            writer?.print(request)
            writer?.flush()

            val response = readResponse()
            Log.d(TAG, "TEARDOWN response:\n$response")
        } catch (e: Exception) {
            Log.e(TAG, "TEARDOWN failed", e)
        } finally {
            disconnect()
        }
    }

    /**
     * Disconnect TCP control channel
     */
    fun disconnect() {
        isConnected = false
        sessionId = null

        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }

        reader = null
        writer = null
        socket = null

        scope.cancel()
    }

    /**
     * Read RTSP response (status line + headers)
     */
    private fun readResponse(): String {
        val response = StringBuilder()
        var line: String?

        try {
            // Read status line
            line = reader?.readLine()
            if (line == null) {
                Log.e(TAG, "Connection closed by server")
                return ""
            }
            response.appendLine(line)

            // Read headers until blank line
            while (reader?.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                response.appendLine(line)
            }

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Response timeout")
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response", e)
            return ""
        }

        return response.toString()
    }

    /**
     * Start keep-alive loop (call OPTIONS every 30s)
     */
    fun startKeepAlive() {
        scope.launch {
            while (isActive && isConnected) {
                delay(30_000)  // 30 seconds
                if (!sendKeepAlive()) {
                    Log.w(TAG, "Keep-alive failed, session may be dead")
                    break
                }
            }
        }
    }
}
