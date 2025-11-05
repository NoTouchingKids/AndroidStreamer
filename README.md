# Android Streamer

A low-latency Android camera pipeline for real-time 4K@60fps video capture and streaming to an RTSP server (such as MediaMTX) using H.265/HEVC encoding over UDP.  
Designed for minimal runtime allocations, stable frame timing, and predictable latency.

---

## Features

- 4K 60 FPS capture using Camera2 API
- Hardware-accelerated H.265 (HEVC) encoding via `MediaCodec`
- Real-time streaming over RTSP (RTP/UDP)
- Optional preview at reduced frame rate (e.g., 24 fps)
- Zero-copy GPU pipeline (camera → encoder)
- Fire-and-forget UDP transmission (no blocking, drops allowed)
- No runtime object allocation after initialization
- Optional “pause preview during capture” for performance

---

## Architecture

CameraDevice
│
├── SurfaceView (Preview)
│ └── optional, sampled at 24 fps
│
└── MediaCodec Input Surface (Encoder)
└── HEVC Encoder → RTP Packetizer → UDP Socket → MediaMTX Server

The application creates a single `CameraCaptureSession` bound to both the preview and encoder surfaces.
During capture bursts, the session issues capture requests that omit the preview surface to dedicate all bandwidth to encoding.

## Components

| Component            | Description                                                                |
| -------------------- | -------------------------------------------------------------------------- |
| **CameraController** | Manages Camera2 initialization, capture session, and control parameters    |
| **MediaEncoder**     | Wraps the `MediaCodec` HEVC encoder with a zero-copy input surface         |
| **RtpPacketizer**    | Implements RFC 7798 (H.265 over RTP) for fragmenting and sending NAL units |
| **RtspPublisher**    | Handles RTSP `ANNOUNCE`, `SETUP`, and `RECORD` requests to MediaMTX        |
| **UdpTransport**     | Non-blocking UDP sender (drops on buffer overflow)                         |

---

## Performance Notes

- `SurfaceView` is used for preview to avoid additional copy overhead.
- `ImageReader` is not used in the streaming path to prevent heap allocations.
- The `MediaCodec` input surface is attached directly to the camera as an output target.
- `DatagramChannel` is used for RTP packet transmission in non-blocking mode.
- All buffers, threads, and capture requests are preallocated during initialization.

---

## RTSP / MediaMTX Setup

1. Install and run [MediaMTX](https://github.com/bluenviron/mediamtx):
   ```bash
   ./mediamtx
   ```

```

2. The default RTSP port is `8554`. The client publishes using:

```

rtsp://<server-ip>:8554/stream

````

3. View the stream with:

```bash
ffplay -fflags nobuffer -flags low_delay -rtsp_transport udp rtsp://<server-ip>:8554/stream
````

4. MediaMTX can also restream the feed over WebRTC or HLS for browser clients.

---

## Encoder Configuration

| Parameter        | Value        | Description              |
| ---------------- | ------------ | ------------------------ |
| Codec            | `video/hevc` | H.265 hardware encoder   |
| Resolution       | 3840x2160    | 4K UHD                   |
| Frame Rate       | 60 fps       | Constant                 |
| Bitrate          | 35–50 Mbps   | Constant bitrate (CBR)   |
| I-frame Interval | 1 s          | One keyframe per second  |
| B-frames         | 0            | Disabled for low latency |
| GOP              | Short (1 s)  | Tuned for live streaming |

---

## RTP Packetization (RFC 7798)

- **Clock rate:** 90 kHz
- **Marker bit:** Set on last packet of each frame
- **NALU fragmentation:** FU (Fragmentation Units) used for large frames
- **Payload type:** 96 (dynamic)
- **Timestamp increment:** 90000 / 60 = 1500 per frame

---

## Future Work

- Optional FEC or retransmission for lossy networks
- Multi-track audio/video synchronization
- Bitrate adaptation
- Stream control via RTSP TEARDOWN / PLAY messages
- WebRTC gateway integration for browser clients

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

---

## Acknowledgments

- Android Camera2 and MediaCodec APIs
- RFC 7798: RTP Payload Format for High Efficiency Video Coding (HEVC)
- MediaMTX (Bluenviron) for RTSP server implementation
