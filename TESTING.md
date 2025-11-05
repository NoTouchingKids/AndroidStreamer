# Testing Guide - AndroidStreamer

## Prerequisites

### Hardware Requirements
- Android device with:
  - Android 10 (API 29) or higher
  - Camera2 API support
  - Rear camera capable of 1080p@60fps
  - Recommended: Snapdragon 8xx series or equivalent

### Software Requirements
- Android Studio Arctic Fox or later
- ADB (Android Debug Bridge)
- USB debugging enabled on device

---

## Building the Project

### 1. Sync Dependencies

```bash
./gradlew clean
./gradlew assembleDebug
```

### 2. Install on Device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use Android Studio:
- Click **Run** → **Run 'app'**
- Select your device from the list

---

## Testing Procedure

### Phase 1: Basic Functionality

1. **Launch Application**
   - App should request camera permissions
   - Grant camera permission when prompted

2. **Verify Camera Preview**
   - Camera preview should appear immediately
   - Preview should be smooth and responsive
   - Check that the preview shows the rear camera feed

3. **Check Stats Display**
   - Bottom overlay should show:
     - **Frames**: Incrementing counter
     - **Dropped**: Should remain at 0 or very low
     - **Buffer**: Should show occupancy (e.g., "2/30")
     - **FPS**: Should display current frame rate

### Expected Results (Phase 1)

| Metric | Expected Value | Acceptable Range |
|--------|---------------|------------------|
| FPS | 30-60 fps | > 25 fps |
| Dropped Frames | 0% | < 0.5% |
| Buffer Occupancy | 1-5 frames | < 10 frames |

---

### Phase 2: Performance Testing

#### Test 1: Sustained Capture

**Duration**: 5 minutes

**Procedure**:
1. Launch app and let it run continuously
2. Monitor stats every 30 seconds
3. Record FPS and dropped frame percentage

**Success Criteria**:
- FPS remains stable (variance < 5 fps)
- Dropped frames < 1%
- No app crashes or freezes
- Device temperature remains reasonable (< 45°C)

#### Test 2: Memory Stability

**Procedure**:
1. Launch app
2. Use Android Profiler to monitor memory
3. Run for 10 minutes
4. Check for memory leaks

**Success Criteria**:
- Heap memory remains stable (no continuous growth)
- GC events minimal during steady state
- No OutOfMemoryError exceptions

**Using Android Profiler**:
```bash
# In Android Studio: View → Tool Windows → Profiler
# Select your device and app process
# Click on Memory timeline
```

**Expected Memory Usage**:
- **Heap**: ~50-100 MB (stable)
- **Native**: ~30-50 MB (ring buffer uses off-heap memory)
- **Total**: ~80-150 MB

---

### Phase 3: Frame Rate Verification

Use `adb logcat` to monitor actual frame rate:

```bash
adb logcat | grep "CameraController"
```

**Look for output like**:
```
D/CameraController: Frames: 60, Dropped: 0 (0.00%)
D/CameraController: Frames: 120, Dropped: 1 (0.83%)
```

**Frame rate calculation**:
- Take two consecutive "Frames: X" log entries
- Note the frame counts and timestamps
- Calculate: `(frame_diff / time_diff_seconds)` = FPS

---

### Phase 4: Stress Testing

#### Test 1: Device Rotation

**Procedure**:
1. Start app in portrait mode
2. Rotate to landscape
3. Rotate back to portrait
4. Repeat 5 times

**Success Criteria**:
- App handles rotation gracefully
- Camera restarts without crashes
- Stats reset or continue correctly

#### Test 2: Background/Foreground Transitions

**Procedure**:
1. Launch app
2. Press Home button (app goes to background)
3. Relaunch app from recent apps
4. Repeat 10 times

**Success Criteria**:
- Camera stops when backgrounded
- Camera restarts when foregrounded
- No resource leaks

#### Test 3: Permission Revocation

**Procedure**:
1. Launch app with camera permission granted
2. While app is running, go to Settings → Apps → AndroidStreamer → Permissions
3. Revoke camera permission
4. Return to app

**Expected Behavior**:
- App should handle permission loss gracefully
- No crashes

---

## Performance Metrics Interpretation

### Frame Rate (FPS)

| FPS Range | Interpretation |
|-----------|---------------|
| 55-60 fps | Excellent - hitting 60fps target |
| 45-54 fps | Good - device may not support 60fps at 1080p |
| 30-44 fps | Acceptable - camera running at 30fps mode |
| < 30 fps | Poor - investigate performance issues |

