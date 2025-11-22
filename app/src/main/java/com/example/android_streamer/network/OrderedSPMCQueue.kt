package com.example.android_streamer.network

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ordered Single-Producer Multiple-Consumer (SPMC) lock-free queue.
 *
 * Key properties:
 * - Single producer (MediaCodec encoder thread)
 * - Multiple consumers (2-4 UDP sender threads)
 * - Lock-free using CAS (Compare-And-Swap)
 * - Preserves FIFO ordering despite concurrent dequeues
 * - Optimized for high-throughput RTP streaming (4K60 @ 800 Mbps)
 *
 * Thread safety:
 * - enqueue(): Called by single producer thread (no synchronization needed)
 * - dequeue(): Called by multiple consumer threads (uses CAS for ordering)
 *
 * Performance:
 * - Enqueue: O(1) lock-free
 * - Dequeue: O(1) with CAS retry (low contention in SPMC pattern)
 * - Suitable for 200,000+ packets/sec throughput
 */
class OrderedSPMCQueue(
    private val capacity: Int = 1024  // Increased for 4K60 high bitrate
) {
    // Queue storage
    private val queue = Array(capacity) { PacketSlot() }

    // Atomic indices for lock-free access
    private val writeIndex = AtomicInteger(0)  // Producer writes here
    private val readIndex = AtomicInteger(0)   // Consumers read here (CAS protected)
    private val queueCount = AtomicInteger(0)  // Number of queued packets

    // Statistics
    private val enqueueCount = AtomicLong(0)
    private val dequeueCount = AtomicLong(0)
    private val casRetries = AtomicLong(0)  // CAS contention metric

    /**
     * Enqueue a packet (producer thread - single threaded, no locks needed).
     *
     * @param data ByteBuffer containing packet data
     * @return true if enqueued, false if queue is full
     */
    fun enqueue(data: ByteBuffer): Boolean {
        // Check if queue is full
        val count = queueCount.get()
        if (count >= capacity) {
            return false  // Queue full, drop packet
        }

        // Get write slot (single producer, no CAS needed)
        val wIdx = writeIndex.get()
        val slot = queue[wIdx]

        // Copy packet data to slot
        slot.buffer.clear()
        val packetSize = data.remaining()

        if (packetSize > slot.buffer.capacity()) {
            Log.e(TAG, "Packet too large: $packetSize bytes (max: ${slot.buffer.capacity()})")
            return false
        }

        slot.buffer.put(data)
        slot.buffer.flip()
        slot.size = packetSize

        // Advance write index (wraps around)
        writeIndex.set((wIdx + 1) % capacity)

        // Increment count atomically
        queueCount.incrementAndGet()
        enqueueCount.incrementAndGet()

        return true
    }

    /**
     * Dequeue a packet (consumer threads - multiple threads, uses CAS).
     *
     * Multiple consumer threads compete for packets using CAS.
     * Only one thread succeeds per packet, preserving FIFO order.
     *
     * @return PacketData if available, null if queue is empty
     */
    fun dequeue(): PacketData? {
        var retries = 0

        while (retries < MAX_CAS_RETRIES) {
            // Check if queue is empty
            if (queueCount.get() == 0) {
                return null
            }

            // Try to claim the next read slot using CAS
            val currentRead = readIndex.get()
            val nextRead = (currentRead + 1) % capacity

            // Atomic CAS: only succeeds if no other thread modified readIndex
            if (readIndex.compareAndSet(currentRead, nextRead)) {
                // Successfully claimed this slot
                queueCount.decrementAndGet()
                dequeueCount.incrementAndGet()

                if (retries > 0) {
                    casRetries.addAndGet(retries.toLong())
                }

                // Copy packet data from slot
                val slot = queue[currentRead]
                val packetData = PacketData(
                    data = ByteBuffer.allocate(slot.size).apply {
                        slot.buffer.rewind()
                        put(slot.buffer)
                        flip()
                    },
                    size = slot.size
                )

                return packetData
            }

            // CAS failed - another thread got this slot, retry
            retries++
        }

        // Too many retries (extreme contention)
        Log.w(TAG, "Dequeue failed after $MAX_CAS_RETRIES CAS retries")
        return null
    }

    /**
     * Get queue statistics.
     */
    fun getStats(): QueueStats {
        return QueueStats(
            capacity = capacity,
            currentOccupancy = queueCount.get(),
            totalEnqueued = enqueueCount.get(),
            totalDequeued = dequeueCount.get(),
            casRetries = casRetries.get()
        )
    }

    /**
     * Get current queue occupancy (0 to capacity).
     */
    fun getOccupancy(): Int = queueCount.get()

    /**
     * Check if queue is healthy (not experiencing high contention).
     */
    fun isHealthy(): Boolean {
        val dequeued = dequeueCount.get()
        if (dequeued < 1000) return true  // Not enough data

        val avgCASRetries = casRetries.get().toFloat() / dequeued
        return avgCASRetries < 2.0  // Less than 2 retries per dequeue on average
    }

    /**
     * Packet slot in the ring buffer.
     */
    private class PacketSlot {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(1500)  // MTU size
        var size: Int = 0
    }

    /**
     * Packet data returned from dequeue.
     */
    data class PacketData(
        val data: ByteBuffer,
        val size: Int
    )

    data class QueueStats(
        val capacity: Int,
        val currentOccupancy: Int,
        val totalEnqueued: Long,
        val totalDequeued: Long,
        val casRetries: Long
    )

    companion object {
        private const val TAG = "OrderedSPMCQueue"
        private const val MAX_CAS_RETRIES = 10  // Prevent infinite loops under extreme contention
    }
}
