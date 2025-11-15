package com.example.android_streamer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware-accelerated HEVC encoder optimized for low-latency real-time streaming.
 *
 * Requirements:
 * - Android 12+ (API 31+)
 * - Hardware HEVC encoder with 4K60 capability
 */
class HEVCEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrate: Int,
    private val onEncodedData: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val callback = EncoderCallback()

    // Performance class check
    private val performanceClass: Int
        get() = Build.VERSION.MEDIA_PERFORMANCE_CLASS

    /**
     * Check if device supports 4K60 encoding based on performance class.
     */
    fun supports4K60(): Boolean {
        return performanceClass >= Build.VERSION_CODES.S && width >= 3840 && height >= 2160
    }

    /**
     * Find best hardware HEVC encoder for the given resolution and frame rate.
     */
    private fun findHardwareEncoder(): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        return codecList.codecInfos
            .filter { info ->
                info.isEncoder &&
                info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC) &&
                info.isHardwareAccelerated
            }
            .firstOrNull { info ->
                // Check if encoder supports our resolution and frame rate
                val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val videoCapabilities = capabilities.videoCapabilities

                val sizeSupported = videoCapabilities.isSizeSupported(width, height)
                // Check if frame rate is supported for this resolution
                val fpsRange = try {
                    videoCapabilities.getSupportedFrameRatesFor(width, height)
                } catch (e: Exception) {
                    null
                }
                val fpsSupported = fpsRange?.contains(frameRate.toDouble()) ?: false

                sizeSupported && fpsSupported
            }
    }

    /**
     * Configure and start the encoder.
     * Returns the input surface for Camera2 to render to.
     */
    fun start(): Surface {
        val codecInfo = findHardwareEncoder()
            ?: throw IllegalStateException("No hardware HEVC encoder found for ${width}x${height}@${frameRate}fps")

        Log.i(TAG, "Selected encoder: ${codecInfo.name}")

        // Create encoder
        encoder = MediaCodec.createByCodecName(codecInfo.name)

        // Configure format for low-latency, CBR encoding
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // I-frame every 1 second

            // Low-latency hints (Android 12+)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            setInteger(MediaFormat.KEY_LATENCY, 0) // Minimize latency

            // Profile and level for HEVC
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            if (width >= 3840 && height >= 2160) {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            } else {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
            }
        }

        // Configure encoder with callback
        encoder!!.setCallback(callback)
        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Create input surface for camera
        inputSurface = encoder!!.createInputSurface()

        // Start encoder
        encoder!!.start()

        Log.i(TAG, "Encoder started: ${width}x${height}@${frameRate}fps, bitrate=${bitrate / 1_000_000}Mbps")

        return inputSurface!!
    }

    /**
     * Stop and release the encoder.
     */
    fun stop() {
        try {
            encoder?.stop()
            encoder?.release()
            inputSurface?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        } finally {
            encoder = null
            inputSurface = null
        }
        Log.i(TAG, "Encoder stopped")
    }

    /**
     * Request sync frame (I-frame) immediately.
     */
    fun requestSyncFrame() {
        encoder?.let { codec ->
            val bundle = android.os.Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(bundle)
        }
    }

    /**
     * Adjust bitrate dynamically (for network adaptation).
     */
    fun setBitrate(newBitrate: Int) {
        encoder?.let { codec ->
            val bundle = android.os.Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            codec.setParameters(bundle)
            Log.d(TAG, "Bitrate adjusted to ${newBitrate / 1_000_000}Mbps")
        }
    }

    /**
     * Encoder callback for asynchronous operation.
     */
    private inner class EncoderCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Not used - we're using Surface input
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (index < 0) return

            try {
                val outputBuffer = codec.getOutputBuffer(index) ?: return

                // Pass encoded data to callback
                if (info.size > 0) {
                    onEncodedData(outputBuffer, info)
                }

                // Release buffer
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing output buffer", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error: ${e.message}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format changed: $format")
        }
    }

    companion object {
        private const val TAG = "HEVCEncoder"

        /**
         * Create encoder for 1080p@60fps with recommended bitrate.
         */
        fun createFor1080p60(onEncodedData: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): HEVCEncoder {
            return HEVCEncoder(
                width = 1920,
                height = 1080,
                frameRate = 60,
                bitrate = 10_000_000, // 10 Mbps for 1080p60
                onEncodedData = onEncodedData
            )
        }

        /**
         * Create encoder for 4K@60fps with recommended bitrate.
         */
        fun createFor4K60(onEncodedData: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): HEVCEncoder {
            return HEVCEncoder(
                width = 3840,
                height = 2160,
                frameRate = 60,
                bitrate = 40_000_000, // 40 Mbps for 4K60
                onEncodedData = onEncodedData
            )
        }

        /**
         * Check if hardware HEVC encoder is available.
         */
        fun isHardwareHEVCAvailable(): Boolean {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return codecList.codecInfos.any { info ->
                info.isEncoder &&
                info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC) &&
                info.isHardwareAccelerated
            }
        }
    }
}
