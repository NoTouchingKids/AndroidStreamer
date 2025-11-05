package com.example.android_streamer.buffer

import java.nio.ByteBuffer

/**
 * Lock-free ring buffer with preallocated fixed-size ByteBuffers.
 * Designed for zero-copy, GC-free operation in high-throughput camera streaming.
 *
 * @param capacity Number of buffers in the ring
 * @param bufferSize Size of each ByteBuffer in bytes
 */
class RingBuffer(
    private val capacity: Int,
    private val bufferSize: Int
) {
    // Preallocated direct ByteBuffers (off-heap, no GC pressure)
    private val buffers: Array<ByteBuffer> = Array(capacity) {
        ByteBuffer.allocateDirect(bufferSize)
    }

    // Metadata for each buffer slot
    private val timestamps: LongArray = LongArray(capacity)
    private val frameSizes: IntArray = IntArray(capacity)

    // Atomic-like indices (using @Volatile for single-producer/single-consumer)
    @Volatile
    private var writeIndex: Int = 0

    @Volatile
    private var readIndex: Int = 0

    @Volatile
    private var count: Int = 0

    /**
     * Get the next available buffer for writing.
     * Returns null if buffer is full (drop-on-overflow behavior).
     *
     * IMPORTANT: Caller must call commitWrite() after filling the buffer.
     */
    fun acquireWriteBuffer(): WriteSlot? {
        if (count >= capacity) {
            // Buffer full - drop frame (fire-and-forget)
            return null
        }

        val index = writeIndex
        val buffer = buffers[index]
        buffer.clear() // Reset position/limit without allocation

        return WriteSlot(index, buffer)
    }

    /**
     * Commit a write operation, making the buffer available for reading.
     *
     * @param slot The WriteSlot obtained from acquireWriteBuffer()
     * @param frameSize Actual size of data written to buffer
     * @param timestampNs Frame timestamp in nanoseconds
     */
    fun commitWrite(slot: WriteSlot, frameSize: Int, timestampNs: Long) {
        require(slot.index == writeIndex) { "Commit out of order" }
        require(frameSize <= bufferSize) { "Frame size exceeds buffer capacity" }

        frameSizes[slot.index] = frameSize
        timestamps[slot.index] = timestampNs

        // Prepare buffer for reading
        slot.buffer.flip()

        // Advance write index
        writeIndex = (writeIndex + 1) % capacity
        count++
    }

    /**
     * Get the next available buffer for reading.
     * Returns null if buffer is empty.
     *
     * IMPORTANT: Caller must call releaseReadBuffer() after consuming the data.
     */
    fun acquireReadBuffer(): ReadSlot? {
        if (count == 0) {
            return null
        }

        val index = readIndex
        return ReadSlot(
            index = index,
            buffer = buffers[index],
            frameSize = frameSizes[index],
            timestampNs = timestamps[index]
        )
    }

    /**
     * Release a read buffer, making it available for writing again.
     *
     * @param slot The ReadSlot obtained from acquireReadBuffer()
     */
    fun releaseReadBuffer(slot: ReadSlot) {
        require(slot.index == readIndex) { "Release out of order" }

        // Advance read index
        readIndex = (readIndex + 1) % capacity
        count--
    }

    /**
     * Get current buffer occupancy (0 to capacity).
     */
    fun size(): Int = count

    /**
     * Check if buffer is empty.
     */
    fun isEmpty(): Boolean = count == 0

    /**
     * Check if buffer is full.
     */
    fun isFull(): Boolean = count >= capacity

    /**
     * Clear all buffers (reset state).
     */
    fun clear() {
        writeIndex = 0
        readIndex = 0
        count = 0
    }

    /**
     * Slot for writing data. Holds a reference to the ByteBuffer and index.
     */
    data class WriteSlot(
        val index: Int,
        val buffer: ByteBuffer
    )

    /**
     * Slot for reading data. Includes timestamp and frame size metadata.
     */
    data class ReadSlot(
        val index: Int,
        val buffer: ByteBuffer,
        val frameSize: Int,
        val timestampNs: Long
    )

    companion object {
        /**
         * Create a RingBuffer sized for 1080p@60fps with RAW YUV 4:2:0 data.
         *
         * IMPORTANT: Currently sized for raw YUV frames with stride padding.
         * Once MediaCodec encoder with input surface is implemented, this can be reduced
         * to ~200KB per frame for encoded H.265 data.
         *
         * YUV 4:2:0 size calculation (without padding):
         * - Y plane: 1920 × 1080 = 2,073,600 bytes
         * - U plane: 960 × 540 = 518,400 bytes
         * - V plane: 960 × 540 = 518,400 bytes
         * - Base total: 3,110,400 bytes ≈ 3.1 MB
         *
         * STRIDE PADDING: Android camera YUV planes include row stride padding
         * for memory alignment. We add 50% safety margin to handle this.
         * - Actual size with padding: ~4.7 MB per frame
         *
         * Buffer count: 20 frames (0.33 seconds at 60fps)
         * Total memory: 4.7 MB × 20 = ~94 MB (off-heap)
         */
        fun createFor1080p60(): RingBuffer {
            val width = 1920
            val height = 1080
            // YUV 4:2:0 format: Y plane + U plane (1/4) + V plane (1/4)
            // Base size: (width × height × 3) / 2 = 3,110,400 bytes
            // Add 50% safety margin for row stride padding (common in Android cameras)
            val baseSize = (width * height * 3) / 2
            val bufferSize = (baseSize * 3) / 2 // ~4.7 MB per frame with padding
            val capacity = 20 // 0.33 seconds @ 60fps (reduced to keep memory reasonable)
            return RingBuffer(capacity, bufferSize)
        }

        /**
         * Create a RingBuffer sized for 4K@60fps with RAW YUV 4:2:0 data.
         *
         * YUV 4:2:0 size: 3840 × 2160 × 1.5 = 12,441,600 bytes ≈ 12 MB per frame
         * Buffer count: 15 frames (0.25 seconds at 60fps)
         * Total memory: 12 MB × 15 = ~180 MB (off-heap)
         */
        fun createFor4K60(): RingBuffer {
            val width = 3840
            val height = 2160
            val bufferSize = (width * height * 3) / 2 // 12,441,600 bytes
            val capacity = 15 // 0.25 seconds @ 60fps (smaller buffer due to memory)
            return RingBuffer(capacity, bufferSize)
        }
    }
}
