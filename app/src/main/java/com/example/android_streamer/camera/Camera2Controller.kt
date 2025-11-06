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
 * Configures dual-surface capture session:
 * 1. Preview surface (for UI)
 * 2. Encoder surface (MediaCodec input surface)
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
     * Start camera with dual surfaces (preview + encoder).
     *
     * @param previewSurface Surface for UI preview
     * @param encoderSurface MediaCodec input surface
     * @param width Target width (1920)
     * @param height Target height (1080)
     * @param fps Target frame rate (60)
     */
    fun start(
        previewSurface: Surface,
        encoderSurface: Surface,
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
     * Create capture session with dual surfaces.
     */
    private fun createCaptureSession(
        camera: CameraDevice,
        previewSurface: Surface,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            // Create capture session with both surfaces
            camera.createCaptureSession(
                listOf(previewSurface, encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(TAG, "Capture session configured")
                        captureSession = session
                        startRepeatingRequest(session, camera, previewSurface, encoderSurface, width, height, fps)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    /**
     * Start repeating capture request to both surfaces.
     */
    private fun startRepeatingRequest(
        session: CameraCaptureSession,
        camera: CameraDevice,
        previewSurface: Surface,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            // Create capture request targeting both surfaces
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                // Add both surfaces as targets
                addTarget(previewSurface)
                addTarget(encoderSurface)

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
                        Log.w(TAG, "Capture failed: ${failure.reason}")
                    }
                },
                cameraHandler
            )

            Log.i(TAG, "Started repeating capture request: ${width}x${height}@${fps}fps")

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    /**
     * Find back camera ID.
     */
    private fun findBackCamera(): String? {
        return cameraManager.cameraIdList.find { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /**
     * Stop camera and release resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping camera")

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        cameraThread.quitSafely()

        Log.i(TAG, "Camera stopped. Total frames: $frameCount")
    }

    /**
     * Get camera statistics.
     */
    fun getStats(): Camera2Stats {
        return Camera2Stats(capturedFrames = frameCount)
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
