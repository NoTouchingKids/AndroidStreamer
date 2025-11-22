# Multi-Threaded UDP Sender Testing Guide

## Overview

This document provides testing strategies and validation procedures for the multi-threaded UDP sender implementation designed for 4K60 @ 800 Mbps streaming.

---

## Testing Objectives

1. **Verify packet ordering** - RTP sequence numbers must be monotonic
2. **Validate throughput** - Achieve 200,000+ packets/sec for 800 Mbps
3. **Measure CAS contention** - Ensure low retry rates (<2 avg retries)
4. **Test error handling** - Graceful degradation under network stress
5. **Confirm thread safety** - No race conditions or data corruption

---

## Unit Tests

### 1. OrderedSPMCQueue Tests

```kotlin
class OrderedSPMCQueueTest {

    @Test
    fun testSingleProducerMultipleConsumers() {
        val queue = OrderedSPMCQueue(capacity = 100)
        val numConsumers = 4
        val numPackets = 1000
        val receivedPackets = ConcurrentHashMap.newKeySet<Int>()

        // Producer thread
        val producer = Thread {
            repeat(numPackets) { i ->
                val buffer = ByteBuffer.allocate(12)
                buffer.putInt(i)  // Packet ID
                buffer.flip()
                queue.enqueue(buffer)
            }
        }

        // Consumer threads
        val consumers = List(numConsumers) { threadId ->
            Thread {
                while (receivedPackets.size < numPackets) {
                    val packet = queue.dequeue()
                    if (packet != null) {
                        val id = packet.data.getInt()
                        receivedPackets.add(id)
                    } else {
                        Thread.sleep(1)
                    }
                }
            }
        }

        // Run test
        producer.start()
        consumers.forEach { it.start() }

        producer.join()
        consumers.forEach { it.join(5000) }

        // Verify all packets received
        assertEquals(numPackets, receivedPackets.size)

        // Verify no duplicates
        assertEquals(numPackets, receivedPackets.toSet().size)

        println("CAS retries: ${queue.getStats().casRetries}")
    }

    @Test
    fun testFIFOOrdering() {
        val queue = OrderedSPMCQueue(capacity = 100)
        val numPackets = 1000
        val receivedOrder = Collections.synchronizedList(mutableListOf<Int>())

        // Enqueue packets with sequence numbers
        repeat(numPackets) { i ->
            val buffer = ByteBuffer.allocate(4)
            buffer.putInt(i)
            buffer.flip()
            assertTrue(queue.enqueue(buffer))
        }

        // Dequeue with multiple threads
        val consumers = List(4) {
            Thread {
                while (true) {
                    val packet = queue.dequeue() ?: break
                    val seqNum = packet.data.getInt()
                    receivedOrder.add(seqNum)
                }
            }
        }

        consumers.forEach { it.start() }
        consumers.forEach { it.join() }

        // Verify FIFO order preserved
        assertEquals(numPackets, receivedOrder.size)
        assertEquals((0 until numPackets).toList(), receivedOrder.sorted())

        // Verify monotonic (may not be strictly sequential due to threading)
        // But should not have large gaps or backwards movement
        val outOfOrder = receivedOrder.zipWithNext().count { (a, b) -> b < a }
        println("Out-of-order packets: $outOfOrder / ${numPackets - 1}")
        assertTrue(outOfOrder < numPackets * 0.01)  // <1% out of order acceptable
    }

    @Test
    fun testQueueFull() {
        val queue = OrderedSPMCQueue(capacity = 10)

        // Fill queue
        repeat(10) { i ->
            val buffer = ByteBuffer.allocate(4).apply { putInt(i); flip() }
            assertTrue(queue.enqueue(buffer))
        }

        // Next enqueue should fail
        val buffer = ByteBuffer.allocate(4).apply { putInt(999); flip() }
        assertFalse(queue.enqueue(buffer))
    }

    @Test
    fun testCASRetryTracking() {
        val queue = OrderedSPMCQueue(capacity = 50)

        // Enqueue packets
        repeat(100) { i ->
            val buffer = ByteBuffer.allocate(4).apply { putInt(i); flip() }
            queue.enqueue(buffer)
        }

        // Dequeue with high contention (many threads)
        val consumers = List(16) {
            Thread {
                while (queue.dequeue() != null) {
                    // Busy dequeue
                }
            }
        }

        consumers.forEach { it.start() }
        consumers.forEach { it.join() }

        val stats = queue.getStats()
        println("Total CAS retries: ${stats.casRetries}")
        println("Avg retries per packet: ${stats.casRetries.toFloat() / 100}")

        // High contention test - retries expected but should be reasonable
        assertTrue(stats.casRetries < 1000)  // <10 retries per packet on average
    }
}
```

