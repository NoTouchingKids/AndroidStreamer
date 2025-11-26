# CLAUDE.md - AI Assistant Guide for AndroidStreamer

This document provides comprehensive guidance for AI assistants working on the AndroidStreamer codebase.

---

## Project Overview

**AndroidStreamer** is a low-latency Android camera pipeline designed for real-time high-framerate video capture and streaming to RTSP servers (such as MediaMTX) using H.265/HEVC encoding over UDP.

### Key Objectives
- Achieve 1080p@60fps camera capture with minimal latency
- Zero-copy GPU pipeline from camera to encoder
- GC-free operation during steady-state capture
- Fire-and-forget UDP transmission with acceptable frame drops
- Hardware-accelerated H.265 encoding via MediaCodec

### Current Status
- ✅ Ring buffer implementation (zero-copy, GC-free)
- ✅ CameraX integration with 1080p@60fps capture
- ✅ Preview at 30fps to save resources
- ⏳ MediaCodec H.265 encoder integration (planned)
- ⏳ RTP packetizer for H.265 (RFC 7798) (planned)
- ⏳ RTSP publisher for MediaMTX (planned)

---

## Codebase Structure

```
AndroidStreamer/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/android_streamer/
│   │   │   │   ├── MainActivity.kt              # Main activity with camera preview UI
│   │   │   │   ├── buffer/
│   │   │   │   │   └── RingBuffer.kt           # Lock-free ring buffer for frame data
│   │   │   │   └── camera/
│   │   │   │       └── CameraController.kt     # CameraX wrapper for low-latency capture
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   └── activity_main.xml       # UI layout with PreviewView and stats
│   │   │   │   └── values/
│   │   │   │       ├── colors.xml
│   │   │   │       ├── strings.xml
│   │   │   │       └── themes.xml
│   │   │   └── AndroidManifest.xml             # App manifest with camera permissions
│   │   ├── androidTest/                         # Instrumentation tests (empty)
│   │   └── test/                                # Unit tests (empty)
│   └── build.gradle.kts                         # App-level Gradle config
├── gradle/
│   ├── libs.versions.toml                       # Version catalog for dependencies
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts                             # Root-level Gradle config
├── settings.gradle.kts                          # Project settings
├── gradle.properties                            # Gradle JVM settings
├── README.md                                    # User-facing project documentation
├── ARCHITECTURE.md                              # Deep-dive into zero-copy design
└── CLAUDE.md                                    # This file (AI assistant guide)
```

### Source File Count
- **Total Kotlin/Java files**: 5
  - MainActivity.kt
  - CameraController.kt
  - RingBuffer.kt
  - 2 test template files (unused)

---

## Key Components

### 1. RingBuffer (`buffer/RingBuffer.kt`)

**Purpose**: Lock-free, GC-free circular buffer for frame data.

**Design Highlights**:
- Preallocated `Array<ByteBuffer>` using `allocateDirect()` (off-heap)
- Single-producer/single-consumer with `@Volatile` indices
- Drop-on-overflow policy (never blocks camera thread)
- Metadata stored in primitive arrays (`LongArray`, `IntArray`)

**API**:
```kotlin
// Acquire buffer for writing
val writeSlot: WriteSlot? = ringBuffer.acquireWriteBuffer()
// Write data, then commit
ringBuffer.commitWrite(writeSlot, frameSize, timestampNs)

// Acquire buffer for reading
val readSlot: ReadSlot? = ringBuffer.acquireReadBuffer()
// Process data, then release
ringBuffer.releaseReadBuffer(readSlot)
```

**Factory Methods**:
- `RingBuffer.createFor1080p60()`: 120 buffers × 200KB = 24MB
- `RingBuffer.createFor4K60()`: 120 buffers × 800KB = 96MB

**Performance Notes**:
- No runtime allocations after initialization
- Returns `null` instead of blocking when full
- Direct buffers can be passed to native MediaCodec

### 2. CameraController (`camera/CameraController.kt`)

**Purpose**: CameraX wrapper optimized for low-latency 1080p@60fps capture.

**Configuration**:
```kotlin
// Preview (30fps, reduced overhead)
Preview.Builder()
    .setTargetResolution(Size(1920, 1080))
    .setTargetFrameRate(Range(30, 30))

// Analysis (60fps, YUV for encoder)
ImageAnalysis.Builder()
    .setTargetResolution(Size(1920, 1080))
    .setTargetFrameRate(Range(60, 60))
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
```

**Frame Processing Flow**:
1. CameraX delivers `ImageProxy` to `processFrame()`
2. Acquire write buffer from ring buffer
3. Copy YUV planes to buffer (temporary copy, see note below)
4. Commit write with frame size and timestamp
5. Notify downstream consumer via callback

