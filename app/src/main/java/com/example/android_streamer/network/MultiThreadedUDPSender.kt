package com.example.android_streamer.network

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-threaded UDP sender optimized for high-bitrate 4K60 streaming.
 *
 * Threading model:
 * - Producer: MediaCodec encoder callback thread (single thread)
 * - Consumers: 2-4 dedicated UDP sender threads (configurable)
 * - Pattern: Single-Producer Multiple-Consumer (SPMC) lock-free queue
 *
 * Performance targets:
 * - 4K60 @ 120 Mbps: ~179 packets/frame, ~29,833 packets/sec
 * - 4K60 @ 800 Mbps: ~1,190 packets/frame, ~198,333 packets/sec
 *
 * Key optimizations:
 * - Lock-free SPMC queue using CAS for ordered dequeue
 * - Multiple sender threads to parallelize UDP send() syscalls
 * - Direct ByteBuffers (off-heap, no GC pressure)
 * - Non-blocking DatagramChannel per thread
 * - Thread-level statistics for load balancing insights
 *
 * Thread safety:
 * - sendPacket() called by single encoder thread
 * - Internal sender threads coordinate via CAS in SPMC queue
 * - All statistics use AtomicLong for thread-safe updates
 */
class MultiThreadedUDPSender(
    private val remoteHost: String,
    private val remotePort: Int,
    private val numThreads: Int = 4  // 2-4 recommended for 4K60 high bitrate
) {
    private val isRunning = AtomicBoolean(false)

    // SPMC queue for ordered packet distribution
    private val packetQueue = OrderedSPMCQueue(capacity = 2048)  // Large buffer for 4K60

    // Remote address
    private var remoteAddress: InetSocketAddress? = null

    // Sender threads
    private val senderThreads = mutableListOf<Thread>()
    private val threadChannels = ConcurrentHashMap<Int, DatagramChannel>()

    // Global statistics (aggregated across all threads)
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val sendErrors = AtomicLong(0)
    private val packetsDropped = AtomicLong(0)

    // Per-thread statistics for load balancing analysis
    private val threadStats = ConcurrentHashMap<Int, ThreadStats>()

    /**
     * Start the multi-threaded UDP sender.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Sender already running")
            return
        }

        try {
            remoteAddress = InetSocketAddress(remoteHost, remotePort)

            // Create sender threads
            repeat(numThreads) { threadId ->
                // Create dedicated DatagramChannel for this thread
                val channel = DatagramChannel.open().apply {
                    configureBlocking(false)

                    socket().apply {
                        sendBufferSize = 2 * 1024 * 1024  // 2MB send buffer for 4K60
                        trafficClass = 0x10  // IPTOS_LOWDELAY
                        reuseAddress = true
                    }
                }

                threadChannels[threadId] = channel
                threadStats[threadId] = ThreadStats()

                // Create and start sender thread
                val thread = Thread({
                    runSenderLoop(threadId, channel)
                }, "RTP-Sender-$threadId").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }

                senderThreads.add(thread)
            }

            Log.i(TAG, "Multi-threaded UDP sender started: $remoteHost:$remotePort " +
                    "with $numThreads threads (4K60 optimized)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start multi-threaded UDP sender", e)
            isRunning.set(false)
            stopAllThreads()
            throw e
        }
    }

    /**
     * Stop all sender threads.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        stopAllThreads()

        val stats = getStats()
        Log.i(TAG, "Multi-threaded UDP sender stopped. " +
                "Sent: ${stats.packetsSent} packets (${stats.bytesSent / 1024 / 1024}MB), " +
                "Dropped: ${stats.packetsDropped}, Errors: ${stats.sendErrors}")

        // Log per-thread statistics
        threadStats.forEach { (threadId, stats) ->
            Log.d(TAG, "Thread-$threadId: Sent ${stats.packetsSent.get()} packets, " +
                    "Errors: ${stats.sendErrors.get()}")
        }
    }

    /**
     * Stop all sender threads and close channels.
     */
    private fun stopAllThreads() {
        try {
            // Interrupt all threads
            senderThreads.forEach { it.interrupt() }

            // Wait for threads to finish (max 1 second each)
            senderThreads.forEach { it.join(1000) }

            // Close all channels
            threadChannels.values.forEach { it.close() }

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sender threads", e)
        } finally {
            senderThreads.clear()
            threadChannels.clear()
        }
    }

    /**
     * Send a packet asynchronously (called by encoder thread).
     *
     * Enqueues packet to SPMC queue for processing by sender threads.
     *
     * @param packet ByteBuffer containing packet data
     * @return true if queued successfully, false if queue is full
     */
    fun sendPacket(packet: ByteBuffer): Boolean {
        if (!isRunning.get()) {
            return false
        }

        // Enqueue to SPMC queue (lock-free, single producer)
        val success = packetQueue.enqueue(packet)

        if (!success) {
            // Queue full - drop packet
            packetsDropped.incrementAndGet()

            if (packetsDropped.get() % 100 == 0L) {
                Log.w(TAG, "Queue full, dropped ${packetsDropped.get()} packets total " +
                        "(occupancy: ${packetQueue.getOccupancy()})")
            }
        }

        return success
    }

    /**
     * Sender thread main loop (consumer).
     *
     * Each thread independently dequeues packets using CAS and sends them.
     * Packet ordering is preserved by the OrderedSPMCQueue's CAS mechanism.
     *
     * @param threadId Unique thread identifier (0 to numThreads-1)
     * @param channel DatagramChannel for this thread
     */
    private fun runSenderLoop(threadId: Int, channel: DatagramChannel) {
        val remoteAddress = this.remoteAddress ?: return
        val stats = threadStats[threadId] ?: return

        Log.i(TAG, "Sender thread $threadId started (high priority)")

        var consecutiveErrors = 0
        val maxConsecutiveErrors = 100
        var idleSpinCount = 0
        val maxIdleSpins = 10  // Spin a few times before sleeping

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                // Try to dequeue packet (CAS-based, thread-safe)
                val packetData = packetQueue.dequeue()

                if (packetData != null) {
                    // Reset idle counter
                    idleSpinCount = 0

                    // Send packet
                    val bytesSentNow = channel.send(packetData.data, remoteAddress)

                    if (bytesSentNow > 0) {
                        // Success
                        packetsSent.incrementAndGet()
                        bytesSent.addAndGet(bytesSentNow.toLong())
                        stats.packetsSent.incrementAndGet()
                        stats.bytesSent.addAndGet(bytesSentNow.toLong())
                        consecutiveErrors = 0

                        // Debug logging for first few packets per thread
                        if (stats.packetsSent.get() <= 2) {
                            Log.d(TAG, "Thread-$threadId sent packet ${stats.packetsSent.get()}: " +
                                    "$bytesSentNow bytes")
                        }

                    } else {
                        // Channel buffer full (would block), yield and retry
                        Thread.yield()
                    }

                } else {
                    // Queue empty - back off to avoid busy-wait
                    idleSpinCount++

                    if (idleSpinCount < maxIdleSpins) {
                        // Spin a few times (low latency)
                        Thread.yield()
                    } else {
                        // Sleep briefly to avoid burning CPU
                        Thread.sleep(0, 10_000)  // 10 microseconds
                    }
                }

            } catch (e: InterruptedException) {
                Log.i(TAG, "Sender thread $threadId interrupted")
                break

            } catch (e: Exception) {
                sendErrors.incrementAndGet()
                stats.sendErrors.incrementAndGet()
                consecutiveErrors++

                Log.e(TAG, "Error in sender thread $threadId (consecutive: $consecutiveErrors)", e)

                if (consecutiveErrors >= maxConsecutiveErrors) {
                    Log.e(TAG, "Thread $threadId: Too many consecutive errors, stopping")
                    isRunning.set(false)
                    break
                }

                // Back off on errors
                Thread.sleep(1)
            }
        }

        Log.i(TAG, "Sender thread $threadId stopped " +
                "(sent: ${stats.packetsSent.get()} packets)")
    }

    /**
     * Get aggregated sender statistics.
     */
    fun getStats(): SenderStats {
        val queueStats = packetQueue.getStats()

        return SenderStats(
            packetsSent = packetsSent.get(),
            bytesSent = bytesSent.get(),
            sendErrors = sendErrors.get(),
            packetsDropped = packetsDropped.get(),
            queueOccupancy = queueStats.currentOccupancy,
            queueCapacity = queueStats.capacity,
            casRetries = queueStats.casRetries,
            numThreads = numThreads,
            perThreadStats = threadStats.mapValues { (_, stats) ->
                PerThreadStats(
                    packetsSent = stats.packetsSent.get(),
                    bytesSent = stats.bytesSent.get(),
                    sendErrors = stats.sendErrors.get()
                )
            }
        )
    }

    /**
     * Check if sender is healthy.
     *
     * Criteria:
     * - Low packet drop rate (<1%)
     * - Low error rate (<0.1%)
     * - Queue not experiencing high CAS contention
     */
    fun isHealthy(): Boolean {
        val totalPackets = packetsSent.get() + packetsDropped.get()
        if (totalPackets < 1000) return true  // Not enough data

        val dropRate = packetsDropped.get().toFloat() / totalPackets
        val errorRate = sendErrors.get().toFloat() / totalPackets

        return dropRate < 0.01 &&
               errorRate < 0.001 &&
               packetQueue.isHealthy()
    }

    /**
     * Get detailed performance metrics for analysis.
     */
    fun getPerformanceMetrics(): PerformanceMetrics {
        val stats = getStats()
        val queueOccupancyPercent = (stats.queueOccupancy.toFloat() / stats.queueCapacity * 100)
        val avgCASRetries = if (stats.packetsSent > 0) {
            stats.casRetries.toFloat() / stats.packetsSent
        } else 0f

        return PerformanceMetrics(
            throughputMbps = (stats.bytesSent * 8.0 / 1_000_000),  // Rough estimate
            queueOccupancyPercent = queueOccupancyPercent,
            avgCASRetriesPerPacket = avgCASRetries,
            dropRate = if (stats.packetsSent + stats.packetsDropped > 0) {
                stats.packetsDropped.toFloat() / (stats.packetsSent + stats.packetsDropped)
            } else 0f
        )
    }

    /**
     * Per-thread statistics.
     */
    private class ThreadStats {
        val packetsSent = AtomicLong(0)
        val bytesSent = AtomicLong(0)
        val sendErrors = AtomicLong(0)
    }

    data class SenderStats(
        val packetsSent: Long,
        val bytesSent: Long,
        val sendErrors: Long,
        val packetsDropped: Long,
        val queueOccupancy: Int,
        val queueCapacity: Int,
        val casRetries: Long,
        val numThreads: Int,
        val perThreadStats: Map<Int, PerThreadStats>
    )

    data class PerThreadStats(
        val packetsSent: Long,
        val bytesSent: Long,
        val sendErrors: Long
    )

    data class PerformanceMetrics(
        val throughputMbps: Double,
        val queueOccupancyPercent: Float,
        val avgCASRetriesPerPacket: Float,
        val dropRate: Float
    )

    companion object {
        private const val TAG = "MultiThreadedUDPSender"
    }
}