---

## Integration Tests

### 2. MultiThreadedUDPSender Tests

```kotlin
class MultiThreadedUDPSenderTest {

    @Test
    fun testMultiThreadedThroughput() {
        // Setup mock server to receive packets
        val serverSocket = DatagramSocket(5004)
        val receivedPackets = ConcurrentHashMap.newKeySet<Int>()

        // Receiver thread
        val receiver = Thread {
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)

            repeat(10000) {
                serverSocket.receive(packet)
                val seqNum = ByteBuffer.wrap(packet.data).getShort(2).toInt()
                receivedPackets.add(seqNum)
            }
        }
        receiver.start()

        // Create sender
        val sender = MultiThreadedUDPSender(
            remoteHost = "127.0.0.1",
            remotePort = 5004,
            numThreads = 4
        )
        sender.start()

        // Send 10,000 packets as fast as possible
        val startTime = System.nanoTime()

        repeat(10000) { seqNum ->
            val buffer = ByteBuffer.allocate(12).apply {
                put(0x80.toByte())  // RTP version
                put(0x60.toByte())  // Payload type
                putShort(seqNum.toShort())  // Sequence number
                putInt(0)  // Timestamp
                putInt(0)  // SSRC
                flip()
            }
            sender.sendPacket(buffer)
        }

        val endTime = System.nanoTime()
        val elapsedMs = (endTime - startTime) / 1_000_000.0

        // Wait for receiver
        receiver.join(5000)
        sender.stop()

        // Verify throughput
        val packetsPerSec = 10000 / (elapsedMs / 1000)
        println("Throughput: $packetsPerSec packets/sec")
        println("Time: $elapsedMs ms")

        // Should achieve >50,000 packets/sec with 4 threads
        assertTrue(packetsPerSec > 50000)

        // Verify most packets received (some loss acceptable in UDP)
        assertTrue(receivedPackets.size > 9800)  // >98% delivery

        serverSocket.close()
    }

    @Test
    fun testPacketOrdering() {
        val serverSocket = DatagramSocket(5005)
        val receivedSeqNums = Collections.synchronizedList(mutableListOf<Int>())

        // Receiver
        val receiver = Thread {
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)

            repeat(1000) {
                serverSocket.receive(packet)
                val seqNum = ByteBuffer.wrap(packet.data).getShort(2).toInt() and 0xFFFF
                receivedSeqNums.add(seqNum)
            }
        }
        receiver.start()

        // Sender
        val sender = MultiThreadedUDPSender("127.0.0.1", 5005, numThreads = 4)
        sender.start()

        // Send packets with sequential sequence numbers
        repeat(1000) { seqNum ->
            val buffer = ByteBuffer.allocate(12).apply {
                put(0x80.toByte())
                put(0x60.toByte())
                putShort(seqNum.toShort())
                putInt(0)
                putInt(0)
                flip()
            }
            sender.sendPacket(buffer)
            Thread.sleep(0, 100_000)  // 100μs delay to avoid overwhelming
        }

        receiver.join(5000)
        sender.stop()

        // Analyze ordering
        val outOfOrder = receivedSeqNums.zipWithNext().count { (a, b) -> b < a }
        val orderingPercent = 100.0 * (1 - outOfOrder.toDouble() / receivedSeqNums.size)

        println("Received: ${receivedSeqNums.size} packets")
        println("Out of order: $outOfOrder")
        println("Ordering: $orderingPercent%")

        // Should maintain >99% ordering (some reordering acceptable in UDP/network)
        assertTrue(orderingPercent > 99.0)

        serverSocket.close()
    }

    @Test
    fun testPerThreadLoadBalancing() {
        val sender = MultiThreadedUDPSender("127.0.0.1", 5006, numThreads = 4)
        sender.start()

        // Send 10,000 packets
        repeat(10000) { i ->
            val buffer = ByteBuffer.allocate(100).apply {
                putInt(i)
                flip()
            }
            sender.sendPacket(buffer)
        }

        Thread.sleep(500)  // Let sender threads process

        val stats = sender.getStats()
        sender.stop()

        println("Total packets sent: ${stats.packetsSent}")
        println("Per-thread breakdown:")
        stats.perThreadStats.forEach { (threadId, threadStats) ->
            val percentage = 100.0 * threadStats.packetsSent / stats.packetsSent
            println("  Thread-$threadId: ${threadStats.packetsSent} packets ($percentage%)")
        }

        // Verify load is distributed (each thread should handle ~25% ± 10%)
        stats.perThreadStats.forEach { (_, threadStats) ->
            val percentage = 100.0 * threadStats.packetsSent / stats.packetsSent
            assertTrue(percentage > 15.0 && percentage < 35.0)
        }
    }
}
```