**Temporary Limitation**:
- Currently copies YUV data from `ImageProxy` to `ByteBuffer` (`copyImageToBuffer()`)
- **Future**: Use MediaCodec input surface for true zero-copy (GPU → encoder)

**API**:
```kotlin
cameraController.startCamera(previewView) { readSlot ->
    // Handle frame (e.g., send to encoder)
}

val stats = cameraController.getStats()
// stats.totalFrames, stats.droppedFrames, stats.bufferOccupancy
```

### 3. MainActivity (`MainActivity.kt`)

**Purpose**: Main activity with camera preview and performance stats.

**Responsibilities**:
- Request `CAMERA` permission
- Initialize `CameraController` and bind to `PreviewView`
- Display real-time stats (FPS, frame count, drops, buffer occupancy)
- Placeholder for encoder/network integration (see `processFrame()`)

**UI Elements** (from `activity_main.xml`):
- `previewView`: CameraX PreviewView for camera feed
- `tvFrameCount`: Total frames captured
- `tvDroppedFrames`: Dropped frames (count + percentage)
- `tvBufferOccupancy`: Ring buffer usage (0-120)
- `tvFps`: Actual capture frame rate

---

## Development Environment

### Prerequisites
- **Android Studio**: Arctic Fox (2020.3.1) or later
- **JDK**: Java 11 (configured in `build.gradle.kts`)
- **Android SDK**: API 29+ (minSdk), API 36 (targetSdk, compileSdk)
- **Gradle**: 8.13.0 (via wrapper)

### Dependencies (from `gradle/libs.versions.toml`)
```toml
[versions]
agp = "8.13.0"
kotlin = "2.0.21"
coreKtx = "1.17.0"
cameraX = "1.4.1"
lifecycle = "2.8.7"

[libraries]
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version = "1.4.1" }
androidx-camera-camera2 = { ... }
androidx-camera-lifecycle = { ... }
androidx-camera-video = { ... }
androidx-camera-view = { ... }
androidx-lifecycle-runtime-ktx = { ... }
```

### Build Configuration
- **Namespace**: `com.example.android_streamer`
- **Application ID**: `com.example.android_streamer`
- **Min SDK**: 29 (Android 10)
- **Target SDK**: 36 (Android 15)
- **ViewBinding**: Enabled

---

## Build & Run Instructions

### Build the Project
```bash
./gradlew clean build
```

### Run on Device/Emulator
```bash
./gradlew installDebug
adb shell am start -n com.example.android_streamer/.MainActivity
```

### Generate APK
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

### Run Tests
```bash
# Unit tests
./gradlew test

# Instrumentation tests (requires connected device)
./gradlew connectedAndroidTest
```

---

## Code Conventions & Patterns

### Kotlin Style
- **Code style**: `official` (configured in `gradle.properties`)
- **Naming**: camelCase for variables, PascalCase for classes
- **Visibility**: Prefer `private` by default, expose only necessary APIs

### Performance Patterns
1. **Avoid allocations in hot paths**:
   - Reuse preallocated buffers
   - Use primitive arrays instead of `List<>`
   - Prefer `@Volatile` over `AtomicInteger` for single-producer/consumer

2. **Zero-copy principles**:
   - Use `ByteBuffer.allocateDirect()` for off-heap memory
   - Pass direct buffers to native APIs (MediaCodec, sockets)
   - Avoid `ImageReader` in streaming path (causes heap allocations)

3. **Non-blocking operations**:
   - Drop frames instead of blocking camera thread
   - Use `DatagramChannel` in non-blocking mode for UDP
   - Executor services with fixed thread pools (no unbounded queues)

### Error Handling
- Log errors with appropriate levels (`Log.e()`, `Log.w()`)
- Never throw exceptions in frame processing callbacks
- Gracefully degrade (drop frames) instead of crashing

### Logging Tags
- Use class-level `TAG` constants:
  ```kotlin
  companion object {
      private const val TAG = "ClassName"
  }
  ```

---

## Common Development Tasks

### Adding a New Component

1. **Create Kotlin file** in appropriate package:
   ```
   app/src/main/java/com/example/android_streamer/<package>/<ClassName>.kt
   ```

2. **Follow naming conventions**:
   - Controllers: `*Controller.kt`
   - Utilities: `*Utils.kt`
   - Data classes: `*Data.kt`

3. **Add tests** (if testable):
   ```
   app/src/test/java/com/example/android_streamer/<package>/<ClassName>Test.kt
   ```

### Modifying Camera Configuration

Edit `CameraController.kt` → `bindCameraUseCases()`:
- Change resolution: `setTargetResolution(Size(width, height))`
- Change frame rate: `setTargetFrameRate(Range(fps, fps))`
- Change backpressure: `setBackpressureStrategy(...)`

### Tuning Ring Buffer Size

