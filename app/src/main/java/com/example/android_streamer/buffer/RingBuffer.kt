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
         * Create a RingBuffer sized for 1080p@60fps H.265 streaming.
         *
         * Estimated I-frame size: ~150KB (1920x1080 * 0.075 bpp)
         * Buffer count: 120 frames (2 seconds at 60fps)
         */
        fun createFor1080p60(): RingBuffer {
            val bufferSize = 200 * 1024 // 200KB per buffer (safety margin for I-frames)
            val capacity = 120 // 2 seconds @ 60fps
            return RingBuffer(capacity, bufferSize)
        }

        /**
         * Create a RingBuffer sized for 4K@60fps H.265 streaming.
         *
         * Estimated I-frame size: ~600KB (3840x2160 * 0.075 bpp)
         * Buffer count: 120 frames (2 seconds at 60fps)
         */
        fun createFor4K60(): RingBuffer {
            val bufferSize = 800 * 1024 // 800KB per buffer (safety margin)
            val capacity = 120 // 2 seconds @ 60fps
            return RingBuffer(capacity, bufferSize)
        }
    }
}
