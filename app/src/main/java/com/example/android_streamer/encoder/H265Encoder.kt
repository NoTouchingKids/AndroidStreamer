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
 * H.265 (HEVC) encoder with zero-copy surface input and lock-free ring buffer.
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
class H265Encoder(
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

    // Codec-specific data (SPS/PPS for H.265)
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var vpsData: ByteArray? = null

    /**
     * Start the encoder and consumer thread.
     *
     * @return Input Surface for camera to render into
     */
    fun start(): Surface {
        Log.i(TAG, "Starting H.265 encoder: ${width}x${height} @ ${frameRate}fps, ${bitrate / 1_000_000}Mbps")

        // Configure MediaCodec
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // I-frame every 1 second
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)

            // Low-latency optimizations
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            setInteger(MediaFormat.KEY_LATENCY, 0) // Request lowest latency
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1) // Include SPS/PPS with keyframes
        }

        Log.d(TAG, "Creating MediaCodec encoder...")
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
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

            // Extract codec-specific data (SPS/PPS/VPS) for RTSP
            try {
                val outputFormat = outputFormat
                vpsData = outputFormat.getByteBuffer("csd-0")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                spsData = outputFormat.getByteBuffer("csd-1")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                ppsData = outputFormat.getByteBuffer("csd-2")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                Log.i(TAG, "Extracted codec data: VPS=${vpsData?.size ?: 0}B, SPS=${spsData?.size ?: 0}B, PPS=${ppsData?.size ?: 0}B")
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract codec-specific data from output format", e)
            }
        }

        // Start consumer thread
        running.set(true)
        senderThread = Thread(SenderRunnable(), "EncoderSender").apply {
            priority = Thread.MAX_PRIORITY // High priority for low latency
            start()
        }

        Log.i(TAG, "H.265 encoder fully initialized. Surface valid: ${inputSurface?.isValid}")
        return inputSurface!!
    }

    /**
     * Stop the encoder and consumer thread.
     */
    fun stop() {
        Log.i(TAG, "Stopping H.265 encoder")

        running.set(false)
        senderThread?.interrupt()
        senderThread?.join(1000)

        mediaCodec?.apply {
            stop()
            release()
        }
        mediaCodec = null
        inputSurface = null

        Log.i(TAG, "H.265 encoder stopped. Stats: encoded=$encodedFrames, dropped=$droppedFrames, keyframes=$keyFrames")
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
     * Get codec-specific data (SPS/PPS/VPS) for RTSP.
     *
     * @return Codec data if available, null otherwise
     */
    fun getCodecData(): CodecData? {
        return if (spsData != null && ppsData != null) {
            CodecData(vps = vpsData, sps = spsData!!, pps = ppsData!!)
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
        val vps: ByteArray?,
        val sps: ByteArray,
        val pps: ByteArray
    )

    companion object {
        private const val TAG = "H265Encoder"
    }
}
