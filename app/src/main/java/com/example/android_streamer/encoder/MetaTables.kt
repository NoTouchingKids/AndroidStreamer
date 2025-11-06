package com.example.android_streamer.encoder

import android.media.MediaCodec

/**
 * Preallocated parallel primitive arrays for MediaCodec buffer metadata.
 *
 * Stores offset, size, flags, and presentation timestamp for each buffer index.
 * No object allocations - all data in primitive arrays for cache efficiency.
 *
 * Indexed by MediaCodec output buffer index (0 to maxSlots-1).
 *
 * @param maxSlots Maximum number of buffer slots (typically 64)
 */
class MetaTables(private val maxSlots: Int) {

    // Parallel arrays indexed by buffer index
    private val offset = IntArray(maxSlots)
    private val size = IntArray(maxSlots)
    private val flags = IntArray(maxSlots)
    private val ptsUs = LongArray(maxSlots)

    /**
     * Store metadata for a buffer index.
     *
     * Called by producer (MediaCodec callback) when buffer becomes available.
     *
     * @param index MediaCodec output buffer index
     * @param info MediaCodec.BufferInfo containing offset, size, flags, pts
     */
    fun store(index: Int, info: MediaCodec.BufferInfo) {
        require(index >= 0 && index < maxSlots) { "Index out of bounds: $index" }

        offset[index] = info.offset
        size[index] = info.size
        flags[index] = info.flags
        ptsUs[index] = info.presentationTimeUs
    }

    /**
     * Get buffer offset.
     */
    fun getOffset(index: Int): Int {
        require(index >= 0 && index < maxSlots) { "Index out of bounds: $index" }
        return offset[index]
    }

    /**
     * Get buffer size.
     */
    fun getSize(index: Int): Int {
        require(index >= 0 && index < maxSlots) { "Index out of bounds: $index" }
        return size[index]
    }

    /**
     * Get buffer flags.
     */
    fun getFlags(index: Int): Int {
        require(index >= 0 && index < maxSlots) { "Index out of bounds: $index" }
        return flags[index]
    }

    /**
     * Get presentation timestamp in microseconds.
     */
    fun getPtsUs(index: Int): Long {
        require(index >= 0 && index < maxSlots) { "Index out of bounds: $index" }
        return ptsUs[index]
    }

    /**
     * Check if buffer is a keyframe.
     */
    fun isKeyFrame(index: Int): Boolean {
        return (getFlags(index) and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
    }

    /**
     * Check if buffer is end of stream.
     */
    fun isEndOfStream(index: Int): Boolean {
        return (getFlags(index) and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
    }
}
