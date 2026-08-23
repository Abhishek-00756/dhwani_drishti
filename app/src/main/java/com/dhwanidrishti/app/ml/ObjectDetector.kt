package com.dhwanidrishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.dhwanidrishti.app.processing.RawDetection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Local YOLO detector used by Narrated mode.
 *
 * IMPORTANT: this intentionally restores the previously working 80-class
 * YOLOv8 model. Hyper mode does not use this detector.
 *
 * Door/stair/pothole are not COCO classes, so they are handled separately by
 * demo reference detectors using the supplied reference images.
 */
class ObjectDetector(context: Context, modelPath: String = "yolov8n_fp16.tflite") {

    companion object {
        private const val TAG = "ObjectDetector"
        private const val INPUT_SIZE = 320
        private const val NUM_DETECTIONS = 20
        private const val CONFIDENCE_THRESHOLD = 0.4f
        private const val WORKING_MODEL = "yolov8n_fp16.tflite"

        val COCO_LABELS = listOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
            "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
            "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
            "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
            "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
            "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
            "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
            "remote","keyboard","cell phone","microwave","oven","toaster","sink",
            "refrigerator","book","clock","vase","scissors","teddy bear","hair drier",
            "toothbrush"
        )
    }

    private val interpreter: Interpreter
    private val referenceDetector = DemoReferenceDetector()
    private val potholeReferenceDetector = PotholeReferenceDetector()

    init {
        // Narrated mode must use the old working 80-class YOLOv8 model.
        // Ignore the caller's legacy 17-class path so a stale ModeBEngine
        // configuration cannot break inference.
        val model = loadModelFile(context, WORKING_MODEL)
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(model, options)
        Log.d(TAG, "Narrated YOLOv8 initialized: $WORKING_MODEL (requested=$modelPath)")
        Log.d(TAG, "Input=${interpreter.getInputTensor(0).shape().contentToString()} ${interpreter.getInputTensor(0).dataType()}")
        Log.d(TAG, "Output=${interpreter.getOutputTensor(0).shape().contentToString()} ${interpreter.getOutputTensor(0).dataType()}")
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): List<RawDetection> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val inputBuffer = preprocess(bitmap)
        val output = Array(1) { Array(NUM_DETECTIONS) { FloatArray(6) } }

        try {
            interpreter.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e(TAG, "YOLOv8 inference failed", e)
            return referenceDetections(bitmap)
        }

        Log.d(TAG, "raw row0: ${output[0][0].joinToString()}")

        val detections = mutableListOf<RawDetection>()
        for (i in 0 until NUM_DETECTIONS) {
            val row = output[0][i]
            val confidence = row[4]
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val classId = row[5].toInt()
            if (classId !in COCO_LABELS.indices) {
                Log.w(TAG, "Ignoring invalid COCO classId=$classId confidence=$confidence")
                continue
            }

            val label = COCO_LABELS[classId]
            val box = normalizeBox(row[0], row[1], row[2], row[3])
            if (box.width() <= 0f || box.height() <= 0f) continue

            detections.add(
                RawDetection(
                    label = label,
                    boundingBox = box,
                    confidence = confidence,
                )
            )
        }

        Log.d(TAG, "COCO detections: " + if (detections.isEmpty()) "none" else detections.joinToString { "${it.label} ${"%.2f".format(it.confidence)}" })

        val reference = referenceDetections(bitmap)
        for (detection in reference) {
            if (detections.none { it.label == detection.label }) {
                detections.add(detection)
                Log.d(TAG, "REFERENCE detection: ${detection.label}")
            }
        }

        return detections
    }

    private fun referenceDetections(bitmap: Bitmap): List<RawDetection> {
        val detections = mutableListOf<RawDetection>()

        try {
            // IMPORTANT: call each reference matcher once per camera frame.
            // This preserves their two-consecutive-frame confirmation logic.
            detections += referenceDetector.detect(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Door/stair reference detector failed; keeping COCO detections", e)
            referenceDetector.reset()
        }

        try {
            detections += potholeReferenceDetector.detect(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Pothole reference detector failed; keeping other detections", e)
            potholeReferenceDetector.reset()
        }

        return detections.distinctBy { it.label }
    }

    private fun normalizeBox(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val looksNormalized = x1 <= 1.5f && y1 <= 1.5f && x2 <= 1.5f && y2 <= 1.5f
        return if (looksNormalized) {
            RectF(x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f), x2.coerceIn(0f, 1f), y2.coerceIn(0f, 1f))
        } else {
            RectF(
                (x1 / INPUT_SIZE).coerceIn(0f, 1f),
                (y1 / INPUT_SIZE).coerceIn(0f, 1f),
                (x2 / INPUT_SIZE).coerceIn(0f, 1f),
                (y2 / INPUT_SIZE).coerceIn(0f, 1f)
            )
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        buffer.rewind()
        if (resized !== bitmap) {
            try { resized.recycle() } catch (_: Exception) {}
        }
        return buffer
    }

    fun close() {
        try { interpreter.close() } catch (e: Exception) { Log.e(TAG, "Error closing YOLOv8", e) }
    }
}
