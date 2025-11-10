package com.example.android_streamer.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat

/**
 * Camera2 API controller for zero-copy capture to MediaCodec surface.
 *
 * True zero-copy pipeline: Camera2 → GPU → MediaCodec Surface (no CPU copy)
 *
 * Supports dual-surface (preview + encoder) or encoder-only mode.
 * For production streaming, use encoder-only to save GPU bandwidth.
 *
 * ## Expert Gotchas & Best Practices
 *
 * ### 1. Surface Lifecycle Management
 * - Camera captures require VALID surfaces for entire session duration
 * - MediaCodec surface MUST remain valid until encoder.stop()
 * - Preview surface destroyed (e.g., app background) → session fails
 * - Solution: Use encoder-only mode for production (no preview dependency)
 *
 * ### 2. Session Recreation Overhead
 * - Cannot dynamically add/remove surfaces from active session
 * - Must call session.close() + createCaptureSession() to change surfaces
 * - Session creation takes ~100-500ms → drops 6-30 frames at 60fps
 * - Solution: Decide preview on/off BEFORE starting, not during capture
 *
 * ### 3. GPU Bandwidth & Memory
 * - Dual-surface means GPU writes SAME frame to TWO surfaces
 * - At 1080p@60fps: ~373 MB/s per surface (746 MB/s total dual-surface)
 * - GPU memory allocation: ~12 MB for dual-surface buffer pools
 * - Preview during capture = wasted bandwidth + thermal throttling risk
 * - Solution: Encoder-only mode (set previewSurface = null)
 *
 * ### 4. TEMPLATE_RECORD Optimization
 * - TEMPLATE_RECORD optimizes for encoder surface, not preview quality
 * - May apply aggressive noise reduction (higher latency) for encoding
 * - Preview might look worse than TEMPLATE_PREVIEW
 * - We disable NR/edge enhancement for lowest latency (see startRepeatingRequest)
 * - Solution: Accept preview quality tradeoff, or use TEMPLATE_PREVIEW initially
 *
 * ### 5. Surface Format Constraints
 * - MediaCodec surface format: PRIVATE (opaque GPU format)
 * - Preview surface: PRIVATE or RGB_888
 * - Camera must support PRIVATE format at target resolution/FPS
 * - Check SCALER_STREAM_CONFIGURATION_MAP for ImageFormat.PRIVATE support
 * - Solution: checkCapabilities() validates before starting
 *
 * ### 6. Frame Rate Stability
 * - CONTROL_AE_TARGET_FPS_RANGE sets target, not guarantee
 * - Actual FPS depends on: scene brightness, exposure time, processing load
 * - Dark scenes → longer exposure → lower FPS (e.g., 30fps instead of 60fps)
 * - Video stabilization adds ~5-15ms latency
 * - Solution: Disable stabilization, ensure adequate lighting, fixed exposure if needed
 *
 * ### 7. Timestamp Synchronization
 * - Camera timestamps (SENSOR_TIMESTAMP) in nanoseconds, monotonic clock
 * - MediaCodec expects presentationTimeUs in microseconds
 * - Must convert: presentationTimeUs = SENSOR_TIMESTAMP / 1000
 * - Timestamp drift causes A/V sync issues in RTSP stream
 * - Solution: Use SENSOR_TIMESTAMP from CaptureResult (not System.nanoTime())
 *
 * ### 8. Focus & Exposure Locks
 * - CONTINUOUS_VIDEO AF constantly hunts → frame drops during refocus
 * - Auto-exposure adjusts brightness → bitrate spikes (bright scenes = more bits)
 * - For lowest latency: lock AF/AE after initial convergence
 * - Solution: Add AF/AE lock after first 60 frames (1 second at 60fps)
 *
 * ### 9. Buffer Pool Exhaustion
 * - Camera outputs to surfaces backed by BufferQueue (typically 3-4 buffers)
 * - If MediaCodec is slow consuming → BufferQueue fills → camera stalls
 * - Stall causes frame drops and jittery playback
 * - MediaCodec with Surface input: async processing (no stall normally)
 * - Solution: Monitor MediaCodec INFO_TRY_AGAIN_LATER events
 *
 * ### 10. Thermal Throttling
 * - 1080p@60fps HEVC encoding = ~1.5W power draw
 * - Camera sensor + ISP = ~0.5W
 * - GPU surface ops = ~0.3W
 * - Total: ~2.3W sustained → thermal throttling in 3-5 minutes
 * - Throttling → FPS drop to 30fps, bitrate reduction
 * - Solution: Monitor thermal state (PowerManager), reduce FPS/bitrate proactively
 *
 * @param context Android context
 */