### Dropped Frames

| Drop Rate | Interpretation |
|-----------|---------------|
| 0% | Perfect - no frames lost |
| < 0.5% | Excellent - occasional drops acceptable |
| 0.5-2% | Acceptable - ring buffer absorbing jitter |
| > 2% | Problem - encoder/network can't keep up |

### Buffer Occupancy

| Occupancy | Interpretation |
|-----------|---------------|
| 0-3 frames | Healthy - consumer keeping up |
| 3-10 frames | Moderate - some backlog building |
| 10-20 frames | High - consumer struggling |
| > 20 frames | Critical - about to drop frames (capacity: 30) |

---

## Troubleshooting

### Issue: Low FPS (< 30 fps)

**Possible Causes**:
- Device doesn't support 60fps at 1080p
- Thermal throttling
- Background processes consuming CPU

**Solutions**:
1. Check device camera capabilities:
   ```bash
   adb shell dumpsys media.camera | grep "1920x1080"
   ```
2. Ensure device is plugged in (prevents thermal throttling)
3. Close background apps

---

### Issue: High Dropped Frame Rate

**Possible Causes**:
- Ring buffer too small
- Processing callback too slow
- Memory allocation in hot path

**Solutions**:
1. Check GC logs for allocations:
   ```bash
   adb logcat | grep "GC_"
   ```
2. Increase ring buffer size in `RingBuffer.createFor1080p60()`
3. Profile with Android Studio's CPU Profiler

---

### Issue: App Crashes on Launch

**Debugging Steps**:
1. Check logcat for stack traces:
   ```bash
   adb logcat | grep "AndroidRuntime"
   ```
2. Verify camera permissions in manifest
3. Check device supports Camera2 API:
   ```bash
   adb shell getprop | grep "camera"
   ```

---

## Benchmarking Commands

### CPU Usage
```bash
adb shell top | grep "android_streamer"
```

### Memory Usage
```bash
adb shell dumpsys meminfo com.example.android_streamer
```

### Detailed Frame Timing (requires root)
```bash
adb shell atrace --async_start -b 16384 -t 10 gfx input view camera
# Run test...
adb shell atrace --async_stop > trace.html
# Open trace.html in Chrome at chrome://tracing
```

---

## Next Steps After Verification

Once basic capture is working:

1. ✅ **Capture Pipeline Verified** → Implement MediaCodec H.265 encoder
2. ✅ **Encoder Working** → Implement RTP packetizer (RFC 7798)
3. ✅ **RTP Working** → Implement RTSP publisher for MediaMTX
4. ✅ **RTSP Working** → End-to-end latency optimization

---

## Known Limitations

### Current Implementation

- **One Memory Copy**: YUV data copied from `ImageProxy` to ring buffer
  - **Impact**: ~2-5ms additional latency per frame
  - **Future Fix**: Use MediaCodec input surface for true zero-copy

- **Large Buffer Size for Raw YUV**: Ring buffer sized for uncompressed YUV frames
  - **Current**: 3.1 MB per frame × 30 frames = ~93 MB off-heap memory
  - **Impact**: Higher memory usage than final implementation
  - **Future**: Once encoded, 200 KB × 120 frames = ~24 MB (74% reduction)

- **Frame Rate Control**: Uses default camera frame rate (not locked to 60fps)
  - **Impact**: May run at 30fps on some devices
  - **Future Fix**: Implement Camera2Interop for precise frame rate control

- **No Encoding Yet**: Frames captured but not encoded
  - **Impact**: Cannot stream yet
  - **Next Step**: Integrate MediaCodec H.265 encoder

---

## Success Metrics Summary

### Minimum Viable Product (MVP)

- ✅ App launches without crashes
- ✅ Camera permission flow works
- ✅ Preview displays camera feed
- ✅ Stats update in real-time
- ✅ FPS > 25
- ✅ Dropped frames < 2%
- ✅ Runs for 5+ minutes without crashes

### Production Ready

- ✅ FPS = 60 (or device maximum)
- ✅ Dropped frames < 0.1%
- ✅ No memory leaks after 1 hour
- ✅ GC pauses < 5ms during capture
- ✅ Handles all lifecycle transitions
- ✅ Works across multiple device models

---

**Questions or Issues?**

Check logcat output and file an issue with:
- Device model and Android version
- Logcat excerpt
- Steps to reproduce
- Expected vs actual behavior
