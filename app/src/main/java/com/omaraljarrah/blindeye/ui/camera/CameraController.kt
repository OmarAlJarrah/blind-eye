package com.omaraljarrah.blindeye.ui.camera

import android.graphics.*
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    private var imageCapture: ImageCapture? = null

    /**
     * Suspend function to start the camera.
     */
    suspend fun startCamera() = suspendCancellableCoroutine<Unit> { cont ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Build preview use case
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Build image capture use case
                imageCapture = ImageCapture.Builder().build()

                // Unbind all use cases and bind the new ones
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                // If we get here, camera started successfully
                if (!cont.isCompleted) cont.resume(Unit)
            } catch (exc: Exception) {
                if (!cont.isCompleted) cont.resumeWithException(exc)
            }
        }, ContextCompat.getMainExecutor(activity))

        // If the coroutine is cancelled, you could do cleanup here if needed
        cont.invokeOnCancellation {
            // Optionally unbind or release camera resources if you wish
        }
    }

    /**
     * Captures a photo directly as a [Bitmap], returning it via coroutine.
     */
    suspend fun takePhoto(): Bitmap? = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            // Immediately resume with null if not initialized
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    // Important to close the image after processing
                    imageProxy.close()

                    if (!cont.isCompleted) {
                        cont.resume(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraController", "takePhoto() failed", exception)
                    if (!cont.isCompleted) {
                        cont.resumeWithException(exception)
                    }
                }
            }
        )

        // If the coroutine is cancelled, close out or handle appropriately
        cont.invokeOnCancellation {
            // Optionally handle cancellation
        }
    }

    /**
     * Converts an [ImageProxy] to a [Bitmap].
     */
    private fun ImageProxy.toBitmap(): Bitmap? {
        // For simplicity, assuming the image format is YUV_420_888
        val nv21 = yuvToNv21()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    /**
     * Helper to convert YUV_420_888 ImageProxy to NV21 byte array.
     */
    private fun ImageProxy.yuvToNv21(): ByteArray {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }
}
