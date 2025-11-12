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
 * Camera2 controller for zero-copy capture to MediaCodec surface.
 * Encoder-only mode - no preview support for maximum efficiency.
 */
class Camera2Controller(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraThread = HandlerThread("Camera2").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    @Volatile
    private var frameCount = 0L

    fun start(
        encoderSurface: Surface,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission denied")
            return
        }

        val cameraId = findBackCamera() ?: run {
            Log.e(TAG, "No back camera")
            return
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera, encoderSurface, width, height, fps)
            }

            override fun onDisconnected(camera: CameraDevice) {
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

    private fun createCaptureSession(
        camera: CameraDevice,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            if (!encoderSurface.isValid) {
                Log.e(TAG, "Invalid encoder surface")
                return
            }

            val outputConfig = android.hardware.camera2.params.OutputConfiguration(encoderSurface)
            val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                java.util.concurrent.Executor { runnable -> cameraHandler.post(runnable) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingRequest(session, camera, encoderSurface, width, height, fps)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session config failed")
                    }
                }
            )

            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Create session failed", e)
        }
    }

    private fun startRepeatingRequest(
        session: CameraCaptureSession,
        camera: CameraDevice,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int
    ) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val supportedFpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: arrayOf(Range(30, 30))

            val targetRange = Range(fps, fps)
            val fpsRange = when {
                supportedFpsRanges.contains(targetRange) -> targetRange
                else -> {
                    supportedFpsRanges
                        .filter { it.lower < it.upper && it.upper >= fps }
                        .maxByOrNull { it.upper }
                        ?: supportedFpsRanges.maxByOrNull { it.upper }
                        ?: Range(30, 30)
                }
            }

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encoderSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                // Samsung 60fps fix: Use SENSOR_FRAME_DURATION to bypass FPS restrictions
                if (fps >= 60) {
                    set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
                }

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "Start request failed", e)
        }
    }

    private fun findBackCamera(): String? {
        val backCameras = cameraManager.cameraIdList.filter { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        }

        if (backCameras.isEmpty()) return null

        // Prefer camera with highest hardware level
        return backCameras.maxByOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        }
    }

    fun stop() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    fun release() {
        stop()
        cameraThread.quitSafely()
    }

    fun getStats() = Camera2Stats(capturedFrames = frameCount)

    fun getMaxSupportedFps(width: Int, height: Int): Int {
        val cameraId = findBackCamera() ?: return 30
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

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

    data class Camera2Stats(val capturedFrames: Long)

    companion object {
        private const val TAG = "Camera2"
    }
}