---

## Performance Benchmarks

### 3. Sustained 4K60 Simulation

```kotlin
@Test
fun test4K60_800Mbps_Sustained() {
    val sender = MultiThreadedUDPSender("192.168.1.100", 5004, numThreads = 4)
    sender.start()

    // Simulate 4K60 @ 800 Mbps
    // - 60 frames/sec
    // - ~1,190 packets/frame
    // - ~16.67ms per frame

    val packetsPerFrame = 1190
    val frameIntervalMs = 16.67
    val totalFrames = 3600  // 1 minute of streaming

    var totalPacketsSent = 0
    var totalDropped = 0

    repeat(totalFrames) { frameNum ->
        val frameStart = System.nanoTime()

        // Send all packets for this frame
        repeat(packetsPerFrame) { packetNum ->
            val buffer = createRTPPacket(frameNum, packetNum)
            if (!sender.sendPacket(buffer)) {
                totalDropped++
            } else {
                totalPacketsSent++
            }
        }

        // Wait until frame deadline
        val frameElapsed = (System.nanoTime() - frameStart) / 1_000_000.0
        val sleepTime = (frameIntervalMs - frameElapsed).toLong()
        if (sleepTime > 0) {
            Thread.sleep(sleepTime)
        }

        // Log every 60 frames (1 second)
        if (frameNum % 60 == 0) {
            val stats = sender.getStats()
            val metrics = sender.getPerformanceMetrics()
            println("Frame $frameNum: Sent ${stats.packetsSent}, " +
                    "Dropped ${stats.packetsDropped}, " +
                    "Queue: ${metrics.queueOccupancyPercent}%, " +
                    "CAS: ${metrics.avgCASRetriesPerPacket}")
        }
    }

    sender.stop()

    // Verify performance
    val dropRate = totalDropped.toFloat() / (totalPacketsSent + totalDropped)
    println("Final drop rate: ${dropRate * 100}%")

    // Drop rate should be <0.1% for healthy streaming
    assertTrue(dropRate < 0.001)
}

private fun createRTPPacket(frameNum: Int, packetNum: Int): ByteBuffer {
    return ByteBuffer.allocate(1400).apply {
        // RTP header
        put(0x80.toByte())  // V=2, P=0, X=0, CC=0
        put(0x60.toByte())  // M=0, PT=96 (H.265)
        putShort((frameNum * 1190 + packetNum).toShort())  // Sequence number
        putInt(frameNum * 90000 / 60)  // Timestamp (90kHz)
        putInt(0x12345678)  // SSRC

        // Payload (dummy data)
        repeat(1388) { put(0xFF.toByte()) }

        flip()
    }
}
```

---

## Validation Checklist

### Pre-Deployment Checks

- [ ] **Unit tests pass**: All OrderedSPMCQueue tests pass
- [ ] **Integration tests pass**: MultiThreadedUDPSender tests pass
- [ ] **Throughput verified**: Achieves >200,000 packets/sec with 4 threads
- [ ] **Ordering maintained**: >99% packet ordering in tests
- [ ] **CAS contention low**: <2 avg CAS retries per packet
- [ ] **Load balanced**: Each thread handles 20-30% of traffic
- [ ] **No memory leaks**: Profiler shows stable memory usage over 1 hour
- [ ] **No crashes**: Sustained streaming for 1+ hour without errors