Edit `RingBuffer.kt` → `Companion` object factory methods:
```kotlin
fun createFor1080p60(): RingBuffer {
    val bufferSize = 200 * 1024 // Adjust based on encoder bitrate
    val capacity = 120           // Adjust for desired latency tolerance
    return RingBuffer(capacity, bufferSize)
}
```

### Adding Encoder Integration (Future Task)

1. Create `encoder/MediaEncoder.kt`:
   ```kotlin
   class MediaEncoder {
       fun configure(width: Int, height: Int, fps: Int, bitrate: Int)
       fun getInputSurface(): Surface
       fun start()
       fun stop()
   }
   ```

2. Modify `CameraController` to use encoder surface:
   ```kotlin
   // Replace ImageAnalysis with VideoCapture or use MediaCodec surface
   ```

3. Wire up encoder output to RTP packetizer

### Adding RTP Packetizer (Future Task)

1. Create `network/RtpPacketizer.kt` implementing RFC 7798:
   - Fragment NAL units into RTP packets
   - Set marker bit on last packet of each frame
   - Timestamp increment: 1500 per frame (90000 / 60fps)

2. Integrate with `UdpTransport.kt` for non-blocking sends

---

## Performance Benchmarking

### Measuring Frame Drops
```kotlin
val stats = cameraController.getStats()
val dropRate = (stats.droppedFrames.toFloat() / stats.totalFrames) * 100
// Target: < 0.1% drops
```

### Monitoring GC Events
```bash
adb logcat | grep "GC_"
# Expect: No GC events during steady-state capture
```

### Profiling with Android Studio
1. Open **View → Tool Windows → Profiler**
2. Select device and app
3. Record **Memory** allocation during capture
4. Verify: No allocations in frame processing loop

### Measuring Latency
```bash
# Use systrace for end-to-end analysis
adb shell atrace --async_start -a com.example.android_streamer camera
# ... record session ...
adb shell atrace --async_stop
```

---

## Git Workflow

### Branch Strategy
- **Main branch**: `main` (stable releases)
- **Feature branches**: `claude/<session-id>` (e.g., `claude/claude-md-mig1qf82aj57v013-01S1jMgWLPck9BEg48BfbEdt`)
- **Pull requests**: All changes must go through PR review

### Recent Commits
```
3ce2feb Merge pull request #1 from NoTouchingKids/claude/review-feedback-011CUpU5dqwHAJ7CT9eeKkQG
c813a4e Implement 1080p@60fps camera capture with zero-copy ring buffer
0d42883 [UPDATE] Updated Configs
0589397 first commit
```

### Commit Message Style
- Use imperative mood: "Add feature" not "Added feature"
- Reference issue/PR numbers where applicable
- Keep first line under 72 characters
- Add detailed description after blank line if needed

### Making Changes
1. **Create feature branch**:
   ```bash
   git checkout -b claude/<session-id>
   ```

2. **Make changes and commit**:
   ```bash
   git add .
   git commit -m "$(cat <<'EOF'
   Add MediaCodec H.265 encoder integration

   - Configure encoder with CBR, 1080p@60fps, 40Mbps
   - Use input surface for zero-copy camera → encoder
   - Handle encoder output buffers in separate thread
   EOF
   )"
   ```

3. **Push to remote**:
   ```bash
   git push -u origin claude/<session-id>
   ```

4. **Create pull request** via GitHub CLI or web interface

---

## Future Roadmap

### Phase 1: Encoder Integration (Current)
- [ ] Implement `MediaEncoder.kt` with H.265 hardware encoding
- [ ] Switch from `ImageAnalysis` to MediaCodec input surface
- [ ] Benchmark end-to-end latency (target: < 50ms)

### Phase 2: Network Streaming
- [ ] Implement `RtpPacketizer.kt` (RFC 7798)
- [ ] Implement `UdpTransport.kt` with non-blocking sends
- [ ] Implement `RtspPublisher.kt` for MediaMTX handshake

### Phase 3: Production Hardening
- [ ] Add error recovery (encoder crashes, network failures)
- [ ] Add adaptive bitrate based on network conditions
- [ ] Add configuration UI (resolution, bitrate, server URL)
- [ ] Add proper logging with configurable levels

### Phase 4: Advanced Features
- [ ] Multi-camera support
- [ ] Audio capture and AAC encoding
- [ ] Audio/video synchronization
- [ ] WebRTC gateway integration

---

## Key Files Reference

