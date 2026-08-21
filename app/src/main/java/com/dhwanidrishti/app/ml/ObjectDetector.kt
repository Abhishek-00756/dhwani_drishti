package com.dhwanidrishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.dhwanidrishti.app.processing.RawDetection
import org.tensorflow.lite.DataType
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
 * Local YOLO detector for the 17 Dhwani Drishti classes.
 *
 * YOLO remains the primary detector for every class. Door/stair have a
 * demo-only visual-reference fallback so the demo can still recognize them
 * when the trained model misses them.
 */
class ObjectDetector(
    context: Context,
    modelPath: String = MODEL_NAME
) {
    companion object {
        private const val TAG = "ObjectDetector"
        private const val MODEL_NAME = "dhwani_drishti_17class.tflite"
        private const val INPUT_SIZE = 512
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val NMS_OUTPUT_VALUES = 6

        val LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "truck",
            "stop sign", "bench", "dog", "chair", "bed",
            "laptop", "book", "bag", "door", "window", "stair", "pothole"
        )
    }

    private val interpreter: Interpreter
    private val inputShape: IntArray
    private val inputType: DataType
    private val outputShape: IntArray
    private val outputType: DataType
    private val inputWidth: Int
    private val inputHeight: Int
    private val referenceDetector = DemoReferenceDetector()

    init {
        val model = loadModelFile(context, modelPath)
        interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })

        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        inputShape = inputTensor.shape()
        inputType = inputTensor.dataType()
        outputShape = outputTensor.shape()
        outputType = outputTensor.dataType()

        inputWidth = when {
            inputShape.size == 4 && inputShape[1] == 3 -> inputShape[3]
            inputShape.size == 4 -> inputShape[2]
            else -> INPUT_SIZE
        }
        inputHeight = when {
            inputShape.size == 4 && inputShape[1] == 3 -> inputShape[2]
            inputShape.size == 4 -> inputShape[1]
            else -> INPUT_SIZE
        }

        Log.d(TAG, "YOLO initialized")
        Log.d(TAG, "Input=${inputShape.contentToString()} type=$inputType")
        Log.d(TAG, "Output=${outputShape.contentToString()} type=$outputType")
        Log.d(TAG, "Input image=${inputWidth}x$inputHeight classes=${LABELS.size}")
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
        val input = try {
            preprocess(letterbox.bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Preprocessing failed", e)
            recycle(letterbox.bitmap)
            return referenceDetector.detect(bitmap)
        }

        val outputTensor = interpreter.getOutputTensor(0)
        val outputBuffer = ByteBuffer
            .allocateDirect(outputTensor.numElements() * bytesPerElement(outputType))
            .order(ByteOrder.nativeOrder())

        try {
            interpreter.run(input, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "YOLO inference failed", e)
            Log.e(TAG, "Input shape=${inputShape.contentToString()} type=$inputType")
            Log.e(TAG, "Output shape=${outputShape.contentToString()} type=$outputType")
            recycle(letterbox.bitmap)
            // Door/stair demo fallback must remain available even if YOLO fails.
            return referenceDetector.detect(bitmap)
        }

        val yolo = try {
            decodeOutput(outputBuffer, letterbox)
        } catch (e: Exception) {
            Log.e(TAG, "YOLO output decoding failed", e)
            emptyList()
        }

        recycle(letterbox.bitmap)

        Log.d(TAG, "YOLO detections=${yolo.size}")
        yolo.forEachIndexed { i, d ->
            Log.d(TAG, "YOLO[$i] ${d.label} conf=${"%.3f".format(d.confidence)} box=${d.boundingBox}")
        }

        // Only door/stair use the visual reference fallback.
        val fallback = if (yolo.any { it.label == "door" || it.label == "stair" }) {
            referenceDetector.reset()
            emptyList()
        } else {
            referenceDetector.detect(bitmap)
        }

        if (fallback.isNotEmpty()) {
            Log.d(TAG, "REFERENCE FALLBACK -> ${fallback.first().label}")
        }

        return yolo + fallback.filter { ref -> yolo.none { it.label == ref.label } }
    }

    private fun bytesPerElement(type: DataType): Int = when (type) {
        DataType.FLOAT32 -> 4
        DataType.UINT8, DataType.INT8 -> 1
        else -> throw IllegalArgumentException("Unsupported output type: $type")
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val bytesPer = when (inputType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("Unsupported input type: $inputType")
        }

        val buffer = ByteBuffer
            .allocateDirect(inputWidth * inputHeight * 3 * bytesPer)
            .order(ByteOrder.nativeOrder())

        val isNchw = inputShape.size == 4 && inputShape[1] == 3

        fun red(p: Int) = (p shr 16) and 255
        fun green(p: Int) = (p shr 8) and 255
        fun blue(p: Int) = p and 255

        fun putValue(v: Int) {
            when (inputType) {
                DataType.FLOAT32 -> buffer.putFloat(v / 255f)
                DataType.UINT8 -> buffer.put(v.toByte())
                DataType.INT8 -> {
                    val q = inputTensorQuantized(v / 255f)
                    buffer.put(q.toByte())
                }
                else -> error("Unsupported input type: $inputType")
            }
        }

        if (isNchw) {
            for (p in pixels) putValue(red(p))
            for (p in pixels) putValue(green(p))
            for (p in pixels) putValue(blue(p))
        } else {
            for (p in pixels) {
                putValue(red(p))
                putValue(green(p))
                putValue(blue(p))
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun inputTensorQuantized(value: Float): Int {
        val params = interpreter.getInputTensor(0).quantizationParams()
        if (params.scale == 0f) return (value * 127f).toInt().coerceIn(-128, 127)
        return (value / params.scale + params.zeroPoint).toInt().coerceIn(-128, 127)
    }

    private fun decodeOutput(
        buffer: ByteBuffer,
        letterbox: LetterboxResult
    ): List<RawDetection> {
        if (outputType != DataType.FLOAT32) {
            Log.e(TAG, "Only FLOAT32 output is currently supported; got $outputType")
            return emptyList()
        }

        buffer.rewind()
        val values = FloatArray(outputTensorElementCount())
        buffer.asFloatBuffer().get(values)

        if (values.isEmpty()) return emptyList()

        Log.d(TAG, "Output element count=${values.size} first=${values.take(12).joinToString()}")

        return when {
            outputShape.contentEquals(intArrayOf(1, 300, NMS_OUTPUT_VALUES)) -> {
                decodeNmsRows(values, 300, letterbox)
            }
            outputShape.size == 3 && outputShape[0] == 1 && outputShape[2] == NMS_OUTPUT_VALUES -> {
                decodeNmsRows(values, outputShape[1], letterbox)
            }
            outputShape.size == 3 && outputShape[0] == 1 && outputShape[1] == NMS_OUTPUT_VALUES -> {
                decodeNmsTransposed(values, outputShape[2], letterbox)
            }
            outputShape.size == 3 && outputShape[0] == 1 -> {
                decodeRawYolo(values, outputShape[1], outputShape[2], letterbox)
            }
            else -> {
                Log.e(TAG, "Unsupported YOLO output shape=${outputShape.contentToString()}")
                emptyList()
            }
        }
    }

    private fun outputTensorElementCount(): Int = interpreter.getOutputTensor(0).numElements()

    private fun decodeNmsRows(
        values: FloatArray,
        count: Int,
        letterbox: LetterboxResult
    ): List<RawDetection> {
        val result = mutableListOf<RawDetection>()
        for (i in 0 until count) {
            val base = i * 6
            if (base + 5 >= values.size) break
            val confidence = values[base + 4]
            if (confidence < CONFIDENCE_THRESHOLD) continue
            val classId = values[base + 5].roundToClassId()
            if (classId !in LABELS.indices) continue
            val box = mapBoxToOriginalFrame(
                values[base], values[base + 1], values[base + 2], values[base + 3], letterbox
            )
            if (box.width() > 0f && box.height() > 0f) {
                result += RawDetection(LABELS[classId], box, confidence)
            }
        }
        return result
    }

    private fun decodeNmsTransposed(
        values: FloatArray,
        count: Int,
        letterbox: LetterboxResult
    ): List<RawDetection> {
        val result = mutableListOf<RawDetection>()
        for (i in 0 until count) {
            fun v(row: Int) = values[row * count + i]
            val confidence = v(4)
            if (confidence < CONFIDENCE_THRESHOLD) continue
            val classId = v(5).roundToClassId()
            if (classId !in LABELS.indices) continue
            val box = mapBoxToOriginalFrame(v(0), v(1), v(2), v(3), letterbox)
            if (box.width() > 0f && box.height() > 0f) {
                result += RawDetection(LABELS[classId], box, confidence)
            }
        }
        return result
    }

    /**
     * Supports common raw YOLO layouts:
     * [1, 4+classes, N] and [1, 5+classes, N], plus their transposed form.
     * Raw boxes are interpreted as cx, cy, w, h in model-input coordinates.
     */
    private fun decodeRawYolo(
        values: FloatArray,
        dimA: Int,
        dimB: Int,
        letterbox: LetterboxResult
    ): List<RawDetection> {
        val channelsFirst = dimA <= dimB
        val channels = if (channelsFirst) dimA else dimB
        val count = if (channelsFirst) dimB else dimA
        val classCount = LABELS.size

        val hasObjectness = channels == classCount + 5
        val hasNoObjectness = channels == classCount + 4
        if (!hasObjectness && !hasNoObjectness) {
            Log.e(TAG, "Cannot infer raw YOLO layout channels=$channels classes=$classCount")
            return emptyList()
        }

        fun value(channel: Int, index: Int): Float = if (channelsFirst) {
            values[channel * count + index]
        } else {
            values[index * channels + channel]
        }

        val result = mutableListOf<RawDetection>()
        val classStart = 4 + if (hasObjectness) 1 else 0
        for (i in 0 until count) {
            val cx = value(0, i)
            val cy = value(1, i)
            val w = value(2, i)
            val h = value(3, i)

            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until classCount) {
                val classScore = value(classStart + c, i)
                if (classScore > bestScore) {
                    bestScore = classScore
                    bestClass = c
                }
            }

            val objectness = if (hasObjectness) value(4, i) else 1f
            val confidence = objectness * bestScore
            if (confidence < CONFIDENCE_THRESHOLD || bestClass !in LABELS.indices) continue

            val maxCoord = max(max(abs(cx), abs(cy)), max(abs(w), abs(h)))
            val scale = if (maxCoord <= 2f) inputWidth.toFloat() else 1f
            val x1 = (cx - w / 2f) * scale
            val y1 = (cy - h / 2f) * scale
            val x2 = (cx + w / 2f) * scale
            val y2 = (cy + h / 2f) * scale

            val box = mapBoxToOriginalFrame(x1, y1, x2, y2, letterbox)
            if (box.width() > 0f && box.height() > 0f) {
                result += RawDetection(LABELS[bestClass], box, confidence)
            }
        }
        return result
    }

    private fun Float.roundToClassId(): Int = kotlin.math.round(this).toInt()

    private fun letterbox(bitmap: Bitmap): LetterboxResult {
        val originalWidth = bitmap.width.toFloat()
        val originalHeight = bitmap.height.toFloat()
        val scale = min(inputWidth / originalWidth, inputHeight / originalHeight)
        val resizedWidth = max(1, (originalWidth * scale).toInt())
        val resizedHeight = max(1, (originalHeight * scale).toInt())

        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val output = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val padX = (inputWidth - resizedWidth) / 2f
        val padY = (inputHeight - resizedHeight) / 2f
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== bitmap) recycle(resized)

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

    private fun mapBoxToOriginalFrame(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        letterbox: LetterboxResult
    ): RectF {
        val maxCoordinate = max(max(abs(x1), abs(x2)), max(abs(y1), abs(y2)))
        val modelX1 = if (maxCoordinate <= 1.5f) x1 * inputWidth else x1
        val modelY1 = if (maxCoordinate <= 1.5f) y1 * inputHeight else y1
        val modelX2 = if (maxCoordinate <= 1.5f) x2 * inputWidth else x2
        val modelY2 = if (maxCoordinate <= 1.5f) y2 * inputHeight else y2

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

    private fun recycle(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {}
    }

    fun close() {
        try { interpreter.close() } catch (e: Exception) { Log.e(TAG, "Error closing detector", e) }
    }
}
