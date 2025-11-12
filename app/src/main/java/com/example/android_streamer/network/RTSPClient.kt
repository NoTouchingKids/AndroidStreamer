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
 * RTSP client for publishing H.265 stream to MediaMTX.
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

    private var keepaliveThread: HandlerThread? = null
    private var keepaliveHandler: Handler? = null
    private val keepaliveInterval = 30000L

    private var width: Int = 1920
    private var height: Int = 1080
    private var fps: Int = 30
    private var sps: String? = null
    private var pps: String? = null

    private var clientRtpPort: Int = 5004
    private var serverRtpPort: Int = 0

    var onReadyToStream: ((serverIp: String, serverRtpPort: Int) -> Unit)? = null

    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            sendGetParameter()
            if (isConnected.get()) {
                keepaliveHandler?.postDelayed(this, keepaliveInterval)
            }
        }
    }

    fun setStreamParameters(width: Int, height: Int, fps: Int, sps: ByteArray?, pps: ByteArray?) {
        this.width = width
        this.height = height
        this.fps = fps
        this.sps = sps?.toBase64()
        this.pps = pps?.toBase64()
    }

    fun connect() {
        if (isConnected.get()) return

        thread(name = "RTSP-Client") {
            try {
                socket = Socket(serverIp, serverPort)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream()), true)

                isConnected.set(true)

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

                startKeepalive()
                onReadyToStream?.invoke(serverIp, serverRtpPort)

            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                disconnect()
            }
        }
    }

    private fun sendOptions(): Boolean {
        val request = "OPTIONS rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "\r\n"

        return sendRequestAndCheckResponse(request, "OPTIONS")
    }

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

    private fun sendSetup(): Boolean {
        val request = "SETUP rtsp://$serverIp:$serverPort$streamPath/track0 RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "Transport: RTP/AVP/UDP;unicast;client_port=$clientRtpPort-${clientRtpPort + 1};mode=record\r\n" +
                      "\r\n"

        val response = sendRequestAndGetResponse(request, "SETUP") ?: return false

        val sessionMatch = Regex("Session: ([^;\r\n]+)").find(response)
        if (sessionMatch != null) {
            sessionId = sessionMatch.groupValues[1].trim()
        } else {
            Log.e(TAG, "No Session ID")
            return false
        }

        val transportLine = response.lines().find { it.startsWith("Transport:", ignoreCase = true) }

        if (transportLine != null) {
            val serverPortMatch = Regex("server_port=(\\d+)").find(transportLine)
            if (serverPortMatch != null) {
                serverRtpPort = serverPortMatch.groupValues[1].toInt()
            } else {
                Log.e(TAG, "No server_port in Transport")
                return false
            }
        } else {
            Log.e(TAG, "No Transport header")
            return false
        }

        return true
    }

    private fun sendRecord(): Boolean {
        val session = sessionId ?: run {
            Log.e(TAG, "No session ID")
            return false
        }

        val request = "RECORD rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                      "CSeq: ${cseq++}\r\n" +
                      "Session: $session\r\n" +
                      "Range: npt=0.000-\r\n" +
                      "\r\n"

        return sendRequestAndCheckResponse(request, "RECORD")
    }

    private fun sendGetParameter() {
        val session = sessionId ?: return

        try {
            val request = "GET_PARAMETER rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                          "CSeq: ${cseq++}\r\n" +
                          "Session: $session\r\n" +
                          "\r\n"

            writer?.print(request)
            writer?.flush()
        } catch (e: Exception) {
            // Keepalive failure not critical
        }
    }

    private fun startKeepalive() {
        keepaliveThread = HandlerThread("RTSP-Keepalive").apply {
            start()
            keepaliveHandler = Handler(looper)
        }

        keepaliveHandler?.postDelayed(keepaliveRunnable, keepaliveInterval)
    }

    private fun stopKeepalive() {
        keepaliveHandler?.removeCallbacks(keepaliveRunnable)
        keepaliveThread?.quitSafely()
        keepaliveThread = null
        keepaliveHandler = null
    }

    private fun sendRequestAndCheckResponse(request: String, method: String): Boolean {
        val response = sendRequestAndGetResponse(request, method)
        return response != null && response.contains("200 OK")
    }

    private fun sendRequestAndGetResponse(request: String, method: String): String? {
        try {
            writer?.print(request)
            writer?.flush()

            val response = StringBuilder()
            val statusLine = reader?.readLine() ?: return null
            response.append(statusLine).append("\n")

            var contentLength = 0
            while (true) {
                val line = reader?.readLine() ?: break
                if (line.isEmpty()) break

                response.append(line).append("\n")

                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (contentLength > 0) {
                val body = CharArray(contentLength)
                reader?.read(body, 0, contentLength)
                response.append(String(body))
            }

            val responseStr = response.toString()

            if (!statusLine.contains("200 OK")) {
                Log.w(TAG, "$method failed: $statusLine")
            }

            return responseStr

        } catch (e: Exception) {
            Log.e(TAG, "$method error", e)
            return null
        }
    }

    private fun buildSDP(): String {
        val spsParam = sps ?: ""
        val ppsParam = pps ?: ""

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

    fun disconnect() {
        if (!isConnected.get()) return

        stopKeepalive()

        thread {
            try {
                sessionId?.let { session ->
                    val request = "TEARDOWN rtsp://$serverIp:$serverPort$streamPath RTSP/1.0\r\n" +
                                  "CSeq: ${cseq++}\r\n" +
                                  "Session: $session\r\n" +
                                  "\r\n"

                    writer?.print(request)
                    writer?.flush()
                }
            } catch (e: Exception) {
                // Teardown error not critical
            }

            try {
                reader?.close()
                writer?.close()
                socket?.close()
            } catch (e: Exception) {
                // Close error not critical
            }

            isConnected.set(false)
            sessionId = null
        }
    }

    fun isConnected(): Boolean = isConnected.get()

    fun getServerUrl(): String = "rtsp://$serverIp:$serverPort$streamPath"

    fun getClientRtpPort(): Int = clientRtpPort

    companion object {
        private const val TAG = "RTSPClient"

        private fun ByteArray.toBase64(): String {
            return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
        }
    }
}