| File | Path | Purpose |
|------|------|---------|
| MainActivity | `app/src/main/java/com/example/android_streamer/MainActivity.kt` | Main activity with UI |
| CameraController | `app/src/main/java/com/example/android_streamer/camera/CameraController.kt` | Camera management |
| RingBuffer | `app/src/main/java/com/example/android_streamer/buffer/RingBuffer.kt` | Lock-free frame buffer |
| AndroidManifest | `app/src/main/AndroidManifest.xml` | App permissions and config |
| App Gradle | `app/build.gradle.kts` | Dependencies and build config |
| Versions Catalog | `gradle/libs.versions.toml` | Version management |
| README | `README.md` | User-facing documentation |
| ARCHITECTURE | `ARCHITECTURE.md` | Zero-copy design deep-dive |

---

## Testing Checklist

When making changes, verify:

- [ ] **Builds successfully**: `./gradlew build`
- [ ] **No new compiler warnings**: Check build output
- [ ] **Permissions declared**: Verify `AndroidManifest.xml`
- [ ] **ViewBinding generated**: Rebuild if layout changed
- [ ] **Frame rate stable**: Check FPS counter in UI
- [ ] **Low drop rate**: < 0.1% in `tvDroppedFrames`
- [ ] **No GC pauses**: Check logcat during capture
- [ ] **Camera stops cleanly**: Press back button, check logs

---

## Troubleshooting

### Issue: Low Frame Rate (< 60fps)
**Causes**:
- Device thermal throttling
- Insufficient processing power
- Preview consuming too many resources

**Solutions**:
- Reduce preview frame rate (currently 30fps)
- Disable preview during capture
- Test on high-end device (Snapdragon 8-series)

### Issue: High Drop Rate (> 1%)
**Causes**:
- Ring buffer too small
- Downstream consumer (encoder) too slow
- Camera hardware limitations

**Solutions**:
- Increase ring buffer capacity (see `RingBuffer.createFor1080p60()`)
- Profile encoder performance
- Reduce resolution or frame rate

### Issue: Camera Permission Denied
**Causes**:
- User denied permission
- Permission not declared in manifest

**Solutions**:
- Check `AndroidManifest.xml` includes `<uses-permission android:name="android.permission.CAMERA" />`
- Uninstall and reinstall app
- Check device settings → Apps → Permissions

---

## AI Assistant Guidelines

### When Working on This Project

1. **Read before editing**:
   - Always read existing files before making changes
   - Understand the zero-copy design principles
   - Check ARCHITECTURE.md for performance rationale

2. **Preserve performance characteristics**:
   - Never introduce allocations in frame processing paths
   - Maintain non-blocking operations
   - Keep drop-on-overflow semantics

3. **Follow existing patterns**:
   - Use same logging conventions (`TAG`, `Log.i/d/w/e`)
   - Match code style (official Kotlin style)
   - Maintain single-producer/single-consumer pattern

4. **Test thoroughly**:
   - Run on physical device (emulator may have different performance)
   - Monitor FPS and drop rate
   - Check logcat for GC events

5. **Document changes**:
   - Update README.md for user-facing features
   - Update ARCHITECTURE.md for design decisions
   - Update CLAUDE.md for AI assistant guidance
   - Add inline comments for non-obvious logic

### Common Pitfalls to Avoid

❌ **Don't**:
- Add blocking operations in frame callback
- Use `synchronized` on hot paths
- Allocate objects in frame processing loop
- Use `ImageReader` for streaming (causes allocations)
- Ignore frame drops (they indicate performance issues)

✅ **Do**:
- Use preallocated buffers
- Prefer `@Volatile` over locks for single-producer/consumer
- Log performance metrics regularly
- Profile before optimizing
- Keep camera thread responsive

---

## Documentation Standards

When updating documentation:

1. **Keep README.md user-focused**: Installation, usage, features
2. **Keep ARCHITECTURE.md design-focused**: Why decisions were made
3. **Keep CLAUDE.md assistant-focused**: How to work on the code
4. **Use consistent formatting**:
   - Code blocks with language tags
   - Tables for structured data
   - Checklists for tasks
   - File path references in backticks

---

## Contact & Resources

### Documentation
- [Android CameraX Documentation](https://developer.android.com/training/camerax)
- [MediaCodec Documentation](https://developer.android.com/reference/android/media/MediaCodec)
- [RFC 7798: RTP Payload Format for H.265](https://datatracker.ietf.org/doc/html/rfc7798)

### External Tools
- **MediaMTX**: RTSP server for testing streams ([GitHub](https://github.com/bluenviron/mediamtx))
- **ffplay**: Stream player for verification
  ```bash
  ffplay -fflags nobuffer -flags low_delay -rtsp_transport udp rtsp://<ip>:8554/stream
  ```

### Project Links
- **Repository**: NoTouchingKids/AndroidStreamer
- **Current Branch**: `claude/claude-md-mig1qf82aj57v013-01S1jMgWLPck9BEg48BfbEdt`

---

**Last Updated**: 2025-11-26
**Document Version**: 1.0
**Project Status**: Phase 1 (Camera capture complete, encoder integration in progress)
