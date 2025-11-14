# Threading & Concurrency Model

## Overview

This document details the threading architecture for low-latency, zero-copy H.265/RTP streaming to MediaMTX.

**Key Design Principle**: **Single-Producer Single-Consumer (SPSC)** lock-free queues for minimal latency and zero contention.

---

## Thread Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Thread Diagram                           │
└─────────────────────────────────────────────────────────────────┘

Thread 1: UI Thread (Main)
  ├─ MainActivity UI updates (500ms interval)
  └─ StreamingPipeline control (start/stop)

Thread 2: Camera2 HandlerThread ("Camera2Thread")
  ├─ Priority: THREAD_PRIORITY_DISPLAY (high priority)
  ├─ Camera control and configuration
  └─ Frame capture → Sends to GPU surface → MediaCodec

Thread 3: MediaCodec Encoder Callback Thread
  ├─ Priority: Set by MediaCodec (high)
  ├─ Receives encoded H.265 NAL units from hardware encoder
  ├─ Calls: RTPPacketizer.packetize() [SINGLE-THREADED]
  │   └─ Generates multiple RTP packets per frame
  │       └─ For each packet: UDPSender.sendPacket()
  │           └─ [LOCK-FREE] Enqueues to SPSC ring buffer
  └─ NEVER blocks (lock-free enqueue)

Thread 4: RTP-Sender Thread ("RTP-Sender")
  ├─ Priority: Thread.MAX_PRIORITY (highest)
  ├─ [LOCK-FREE] Dequeues packets from SPSC ring buffer
  ├─ Sends via DatagramChannel.send()
  └─ Sleep: 50 microseconds when idle (minimal latency)
```

---

## Lock-Free SPSC Queue (UDPSender)

**Producer**: MediaCodec encoder callback thread (single thread)
**Consumer**: RTP-Sender thread (single thread)

### Implementation

```kotlin
// Lock-free indices using AtomicInteger
private val writeIndex = AtomicInteger(0)  // Producer writes here
private val readIndex = AtomicInteger(0)   // Consumer reads here
private val queueCount = AtomicInteger(0)  // Atomic count

// Producer (encoder thread)
fun sendPacket(packet: ByteBuffer): Boolean {
    val count = queueCount.get()
    if (count >= capacity) return false  // Drop on overflow

    val wIdx = writeIndex.get()
    // ... copy packet to queue[wIdx] ...

    writeIndex.set((wIdx + 1) % capacity)  // Advance write
    queueCount.incrementAndGet()           // Atomic increment
    return true
}

// Consumer (sender thread)
private fun runSenderLoop() {
    while (running) {
        val count = queueCount.get()
        if (count > 0) {
            val rIdx = readIndex.get()
            // ... send packet from queue[rIdx] ...

            readIndex.set((rIdx + 1) % capacity)  // Advance read
            queueCount.decrementAndGet()          // Atomic decrement
        } else {
            Thread.sleep(0, 50_000)  // 50μs - avoid busy-wait
        }
    }
}
```

### Why Lock-Free?

1. **No Mutex Contention**: Encoder thread never blocks
2. **Predictable Latency**: No lock acquisition delays
3. **Cache-Friendly**: SPSC pattern has excellent CPU cache behavior
4. **Scalable**: Works well even under high load

---

## Component Thread Safety

### ✅ Thread-Safe Components

| Component | Thread Model | Synchronization |
|-----------|-------------|----------------|
| `UDPSender` | SPSC lock-free queue | AtomicInteger indices |
| `HEVCEncoder` | MediaCodec manages callbacks | Internally synchronized by MediaCodec |
| `Camera2Controller` | HandlerThread | Single-threaded via Handler |
| Statistics counters | AtomicLong | Lock-free atomic operations |

### ⚠️ Single-Threaded Components (Not Thread-Safe)

| Component | Caller Thread | Notes |
|-----------|--------------|-------|
| `RTPPacketizer` | Encoder callback thread | Reuses `packetBuffer` - NOT thread-safe for concurrent calls |
| `StreamingPipeline.handleEncodedData()` | Encoder callback thread | Single caller guaranteed by MediaCodec |

**IMPORTANT**: `RTPPacketizer` is designed for **single-threaded use**. MediaCodec guarantees that `onOutputBufferAvailable()` callbacks are serialized, so concurrent calls never occur.

---

## Data Flow with Thread Handoffs

```
┌──────────────┐
│  Camera2     │ (Camera2 HandlerThread)
│  Capture     │
└──────┬───────┘
       │ GPU Surface (zero-copy)
       ▼
┌──────────────┐
│  MediaCodec  │ (Hardware Encoder)
│  HEVC Encode │
└──────┬───────┘
       │ Callback Thread
       │
       ▼
┌──────────────┐
│ RTPPacketizer│ (Encoder Callback Thread - SINGLE-THREADED)
│  NAL → RTP   │ • Reuses packetBuffer (zero-allocation)
└──────┬───────┘ • Generates 1-N packets per frame
       │
       ▼ (for each packet)
┌──────────────┐
│  UDPSender   │
│ .sendPacket()│ (Encoder Callback Thread)
└──────┬───────┘ [LOCK-FREE ENQUEUE]
       │           writeIndex.set(...)
       │           queueCount.incrementAndGet()
       │
       │ SPSC Ring Buffer (512 slots × 1500 bytes)
       │
       ▼ [LOCK-FREE DEQUEUE]
