# MediaMTX Setup for AndroidStreamer

This guide explains how to configure MediaMTX to receive H.265 streams from AndroidStreamer.

## Architecture: Android = RTSP Client

AndroidStreamer uses the **standard RTSP publishing model**:

- **MediaMTX (192.168.0.2)** = RTSP Server (stable IP, always running)
- **Android (192.168.0.20)** = RTSP Client (connects and publishes stream)

This is the correct architecture because MediaMTX server has a stable IP, and Android connects TO it.

## Quick Start

### 1. Install MediaMTX

Download from: https://github.com/bluenviron/mediamtx/releases

```bash
# Linux/Mac
wget https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz
tar -xzf mediamtx_linux_amd64.tar.gz
```

### 2. Configure MediaMTX (Simple!)

Edit `mediamtx.yml` - use the default config or add low latency settings:

```yaml
# RTSP server listens on port 8554 (default)
rtspAddress: :8554

# Optional: Low latency settings
paths:
  all:
    readTimeout: 10s
    readBuffer: 512KB
```

**That's it!** MediaMTX automatically accepts published streams.

### 3. Start MediaMTX

```bash
./mediamtx
```

You should see:
```
INF [RTSP] listener opened on :8554 (TCP)
```

### 4. Configure AndroidStreamer

In `MainActivity.kt`, set your MediaMTX server IP:

```kotlin
private const val MEDIAMTX_SERVER_IP = "192.168.0.2" // Your MediaMTX server IP
private const val MEDIAMTX_RTSP_PORT = 8554          // MediaMTX RTSP port
private const val STREAM_PATH = "/android"           // Stream path
```

### 5. Start Streaming

1. Launch AndroidStreamer app on your Android device
2. The app will show: `MediaMTX Server: 192.168.0.2`
3. Tap "START CAPTURE"
4. Android connects to MediaMTX and publishes the stream

### 6. View Stream

Access the stream via RTSP from MediaMTX:

```bash
# VLC (replace 192.168.0.2 with your MediaMTX server IP)
vlc rtsp://192.168.0.2:8554/android

# ffplay (low latency)
ffplay -fflags nobuffer -flags low_delay rtsp://192.168.0.2:8554/android

# Web browser (if MediaMTX WebRTC is enabled)
http://192.168.0.2:8889/android
```

## How It Works

```
Android Device (RTSP Client)    MediaMTX Server (RTSP Server)    Viewers
┌──────────────────────────┐   ┌──────────────────────────┐    ┌──────────┐
│  Camera2 → H.265 Encoder │   │  RTSP Server :8554       │    │   VLC    │
│           ↓              │   │  (stable IP, listening)  │    │          │
│  RTSP Client             │──→│  ← ANNOUNCE (SDP)        │    │  ffplay  │
│  (connects TO server)    │   │  ← SETUP (RTP ports)     │    │          │
│           ↓              │   │  ← RECORD (start)        │    │  Browser │
│  RTP Sender              │───│→ Receives RTP/UDP        │────│→         │
│  (UDP :5004)             │   │  Re-streams RTSP/WebRTC  │    │          │
└──────────────────────────┘   └──────────────────────────┘    └──────────┘
```

**RTSP Handshake (Android → MediaMTX):**
```
Android (Client)          MediaMTX (Server)
    |--- ANNOUNCE --->|  (here's my H.265 stream with SDP)
    |<-- 200 OK ------|
    |--- SETUP ------>|  (negotiate RTP ports)
    |<-- 200 OK ------|
    |--- RECORD ----->|  (start publishing)
    |<-- 200 OK ------|
    |=== RTP data ===>|  (H.265 video over UDP)
```

## Network Requirements

- **Local network only** (same WiFi/LAN)
- **Gigabit recommended** for 1080p@30fps at 8Mbps
- **Low latency network** (<1ms RTT preferred)
- **Android and MediaMTX must be on the same network**

## Troubleshooting

### Cannot connect to MediaMTX

1. **Verify MediaMTX is running:**
   ```bash
   # Check MediaMTX logs
   ./mediamtx
   # Should show: INF [RTSP] listener opened on :8554
   ```

