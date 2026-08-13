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

class ObjectDetector(context: Context, modelPath: String = "yolov8n_fp16.tflite") {

    companion object {
        private const val TAG = "ObjectDetector"
        private const val INPUT_SIZE = 320
        private const val NUM_DETECTIONS = 20
        private const val CONFIDENCE_THRESHOLD = 0.4f

        // Standard COCO 80-class order (index 0 = person)
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

    private var interpreter: Interpreter

    init {
        val model = loadModelFile(context, modelPath)
        val options = Interpreter.Options().apply {
            numThreads = 4
            // GPU delegate intentionally NOT used here — see project notes on
            // Mali gralloc instability. Revisit once detection is verified on CPU.
        }
        interpreter = Interpreter(model, options)
        Log.d(TAG, "Interpreter loaded: $modelPath")
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Runs detection on a single frame. Bitmap can be any size — it's resized
     * internally to 320x320 to match the model's fixed input. Returns
     * [RawDetection]s with boundingBox normalized 0..1 relative to the
     * original frame, ready for [com.dhwanidrishti.app.processing.fuseDetectionsWithDepth].
     */
    fun detect(bitmap: Bitmap): List<RawDetection> {
        val inputBuffer = preprocess(bitmap)

        // Output shape confirmed via inspect_yolo.py: [1, 20, 6]
        // Each row: [x1, y1, x2, y2, confidence, classId]
        val output = Array(1) { Array(NUM_DETECTIONS) { FloatArray(6) } }
        interpreter.run(inputBuffer, output)

        // One-time diagnostic: log the raw first row so we can confirm whether
        // coordinates are normalized (0..1) or pixel-space (0..320).
        Log.d(TAG, "raw row0: ${output[0][0].joinToString()}")

        val detections = mutableListOf<RawDetection>()
        for (i in 0 until NUM_DETECTIONS) {
            val row = output[0][i]
            val confidence = row[4]
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val classId = row[5].toInt()
            val label = COCO_LABELS.getOrElse(classId) { "unknown" }
            val box = normalizeBox(row[0], row[1], row[2], row[3])

            detections.add(
                RawDetection(
                    label = label,
                    boundingBox = box,
                    confidence = confidence,
                )
            )
        }

        if (detections.isNotEmpty()) {
            Log.d(TAG, "Detections: " + detections.joinToString {
                "${it.label} ${"%.2f".format(it.confidence)}"
            })
        }

        return detections
    }

    /**
     * Handles both possible coordinate conventions from the export:
     * - already normalized (0f..1f), or
     * - pixel-space relative to the 320x320 input.
     * Detected automatically from the raw magnitude — no guessing needed at call sites.
     */
    private fun normalizeBox(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val looksNormalized = x1 <= 1.5f && y1 <= 1.5f && x2 <= 1.5f && y2 <= 1.5f
        return if (looksNormalized) {
            RectF(x1, y1, x2, y2)
        } else {
            RectF(x1 / INPUT_SIZE, y1 / INPUT_SIZE, x2 / INPUT_SIZE, y2 / INPUT_SIZE)
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }
        buffer.rewind()
        return buffer
    }

    fun close() {
        interpreter.close()
    }
}