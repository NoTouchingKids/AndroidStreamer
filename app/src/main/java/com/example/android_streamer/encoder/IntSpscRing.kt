package com.example.android_streamer.encoder

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free Single-Producer Single-Consumer ring buffer for primitive ints.
 *
 * Designed for zero-allocation, cache-friendly operation in high-throughput scenarios.
 * Uses power-of-two capacity for fast modulo via bitwise AND.
 *
 * Memory ordering:
 * - Producer writes to writeIndex (volatile via AtomicLong)
 * - Consumer reads from readIndex (volatile via AtomicLong)
 * - No locks, no CAS loops, pure SPSC semantics
 *
 * @param capacityPow2 Capacity as power of 2 (e.g., 32, 64, 128)
 */
class IntSpscRing(capacityPow2: Int) {

    init {
        require(capacityPow2 > 0 && (capacityPow2 and (capacityPow2 - 1)) == 0) {
            "Capacity must be power of 2, got: $capacityPow2"
        }
    }

    private val capacity = capacityPow2
    private val mask = capacity - 1 // For fast modulo: index & mask

    // Ring buffer storage (primitive int array, no objects)
    private val buffer = IntArray(capacity)

    // Separate cache lines for write/read indices to avoid false sharing
    // Using AtomicLong for volatile semantics (writes visible to consumer)
    @Volatile
    private var writeIndex = AtomicLong(0)

    @Volatile
    private var readIndex = AtomicLong(0)

    /**
     * Producer: Offer an int to the ring buffer.
     *
     * @param value The int value to enqueue (MediaCodec buffer index)
     * @return true if enqueued, false if full (caller should drop)
     */
    fun offer(value: Int): Boolean {
        val wIdx = writeIndex.get()
        val rIdx = readIndex.get()

        // Check if full: (writeIndex + 1) % capacity == readIndex
        if (((wIdx + 1) and mask.toLong()) == (rIdx and mask.toLong())) {
            return false // Full
        }

        // Write value
        buffer[(wIdx and mask.toLong()).toInt()] = value

        // Advance write index (volatile write, visible to consumer)
        writeIndex.lazySet(wIdx + 1)

        return true
    }

    /**
     * Consumer: Poll an int from the ring buffer.
     *
     * @return The int value, or -1 if empty
     */
    fun poll(): Int {
        val rIdx = readIndex.get()
        val wIdx = writeIndex.get()

        // Check if empty
        if ((rIdx and mask.toLong()) == (wIdx and mask.toLong())) {
            return -1 // Empty
        }

        // Read value
        val value = buffer[(rIdx and mask.toLong()).toInt()]

        // Advance read index (volatile write, visible to producer)
        readIndex.lazySet(rIdx + 1)

        return value
    }

    /**
     * Get current size (approximate, may be stale).
     * Not guaranteed to be accurate due to concurrent access.
     */
    fun size(): Int {
        val wIdx = writeIndex.get()
        val rIdx = readIndex.get()
        return ((wIdx - rIdx) and mask.toLong()).toInt()
    }

    /**
     * Check if buffer is empty (approximate).
     */
    fun isEmpty(): Boolean = size() == 0

    /**
     * Check if buffer is full (approximate).
     */
    fun isFull(): Boolean = size() >= capacity - 1

    /**
     * Get the capacity of this ring buffer.
     */
    fun capacity(): Int = capacity
}