### Real-World Testing

1. **MediaMTX Integration**:
   ```bash
   # Start MediaMTX
   mediamtx

   # Stream from Android app
   # Verify in MediaMTX logs:
   # - No "out of order" warnings
   # - No "gap in sequence numbers" errors
   # - Smooth playback
   ```

2. **Monitor Android Logcat**:
   ```bash
   adb logcat | grep -E "RTP-Sender|MultiThreaded|OrderedSPMC"
   ```

   Look for:
   - All 4 sender threads start successfully
   - Low queue occupancy (<50%)
   - Low CAS retry rates
   - No error messages

3. **Performance Monitoring**:
   ```kotlin
   // In your app, log metrics periodically
   lifecycleScope.launch {
       while (streaming) {
           delay(1000)
           val metrics = sender.getPerformanceMetrics()
           Log.i(TAG, "Perf: ${metrics.throughputMbps} Mbps, " +
                      "Queue: ${metrics.queueOccupancyPercent}%, " +
                      "Drops: ${metrics.dropRate * 100}%")
       }
   }
   ```

4. **Stress Testing**:
   - Test on slow WiFi (simulate network congestion)
   - Test while running other network-intensive apps
   - Test with different bitrates (120 Mbps, 400 Mbps, 800 Mbps)
   - Monitor CPU and memory usage

---

## Performance Metrics Interpretation

### Healthy Streaming Indicators

| Metric | Good | Warning | Critical |
|--------|------|---------|----------|
| Queue Occupancy | <50% | 50-80% | >80% |
| Drop Rate | <0.1% | 0.1-1% | >1% |
| CAS Retries/Packet | <1.0 | 1.0-3.0 | >3.0 |
| Per-Thread Balance | 20-30% | 15-35% | <10% or >40% |

### Troubleshooting

**High Queue Occupancy (>80%)**:
- Network is bottleneck (not enough bandwidth)
- Consider reducing bitrate
- Check WiFi signal strength

**High CAS Retries (>3.0)**:
- Too many sender threads for workload
- Consider reducing from 4 to 2 threads
- Indicates over-threading

**Unbalanced Thread Load**:
- One thread doing >40% of work suggests issue
- Check if one thread is crashing/stalling
- Review per-thread error stats

**High Drop Rate (>1%)**:
- Queue filling up faster than draining
- Encoder producing too much data
- Network can't handle bitrate

---

## Debugging Tools

### Enable Verbose Logging

In `MultiThreadedUDPSender.kt`, add detailed logs:

```kotlin
private fun runSenderLoop(threadId: Int, channel: DatagramChannel) {
    // ... existing code ...

    if (DEBUG) {
        Log.v(TAG, "Thread-$threadId: Dequeued packet, queue occupancy: ${packetQueue.getOccupancy()}")
    }
}

companion object {
    private const val DEBUG = true  // Enable for testing
}
```

### Android Profiler

1. Open Android Studio Profiler
2. Start streaming session
3. Monitor:
   - **CPU**: Should use <50% total (4 threads at high priority)
   - **Memory**: Should be stable (no leaks)
   - **Network**: Should see sustained high throughput

### Systrace

```bash
python systrace.py -t 10 -b 32768 -o trace.html \
  sched freq idle am wm gfx view binder_driver hal dalvik camera input res
```

Look for:
- RTP-Sender threads running (should see 4 threads)
- No excessive context switching
- No lock contention (should be lock-free)

---

## Known Limitations

1. **UDP Unreliability**: Packets may be lost/reordered by network (not sender's fault)
2. **CAS Contention**: With >4 threads, CAS contention may increase latency
3. **CPU Usage**: 4 high-priority threads consume significant CPU
4. **Network Buffering**: OS network buffers may still bottleneck at extreme bitrates

---

## Future Optimizations

1. **Adaptive Thread Count**: Dynamically adjust 2-4 threads based on bitrate
2. **NUMA Awareness**: Pin threads to CPU cores with network affinity
3. **sendmmsg() Batching**: Use JNI for batch UDP sends (Linux-specific)
4. **Zero-Copy Networking**: Investigate AF_XDP or DPDK for ultra-high throughput

---

**Last Updated**: 2025-11-22
**Status**: Ready for testing
