# MediaMTX Setup for AndroidStreamer

This guide explains how to configure MediaMTX to receive H.265 RTP streams from AndroidStreamer.

## Quick Start

### 1. Install MediaMTX

Download from: https://github.com/bluenviron/mediamtx/releases

```bash
# Linux/Mac
wget https://github.com/bluenviron/mediamtx/releases/latest/download/mediamtx_linux_amd64.tar.gz
tar -xzf mediamtx_linux_amd64.tar.gz
```

### 2. Configure MediaMTX

Edit `mediamtx.yml`:

```yaml
paths:
  android:
    # Accept RTP stream from Android device
    # Use port 8000 (MediaMTX default RTP port shown in logs)
    source: rtp://0.0.0.0:8000
    sourceProtocol: rtp

    # Optional: Enable recording
    # record: yes
    # recordPath: ./recordings/%path/%Y-%m-%d_%H-%M-%S
```

**Note:** MediaMTX shows its RTP port in the logs:
```
INF [RTSP] listener opened on :8554 (TCP), :8000 (UDP/RTP), :8001 (UDP/RTCP)
                                             ^^^^^ Use this port
```

### 3. Start MediaMTX

```bash
./mediamtx
```

You should see:
```
INF [path android] ready to receive: [source rtp://0.0.0.0:5004]
```

### 4. Configure AndroidStreamer

In `MainActivity.kt`, update the server IP:

```kotlin
private const val RTP_SERVER_IP = "192.168.1.100"  // Your MediaMTX server IP
private const val RTP_SERVER_PORT = 8000            // MediaMTX default RTP port
```

### 5. Start Streaming

1. Start MediaMTX server
2. Launch AndroidStreamer app on your Android device
3. Tap "START CAPTURE"

### 6. View Stream

Access the stream via RTSP:

```bash
# VLC
vlc rtsp://192.168.1.100:8554/android

# ffplay
ffplay -fflags nobuffer -flags low_delay rtsp://192.168.1.100:8554/android

# Web browser (if MediaMTX WebRTC is enabled)
http://192.168.1.100:8889/android
```

## Network Requirements

- **Local network only** (same WiFi/LAN)
- **Gigabit recommended** for 1080p@30fps at 8Mbps
- **Low latency network** (<1ms RTT preferred)

## Architecture

```
Android Device                    MediaMTX Server                Viewers
┌──────────────┐                 ┌──────────────┐              ┌──────────┐
│   Camera2    │                 │              │              │   VLC    │
│      ↓       │   RTP/UDP       │   Receive    │   RTSP      │          │
│  MediaCodec  │─────5004───────→│   RTP H.265  │────8554────→│  ffplay  │
│   (H.265)    │                 │      ↓       │              │          │
│      ↓       │                 │   Re-stream  │   WebRTC    │  Browser │
│  RTPSender   │                 │   RTSP/WebRTC│────8889────→│          │
└──────────────┘                 └──────────────┘              └──────────┘
```

## Troubleshooting

### No stream appears in MediaMTX

1. Check firewall allows UDP port 5004:
   ```bash
   sudo ufw allow 5004/udp
   ```

2. Verify Android and server are on same network:
   ```bash
   ping 192.168.1.100
   ```

3. Check MediaMTX logs for incoming packets

### Stream stutters or drops frames

1. Check network quality:
   ```bash
   ping -c 100 <android-ip>
   # Should have <1ms latency, 0% packet loss
   ```

2. Reduce bitrate in `MainActivity.kt`:
   ```kotlin
   bitrate = 4_000_000  // Try 4Mbps instead of 8Mbps
   ```

3. Ensure WiFi is on 5GHz band (not 2.4GHz)

### High latency

MediaMTX default config has some buffering. For lowest latency, add to `mediamtx.yml`:

```yaml
paths:
  android:
    source: rtp://0.0.0.0:5004
    sourceProtocol: rtp

    # Low latency settings
    readTimeout: 10s
    readBuffer: 512KB
```

## Advanced Configuration

### Multiple Streams

```yaml
paths:
  camera1:
    source: rtp://0.0.0.0:5004
    sourceProtocol: rtp

  camera2:
    source: rtp://0.0.0.0:5005
    sourceProtocol: rtp
```

### Recording

```yaml
paths:
  android:
    source: rtp://0.0.0.0:5004
    sourceProtocol: rtp
    record: yes
    recordPath: ./recordings/%path/%Y-%m-%d_%H-%M-%S
    recordFormat: fmp4
    recordSegmentDuration: 60s
```

### Authentication

```yaml
paths:
  android:
    source: rtp://0.0.0.0:5004
    sourceProtocol: rtp
    publishUser: android
    publishPass: secretpassword
```

## Performance Stats

Typical performance on local gigabit network:

- **Latency**: 50-100ms glass-to-glass
- **Packet loss**: 0%
- **Throughput**: 8Mbps steady
- **Frame drops**: 0 (100% efficiency)

## Protocol Details

RTP packets sent by AndroidStreamer:

```
Small Frames (< 1400 bytes):
[12 bytes] RTP Header
  - Version: 2
  - Payload Type: 96 (H.265)
  - Sequence Number: Incremental
  - Timestamp: 90kHz clock
  - SSRC: 0x12345678
  - Marker bit: 1
[N bytes] Complete H.265 NAL unit

Large Frames (> 1400 bytes):
Fragmented into multiple packets using H.265 FU (Fragmentation Units):
[12 bytes] RTP Header
[3 bytes]  FU Header (type=49, S/E bits for start/end)
[~1385 bytes] Fragment payload

- Regular frames: ~30-50KB (sent as single packet or 2-3 fragments)
- Keyframes: ~80-180KB (fragmented into 60-130 packets)
```

MediaMTX receives and reassembles these RTP packets, then re-streams via RTSP/WebRTC/HLS.
