package com.dhwanidrishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LiteRT inference for MiDaS small (relative inverse depth), 256x256 in,
 * 256x256 out. GPU delegate for sustained throughput; CPU threads as fallback.
 *
 * All hot-path buffers are allocated once and reused every frame so the GC
 * never has to pause the live stream.
 */
class DepthEstimator(context: Context) {

    private val interpreter: Interpreter
    private val gpuDelegate: Delegate?

    private val resizedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val resizeCanvas = Canvas(resizedBitmap)
    private val resizePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    private val reusableInputBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .apply { order(ByteOrder.nativeOrder()) }

    private val reusableOutput = Array(1) { Array(INPUT_SIZE) { FloatArray(INPUT_SIZE) } }

    init {
        gpuDelegate = buildGpuDelegate()
        val options = Interpreter.Options().apply {
            if (gpuDelegate != null) {
                addDelegate(gpuDelegate)
            }
            setNumThreads(4)
        }
        val model = FileUtil.loadMappedFile(context, "midas_small.tflite")
        interpreter = Interpreter(model, options)
        // Step 8: some devices silently fall back to CPU, which changes the
        // whole latency profile. Log which delegate actually attached.
        Log.i(TAG, if (gpuDelegate != null) "GPU delegate attached" else "GPU delegate unavailable, running on CPU")
    }

    /**
     * Returns a 256x256 relative inverse-depth map (larger value = closer).
     * The returned array is a shared buffer: valid only until the next call.
     */
    fun runInference(source: Bitmap): Array<FloatArray> {
        resizeCanvas.drawBitmap(source, null, Rect(0, 0, INPUT_SIZE, INPUT_SIZE), resizePaint)
        fillInputBuffer()
        interpreter.run(reusableInputBuffer, reusableOutput)
        return reusableOutput[0]
    }

    private fun fillInputBuffer() {
        resizedBitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        reusableInputBuffer.rewind()

        // MiDaS model expects NCHW:
        // [1, 3, 256, 256]
        //
        // So write:
        // 1. all R values
        // 2. all G values
        // 3. all B values

        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            reusableInputBuffer.putFloat(
                (r - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
            )
        }

        for (p in pixels) {
            val g = ((p shr 8) and 0xFF) / 255f
            reusableInputBuffer.putFloat(
                (g - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
            )
        }

        for (p in pixels) {
            val b = (p and 0xFF) / 255f
            reusableInputBuffer.putFloat(
                (b - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
            )
        }

        reusableInputBuffer.rewind()
    }
    // private fun buildGpuDelegate(): Delegate? = null // force CPU, test crash goes away//
    private fun buildGpuDelegate(): Delegate? {
        return try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) GpuDelegate() else null
        } catch (t: Throwable) {
            null
        }
    }

    fun close() {
        gpuDelegate?.close()
        interpreter.close()
    }

    private companion object {
        const val INPUT_SIZE = 256
        const val TAG = "DhwaniDrishti.Depth"
        val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
