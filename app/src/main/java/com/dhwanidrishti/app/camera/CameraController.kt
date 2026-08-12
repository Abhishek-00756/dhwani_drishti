package com.dhwanidrishti.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX capture: binds Preview + ImageAnalysis on a dedicated single-thread
 * executor. STRATEGY_KEEP_ONLY_LATEST drops stale frames while inference is
 * busy, so we always process the freshest reality, never a backlog.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val frameConsumer: (Bitmap) -> Unit,
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 320))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val bitmap = imageProxy.toBitmap()
                frameConsumer(bitmap)
            } finally {
                // Must always close, or CameraX stops delivering frames.
                imageProxy.close()
            }
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind camera use cases", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraExecutor.shutdown()
    }

    /**
     * RGBA_8888 output is a single plane with per-pixel byte order R,G,B,A.
     * rowStride can exceed width * 4 (padding), so copy row by row.
     */
    private fun ImageProxy.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val offset = row * rowStride + col * pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = buffer.get(offset + 3).toInt() and 0xFF
                pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "DhwaniDrishti.Camera"
    }
}
