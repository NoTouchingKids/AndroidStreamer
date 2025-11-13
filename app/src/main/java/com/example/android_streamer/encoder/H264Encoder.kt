package com.example.android_streamer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.example.android_streamer.network.RTPSender
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 (AVC) encoder with zero-copy surface input and lock-free ring buffer.
 *
 * Architecture:
 * - Camera2 → MediaCodec Surface (GPU, zero-copy)
 * - Producer: MediaCodec callback → IntSpscRing (buffer indices only)
 * - Consumer: Sender thread → reads from ring → sends → releases buffers
 *
 * No allocations in hot path. Drop-on-full with keyframe protection.
 *
 * @param width Video width (e.g., 1920)
 * @param height Video height (e.g., 1080)
 * @param bitrate Target bitrate in bps (e.g., 8_000_000 for 8 Mbps)
 * @param frameRate Target frame rate (e.g., 60)
 * @param rtpSender Optional RTP sender for network streaming (null = local mode)
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val frameRate: Int,
    private val rtpSender: RTPSender? = null
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    // Lock-free SPSC ring buffer (32 slots for low latency)
    private val ring = IntSpscRing(32)

    // Metadata storage (64 slots to handle all possible buffer indices)
    private val metaTables = MetaTables(64)

    // Consumer thread
    private var senderThread: Thread? = null
    private val running = AtomicBoolean(false)

    // Stats
    @Volatile
    private var encodedFrames = 0L

    @Volatile
    private var droppedFrames = 0L

    @Volatile
    private var keyFrames = 0L

    // Codec-specific data (SPS/PPS for H.264)
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    // Callback when codec data (SPS/PPS) is available
    var onCodecDataReady: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null

    /**
     * Start the encoder and consumer thread.
     *
     * @return Input Surface for camera to render into
     */
    fun start(): Surface {
        Log.i(TAG, "Starting H.264 encoder: ${width}x${height} @ ${frameRate}fps, ${bitrate / 1_000_000}Mbps")

        // Configure MediaCodec
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // I-frame every 1 second
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)

            // Low-latency optimizations
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            setInteger(MediaFormat.KEY_LATENCY, 0) // Request lowest latency
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1) // Include SPS/PPS with keyframes
        }

        Log.d(TAG, "Creating MediaCodec encoder...")
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            Log.d(TAG, "Setting callback...")
            setCallback(EncoderCallback(), null) // null = use codec's thread

            Log.d(TAG, "Configuring encoder...")
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            Log.d(TAG, "Creating input surface...")
            inputSurface = createInputSurface()
            Log.i(TAG, "Input surface created: isValid=${inputSurface?.isValid}")

            Log.d(TAG, "Starting MediaCodec...")
            start()
            Log.i(TAG, "MediaCodec started successfully")
            Log.i(TAG, "Codec data will be extracted when onOutputFormatChanged is called")
        }

        // Start consumer thread
        running.set(true)
        senderThread = Thread(SenderRunnable(), "EncoderSender").apply {
            priority = Thread.MAX_PRIORITY // High priority for low latency
            start()
        }

        Log.i(TAG, "H.264 encoder fully initialized. Surface valid: ${inputSurface?.isValid}")
        return inputSurface!!
    }

    /**
     * Stop the encoder and consumer thread.
     */
    fun stop() {
        Log.i(TAG, "Stopping H.264 encoder")

        running.set(false)
        senderThread?.interrupt()
        senderThread?.join(1000)

        mediaCodec?.apply {
            stop()
            release()
        }
        mediaCodec = null
        inputSurface = null

        Log.i(TAG, "H.264 encoder stopped. Stats: encoded=$encodedFrames, dropped=$droppedFrames, keyframes=$keyFrames")
    }

    /**
     * Get encoding statistics.
     */
    fun getStats(): EncoderStats {
        return EncoderStats(
            encodedFrames = encodedFrames,
            droppedFrames = droppedFrames,
            keyFrames = keyFrames,
            ringOccupancy = ring.size()
        )
    }

    /**
     * Get codec-specific data (SPS/PPS) for RTSP.
     *
     * @return Codec data if available, null otherwise
     */
    fun getCodecData(): CodecData? {
        return if (spsData != null && ppsData != null) {
            CodecData(sps = spsData!!, pps = ppsData!!)
        } else {
            null
        }
    }

    /**
     * MediaCodec callback (Producer).
     *
     * Runs on MediaCodec's internal thread.
     * NO ALLOCATIONS in this hot path.
     */
    private inner class EncoderCallback : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Not used - camera renders directly to input surface
            // This should NEVER be called when using surface input
            Log.w(TAG, "onInputBufferAvailable called (unexpected for surface input!)")
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // Log first few frames to verify we're receiving encoded data
            if (encodedFrames < 5) {
                Log.d(TAG, "onOutputBufferAvailable: index=$index, size=${info.size}, flags=${info.flags}, pts=${info.presentationTimeUs}")
            }

            // Ignore empty buffers and codec config
            if (info.size == 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (info.size == 0) {
                    Log.d(TAG, "Releasing empty buffer: index=$index")
                } else {
                    Log.d(TAG, "Releasing codec config buffer: index=$index, size=${info.size}")
                }
                codec.releaseOutputBuffer(index, false)
                return
            }

            // Store metadata in parallel arrays
            metaTables.store(index, info)

            // Try to enqueue buffer index
            if (ring.offer(index)) {
                // Successfully enqueued
                encodedFrames++
                if (metaTables.isKeyFrame(index)) {
                    keyFrames++
                }
            } else {
                // Ring buffer full - apply drop policy
                val isKeyFrame = metaTables.isKeyFrame(index)

                if (!isKeyFrame) {
                    // Drop non-keyframe immediately
                    codec.releaseOutputBuffer(index, false)
                    droppedFrames++
                } else {
                    // Keyframe: tiny bounded spin (128 × ~400ns = ~50μs)
                    var spinCount = 0
                    while (spinCount < 128 && !ring.offer(index)) {
                        // Busy-wait (Thread.onSpinWait not available on all Android 10 devices)
                        spinCount++
                    }

                    if (spinCount >= 128) {
                        // Still full after spin - drop even keyframe
                        codec.releaseOutputBuffer(index, false)
                        droppedFrames++
                        Log.w(TAG, "Dropped KEYFRAME after spin - severe backpressure")
                    } else {
                        encodedFrames++
                        keyFrames++
                    }
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec error: ${e.message}", e)
            Log.e(TAG, "  Error code: ${e.errorCode}")
            Log.e(TAG, "  Is transient: ${e.isTransient}")
            Log.e(TAG, "  Is recoverable: ${e.isRecoverable}")
            Log.e(TAG, "  Diagnostic info: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format changed: $format")
            // Note: Output format doesn't contain all input keys (e.g., color-format is input-only)
            if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                Log.i(TAG, "  Width: ${format.getInteger(MediaFormat.KEY_WIDTH)}")
            }
            if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                Log.i(TAG, "  Height: ${format.getInteger(MediaFormat.KEY_HEIGHT)}")
            }
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                Log.i(TAG, "  Frame rate: ${format.getInteger(MediaFormat.KEY_FRAME_RATE)}")
            }

            // Extract codec-specific data (SPS/PPS) for RTSP
            // For H.264, SPS is in csd-0 and PPS is in csd-1
            try {
                val csd0 = format.getByteBuffer("csd-0")
                val csd1 = format.getByteBuffer("csd-1")

                if (csd0 != null && csd1 != null) {
                    // Extract SPS from csd-0
                    val csd0Data = ByteArray(csd0.remaining()).also { csd0.get(it) }
                    // Extract PPS from csd-1
                    val csd1Data = ByteArray(csd1.remaining()).also { csd1.get(it) }

                    Log.i(TAG, "csd-0 (SPS) size: ${csd0Data.size} bytes")
                    Log.i(TAG, "csd-1 (PPS) size: ${csd1Data.size} bytes")

                    // Parse to extract actual SPS/PPS NAL units (remove start codes if present)
                    spsData = extractNalUnit(csd0Data)
                    ppsData = extractNalUnit(csd1Data)

                    val spsSize = spsData?.size ?: 0
                    val ppsSize = ppsData?.size ?: 0

                    Log.i(TAG, "Parsed codec data: SPS=${spsSize}B, PPS=${ppsSize}B")

                    // Notify callback if we have both SPS and PPS
                    if (spsData != null && ppsData != null) {
                        Log.i(TAG, "Codec data ready! Triggering callback...")
                        onCodecDataReady?.invoke(spsData!!, ppsData!!)
                    } else {
                        Log.w(TAG, "Codec data incomplete (missing SPS or PPS)")
                    }
                } else {
                    Log.w(TAG, "csd-0 or csd-1 not found in output format")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract codec-specific data from output format", e)
            }
        }

        /**
         * Extract NAL unit from data, removing start codes if present.
         */
        private fun extractNalUnit(data: ByteArray): ByteArray {
            // Check for 4-byte start code (0x00000001)
            if (data.size >= 4 &&
                data[0] == 0.toByte() &&
                data[1] == 0.toByte() &&
                data[2] == 0.toByte() &&
                data[3] == 1.toByte()) {
                return data.copyOfRange(4, data.size)
            }

            // Check for 3-byte start code (0x000001)
            if (data.size >= 3 &&
                data[0] == 0.toByte() &&
                data[1] == 0.toByte() &&
                data[2] == 1.toByte()) {
                return data.copyOfRange(3, data.size)
            }

            // No start code, return as-is
            return data
        }
    }

    /**
     * Consumer thread (Sender).
     *
     * Polls ring buffer, sends encoded data, releases buffers.
     * NO ALLOCATIONS in this loop.
     */
    private inner class SenderRunnable : Runnable {

        override fun run() {
            Log.i(TAG, "Sender thread started")

            while (running.get() || !ring.isEmpty()) {
                // Poll for next buffer index
                val index = ring.poll()

                if (index == -1) {
                    // Ring empty - yield to other threads
                    // (Thread.onSpinWait not available on all Android 10 devices)
                    Thread.yield()
                    continue
                }

                try {
                    // Get encoded buffer from MediaCodec
                    val codec = mediaCodec ?: break
                    val buffer = codec.getOutputBuffer(index) ?: continue

                    // Set position/limit using metadata
                    val offset = metaTables.getOffset(index)
                    val size = metaTables.getSize(index)
                    val ptsUs = metaTables.getPtsUs(index)
                    val isKeyFrame = metaTables.isKeyFrame(index)

                    buffer.position(offset)
                    buffer.limit(offset + size)

                    // Send buffer to network if RTP sender is configured
                    if (rtpSender != null) {
                        try {
                            rtpSender.sendFrame(buffer, ptsUs, isKeyFrame)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send frame over RTP", e)
                        }
                    } else {
                        // Local mode - just log keyframes
                        if (isKeyFrame) {
                            Log.d(TAG, "Encoded keyframe: ${size} bytes, pts=${ptsUs}μs (no network sender)")
                        }
                    }

                } finally {
                    // ALWAYS release buffer back to MediaCodec
                    mediaCodec?.releaseOutputBuffer(index, false)
                }
            }

            Log.i(TAG, "Sender thread stopped")
        }
    }

    data class EncoderStats(
        val encodedFrames: Long,
        val droppedFrames: Long,
        val keyFrames: Long,
        val ringOccupancy: Int
    )

    data class CodecData(
        val sps: ByteArray,
        val pps: ByteArray
    )

    companion object {
        private const val TAG = "H264Encoder"
    }
}
