package com.example.android_streamer.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi

/**
 * Camera2 controller for zero-copy capture to MediaCodec surface.
 * Optimized for low-latency video streaming with proven device compatibility.
 *
 * Requires Android 12+ (API 31+) for aggressive optimizations.
 */
@RequiresApi(Build.VERSION_CODES.S)
class Camera2Controller(private val context: Context) {
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Camera thread
    private val cameraThread = HandlerThread("Camera2Thread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = java.util.concurrent.Executor { cameraHandler.post(it) }

    // Camera characteristics
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null

    // Frame counting
    @Volatile
    private var frameCount = 0L

    /**
     * Check if camera supports 4K@60fps.
     */
    fun supports4K60(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

                // Check if 4K resolution is supported
                val outputSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                val has4K = outputSizes?.any { size ->
                    size.width == 3840 && size.height == 2160
                } ?: false

                // Check if 60fps is supported
                val has60fps = fpsRanges?.any { range ->
                    range.lower == 60 && range.upper == 60
                } ?: false

                if (has4K && has60fps) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Start camera with specified resolution and frame rate.
     *
     * @param targetSurface Surface to render camera frames to (from MediaCodec)
     * @param previewSurface Optional preview surface
     * @param width Target width
     * @param height Target height
     * @param fps Target frame rate
     */
    @SuppressLint("MissingPermission")
    fun startCamera(
        targetSurface: Surface,
        previewSurface: Surface?,
        width: Int,
        height: Int,
        fps: Int
    ) {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find best back camera
        cameraId = findBestBackCamera()
            ?: throw IllegalStateException("No back camera found")

        characteristics = cameraManager!!.getCameraCharacteristics(cameraId!!)

        Log.i(TAG, "Opening camera: $cameraId for ${width}x${height}@${fps}fps")

        // Open camera
        cameraManager!!.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(targetSurface, previewSurface, width, height, fps)
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
     * Find best back camera based on hardware level.
     */
    private fun findBestBackCamera(): String? {
        val manager = cameraManager ?: return null

        val backCameras = manager.cameraIdList.filter { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        }

        if (backCameras.isEmpty()) {
            Log.e(TAG, "No back camera found")
            return null
        }

        // Prefer camera with highest hardware level
        return backCameras.maxByOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        }
    }

    /**
     * Create capture session with target surfaces.
     */
    private fun createCaptureSession(
        targetSurface: Surface,
        previewSurface: Surface?,
        width: Int,
        height: Int,
        fps: Int
    ) {
        val camera = cameraDevice ?: return

        if (!targetSurface.isValid) {
            Log.e(TAG, "Invalid encoder surface")
            return
        }

        try {
            // Build output configurations
            val outputs = mutableListOf(OutputConfiguration(targetSurface))
            previewSurface?.let { outputs.add(OutputConfiguration(it)) }

            // Create session configuration
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startCapture(targetSurface, previewSurface, fps)
                        Log.i(TAG, "Capture session configured")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                }
            )

            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    /**
     * Start repeating capture request.
     */
    private fun startCapture(targetSurface: Surface, previewSurface: Surface?, fps: Int) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return

        try {
            // Get supported FPS ranges
            val characteristics = cameraManager?.getCameraCharacteristics(camera.id)
            val supportedFpsRanges = characteristics?.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: arrayOf(Range(30, 30))

            // Intelligent FPS range selection with fallback
            val targetRange = Range(fps, fps)
            val fpsRange = when {
                supportedFpsRanges.contains(targetRange) -> {
                    Log.i(TAG, "Found exact FPS range: $targetRange")
                    targetRange
                }
                else -> {
                    // Fallback: find range where upper >= target FPS
                    val fallback = supportedFpsRanges
                        .filter { it.lower < it.upper && it.upper >= fps }
                        .maxByOrNull { it.upper }
                        ?: supportedFpsRanges.maxByOrNull { it.upper }
                        ?: Range(30, 30)

                    Log.w(TAG, "Using fallback FPS range: $fallback (requested: $fps)")
                    fallback
                }
            }

            // Log all available FPS ranges for debugging
            supportedFpsRanges.forEach { range ->
                Log.d(TAG, "Available FPS range: ${range.lower}-${range.upper}")
            }

            // Build capture request
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(targetSurface)
                previewSurface?.let { addTarget(it) }

                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                // Samsung 60fps fix: Use SENSOR_FRAME_DURATION to bypass FPS restrictions
                if (fps >= 60) {
                    set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
                    Log.d(TAG, "Applied SENSOR_FRAME_DURATION: ${1_000_000_000L / fps}ns")
                }

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
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
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        if (frameCount <= 3) {
                            Log.e(TAG, "Capture failed: ${failure.reason}")
                        }
                    }
                },
                cameraHandler
            )

            Log.i(TAG, "Capture started: ${fpsRange.lower}-${fpsRange.upper}fps" +
                if (fps >= 60) " (frame duration: ${1_000_000_000L / fps}ns)" else "")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
        }
    }

    /**
     * Get maximum supported FPS for given resolution.
     */
    fun getMaxSupportedFps(width: Int, height: Int): Int {
        val id = cameraId ?: findBestBackCamera() ?: return 30
        val manager = cameraManager ?: return 30
        val characteristics = manager.getCameraCharacteristics(id)

        // Check min frame duration for actual capability
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val minFrameDuration = configMap?.getOutputMinFrameDuration(
            android.graphics.ImageFormat.PRIVATE,
            Size(width, height)
        )

        val maxFpsFromDuration = if (minFrameDuration != null && minFrameDuration > 0) {
            (1_000_000_000.0 / minFrameDuration).toInt()
        } else {
            null
        }

        // Check FPS ranges
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return 30
        val maxFpsFromRanges = fpsRanges.maxByOrNull { it.upper }?.upper ?: 30

        // Use highest from either source
        val detectedFps = maxOf(maxFpsFromRanges, maxFpsFromDuration ?: 0)

        return when {
            detectedFps >= 120 -> 120
            detectedFps >= 60 -> 60
            else -> maxOf(detectedFps, 30)
        }
    }

    /**
     * Get camera statistics.
     */
    fun getStats() = Camera2Stats(capturedFrames = frameCount)

    /**
     * Stop camera and release resources.
     */
    fun stopCamera() {
        captureSession?.close()
        cameraDevice?.close()

        captureSession = null
        cameraDevice = null

        Log.i(TAG, "Camera stopped (captured $frameCount frames)")
    }

    /**
     * Release all resources including camera thread.
     */
    fun release() {
        stopCamera()
        cameraThread.quitSafely()
    }

    data class Camera2Stats(val capturedFrames: Long)

    companion object {
        private const val TAG = "Camera2Controller"
    }
}
