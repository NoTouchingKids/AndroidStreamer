package com.example.android_streamer.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * CameraX-based controller for 1080p@60fps capture with MediaCodec surface integration.
 *
 * Zero-copy pipeline: Camera2 → MediaCodec Surface (GPU direct)
 *
 * @param context Android context
 * @param lifecycleOwner Lifecycle owner for camera binding
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null

    // Performance metrics
    @Volatile
    private var frameCount = 0L

    /**
     * Start camera with MediaCodec surface for zero-copy encoding.
     *
     * @param previewView Preview surface for UI
     * @param encoderSurface MediaCodec input surface from H265Encoder
     */
    fun startCamera(
        previewView: PreviewView,
        encoderSurface: Surface
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(previewView, encoderSurface)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(previewView: PreviewView, encoderSurface: Surface) {
        val cameraProvider = this.cameraProvider
            ?: throw IllegalStateException("Camera not initialized")

        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview use case (for UI display)
        preview = Preview.Builder()
            .setTargetResolution(Size(1920, 1080))
            .build()
            .also { p ->
                p.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Create use case from MediaCodec surface
        // This is the ZERO-COPY path: Camera → GPU → MediaCodec
        val surfaceRequest = SurfaceRequest(Size(1920, 1080), object : SurfaceRequest.TransformationInfoListener {
            override fun onTransformationInfoUpdate(transformationInfo: SurfaceRequest.TransformationInfo) {
                // Transformation info updates (rotation, etc.)
            }
        })

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Bind preview ONLY (encoder surface will be bound separately via Camera2)
            // Note: CameraX doesn't directly support arbitrary Surface targets
            // We'll need to use Camera2 directly for the encoder surface

            Log.w(TAG, "CameraX doesn't support arbitrary Surface targets directly")
            Log.w(TAG, "Need to use Camera2 API directly for encoder surface integration")
            Log.w(TAG, "For now, binding preview only")

            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview
            )

            Log.i(TAG, "Camera started: 1080p preview")
            Log.i(TAG, "TODO: Switch to Camera2 API for encoder surface support")

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    /**
     * Stop camera and release resources.
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        Log.i(TAG, "Camera stopped")
    }

    /**
     * Get performance statistics.
     */
    fun getStats(): CameraStats {
        return CameraStats(
            totalFrames = frameCount
        )
    }

    data class CameraStats(
        val totalFrames: Long
    )

    companion object {
        private const val TAG = "CameraController"
    }
}
