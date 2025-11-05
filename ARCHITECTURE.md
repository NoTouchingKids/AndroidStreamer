# AndroidStreamer Architecture

## Zero-Copy & GC Optimizations

This document outlines the design decisions made to achieve **low-latency, GC-free operation** in the AndroidStreamer pipeline.

---

## 1. Ring Buffer Design

### Preallocated Direct ByteBuffers

```kotlin
private val buffers: Array<ByteBuffer> = Array(capacity) {
    ByteBuffer.allocateDirect(bufferSize)
}
```

**Key Benefits:**
- **Off-heap allocation**: Direct ByteBuffers live outside the Java heap, eliminating GC pressure
- **Zero runtime allocation**: All buffers created at initialization time
- **Native interop**: Direct buffers can be passed to native code (MediaCodec) without copying

### Lock-Free Single Producer/Consumer

```kotlin
@Volatile
private var writeIndex: Int = 0

@Volatile
private var readIndex: Int = 0
```

**Design Choice:**
- Uses `@Volatile` instead of `AtomicInteger` for single-producer/single-consumer pattern
- Avoids synchronized blocks and their associated overhead
- Producer (camera) and consumer (encoder/network) never block each other

### Drop-on-Overflow Policy

```kotlin
fun acquireWriteBuffer(): WriteSlot? {
    if (count >= capacity) {
        // Buffer full - drop frame
        return null
    }
    // ...
}
```

**Rationale:**
- Prioritizes low latency over guaranteed delivery
- Never blocks the camera thread
- Matches UDP "fire-and-forget" semantics

---

## 2. CameraX Configuration

### Low-Latency Settings

```kotlin
imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(1920, 1080))
    .setTargetFrameRate(Range(60, 60))
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build()
```

**Optimization Details:**

| Setting | Value | Rationale |
|---------|-------|-----------|
| `STRATEGY_KEEP_ONLY_LATEST` | Drop old frames | Prevents frame queue buildup |
| `OUTPUT_IMAGE_FORMAT_YUV_420_888` | YUV color space | Native format for MediaCodec H.265 encoder |
| Fixed frame rate `Range(60, 60)` | Precise timing | Reduces jitter for consistent latency |

### Preview at Lower Frame Rate

```kotlin
preview = Preview.Builder()
    .setTargetFrameRate(Range(30, 30)) // Preview at 30fps
```

**Why?**
- Preview consumes CPU/GPU resources for rendering
- Running preview at 30fps while capturing at 60fps saves power and reduces thermal throttling
- User doesn't perceive difference in preview smoothness

---

## 3. Current Limitations & Future Zero-Copy Path

### Current: ImageProxy → ByteBuffer Copy

```kotlin
private fun copyImageToBuffer(image: ImageProxy, buffer: ByteBuffer): Int {
    // Copies YUV planes from ImageProxy to ByteBuffer
    // NOT zero-copy (temporary solution)
}
```

**Why this exists:**
- CameraX `ImageAnalysis` delivers `ImageProxy` objects
- We need to copy YUV data into our ring buffer for downstream processing
- This introduces one memory copy operation

### Future: MediaCodec Input Surface (True Zero-Copy)

**Ideal Pipeline:**
```
Camera2 Surface → MediaCodec Input Surface → Encoded H.265 → RTP Packetizer
```

**Implementation Plan:**
1. Switch from `ImageAnalysis` to `VideoCapture` use case
2. Configure MediaCodec with `createInputSurface()`
3. Attach MediaCodec surface to CameraX recorder
4. Encoder receives frames directly from GPU (zero-copy)

**Benefits:**
- Eliminates CPU copy of YUV data
- Reduces latency by ~2-5ms per frame
- Lowers CPU usage significantly
- True hardware-accelerated pipeline

---

## 4. Memory Layout

### Ring Buffer Sizing

For **1080p@60fps** with H.265 encoding:

```kotlin
companion object {
    fun createFor1080p60(): RingBuffer {
        val bufferSize = 200 * 1024 // 200KB per buffer
        val capacity = 120 // 2 seconds @ 60fps
        return RingBuffer(capacity, bufferSize)
    }
}
```

**Calculations:**
- **I-frame size estimate**: 1920×1080 × 0.075 bpp ≈ 150KB
- **Buffer size**: 200KB (safety margin)
- **Total memory**: 200KB × 120 = 24MB (off-heap)

### Why 120 Frames (2 Seconds)?

- Provides buffer headroom for encoding/network jitter
- 2 seconds allows encoder to catch up during brief CPU spikes
- Small enough to avoid excessive memory usage
- Large enough to absorb typical Android frame scheduling variance

---

## 5. GC Considerations

### What We Avoid

❌ **No runtime allocations in hot path:**
```kotlin
// BAD: Creates new ByteBuffer every frame
fun processFrame() {
    val buffer = ByteBuffer.allocate(size) // GC pressure!
}

// GOOD: Reuse preallocated buffer
fun processFrame() {
    val slot = ringBuffer.acquireWriteBuffer()
    // slot.buffer is reused, no allocation
}
```

❌ **No object creation in frame loop:**
```kotlin
// Frame metadata stored in primitive arrays
private val timestamps: LongArray = LongArray(capacity)
private val frameSizes: IntArray = IntArray(capacity)
```

### Verification Tools

To verify zero allocations during capture:

```bash
# Use Android Profiler
adb shell am profile start <package> --streaming

# Monitor GC events
adb logcat | grep "GC_"

# Expect: No GC events during steady-state capture
```

---

## 6. Performance Targets

| Metric | Target | Current Status |
|--------|--------|---------------|
| Frame rate | 60 fps | ✅ Configured |
| Resolution | 1920×1080 | ✅ Configured |
| Buffer drops | < 0.1% | ⏳ To be measured |
| GC pauses during capture | 0 | ⏳ To be verified |
| End-to-end latency (Camera → Network) | < 50ms | ⏳ Not yet implemented |

---

## Next Steps

1. **Integrate MediaCodec H.265 encoder** with input surface (true zero-copy)
2. **Implement RTP packetizer** (RFC 7798) for H.265/RTP
3. **Add RTSP publisher** for MediaMTX integration
4. **Benchmark end-to-end latency** with systrace
5. **Profile memory allocations** during steady-state capture
6. **Test on multiple device chipsets** (Qualcomm, MediaTek, Exynos)

---

## References

- [CameraX Documentation](https://developer.android.com/training/camerax)
- [MediaCodec Best Practices](https://developer.android.com/reference/android/media/MediaCodec)
- [RFC 7798: RTP Payload Format for H.265](https://datatracker.ietf.org/doc/html/rfc7798)