┌──────────────┐
│ RTP-Sender   │ (Dedicated Thread - MAX_PRIORITY)
│   Thread     │ readIndex.set(...)
└──────┬───────┘ queueCount.decrementAndGet()
       │
       ▼
┌──────────────┐
│ DatagramChan │ (Non-blocking UDP send)
│ nel.send()   │
└──────┬───────┘
       │
       ▼
   MediaMTX Server
```

---

## Backpressure Handling

### Queue Full Strategy (Fire-and-Forget)

When the SPSC queue is full (512 packets buffered):

1. **Drop packet** (no blocking)
2. Increment `packetsDropped` counter
3. Log warning every 100 drops
4. Health check marks stream unhealthy if drop rate >1%

**Rationale**:
- **Low latency** > guaranteed delivery (UDP semantics)
- Blocking encoder thread would cause cascading delays
- MediaMTX will request I-frame via RTCP if too many drops

---

## Performance Characteristics

### Thread Priorities

| Thread | Priority | Rationale |
|--------|----------|-----------|
| Camera2 HandlerThread | `THREAD_PRIORITY_DISPLAY` | Critical for frame capture |
| Encoder Callback | Set by MediaCodec | Hardware encoder priority |
| RTP-Sender Thread | `Thread.MAX_PRIORITY` | Minimize network send latency |
| UI Thread | `THREAD_PRIORITY_DEFAULT` | Non-critical |

### Latency Budget (Target: <50ms end-to-end)

| Stage | Estimated Latency | Notes |
|-------|------------------|-------|
| Camera capture | ~5-10ms | Depends on exposure time |
| Encoder (hardware) | ~10-20ms | 1-2 frame buffering |
| RTP packetization | <1ms | Zero-allocation, single thread |
| SPSC queue enqueue | <0.1ms | Lock-free, no contention |
| SPSC queue dequeue | <0.1ms | Lock-free, 50μs sleep |
| UDP send | ~1-5ms | Network stack latency |
| **Total** | **~17-36ms** | Well under 50ms target |

### Queue Sizing

```kotlin
queueCapacity = 512 packets

// Worst-case buffering:
// - 60fps × 1.5 avg packets/frame × 2 sec buffer
// - 60 × 1.5 × 2 = 180 packets
// - 512 capacity provides ~2.8x safety margin
```

---

## Concurrency Pitfalls Avoided

### ❌ What We DON'T Do

1. **No synchronized blocks in hot path**
   - Encoder callback thread never blocks on locks
   - Only atomic operations (lock-free)

2. **No SharedFlow or Kotlin Channels in hot path**
   - Coroutine overhead unsuitable for low-latency streaming
   - Used simple SPSC queue instead

3. **No object allocation in hot path**
   - All buffers preallocated (DirectByteBuffer)
   - Stats use AtomicLong (no object creation)

4. **No multi-threaded access to RTPPacketizer**
   - Single-threaded by design (documented clearly)
   - MediaCodec guarantees serialized callbacks

---

## Thread-Safety Verification

### Testing Checklist

- [ ] Run under Android Profiler with "Record system calls"
- [ ] Check for `synchronized` or `wait` in hot path
- [ ] Verify AtomicInteger operations via systrace
- [ ] Measure end-to-end latency under sustained 60fps
- [ ] Stress test with intentionally slow network (packet drops)
- [ ] Validate no GC pauses during steady-state streaming

### Logging Thread Names

All threads have descriptive names for easy debugging:
- `"Camera2Thread"` - Camera2 operations
- `"RTP-Sender"` - UDP packet transmission
- `"MediaCodec-*"` - Encoder callbacks (set by MediaCodec)

Use `adb logcat | grep "RTP\|Camera2"` to filter relevant logs.

---

## MediaMTX Integration Notes

### Network Considerations

1. **UDP is unreliable** - Packet loss is expected
2. **MediaMTX expects**:
   - RFC 7798 compliant H.265/RTP packets ✅
   - Monotonic RTP sequence numbers ✅
   - 90kHz timestamp clock ✅
   - Payload type 96 (configurable) ✅

3. **Handling packet loss**:
   - MediaMTX will request I-frame via RTCP (not yet implemented)
   - High drop rates trigger health warnings in our pipeline

### Configuration

```yaml
# MediaMTX configuration for this stream
paths:
  streamer:
    source: rtp://0.0.0.0:5004
    sourceProtocol: udp
    sourceOnDemand: false
    sourceOnDemandCloseAfter: 10s
```

---

## Future Optimizations

1. **RTCP Support**:
   - Sender Reports (SR) for clock synchronization
   - Receiver Reports (RR) to detect packet loss
   - Request I-frames when loss detected

2. **Adaptive Bitrate**:
   - Monitor queue occupancy
   - Lower bitrate if queue fills (backpressure)
   - Increase bitrate if queue consistently empty

3. **Thread Affinity** (Android 12+):
   - Pin RTP-Sender thread to dedicated CPU core
   - Reduce context switching overhead

4. **NUMA-aware allocation** (future Android):
   - Allocate DirectByteBuffers on same NUMA node as network controller

---

## References

- [RFC 7798: RTP Payload Format for H.265](https://datatracker.ietf.org/doc/html/rfc7798)
- [Android MediaCodec Best Practices](https://developer.android.com/reference/android/media/MediaCodec)
- [Lock-Free SPSC Queues](https://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue)
- [MediaMTX Documentation](https://github.com/bluenviron/mediamtx)

---

**Last Updated**: 2025-11-14
**Author**: Claude (Anthropic)
**Pipeline Version**: 2.0 (Lock-Free Optimized)
