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
import kotlin.math.max
import kotlin.math.min

/**
 * Local YOLO26m detector used by Narrated mode.
 *
 * The YOLO model remains the primary detector for all 17 classes.
 * Door and stair have a small demo-only reference fallback so the
 * supplied examples can be recognized when YOLO misses them.
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
        private const val CONFIDENCE_THRESHOLD = 0.35f

        /** Must exactly match the trained model / labels.txt order. */
        val LABELS = listOf(
            "person",       // 0
            "bicycle",      // 1
            "car",          // 2
            "motorcycle",   // 3
            "truck",        // 4
            "stop sign",    // 5
            "bench",        // 6
            "dog",          // 7
            "chair",        // 8
            "bed",          // 9
            "laptop",       // 10
            "book",         // 11
            "bag",          // 12
            "door",         // 13
            "window",       // 14
            "stair",        // 15
            "pothole"       // 16
        )
    }

    private val interpreter: Interpreter
    private val inputShape: IntArray
    private val referenceDetector = DemoReferenceDetector()

    init {
        val model = loadModelFile(context, modelPath)

        interpreter = Interpreter(
            model,
            Interpreter.Options().apply { numThreads = 4 }
        )

        inputShape = interpreter.getInputTensor(0).shape()

        Log.d(TAG, "========================================")
        Log.d(TAG, "YOLO26m ObjectDetector initialized")
        Log.d(TAG, "Model: $modelPath")
        Log.d(TAG, "Input shape: ${inputShape.contentToString()}")
        Log.d(TAG, "Input type: ${interpreter.getInputTensor(0).dataType()}")
        Log.d(TAG, "Output shape: ${interpreter.getOutputTensor(0).shape().contentToString()}")
        Log.d(TAG, "Output type: ${interpreter.getOutputTensor(0).dataType()}")
        Log.d(TAG, "Classes: ${LABELS.size}")
        Log.d(TAG, "Labels: ${LABELS.joinToString()}")
        Log.d(TAG, "========================================")
    }

    private fun loadModelFile(
        context: Context,
        modelPath: String
    ): MappedByteBuffer {
        val afd = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val channel = inputStream.channel

        return try {
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        } finally {
            try { channel.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            try { afd.close() } catch (_: Exception) {}
        }
    }

    /**
     * Runs YOLO and, only when YOLO misses door/stair, runs the
     * seven-reference fallback. Every other class is YOLO-only.
     */
    fun detect(bitmap: Bitmap): List<RawDetection> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val letterbox = letterbox(bitmap)
        val input = preprocess(letterbox.bitmap)

        val output = Array(1) {
            Array(NUM_DETECTIONS) { FloatArray(VALUES_PER_DETECTION) }
        }

        try {
            interpreter.run(input, output)
        } catch (e: Exception) {
            Log.e(TAG, "YOLO26 inference failed", e)
            return emptyList()
        } finally {
            if (letterbox.bitmap !== bitmap) {
                try { letterbox.bitmap.recycle() } catch (_: Exception) {}
            }
        }

        val yoloDetections = mutableListOf<RawDetection>()

        for (i in 0 until NUM_DETECTIONS) {
            val row = output[0][i]
            val confidence = row[4]
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val classId = row[5].toInt()
            if (classId !in LABELS.indices) {
                Log.w(TAG, "Invalid YOLO class ID: $classId")
                continue
            }

            val box = mapBoxToOriginalFrame(
                x1 = row[0],
                y1 = row[1],
                x2 = row[2],
                y2 = row[3],
                letterbox = letterbox
            )

            if (box.width() <= 0f || box.height() <= 0f) continue

            yoloDetections += RawDetection(
                label = LABELS[classId],
                boundingBox = box,
                confidence = confidence
            )
        }

        // YOLO is authoritative whenever it already found door/stair.
        val yoloSpecial = yoloDetections.any {
            it.label == "door" || it.label == "stair"
        }

        val referenceDetections = if (yoloSpecial) {
            referenceDetector.reset()
            emptyList()
        } else {
            referenceDetector.detect(bitmap)
        }

        val combined = yoloDetections.toMutableList()
        for (reference in referenceDetections) {
            // Never duplicate a class already emitted by YOLO.
            if (combined.none { it.label == reference.label }) {
                combined += reference
                Log.d(
                    TAG,
                    "REFERENCE FALLBACK -> ${reference.label} " +
                            "confidence=${reference.confidence} " +
                            "box=${reference.boundingBox}"
                )
            }
        }

        Log.d(TAG, "YOLO detections=${yoloDetections.size}, combined=${combined.size}")
        combined.forEachIndexed { index, detection ->
            Log.d(
                TAG,
                "DET[$index] label=${detection.label} " +
                        "confidence=${"%.2f".format(detection.confidence)} " +
                        "box=${detection.boundingBox}"
            )
        }

        return combined
    }

    private fun letterbox(bitmap: Bitmap): LetterboxResult {
        val originalWidth = bitmap.width.toFloat()
        val originalHeight = bitmap.height.toFloat()

        val scale = min(
            INPUT_SIZE / originalWidth,
            INPUT_SIZE / originalHeight
        )

        val resizedWidth = max(1, (originalWidth * scale).toInt())
        val resizedHeight = max(1, (originalHeight * scale).toInt())

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            resizedWidth,
            resizedHeight,
            true
        )

        val output = Bitmap.createBitmap(
            INPUT_SIZE,
            INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val padX = (INPUT_SIZE - resizedWidth) / 2f
        val padY = (INPUT_SIZE - resizedHeight) / 2f
        canvas.drawBitmap(resized, padX, padY, null)

        if (resized !== bitmap) {
            try { resized.recycle() } catch (_: Exception) {}
        }

        return LetterboxResult(
            bitmap = output,
            scale = scale,
            padX = padX,
            padY = padY,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height
        )
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
        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(pixelCount)
        bitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        val isNchw = inputShape.size == 4 &&
                inputShape[1] == 3 &&
                inputShape[2] == INPUT_SIZE &&
                inputShape[3] == INPUT_SIZE

        if (isNchw) {
            for (pixel in pixels) buffer.putFloat(((pixel shr 16) and 255) / 255f)
            for (pixel in pixels) buffer.putFloat(((pixel shr 8) and 255) / 255f)
            for (pixel in pixels) buffer.putFloat((pixel and 255) / 255f)
        } else {
            // Also support an NHWC export without silently feeding the
            // model the wrong tensor layout.
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 255) / 255f)
                buffer.putFloat(((pixel shr 8) and 255) / 255f)
                buffer.putFloat((pixel and 255) / 255f)
            }
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Supports both common Ultralytics TFLite export conventions:
     * coordinates in 512-model pixels OR normalized 0..1 coordinates.
     * The current model is logged at runtime so the chosen convention
     * can be verified from Logcat.
     */
    private fun mapBoxToOriginalFrame(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        letterbox: LetterboxResult
    ): RectF {
        val maxCoordinate = max(
            max(abs(x1), abs(x2)),
            max(abs(y1), abs(y2))
        )

        val modelX1: Float
        val modelY1: Float
        val modelX2: Float
        val modelY2: Float

        if (maxCoordinate <= 1.5f) {
            modelX1 = x1 * INPUT_SIZE
            modelY1 = y1 * INPUT_SIZE
            modelX2 = x2 * INPUT_SIZE
            modelY2 = y2 * INPUT_SIZE
        } else {
            modelX1 = x1
            modelY1 = y1
            modelX2 = x2
            modelY2 = y2
        }

        val originalX1 = (modelX1 - letterbox.padX) / letterbox.scale
        val originalY1 = (modelY1 - letterbox.padY) / letterbox.scale
        val originalX2 = (modelX2 - letterbox.padX) / letterbox.scale
        val originalY2 = (modelY2 - letterbox.padY) / letterbox.scale

        val normalizedX1 = (originalX1 / letterbox.originalWidth).coerceIn(0f, 1f)
        val normalizedY1 = (originalY1 / letterbox.originalHeight).coerceIn(0f, 1f)
        val normalizedX2 = (originalX2 / letterbox.originalWidth).coerceIn(0f, 1f)
        val normalizedY2 = (originalY2 / letterbox.originalHeight).coerceIn(0f, 1f)

        return RectF(
            min(normalizedX1, normalizedX2),
            min(normalizedY1, normalizedY2),
            max(normalizedX1, normalizedX2),
            max(normalizedY1, normalizedY2)
        )
    }

    fun close() {
        try {
            interpreter.close()
            Log.d(TAG, "YOLO26m interpreter closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing YOLO26m interpreter", e)
        }
    }
}
