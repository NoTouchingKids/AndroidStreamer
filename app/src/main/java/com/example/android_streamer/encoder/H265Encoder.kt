package com.example.android_streamer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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

    // Callback when codec data (SPS/PPS/VPS) is available
    var onCodecDataReady: ((vps: ByteArray?, sps: ByteArray, pps: ByteArray) -> Unit)? = null

    /**
     * Start the encoder and consumer thread.
     *
     * @return Input Surface for camera to render into
     */
    fun start(): Surface {
        Log.i(TAG, "Starting H.265 encoder: ${width}x${height} @ ${frameRate}fps, ${bitrate / 1_000_000}Mbps")

        // Find best hardware encoder
        val codecName = findBestHEVCEncoder()
        Log.i(TAG, "Selected codec: $codecName")

        // Configure MediaCodec
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // I-frame interval: 0.5s for low latency (max 500ms decode startup, faster recovery from packet loss)
            // Tradeoff: ~10-15% bitrate increase vs 50% latency reduction
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f) // I-frame every 500ms
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            // Level 5.1 supports 1080p@120fps and provides headroom for future 4K@60fps
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)

            // Low-latency optimizations
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1) // Include SPS/PPS with keyframes

            // Bitrate mode: CBR for predictable latency (50 Mbps for 1080p@60fps high quality)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            // Quality tuning: Use balanced complexity for good quality + performance
            try {
                // KEY_COMPLEXITY: 0 (fast), 1 (balanced), 2 (quality)
                // Using 1 (balanced) for good quality without excessive CPU load
                // Keeps frame rate high while maintaining image quality
                setInteger(MediaFormat.KEY_COMPLEXITY, 1) // Balanced quality/performance
                Log.i(TAG, "Encoder complexity set to balanced (1) for quality + performance")
            } catch (e: Exception) {
                Log.d(TAG, "KEY_COMPLEXITY not supported on this device (expected for Exynos)")
            }

            // Note: KEY_LOW_LATENCY, KEY_LATENCY, KEY_OPERATING_RATE not supported on Exynos HEVC encoder
            // Removed to avoid configure() errors on Samsung devices
        }

        mediaCodec = MediaCodec.createByCodecName(codecName).apply {
            setCallback(EncoderCallback(), null) // null = use codec's thread
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        // Start consumer thread
        running.set(true)
        senderThread = Thread(SenderRunnable(), "EncoderSender").apply {
            priority = Thread.MAX_PRIORITY // High priority for low latency
            start()
        }

        Log.i(TAG, "H.265 encoder initialized: ${width}x${height}@${frameRate}fps, ${bitrate / 1_000_000}Mbps using $codecName")
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

            // Extract codec-specific data (SPS/PPS/VPS) for RTSP
            // For H.265, all parameter sets are typically in csd-0 concatenated together
            try {
                val csd0 = format.getByteBuffer("csd-0")
                if (csd0 != null) {
                    val csd0Data = ByteArray(csd0.remaining()).also { csd0.get(it) }
                    Log.i(TAG, "csd-0 size: ${csd0Data.size} bytes")

                    // Parse csd-0 to extract VPS, SPS, and PPS
                    parseH265ParameterSets(csd0Data)

                    val vpsSize = vpsData?.size ?: 0
                    val spsSize = spsData?.size ?: 0
                    val ppsSize = ppsData?.size ?: 0

                    Log.i(TAG, "Parsed codec data: VPS=${vpsSize}B, SPS=${spsSize}B, PPS=${ppsSize}B")

                    // Notify callback if we have at least SPS and PPS
                    if (spsData != null && ppsData != null) {
                        Log.i(TAG, "Codec data ready! Triggering callback...")
                        onCodecDataReady?.invoke(vpsData, spsData!!, ppsData!!)
                    } else {
                        Log.w(TAG, "Codec data incomplete (missing SPS or PPS)")
                    }
                } else {
                    Log.w(TAG, "csd-0 not found in output format")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract codec-specific data from output format", e)
            }
        }

        /**
         * Parse H.265 parameter sets from csd-0 buffer.
         * The buffer contains NAL units with either start codes (0x00000001) or length prefixes.
         */
        private fun parseH265ParameterSets(data: ByteArray) {
            var offset = 0

            while (offset < data.size) {
                var nalStart = offset
                var nalSize = 0

                // Check for start code (0x00000001 or 0x000001)
                if (offset + 3 < data.size &&
                    data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 0.toByte() &&
                    data[offset + 3] == 1.toByte()) {
                    // 4-byte start code
                    nalStart = offset + 4
                    // Find next start code or end of buffer
                    var nextStart = nalStart + 1
                    while (nextStart + 3 < data.size) {
                        if (data[nextStart] == 0.toByte() &&
                            data[nextStart + 1] == 0.toByte() &&
                            (data[nextStart + 2] == 0.toByte() || data[nextStart + 2] == 1.toByte())) {
                            break
                        }
                        nextStart++
                    }
                    nalSize = if (nextStart + 3 < data.size) nextStart - nalStart else data.size - nalStart
                    offset = nextStart
                } else if (offset + 2 < data.size &&
                           data[offset] == 0.toByte() &&
                           data[offset + 1] == 0.toByte() &&
                           data[offset + 2] == 1.toByte()) {
                    // 3-byte start code
                    nalStart = offset + 3
                    var nextStart = nalStart + 1
                    while (nextStart + 2 < data.size) {
                        if (data[nextStart] == 0.toByte() &&
                            data[nextStart + 1] == 0.toByte() &&
                            data[nextStart + 2] == 1.toByte()) {
                            break
                        }
                        nextStart++
                    }
                    nalSize = if (nextStart + 2 < data.size) nextStart - nalStart else data.size - nalStart
                    offset = nextStart
                } else if (offset + 4 <= data.size) {
                    // Length-prefixed (4 bytes big-endian length, then NAL data)
                    nalSize = ((data[offset].toInt() and 0xFF) shl 24) or
                              ((data[offset + 1].toInt() and 0xFF) shl 16) or
                              ((data[offset + 2].toInt() and 0xFF) shl 8) or
                              (data[offset + 3].toInt() and 0xFF)
                    nalStart = offset + 4

                    if (nalStart + nalSize > data.size) {
                        Log.w(TAG, "Invalid NAL size: $nalSize at offset $offset")
                        break
                    }
                    offset = nalStart + nalSize
                } else {
                    break
                }

                // Extract NAL unit
                if (nalSize > 0 && nalStart + nalSize <= data.size) {
                    val nalUnit = data.copyOfRange(nalStart, nalStart + nalSize)

                    // Get NAL unit type from first byte (bits 1-6, shifted right by 1)
                    val nalType = (nalUnit[0].toInt() shr 1) and 0x3F

                    when (nalType) {
                        32 -> {
                            vpsData = nalUnit
                            Log.d(TAG, "Found VPS: ${nalUnit.size} bytes")
                        }
                        33 -> {
                            spsData = nalUnit
                            Log.d(TAG, "Found SPS: ${nalUnit.size} bytes")
                        }
                        34 -> {
                            ppsData = nalUnit
                            Log.d(TAG, "Found PPS: ${nalUnit.size} bytes")
                        }
                        else -> {
                            Log.d(TAG, "Found NAL type $nalType: ${nalUnit.size} bytes")
                        }
                    }
                }
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

    /**
     * Find the best available HEVC encoder.
     * Prioritizes hardware encoders over software ones.
     *
     * Priority order:
     * 1. OMX.Exynos.HEVC.Encoder (Samsung Exynos hardware)
     * 2. Other hardware encoders (OMX.*, c2.*.hw.*)
     * 3. Software encoders (c2.android.*, OMX.google.*)
     */
    private fun findBestHEVCEncoder(): String {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val encoders = mutableListOf<Pair<String, Boolean>>() // <name, isHardware>

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue

            // Check if this codec supports HEVC
            val types = codecInfo.supportedTypes
            if (!types.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue

            val name = codecInfo.name
            val isHardware = !codecInfo.isSoftwareOnly

            Log.d(TAG, "Found HEVC encoder: $name (HW=$isHardware)")
            encoders.add(name to isHardware)
        }

        // Sort: hardware first, then prefer Exynos, then alphabetically
        encoders.sortWith(compareBy(
            { !(it.second) }, // Hardware first (false < true)
            { !it.first.contains("Exynos", ignoreCase = true) }, // Exynos first
            { it.first } // Alphabetical
        ))

        val selected = encoders.firstOrNull()?.first
            ?: throw RuntimeException("No HEVC encoder found on this device!")

        Log.i(TAG, "Available HEVC encoders: ${encoders.map { "${it.first} (HW=${it.second})" }}")
        return selected
    }

    companion object {
        private const val TAG = "H265Encoder"
    }
}
