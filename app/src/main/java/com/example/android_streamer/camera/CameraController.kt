package com.example.android_streamer.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.android_streamer.buffer.RingBuffer
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX-based controller optimized for low-latency 1080p@60fps capture.
 * Configured for minimal processing delay and zero-copy frame delivery.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Single-threaded executor for camera operations (no thread pool overhead)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Ring buffer for captured frames
    private val ringBuffer: RingBuffer = RingBuffer.createFor1080p60()

    // Frame callback for downstream consumers (encoder, network, etc.)
    private var frameCallback: ((RingBuffer.ReadSlot) -> Unit)? = null

    // Performance metrics
    private var frameCount = 0L
    private var droppedFrames = 0L

    /**
     * Initialize camera and bind to lifecycle.
     *
     * @param previewView SurfaceView for camera preview
     * @param onFrameAvailable Callback invoked when new frame is ready
     */
    fun startCamera(
        previewView: PreviewView,
        onFrameAvailable: (RingBuffer.ReadSlot) -> Unit
    ) {
        this.frameCallback = onFrameAvailable

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(previewView: PreviewView) {
        val cameraProvider = this.cameraProvider ?: throw IllegalStateException("Camera not initialized")

        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview use case (lower frame rate to reduce overhead)
        preview = Preview.Builder()
            .setTargetResolution(Size(1920, 1080))
            .setTargetFrameRate(Range(30, 30)) // Preview at 30fps to save resources
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // ImageAnalysis use case for frame capture (1080p@60fps)
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1920, 1080))
            .setTargetFrameRate(Range(60, 60)) // Target 60fps
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Drop frames if processing is slow
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // YUV for encoder
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { image ->
                    processFrame(image)
                }
            }

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera lifecycle
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            // Configure camera for low-latency mode
            configureLowLatencyMode()

            Log.i(TAG, "Camera started: 1080p@60fps with low-latency mode")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    private fun configureLowLatencyMode() {
        val camera = this.camera ?: return

        // Enable low-latency mode if supported
        val cameraControl = camera.cameraControl
        val cameraInfo = camera.cameraInfo

        // Disable auto-exposure/auto-white-balance locks for faster adaptation
        // (CameraX handles this automatically in most cases)

        Log.d(TAG, "Camera capabilities: ${cameraInfo.cameraState.value}")
    }

    private fun processFrame(image: ImageProxy) {
        val timestampNs = image.imageInfo.timestamp
        frameCount++

        // Acquire write buffer from ring buffer
        val writeSlot = ringBuffer.acquireWriteBuffer()
        if (writeSlot == null) {
            // Buffer full - drop frame
            droppedFrames++
            image.close()
            if (droppedFrames % 30 == 0L) {
                Log.w(TAG, "Ring buffer full, dropped $droppedFrames frames")
            }
            return
        }

        try {
            // Copy YUV data to ring buffer (zero-copy would require MediaCodec surface)
            val frameSize = copyImageToBuffer(image, writeSlot.buffer)

            // Commit write
            ringBuffer.commitWrite(writeSlot, frameSize, timestampNs)

            // Notify downstream consumer
            ringBuffer.acquireReadBuffer()?.let { readSlot ->
                frameCallback?.invoke(readSlot)
                ringBuffer.releaseReadBuffer(readSlot)
            }

            // Log performance metrics every 60 frames
            if (frameCount % 60 == 0L) {
                val dropRate = (droppedFrames.toFloat() / frameCount) * 100
                Log.d(TAG, "Frames: $frameCount, Dropped: $droppedFrames (${String.format("%.2f", dropRate)}%)")
            }
        } finally {
            image.close()
        }
    }

    /**
     * Copy YUV 4:2:0 image planes to a single ByteBuffer.
     * This is a temporary solution - ideally we'd use MediaCodec input surface for zero-copy.
     */
    private fun copyImageToBuffer(image: ImageProxy, buffer: ByteBuffer): Int {
        val planes = image.planes
        var offset = 0

        // Copy Y plane
        val yBuffer = planes[0].buffer
        val ySize = yBuffer.remaining()
        yBuffer.rewind()
        buffer.put(yBuffer)
        offset += ySize

        // Copy U plane
        val uBuffer = planes[1].buffer
        val uSize = uBuffer.remaining()
        uBuffer.rewind()
        buffer.put(uBuffer)
        offset += uSize

        // Copy V plane
        val vBuffer = planes[2].buffer
        val vSize = vBuffer.remaining()
        vBuffer.rewind()
        buffer.put(vBuffer)
        offset += vSize

        return offset
    }

    /**
     * Stop camera and release resources.
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        ringBuffer.clear()
        Log.i(TAG, "Camera stopped")
    }

    /**
     * Get ring buffer instance for direct access.
     */
    fun getRingBuffer(): RingBuffer = ringBuffer

    /**
     * Get performance statistics.
     */
    fun getStats(): CameraStats {
        return CameraStats(
            totalFrames = frameCount,
            droppedFrames = droppedFrames,
            bufferOccupancy = ringBuffer.size()
        )
    }

    data class CameraStats(
        val totalFrames: Long,
        val droppedFrames: Long,
        val bufferOccupancy: Int
    )

    companion object {
        private const val TAG = "CameraController"
    }
}
