package com.example.android_streamer.network

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance UDP sender using DatagramChannel and direct buffers.
 * Optimized for low-latency RTP streaming with zero-copy where possible.
 *
 * Key features:
 * - Non-blocking DatagramChannel
 * - Direct ByteBuffers (off-heap)
 * - Single-threaded sender for minimal overhead
 * - Fire-and-forget semantics (no retransmission)
 */
class UDPSender(
    private val remoteHost: String,
    private val remotePort: Int
) {
    private var channel: DatagramChannel? = null
    private var remoteAddress: InetSocketAddress? = null
    private val isRunning = AtomicBoolean(false)

    // Statistics
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val sendErrors = AtomicLong(0)

    // Sender thread
    private var senderThread: Thread? = null

    // Packet queue using ring buffer-like structure
    private val queueCapacity = 256
    private val packetQueue = Array<PacketSlot>(queueCapacity) { PacketSlot() }
    @Volatile
    private var writeIndex = 0
    @Volatile
    private var readIndex = 0
    @Volatile
    private var queueCount = 0

    /**
     * Start the UDP sender.
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
                socket().sendBufferSize = 512 * 1024 // 512KB send buffer
            }

            remoteAddress = InetSocketAddress(remoteHost, remotePort)

            // Start sender thread
            senderThread = Thread({
                runSenderLoop()
            }, "UDPSender").apply {
                priority = Thread.MAX_PRIORITY // Realtime priority
                start()
            }

            Log.i(TAG, "UDP sender started: $remoteHost:$remotePort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP sender", e)
            isRunning.set(false)
            throw e
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

        Log.i(TAG, "UDP sender stopped. Sent: ${packetsSent.get()} packets, ${bytesSent.get() / 1024}KB")
    }

    /**
     * Send a packet asynchronously.
     * This method is non-blocking - packets are queued and sent by the sender thread.
     *
     * @param packet ByteBuffer containing the packet data
     * @return true if queued successfully, false if queue is full (packet dropped)
     */
    fun sendPacket(packet: ByteBuffer): Boolean {
        if (!isRunning.get()) {
            return false
        }

        // Check if queue is full
        synchronized(packetQueue) {
            if (queueCount >= queueCapacity) {
                // Queue full - drop packet
                Log.w(TAG, "Packet queue full, dropping packet")
                return false
            }

            // Copy packet to queue slot
            val slot = packetQueue[writeIndex]
            slot.buffer.clear()

            val packetSize = packet.remaining()
            if (packetSize > slot.buffer.capacity()) {
                Log.e(TAG, "Packet too large: $packetSize bytes")
                return false
            }

            slot.buffer.put(packet)
            slot.buffer.flip()
            slot.size = packetSize

            // Advance write index
            writeIndex = (writeIndex + 1) % queueCapacity
            queueCount++
        }

        return true
    }

    /**
     * Sender thread main loop.
     */
    private fun runSenderLoop() {
        val channel = this.channel ?: return
        val remoteAddress = this.remoteAddress ?: return

        Log.d(TAG, "Sender loop started")

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                // Check if there are packets to send
                val slot: PacketSlot? = synchronized(packetQueue) {
                    if (queueCount == 0) {
                        null
                    } else {
                        packetQueue[readIndex]
                    }
                }

                if (slot != null) {
                    // Send packet
                    slot.buffer.rewind()
                    val bytesSent = channel.send(slot.buffer, remoteAddress)

                    if (bytesSent > 0) {
                        this.packetsSent.incrementAndGet()
                        this.bytesSent.addAndGet(bytesSent.toLong())

                        // Advance read index
                        synchronized(packetQueue) {
                            readIndex = (readIndex + 1) % queueCapacity
                            queueCount--
                        }
                    } else {
                        // Channel not ready, yield and retry
                        Thread.yield()
                    }
                } else {
                    // No packets to send, sleep briefly
                    Thread.sleep(0, 100_000) // 100 microseconds
                }

            } catch (e: InterruptedException) {
                Log.d(TAG, "Sender thread interrupted")
                break
            } catch (e: Exception) {
                sendErrors.incrementAndGet()
                Log.e(TAG, "Error sending packet", e)
            }
        }

        Log.d(TAG, "Sender loop stopped")
    }

    /**
     * Get sender statistics.
     */
    fun getStats(): SenderStats {
        return SenderStats(
            packetsSent = packetsSent.get(),
            bytesSent = bytesSent.get(),
            sendErrors = sendErrors.get(),
            queueOccupancy = queueCount
        )
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
        val queueOccupancy: Int
    )

    companion object {
        private const val TAG = "UDPSender"
    }
}
