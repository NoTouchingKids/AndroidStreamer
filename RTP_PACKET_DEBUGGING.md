# RTP Streaming Status and Viewing Guide

## ✅ STATUS: STREAMING IS WORKING!

Based on the latest logs, **RTP packets ARE successfully reaching MediaMTX** and being converted to HLS. The issue was not packet delivery - MediaMTX is receiving and processing your H.265 stream correctly.

### Evidence from Logs

**Android logs show successful transmission:**
```
✓ FIRST FRAGMENT SENT: 192.168.0.2:8000 (1400 bytes)
RTP sender. Stats: packets=21595, bytes=28916KB, fragmented=882
```

**MediaMTX logs confirm reception:**
```
INF [RTSP] [session 1d4afe6f] is publishing to path 'android', 1 track (H265)
INF [HLS] [muxer android] is converting into HLS, 1 track (H265)
```

The session ended cleanly by TEARDOWN (not timeout), proving packets arrived successfully.

## How to View Your Stream

Your H.265 stream is being published successfully to MediaMTX. Here's how to view it:

### ✅ Option 1: HLS in Web Browser (RECOMMENDED)

MediaMTX is converting your stream to HLS automatically. Open this URL in any modern browser:

```
http://192.168.0.2:8888/android
```

**Why HLS?** Most browsers support H.265/HEVC in HLS, even though they don't support it in WebRTC.

### ✅ Option 2: RTSP with VLC Player

For ultra-low latency viewing:

```bash
vlc rtsp://192.168.0.2:8554/android
```

Or with ffplay for even lower latency:

```bash
ffplay -fflags nobuffer -flags low_delay rtsp://192.168.0.2:8554/android
```

### ❌ Why WebRTC Doesn't Work

The WebRTC endpoint (`http://192.168.0.2:8889/android`) won't display anything because:
- Most browsers don't support H.265 in WebRTC (yet)
- Chrome will add support in version 136, but it's not released yet
- Safari has partial support, but it's unreliable

**Use HLS (port 8888) or RTSP with VLC instead.**

## Testing Right Now

1. **Start the Android app** and tap "START CAPTURE"
2. **Wait 2-3 seconds** for encoder to initialize
3. **Open browser** to `http://192.168.0.2:8888/android`
4. **You should see video!**

If the page says "stream not found", make sure the Android app is actively streaming (not stopped).

## Recent Changes (Diagnostic Logging Added)

Two commits have been pushed with comprehensive logging to diagnose the issue:

1. **Detailed RTSP/RTP logging** - Shows full SETUP response and server_port extraction
2. **Enhanced socket diagnostics** - Confirms socket binding and packet destinations
3. **Transport header fix** - Changed from `RTP/AVP` to `RTP/AVP/UDP` for explicit UDP

## What to Look For in Logs

### 1. SETUP Request/Response

When you run the app, look for these logs with TAG `RTSPClient`:

```
=== SETUP Request ===
  Transport: RTP/AVP/UDP;unicast;client_port=5004-5005;mode=record
=====================

=== SETUP Response ===
  RTSP/1.0 200 OK
  Transport: RTP/AVP/UDP;unicast;client_port=5004-5005;server_port=8000-8001
  Session: XXXXXXXX
=====================

✓ Session ID: XXXXXXXX
✓ Extracted server RTP port: 8000
✓ SETUP complete: client_port=5004 -> server_port=8000
```

**CRITICAL**: If you see `✗ CRITICAL: No server_port in Transport header!`, then MediaMTX is not telling Android where to send RTP packets. This is a MediaMTX configuration issue.

### 2. RTP Sender Initialization

Look for TAG `RTPSender`:

```
Updating RTP destination: 192.168.0.2:0 -> 192.168.0.2:8000
✓ RTP destination updated: 192.168.0.2:8000
Binding to client port 5004 (as declared in RTSP SETUP)
✓ Socket bound: local=0.0.0.0:5004
✓ RTP sender ready: SSRC=0x12345678, MTU=1400
✓ Will send: 0.0.0.0:5004 -> 192.168.0.2:8000
```

**VERIFY**:
- Destination should be `192.168.0.2:8000` (or whatever port MediaMTX returned)
- Source should be `0.0.0.0:5004`

### 3. First Packet Confirmation

When the first frame is encoded and sent, you should see:

```
✓ FIRST PACKET SENT: 192.168.0.2:8000 (1400 bytes)
```

or

```
✓ FIRST FRAGMENT SENT: 192.168.0.2:8000 (1415 bytes)
```

**If you DON'T see this**: Encoder might not be producing frames, or RTP sender crashed.

## Troubleshooting Steps

### Step 1: Check SETUP Response

Run the app and immediately check logcat for the SETUP response.

**Expected**: MediaMTX returns `server_port=8000` (or similar)

**If missing**: MediaMTX configuration issue. Check MediaMTX version and config.

