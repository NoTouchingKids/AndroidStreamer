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
 * H.265 encoder with zero-copy surface input and lock-free ring buffer.
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

    private val ring = IntSpscRing(32)
    private val metaTables = MetaTables(64)

    private var senderThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var encodedFrames = 0L

    @Volatile
    private var droppedFrames = 0L

    @Volatile
    private var keyFrames = 0L

    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var vpsData: ByteArray? = null

    var onCodecDataReady: ((vps: ByteArray?, sps: ByteArray, pps: ByteArray) -> Unit)? = null

    fun start(): Surface {
        val codecName = findBestHEVCEncoder()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            try {
                setInteger(MediaFormat.KEY_COMPLEXITY, 2)  // Increased from 1 for better quality
            } catch (e: Exception) {
                // Not supported on all devices
            }
        }

        mediaCodec = MediaCodec.createByCodecName(codecName).apply {
            setCallback(EncoderCallback(), null)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        running.set(true)
        senderThread = Thread(SenderRunnable(), "EncoderSender").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return inputSurface!!
    }

    fun stop() {
        running.set(false)
        senderThread?.interrupt()
        senderThread?.join(1000)

        mediaCodec?.apply {
            stop()
            release()
        }
        mediaCodec = null
        inputSurface = null
    }

    fun getStats(): EncoderStats {
        return EncoderStats(
            encodedFrames = encodedFrames,
            droppedFrames = droppedFrames,
            keyFrames = keyFrames,
            ringOccupancy = ring.size()
        )
    }

    fun getCodecData(): CodecData? {
        return if (spsData != null && ppsData != null) {
            CodecData(vps = vpsData, sps = spsData!!, pps = ppsData!!)
        } else {
            null
        }
    }

    private inner class EncoderCallback : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Not used with surface input
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (info.size == 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }

            metaTables.store(index, info)

            if (ring.offer(index)) {
                encodedFrames++
                if (metaTables.isKeyFrame(index)) {
                    keyFrames++
                }
            } else {
                val isKeyFrame = metaTables.isKeyFrame(index)

                if (!isKeyFrame) {
                    codec.releaseOutputBuffer(index, false)
                    droppedFrames++
                } else {
                    var spinCount = 0
                    while (spinCount < 128 && !ring.offer(index)) {
                        spinCount++
                    }

                    if (spinCount >= 128) {
                        codec.releaseOutputBuffer(index, false)
                        droppedFrames++
                        Log.w(TAG, "Dropped keyframe - severe backpressure")
                    } else {
                        encodedFrames++
                        keyFrames++
                    }
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Codec error: ${e.errorCode}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            try {
                val csd0 = format.getByteBuffer("csd-0")
                if (csd0 != null) {
                    val csd0Data = ByteArray(csd0.remaining()).also { csd0.get(it) }
                    parseH265ParameterSets(csd0Data)

                    if (spsData != null && ppsData != null) {
                        onCodecDataReady?.invoke(vpsData, spsData!!, ppsData!!)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract codec data", e)
            }
        }

        private fun parseH265ParameterSets(data: ByteArray) {
            var offset = 0

            while (offset < data.size) {
                var nalStart = offset
                var nalSize = 0

                if (offset + 3 < data.size &&
                    data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 0.toByte() &&
                    data[offset + 3] == 1.toByte()) {
                    nalStart = offset + 4
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
                    nalSize = ((data[offset].toInt() and 0xFF) shl 24) or
                              ((data[offset + 1].toInt() and 0xFF) shl 16) or
                              ((data[offset + 2].toInt() and 0xFF) shl 8) or
                              (data[offset + 3].toInt() and 0xFF)
                    nalStart = offset + 4

                    if (nalStart + nalSize > data.size) {
                        break
                    }
                    offset = nalStart + nalSize
                } else {
                    break
                }

                if (nalSize > 0 && nalStart + nalSize <= data.size) {
                    val nalUnit = data.copyOfRange(nalStart, nalStart + nalSize)
                    val nalType = (nalUnit[0].toInt() shr 1) and 0x3F

                    when (nalType) {
                        32 -> vpsData = nalUnit
                        33 -> spsData = nalUnit
                        34 -> ppsData = nalUnit
                    }
                }
            }
        }
    }

    private inner class SenderRunnable : Runnable {

        override fun run() {
            while (running.get() || !ring.isEmpty()) {
                val index = ring.poll()

                if (index == -1) {
                    // Sleep for ~half frame interval to avoid busy-wait
                    // At 60fps frames arrive every 16.7ms, so sleeping 8ms means
                    // we wake up ~2 times per frame instead of spinning constantly
                    Thread.sleep(8)
                    continue
                }

                try {
                    val codec = mediaCodec ?: break
                    val buffer = codec.getOutputBuffer(index) ?: continue

                    val offset = metaTables.getOffset(index)
                    val size = metaTables.getSize(index)
                    val ptsUs = metaTables.getPtsUs(index)
                    val isKeyFrame = metaTables.isKeyFrame(index)

                    buffer.position(offset)
                    buffer.limit(offset + size)

                    if (rtpSender != null) {
                        try {
                            rtpSender.sendFrame(buffer, ptsUs, isKeyFrame)
                        } catch (e: Exception) {
                            Log.e(TAG, "RTP send failed", e)
                        }
                    }

                } finally {
                    mediaCodec?.releaseOutputBuffer(index, false)
                }
            }
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

    private fun findBestHEVCEncoder(): String {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val encoders = mutableListOf<Pair<String, Boolean>>()

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue

            val types = codecInfo.supportedTypes
            if (!types.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue

            val name = codecInfo.name
            val isHardware = !codecInfo.isSoftwareOnly
            encoders.add(name to isHardware)
        }

        encoders.sortWith(compareBy(
            { !(it.second) },
            { !it.first.contains("Exynos", ignoreCase = true) },
            { it.first }
        ))

        return encoders.firstOrNull()?.first
            ?: throw RuntimeException("No HEVC encoder found")
    }

    companion object {
        private const val TAG = "H265Encoder"
    }
}