class Camera2Controller(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Background thread for camera operations
    private val cameraThread = HandlerThread("Camera2Thread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // Performance metrics
    @Volatile
    private var frameCount = 0L

    /**
     * Start camera with encoder surface (and optional preview).
     *
     * IMPORTANT: For production streaming, set previewSurface = null to save GPU bandwidth.
     * Preview consumes GPU memory/bandwidth and is unnecessary during capture.
     *
     * @param encoderSurface MediaCodec input surface (required)
     * @param previewSurface Surface for UI preview (optional, null = encoder-only mode)
     * @param width Target width (1920)
     * @param height Target height (1080)
     * @param fps Target frame rate (60)
     */
    fun start(
        encoderSurface: Surface,
        previewSurface: Surface? = null,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        // Find back camera
        val cameraId = findBackCamera() ?: run {
            Log.e(TAG, "No back camera found")
            return
        }

        Log.i(TAG, "Opening camera: $cameraId")

        // Open camera
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i(TAG, "Camera opened: ${camera.id}")
                cameraDevice = camera
                createCaptureSession(camera, previewSurface, encoderSurface, width, height, fps)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)
    }

    /**
     * Create capture session with encoder surface (and optional preview).
     */
    private fun createCaptureSession(
        camera: CameraDevice,
        previewSurface: Surface?,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            // Verify encoder surface is valid
            if (!encoderSurface.isValid) {
                Log.e(TAG, "Encoder surface is INVALID before creating session!")
                return
            }
            Log.i(TAG, "Encoder surface is valid before session creation")

            // Verify preview surface if provided
            if (previewSurface != null && !previewSurface.isValid) {
                Log.e(TAG, "Preview surface is INVALID before creating session!")
                return
            }

            // Create capture session - encoder-only or dual-surface
            val surfaces = if (previewSurface != null) {
                Log.i(TAG, "Creating dual-surface session (preview + encoder)")
                listOf(previewSurface, encoderSurface)
            } else {
                Log.i(TAG, "Creating encoder-only session")
                listOf(encoderSurface)
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Capture session configured successfully")
                        captureSession = session
                        startRepeatingRequest(session, camera, previewSurface, encoderSurface, width, height, fps)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration FAILED")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid argument creating capture session (surface issue?)", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Illegal state creating capture session (camera closed?)", e)
        }
    }

    /**
     * Start repeating capture request to encoder surface (and optional preview).
     */
    private fun startRepeatingRequest(
        session: CameraCaptureSession,
        camera: CameraDevice,
        previewSurface: Surface?,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            // Create capture request targeting encoder (and optionally preview)
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                // Always add encoder surface
                addTarget(encoderSurface)

                // Optionally add preview surface
                if (previewSurface != null) {
                    addTarget(previewSurface)
                }

                // Configure for low-latency, high frame rate
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                // Disable video stabilization for lowest latency
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

                // Request fastest processing
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            }

            // Start repeating request
            session.setRepeatingRequest(
                requestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        frameCount++
                        if (frameCount % 60 == 0L) {
                            Log.d(TAG, "Camera frames captured: $frameCount")
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        // Only log first few failures to avoid spam
                        if (frameCount <= 5) {
                            val reasonStr = when (failure.reason) {
                                CaptureFailure.REASON_ERROR -> "REASON_ERROR (generic error)"
                                CaptureFailure.REASON_FLUSHED -> "REASON_FLUSHED (aborted)"
                                else -> "UNKNOWN (${failure.reason})"
                            }
                            Log.w(TAG, "Capture failed: $reasonStr, frame=$failure.frameNumber, sequenceId=${failure.sequenceId}")
                            Log.w(TAG, "  Was image captured: ${failure.wasImageCaptured()}")
                        }
                    }
                },
                cameraHandler
            )

            val mode = if (previewSurface != null) "dual-surface" else "encoder-only"
            Log.i(TAG, "Started repeating capture request: ${width}x${height}@${fps}fps ($mode)")

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    /**
     * Find back camera ID.
     */
    private fun findBackCamera(): String? {
        // Log all available cameras for debugging
        Log.i(TAG, "Available cameras on device:")
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val facingStr = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val levelStr = when (hardwareLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "UNKNOWN"
            }
            Log.i(TAG, "  Camera $id: $facingStr, Level: $levelStr")
        }

        val backCameraId = cameraManager.cameraIdList.find { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        }

        Log.i(TAG, "Selected back camera: $backCameraId")
        return backCameraId
    }

    /**
     * Stop camera and release resources.
     * Note: Handler thread is NOT stopped to allow restarting camera.
     */
    fun stop() {
        Log.i(TAG, "Stopping camera")

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        // Do NOT quit handler thread - keep it alive for restart
        // cameraThread.quitSafely()

        Log.i(TAG, "Camera stopped. Total frames: $frameCount")
    }

    /**
     * Release all resources including handler thread.
     * Call this when Camera2Controller is no longer needed.
     */
    fun release() {
        Log.i(TAG, "Releasing Camera2Controller")
        stop()
        cameraThread.quitSafely()
        Log.i(TAG, "Handler thread stopped")
    }

    /**
     * Get camera statistics.
     */
    fun getStats(): Camera2Stats {
        return Camera2Stats(capturedFrames = frameCount)
    }

    /**
     * Get maximum supported FPS for target resolution.
     * Returns highest fixed FPS range (e.g., [30,30] or [60,60]).
     */
    fun getMaxSupportedFps(width: Int, height: Int): Int {
        val cameraId = findBackCamera() ?: return 30 // Default fallback
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Get supported FPS ranges
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

        if (fpsRanges == null || fpsRanges.isEmpty()) {
            Log.w(TAG, "No FPS ranges available, defaulting to 30fps")
            return 30
        }

        // Log ALL available FPS ranges for debugging
        Log.i(TAG, "All available FPS ranges for ${width}x${height}:")
        fpsRanges.forEach { range ->
            Log.i(TAG, "  [${range.lower}, ${range.upper}]")
        }

        // Check for high-speed video configurations (often where 60fps lives)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val highSpeedSizes = configMap?.highSpeedVideoSizes
        if (highSpeedSizes != null && highSpeedSizes.isNotEmpty()) {
            Log.i(TAG, "High-speed video sizes available:")
            highSpeedSizes.forEach { size ->
                val highSpeedRanges = configMap.getHighSpeedVideoFpsRangesFor(size)
                Log.i(TAG, "  ${size.width}x${size.height}: ${highSpeedRanges.map { "[${it.lower},${it.upper}]" }}")
            }
        }

        // Strategy 1: Try to find fixed FPS range (where lower == upper)
        val fixedFpsRanges = fpsRanges.filter { it.lower == it.upper }
        val maxFixedFps = fixedFpsRanges.maxByOrNull { it.upper }?.upper

        // Strategy 2: Check if there's a variable range that includes our target FPS
        // For example, [30,60] means we can request 60fps
        val maxVariableFps = fpsRanges.maxByOrNull { it.upper }?.upper ?: 30

        Log.i(TAG, "Fixed FPS ranges: ${fixedFpsRanges.map { "[${it.lower},${it.upper}]" }}")
        Log.i(TAG, "Max fixed FPS: $maxFixedFps")
        Log.i(TAG, "Max variable FPS: $maxVariableFps")

        // Prefer fixed FPS if available, otherwise use max from variable range
        val selectedFps = maxFixedFps ?: maxVariableFps

        // If we have a variable range that goes up to 60, we can use 60
        val has60FpsRange = fpsRanges.any { it.upper >= 60 }
        if (has60FpsRange && selectedFps < 60) {
            Log.i(TAG, "Found FPS range supporting 60fps, using 60fps instead of $selectedFps")
            return 60
        }

        Log.i(TAG, "Selected FPS: $selectedFps")
        return selectedFps
    }

    /**
     * Check if device supports target resolution and frame rate.
     */
    fun checkCapabilities(width: Int, height: Int, fps: Int): Boolean {
        val cameraId = findBackCamera() ?: return false
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Check supported output sizes
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = configMap?.getOutputSizes(android.graphics.ImageFormat.PRIVATE) ?: return false

        val targetSize = Size(width, height)
        if (!sizes.contains(targetSize)) {
            Log.w(TAG, "Resolution ${width}x${height} not supported")
            return false
        }

        // Check supported FPS ranges
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val hasFps = fpsRanges?.any { range ->
            range.lower == fps && range.upper == fps
        } ?: false

        if (!hasFps) {
            Log.w(TAG, "FPS $fps not supported")
            return false
        }

        Log.i(TAG, "Device supports ${width}x${height}@${fps}fps")
        return true
    }

    data class Camera2Stats(
        val capturedFrames: Long
    )

    companion object {
        private const val TAG = "Camera2Controller"
    }
}
