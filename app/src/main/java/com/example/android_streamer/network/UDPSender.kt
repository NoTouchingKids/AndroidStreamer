package com.example.android_streamer.network

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free UDP sender optimized for MediaMTX streaming.
 *
 * Threading model:
 * - Producer: MediaCodec encoder callback thread (single thread)
 * - Consumer: Dedicated UDP sender thread (single thread)
 * - Pattern: Single-Producer Single-Consumer (SPSC) lock-free queue
 *
 * Key optimizations:
 * - Lock-free queue using AtomicInteger for SPSC pattern
 * - Direct ByteBuffers (off-heap, no GC)
 * - Non-blocking DatagramChannel
 * - Minimal synchronization overhead
 * - Optimized for MediaMTX RTP streaming
 */
class UDPSender(
    private val remoteHost: String,
    private val remotePort: Int
) {
    private var channel: DatagramChannel? = null
    private var remoteAddress: InetSocketAddress? = null
    private val isRunning = AtomicBoolean(false)

    // Statistics (thread-safe counters)
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val sendErrors = AtomicLong(0)
    private val packetsDropped = AtomicLong(0)

    // Sender thread
    private var senderThread: Thread? = null

    // Lock-free SPSC ring buffer
    private val queueCapacity = 512  // Increased for burst handling
    private val packetQueue = Array(queueCapacity) { PacketSlot() }

    // Use AtomicInteger for lock-free SPSC queue
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)
    private val queueCount = AtomicInteger(0)

    /**
     * Start the UDP sender.
     * Validates MediaMTX connectivity before starting.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Sender already running")
            return
        }

        try {
            // Create non-blocking DatagramChannel
            channel = DatagramChannel.open().apply {
                configureBlocking(false)

                // Optimize socket for low-latency streaming
                socket().apply {
                    sendBufferSize = 1024 * 1024  // 1MB send buffer for burst tolerance
                    trafficClass = 0x10  // IPTOS_LOWDELAY for low latency
                    reuseAddress = true
                }
            }

            remoteAddress = InetSocketAddress(remoteHost, remotePort)

            // Validate connectivity (optional - comment out if not needed)
            validateConnectivity()

            // Start sender thread with high priority
            senderThread = Thread(::runSenderLoop, "RTP-Sender").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "UDP sender started: $remoteHost:$remotePort (MediaMTX optimized)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP sender", e)
            isRunning.set(false)
            channel?.close()
            throw e
        }
    }

    /**
     * Validate connectivity to MediaMTX server.
     */
    private fun validateConnectivity() {
        try {
            val testPacket = ByteBuffer.allocate(12)  // Minimal RTP header
            channel?.send(testPacket, remoteAddress)
            Log.d(TAG, "Connectivity test packet sent to MediaMTX")
        } catch (e: Exception) {
            Log.w(TAG, "Connectivity test failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Stop the UDP sender.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {
            senderThread?.interrupt()
            senderThread?.join(1000)
            channel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UDP sender", e)
        } finally {
            channel = null
            senderThread = null
        }

        val stats = getStats()
        Log.i(TAG, "UDP sender stopped. Sent: ${stats.packetsSent} packets (${stats.bytesSent / 1024}KB), " +
                "Dropped: ${stats.packetsDropped}, Errors: ${stats.sendErrors}")
    }

    /**
     * Send a packet asynchronously (lock-free).
     * Called from encoder callback thread.
     *
     * @param packet ByteBuffer containing the packet data
     * @return true if queued successfully, false if queue is full (packet dropped)
     */
    fun sendPacket(packet: ByteBuffer): Boolean {
        if (!isRunning.get()) {
            return false
        }

        // Lock-free check for queue full
        val count = queueCount.get()
        if (count >= queueCapacity) {
            packetsDropped.incrementAndGet()
            if (packetsDropped.get() % 100 == 0L) {
                Log.w(TAG, "Queue full, dropped ${packetsDropped.get()} packets total")
            }
            return false
        }

        // Get write slot (lock-free)
        val wIdx = writeIndex.get()
        val slot = packetQueue[wIdx]

        // Copy packet data to slot buffer
        slot.buffer.clear()
        val packetSize = packet.remaining()

        if (packetSize > slot.buffer.capacity()) {
            Log.e(TAG, "Packet too large: $packetSize bytes (max: ${slot.buffer.capacity()})")
            return false
        }

        slot.buffer.put(packet)
        slot.buffer.flip()
        slot.size = packetSize

        // Advance write index (lock-free, wraps around)
        writeIndex.set((wIdx + 1) % queueCapacity)

        // Increment count atomically
        queueCount.incrementAndGet()

        return true
    }

    /**
     * Sender thread main loop (consumer).
     * Runs with high priority for minimal latency.
     */
    private fun runSenderLoop() {
        val channel = this.channel ?: return
        val remoteAddress = this.remoteAddress ?: return

        Log.i(TAG, "Sender loop started (high priority thread)")

        var consecutiveErrors = 0
        val maxConsecutiveErrors = 10

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                // Lock-free check for available packets
                val count = queueCount.get()

                if (count > 0) {
                    // Get read slot (lock-free)
                    val rIdx = readIndex.get()
                    val slot = packetQueue[rIdx]

                    // Send packet
                    slot.buffer.rewind()
                    val bytesSentNow = channel.send(slot.buffer, remoteAddress)

                    if (bytesSentNow > 0) {
                        packetsSent.incrementAndGet()
                        bytesSent.addAndGet(bytesSentNow.toLong())
                        consecutiveErrors = 0

                        // Advance read index (lock-free)
                        readIndex.set((rIdx + 1) % queueCapacity)

                        // Decrement count atomically
                        queueCount.decrementAndGet()
                    } else {
                        // Channel not ready (buffer full), yield and retry
                        Thread.yield()
                    }
                } else {
                    // No packets to send, sleep briefly to avoid busy-wait
                    // Use very short sleep for low latency
                    Thread.sleep(0, 50_000) // 50 microseconds
                }

            } catch (e: InterruptedException) {
                Log.i(TAG, "Sender thread interrupted")
                break
            } catch (e: Exception) {
                sendErrors.incrementAndGet()
                consecutiveErrors++

                Log.e(TAG, "Error sending packet (consecutive: $consecutiveErrors)", e)

                if (consecutiveErrors >= maxConsecutiveErrors) {
                    Log.e(TAG, "Too many consecutive errors, stopping sender")
                    isRunning.set(false)
                    break
                }

                // Back off on errors
                Thread.sleep(1)
            }
        }

        Log.i(TAG, "Sender loop stopped")
    }

    /**
     * Get sender statistics.
     */
    fun getStats(): SenderStats {
        return SenderStats(
            packetsSent = packetsSent.get(),
            bytesSent = bytesSent.get(),
            sendErrors = sendErrors.get(),
            packetsDropped = packetsDropped.get(),
            queueOccupancy = queueCount.get()
        )
    }

    /**
     * Check if sender is healthy (no excessive errors).
     */
    fun isHealthy(): Boolean {
        val totalPackets = packetsSent.get() + packetsDropped.get()
        if (totalPackets < 100) return true  // Not enough data

        val dropRate = packetsDropped.get().toFloat() / totalPackets
        val errorRate = sendErrors.get().toFloat() / totalPackets

        return dropRate < 0.01 && errorRate < 0.001  // <1% drops, <0.1% errors
    }

    /**
     * Slot for queued packets.
     */
    private class PacketSlot {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(1500) // MTU size
        var size: Int = 0
    }

    data class SenderStats(
        val packetsSent: Long,
        val bytesSent: Long,
        val sendErrors: Long,
        val packetsDropped: Long,
        val queueOccupancy: Int
    )

    companion object {
        private const val TAG = "UDPSender"
    }
}