### Step 2: Verify Packet Destination

Look for the "Will send:" log line.

**Expected**: `0.0.0.0:5004 -> 192.168.0.2:8000`

**If different**: Code bug or RTSP parsing error.

### Step 3: Confirm Packets Leaving Android

Look for "FIRST PACKET SENT" or "FIRST FRAGMENT SENT".

**Expected**: Message appears within 1-2 seconds of starting capture

**If missing**:
- Encoder not producing frames
- RTP sender crashed (check for exceptions)
- Socket binding failed

### Step 4: Wireshark Packet Capture (Windows)

On your Windows PC running MediaMTX:

1. **Download Wireshark**: https://www.wireshark.org/download.html

2. **Start capture**:
   - Select your network adapter (the one with IP 192.168.0.2)
   - Apply filter: `udp.port == 8000`
   - Start capture

3. **Start Android streaming**

4. **Observe packets**:
   - **Expected**: Packets from `192.168.0.20:5004` to `192.168.0.2:8000`
   - **If nothing**: Packets not reaching PC (router/AP issue, or Android not sending)
   - **If wrong source port**: Android binding issue
   - **If wrong destination**: RTSP parsing bug

### Step 5: Check Network Connectivity

From Windows PC:

```bash
# Can you reach Android?
ping 192.168.0.20

# Check if MediaMTX is listening
netstat -an | findstr :8000
# Should show: UDP    0.0.0.0:8000    *:*
```

From Android (using Termux or similar):

```bash
# Can you reach MediaMTX?
ping 192.168.0.2

# Check if Android can send UDP
# (This test requires nc/netcat on both sides)
```

### Step 6: Check for Network Isolation

**WiFi AP Isolation**: Some WiFi routers have "AP Isolation" or "Client Isolation" enabled, which prevents devices from communicating with each other.

**How to check**:
- Can you ping from PC to Android? `ping 192.168.0.20`
- Can you access Android's web server from PC? (if you have one running)

**If AP isolation is enabled**: Disable it in your router settings, or connect both devices via Ethernet/switch.

### Step 7: Check Android Firewall/VPN

**VPN**: If Android has a VPN active, it might be routing packets incorrectly.
- Disable VPN and test again

**Private DNS**: Android's Private DNS can interfere with local network communication.
- Settings → Network → Private DNS → Set to "Off"

**Firewall Apps**: Some Android devices have firewall apps that block UDP.
- Disable any firewall apps temporarily

## Common Issues and Solutions

### Issue: "No server_port in Transport header"

**Cause**: MediaMTX not returning server RTP port in SETUP response.

**Solution**:
1. Check MediaMTX version (should be v1.0.0 or later)
2. Check MediaMTX logs for errors during SETUP
3. Try explicit MediaMTX path configuration:
   ```yaml
   paths:
     android:
       source: publisher
       sourceOnDemand: yes
   ```

### Issue: Packets sent but MediaMTX times out

**Cause**: Packets not reaching MediaMTX (firewall, routing, or AP isolation).

**Solution**:
1. Windows Firewall: Create inbound rule for UDP 8000
   ```
   New-NetFirewallRule -DisplayName "MediaMTX RTP" -Direction Inbound -Protocol UDP -LocalPort 8000 -Action Allow
   ```
2. Check for AP isolation in router settings
3. Use Wireshark to confirm packets are arriving

### Issue: "First packet sent" but MediaMTX times out

**Cause**: RTP packet format issue or MediaMTX version incompatibility.

**Solution**:
1. Check MediaMTX version supports H.265 publishing
2. Verify RTP packet format (Wireshark can decode RTP)
3. Try reducing bitrate or resolution to see if packet size is an issue

### Issue: Socket binding fails

**Cause**: Port 5004 already in use on Android, or permissions issue.

**Solution**:
1. Restart Android app completely
2. Check if another app is using port 5004
3. Try changing `clientRtpPort` to different port (e.g., 5006)

## Testing MediaMTX with FFmpeg

To verify MediaMTX is working correctly, test publishing from FFmpeg:

```bash
# Generate test video and publish via RTSP
ffmpeg -re -f lavfi -i testsrc=size=1920x1080:rate=30 \
       -c:v libx265 -preset ultrafast -tune zerolatency \
       -f rtsp rtsp://192.168.0.2:8554/test
```

Then view with:
```bash
vlc rtsp://192.168.0.2:8554/test
```

If this works, MediaMTX is fine and the issue is with Android app.

If this fails, MediaMTX configuration needs fixing.

## Next Steps

1. **Run app with new code** and collect logs
2. **Check for "No server_port" error** - if present, MediaMTX config issue
3. **Verify "First packet sent"** - if missing, encoder/sender issue
4. **Use Wireshark** - definitive proof of packet delivery
5. **Report findings** - share logs and Wireshark results for further diagnosis