2. **Verify MediaMTX IP is correct:**
   - Check your MediaMTX server's IP address
   - Update `MEDIAMTX_SERVER_IP` in MainActivity.kt

3. **Check firewall allows TCP port 8554 and UDP port 5004:**
   ```bash
   # On MediaMTX server
   sudo ufw allow 8554/tcp
   sudo ufw allow 5004/udp
   ```

4. **Verify Android and MediaMTX are on same network:**
   ```bash
   # From MediaMTX server
   ping YOUR_ANDROID_IP
   ```

5. **Check Android app logs** (logcat):
   ```
   TAG: RTSPClient
   Look for: "Connecting to MediaMTX at ..."
             "✓ RTSP session established! Ready to stream."
   ```

### Connection established but no video

1. **Check MediaMTX logs** for published stream:
   ```
   INF [path android] [conn] opened
   INF [path android] stream ready
   ```

2. **Check H.265 codec support** in your viewer (VLC, ffplay)

3. **Check Android logs** for RTP sending:
   ```
   TAG: RTPSender
   Look for: "RTP sender started"
             "Updated RTP destination: ..."
   ```

### Stream stutters or drops frames

1. Check network quality:
   ```bash
   ping -c 100 <mediamtx-ip>
   # Should have <1ms latency, 0% packet loss
   ```

2. Reduce bitrate in `MainActivity.kt`:
   ```kotlin
   bitrate = 4_000_000  // Try 4Mbps instead of 8Mbps
   ```

3. Ensure WiFi is on 5GHz band (not 2.4GHz)

## Performance Stats

Typical performance on local gigabit network:

- **Latency**: 50-150ms glass-to-glass (encoding + network + decoding)
- **Packet loss**: 0% (local network)
- **Throughput**: 8Mbps steady
- **Frame drops**: 0 (100% efficiency)

## Advanced Configuration

### Multiple Streams

Run multiple Android devices publishing to different paths:

Device 1:
```kotlin
private const val STREAM_PATH = "/camera1"
```

Device 2:
```kotlin
private const val STREAM_PATH = "/camera2"
```

View with:
```bash
vlc rtsp://192.168.0.2:8554/camera1
vlc rtsp://192.168.0.2:8554/camera2
```

### Recording

Enable recording in `mediamtx.yml`:

```yaml
paths:
  all:
    record: yes
    recordPath: ./recordings/%path/%Y-%m-%d_%H-%M-%S
    recordFormat: fmp4
    recordSegmentDuration: 60s
```

### Authentication

Protect your MediaMTX server:

```yaml
paths:
  android:
    publishUser: android_device
    publishPass: secretpassword
```

Update Android app to send credentials (requires modifying RTSPClient).

## Protocol Details

**RTSP Publishing (ANNOUNCE/SETUP/RECORD):**
- Android connects to MediaMTX via TCP
- ANNOUNCE sends SDP with H.265 codec parameters
- SETUP negotiates RTP/RTCP ports
- RECORD starts the publishing session
- RTP packets flow via UDP

**RTP Streaming (UDP):**
```
Small Frames (< 1400 bytes):
[12 bytes] RTP Header (v=2, PT=96, SSRC=0x12345678)
[N bytes]  H.265 NAL unit

Large Frames (> 1400 bytes):
[12 bytes] RTP Header
[3 bytes]  H.265 FU Header (type=49)
[~1385 bytes] Fragment payload

Keyframes: ~80-180KB → fragmented into 60-130 packets
```

## Why This Architecture?

**Android = Client (recommended) vs Android = Server:**

| Aspect | Android = Client ✅ | Android = Server ❌ |
|--------|---------------------|---------------------|
| **MediaMTX IP** | Stable (192.168.0.2) | N/A |
| **Android IP** | Can change (DHCP) | Must be stable |
| **Config changes** | None (MediaMTX auto-accepts) | Update mediamtx.yml every time Android IP changes |
| **Standard model** | Yes (RTSP ANNOUNCE/RECORD) | No (RTSP DESCRIBE/PLAY but reversed) |
| **Complexity** | Simple | Complex (need to know Android IP) |

Android as Client is the standard RTSP publishing model used by IP cameras and streaming software.
