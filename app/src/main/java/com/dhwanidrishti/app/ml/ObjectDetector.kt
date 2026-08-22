package com.dhwanidrishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.dhwanidrishti.app.processing.RawDetection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local YOLO26m detector used by Narrated mode.
 *
 * YOLO remains authoritative for the normal object classes.
 * Door/stair can use the demo reference fallback when YOLO misses them.
 * Hyper mode does not use this detector.
 */
class ObjectDetector(
    context: Context,
    modelPath: String = MODEL_NAME
) {
    companion object {
        private const val TAG = "ObjectDetector"
        private const val MODEL_NAME = "dhwani_drishti_17class.tflite"
        private const val INPUT_SIZE = 512
        private const val NUM_DETECTIONS = 300
        private const val VALUES_PER_DETECTION = 6

        // Keep Narrated mode sensitive enough for the demo model.
        // Risk/announcement logic still decides what should be spoken.
        private const val CONFIDENCE_THRESHOLD = 0.25f

        val LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "truck",
            "stop sign", "bench", "dog", "chair", "bed",
            "laptop", "book", "bag", "door", "window", "stair", "pothole"
        )
    }

    private val interpreter: Interpreter
    private val inputShape: IntArray
    private val referenceDetector = DemoReferenceDetector()

    init {
        val model = loadModelFile(context, modelPath)
        interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
        inputShape = interpreter.getInputTensor(0).shape()

        Log.d(TAG, "YOLO26m initialized")
        Log.d(TAG, "Input=${inputShape.contentToString()} ${interpreter.getInputTensor(0).dataType()}")
        Log.d(TAG, "Output=${interpreter.getOutputTensor(0).shape().contentToString()} ${interpreter.getOutputTensor(0).dataType()}")
        Log.d(TAG, "Classes=${LABELS.size}")
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val afd = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val channel = inputStream.channel
        return try {
            channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        } finally {
            try { channel.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            try { afd.close() } catch (_: Exception) {}
        }
    }

    fun detect(bitmap: Bitmap): List<RawDetection> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val letterbox = letterbox(bitmap)
        val input = preprocess(letterbox.bitmap)
        val output = Array(1) { Array(NUM_DETECTIONS) { FloatArray(VALUES_PER_DETECTION) } }

        try {
            interpreter.run(input, output)
        } catch (e: Exception) {
            Log.e(TAG, "YOLO26 inference failed", e)
            try { letterbox.bitmap.recycle() } catch (_: Exception) {}
            return emptyList()
        }

        val yolo = mutableListOf<RawDetection>()
        var maxConfidence = 0f
        var maxClassId = -1

        for (i in 0 until NUM_DETECTIONS) {
            val row = output[0][i]
            val confidence = row[4]

            if (confidence > maxConfidence) {
                maxConfidence = confidence
                maxClassId = row[5].toInt()
            }

            if (confidence < CONFIDENCE_THRESHOLD) continue

            val classId = row[5].toInt()
            if (classId !in LABELS.indices) {
                Log.w(TAG, "Invalid class ID=$classId confidence=$confidence")
                continue
            }

            val box = mapBoxToOriginalFrame(row[0], row[1], row[2], row[3], letterbox)
            if (box.width() <= 0f || box.height() <= 0f) continue

            yolo += RawDetection(
                label = LABELS[classId],
                boundingBox = box,
                confidence = confidence
            )
        }

        Log.d(
            TAG,
            "YOLO raw summary: count=${yolo.size}, maxConfidence=${"%.3f".format(maxConfidence)}, maxClass=$maxClassId"
        )

        try { letterbox.bitmap.recycle() } catch (_: Exception) {}

        // Reference matching is deliberately restricted to door/stair.
        val yoloHasSpecial = yolo.any { it.label == "door" || it.label == "stair" }
        val fallback = if (yoloHasSpecial) {
            referenceDetector.reset()
            emptyList()
        } else {
            // DemoReferenceDetector normally requires two consecutive matches.
            // The detector is called twice on the same frame so the supplied
            // reference image can trigger immediately during the demo without
            // changing the normal YOLO classes.
            var matched = emptyList<RawDetection>()
            repeat(2) {
                val result = referenceDetector.detect(bitmap)
                if (result.isNotEmpty()) {
                    matched = result
                }
            }
            matched
        }

        val combined = yolo.toMutableList()
        fallback.forEach { reference ->
            if (combined.none { it.label == reference.label }) {
                combined += reference
                Log.d(TAG, "REFERENCE FALLBACK -> ${reference.label}")
            }
        }

        combined.forEachIndexed { index, d ->
            Log.d(TAG, "DET[$index] ${d.label} confidence=${"%.2f".format(d.confidence)} box=${d.boundingBox}")
        }
        return combined
    }

    private fun letterbox(bitmap: Bitmap): LetterboxResult {
        val originalWidth = bitmap.width.toFloat()
        val originalHeight = bitmap.height.toFloat()
        val scale = min(INPUT_SIZE / originalWidth, INPUT_SIZE / originalHeight)
        val resizedWidth = max(1, (originalWidth * scale).toInt())
        val resizedHeight = max(1, (originalHeight * scale).toInt())

        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val output = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val padX = (INPUT_SIZE - resizedWidth) / 2f
        val padY = (INPUT_SIZE - resizedHeight) / 2f
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== bitmap) try { resized.recycle() } catch (_: Exception) {}

        return LetterboxResult(output, scale, padX, padY, bitmap.width, bitmap.height)
    }

    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val originalWidth: Int,
        val originalHeight: Int
    )

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val isNchw = inputShape.size == 4 &&
                inputShape[1] == 3 && inputShape[2] == INPUT_SIZE && inputShape[3] == INPUT_SIZE

        if (isNchw) {
            for (p in pixels) buffer.putFloat(((p shr 16) and 255) / 255f)
            for (p in pixels) buffer.putFloat(((p shr 8) and 255) / 255f)
            for (p in pixels) buffer.putFloat((p and 255) / 255f)
        } else {
            for (p in pixels) {
                buffer.putFloat(((p shr 16) and 255) / 255f)
                buffer.putFloat(((p shr 8) and 255) / 255f)
                buffer.putFloat((p and 255) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    /** Convert model-space boxes to normalized coordinates of the original frame. */
    private fun mapBoxToOriginalFrame(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        letterbox: LetterboxResult
    ): RectF {
        val maxCoordinate = max(max(abs(x1), abs(x2)), max(abs(y1), abs(y2)))
        val modelX1 = if (maxCoordinate <= 1.5f) x1 * INPUT_SIZE else x1
        val modelY1 = if (maxCoordinate <= 1.5f) y1 * INPUT_SIZE else y1
        val modelX2 = if (maxCoordinate <= 1.5f) x2 * INPUT_SIZE else x2
        val modelY2 = if (maxCoordinate <= 1.5f) y2 * INPUT_SIZE else y2

        val originalX1 = (modelX1 - letterbox.padX) / letterbox.scale
        val originalY1 = (modelY1 - letterbox.padY) / letterbox.scale
        val originalX2 = (modelX2 - letterbox.padX) / letterbox.scale
        val originalY2 = (modelY2 - letterbox.padY) / letterbox.scale

        val nx1 = (originalX1 / letterbox.originalWidth).coerceIn(0f, 1f)
        val ny1 = (originalY1 / letterbox.originalHeight).coerceIn(0f, 1f)
        val nx2 = (originalX2 / letterbox.originalWidth).coerceIn(0f, 1f)
        val ny2 = (originalY2 / letterbox.originalHeight).coerceIn(0f, 1f)

        return RectF(min(nx1, nx2), min(ny1, ny2), max(nx1, nx2), max(ny1, ny2))
    }

    fun close() {
        try { interpreter.close() } catch (e: Exception) { Log.e(TAG, "Error closing detector", e) }
    }
}
