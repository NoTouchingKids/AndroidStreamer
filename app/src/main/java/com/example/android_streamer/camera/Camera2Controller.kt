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
import java.util.concurrent.Executor

/**
 * Camera2 controller optimized for low-latency video capture.
 * Uses Camera2 API directly for maximum control over frame rate and configuration.
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
    private val cameraExecutor = Executor { cameraHandler.post(it) }

    // Camera characteristics
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null

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

        // Find back camera that supports the target resolution and frame rate
        cameraId = findSuitableCamera(width, height, fps)
            ?: throw IllegalStateException("No camera supports ${width}x${height}@${fps}fps")

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
     * Find camera that supports the target resolution and frame rate.
     */
    private fun findSuitableCamera(width: Int, height: Int, fps: Int): String? {
        val manager = cameraManager ?: return null

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            // Use back camera
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

                // Check resolution support
                val outputSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                val supportsResolution = outputSizes?.any { size ->
                    size.width == width && size.height == height
                } ?: false

                // Check fps support
                val supportsFps = fpsRanges?.any { range ->
                    range.lower == fps && range.upper == fps
                } ?: false

                if (supportsResolution && supportsFps) {
                    Log.d(TAG, "Found suitable camera: $id")
                    return id
                }
            }
        }

        return null
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
    }

    /**
     * Start repeating capture request.
     */
    private fun startCapture(targetSurface: Surface, previewSurface: Surface?, fps: Int) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return

        // Build capture request
        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(targetSurface)
            previewSurface?.let { addTarget(it) }

            // Lock frame rate to exact value
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))

            // Auto mode for exposure and white balance
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // Disable video stabilization for lower latency
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

            // Request low-latency mode if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)
            }
        }

        // Start repeating request
        session.setRepeatingRequest(
            requestBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Frame captured - encoder receives it directly via surface
                }
            },
            cameraHandler
        )

        Log.i(TAG, "Capture started at ${fps}fps")
    }

    /**
     * Stop camera and release resources.
     */
    fun stopCamera() {
        captureSession?.close()
        cameraDevice?.close()
        cameraThread.quitSafely()

        captureSession = null
        cameraDevice = null

        Log.i(TAG, "Camera stopped")
    }

    companion object {
        private const val TAG = "Camera2Controller"
    }
}
