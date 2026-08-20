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

class ObjectDetector(
    context: Context,
    modelPath: String = MODEL_NAME
) {

    companion object {

        private const val TAG = "ObjectDetector"

        // ============================================================
        // YOLO26m DHWANI DRISHTI MODEL
        // ============================================================

        /*
         * Input:
         *
         * [1, 3, 512, 512]
         *
         * Output:
         *
         * [1, 300, 6]
         *
         * Each detection:
         *
         * [x1, y1, x2, y2, confidence, classId]
         */

        private const val MODEL_NAME =
            "dhwani_drishti_17class.tflite"

        private const val INPUT_SIZE = 512

        private const val NUM_DETECTIONS = 300

        private const val VALUES_PER_DETECTION = 6

        private const val CONFIDENCE_THRESHOLD = 0.35f

        // ============================================================
        // 17 CUSTOM CLASSES
        // ============================================================

        /*
         * IMPORTANT:
         *
         * The order MUST exactly match the trained model.
         */

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

    // ============================================================
    // INITIALIZATION
    // ============================================================

    init {

        val model = loadModelFile(
            context = context,
            modelPath = modelPath
        )

        val options = Interpreter.Options().apply {

            /*
             * CPU first.
             *
             * We will optimize later after detection is
             * confirmed to work correctly.
             */
            numThreads = 4
        }

        interpreter = Interpreter(
            model,
            options
        )

        Log.d(
            TAG,
            "========================================"
        )

        Log.d(
            TAG,
            "YOLO26m ObjectDetector initialized"
        )

        Log.d(
            TAG,
            "Model: $modelPath"
        )

        Log.d(
            TAG,
            "Expected input: [1, 3, 512, 512]"
        )

        Log.d(
            TAG,
            "Expected output: [1, 300, 6]"
        )

        Log.d(
            TAG,
            "Classes: ${LABELS.size}"
        )

        Log.d(
            TAG,
            "========================================"
        )

        // ========================================================
        // VERIFY ACTUAL MODEL TENSORS
        // ========================================================

        try {

            val inputTensor =
                interpreter.getInputTensor(0)

            val outputTensor =
                interpreter.getOutputTensor(0)

            Log.d(
                TAG,
                "Actual input shape: ${
                    inputTensor.shape().contentToString()
                }"
            )

            Log.d(
                TAG,
                "Actual input type: ${
                    inputTensor.dataType()
                }"
            )

            Log.d(
                TAG,
                "Actual output shape: ${
                    outputTensor.shape().contentToString()
                }"
            )

            Log.d(
                TAG,
                "Actual output type: ${
                    outputTensor.dataType()
                }"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to inspect model tensors",
                e
            )
        }
    }

    // ============================================================
    // LOAD MODEL
    // ============================================================

    private fun loadModelFile(
        context: Context,
        modelPath: String
    ): MappedByteBuffer {

        val assetFileDescriptor =
            context.assets.openFd(modelPath)

        val inputStream =
            FileInputStream(
                assetFileDescriptor.fileDescriptor
            )

        val fileChannel =
            inputStream.channel

        val startOffset =
            assetFileDescriptor.startOffset

        val declaredLength =
            assetFileDescriptor.declaredLength

        return try {

            fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
            )

        } finally {

            /*
             * The mapped buffer remains usable after the
             * underlying stream/channel is closed.
             */

            try {
                fileChannel.close()
            } catch (_: Exception) {
            }

            try {
                inputStream.close()
            } catch (_: Exception) {
            }

            try {
                assetFileDescriptor.close()
            } catch (_: Exception) {
            }
        }
    }

    // ============================================================
    // DETECTION
    // ============================================================

    /**
     * Runs YOLO26m detection on one camera frame.
     *
     * Input:
     *
     * Any Bitmap size.
     *
     * Processing:
     *
     * Original frame
     *      ↓
     * Letterbox
     *      ↓
     * 512 × 512
     *      ↓
     * NCHW float32
     *      ↓
     * YOLO26
     *
     * Output:
     *
     * RawDetection objects with bounding boxes normalized
     * to the ORIGINAL camera frame.
     */
    fun detect(
        bitmap: Bitmap
    ): List<RawDetection> {

        if (
            bitmap.width <= 0 ||
            bitmap.height <= 0
        ) {
            Log.w(
                TAG,
                "Invalid bitmap dimensions"
            )

            return emptyList()
        }

        // ========================================================
        // STEP 1: LETTERBOX
        // ========================================================

        val letterboxResult =
            letterbox(bitmap)

        // ========================================================
        // STEP 2: PREPROCESS
        // ========================================================

        val inputBuffer =
            preprocess(
                letterboxResult.bitmap
            )

        // ========================================================
        // STEP 3: OUTPUT BUFFER
        // ========================================================

        val output =
            Array(1) {

                Array(NUM_DETECTIONS) {

                    FloatArray(
                        VALUES_PER_DETECTION
                    )
                }
            }

        // ========================================================
        // STEP 4: RUN YOLO26
        // ========================================================

        try {

            interpreter.run(
                inputBuffer,
                output
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "YOLO26 inference failed",
                e
            )

            return emptyList()
        }

        // ========================================================
        // DIAGNOSTIC OUTPUT
        // ========================================================

        Log.d(
            TAG,
            "Raw output row 0: ${
                output[0][0].joinToString()
            }"
        )

        val detections =
            mutableListOf<RawDetection>()

        // ========================================================
        // STEP 5: DECODE
        // ========================================================

        for (
        i in 0 until NUM_DETECTIONS
        ) {

            val row =
                output[0][i]

            val x1 =
                row[0]

            val y1 =
                row[1]

            val x2 =
                row[2]

            val y2 =
                row[3]

            val confidence =
                row[4]

            val classId =
                row[5].toInt()

            // ----------------------------------------------------
            // CONFIDENCE FILTER
            // ----------------------------------------------------

            if (
                confidence <
                CONFIDENCE_THRESHOLD
            ) {
                continue
            }

            // ----------------------------------------------------
            // CLASS VALIDATION
            // ----------------------------------------------------

            if (
                classId !in LABELS.indices
            ) {

                Log.w(
                    TAG,
                    "Invalid class ID: $classId"
                )

                continue
            }

            // ----------------------------------------------------
            // MAP BOX
            // ----------------------------------------------------

            val boundingBox =
                mapBoxToOriginalFrame(
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    letterbox = letterboxResult
                )

            // ----------------------------------------------------
            // INVALID BOX CHECK
            // ----------------------------------------------------

            if (
                boundingBox.width() <= 0f ||
                boundingBox.height() <= 0f
            ) {
                continue
            }

            // ----------------------------------------------------
            // LABEL
            // ----------------------------------------------------

            val label =
                LABELS[classId]

            // ----------------------------------------------------
            // CREATE DETECTION
            // ----------------------------------------------------

            detections.add(
                RawDetection(
                    label = label,
                    boundingBox = boundingBox,
                    confidence = confidence
                )
            )
        }

        // ========================================================
        // LOG RESULTS
        // ========================================================

        if (
            detections.isNotEmpty()
        ) {

            Log.d(
                TAG,
                "========================================"
            )

            Log.d(
                TAG,
                "YOLO26 DETECTIONS: ${detections.size}"
            )

            detections.forEach { detection ->

                Log.d(
                    TAG,
                    "${detection.label} " +
                            "confidence=${
                                "%.2f".format(
                                    detection.confidence
                                )
                            } " +
                            "box=${
                                detection.boundingBox
                            }"
                )
            }

            Log.d(
                TAG,
                "========================================"
            )

        } else {

            Log.d(
                TAG,
                "No detections above threshold"
            )
        }

        return detections
    }

    // ============================================================
    // LETTERBOX
    // ============================================================

    /**
     * Preserves the camera frame aspect ratio.
     *
     * Example:
     *
     * 1280 × 720
     *
     * becomes something like:
     *
     * 512 × 288
     *
     * inside:
     *
     * 512 × 512
     *
     * with gray padding.
     */
    private fun letterbox(
        bitmap: Bitmap
    ): LetterboxResult {

        val originalWidth =
            bitmap.width.toFloat()

        val originalHeight =
            bitmap.height.toFloat()

        // --------------------------------------------------------
        // SCALE
        // --------------------------------------------------------

        val scale =
            min(
                INPUT_SIZE / originalWidth,
                INPUT_SIZE / originalHeight
            )

        // --------------------------------------------------------
        // RESIZED DIMENSIONS
        // --------------------------------------------------------

        val resizedWidth =
            max(
                1,
                (originalWidth * scale).toInt()
            )

        val resizedHeight =
            max(
                1,
                (originalHeight * scale).toInt()
            )

        // --------------------------------------------------------
        // RESIZE
        // --------------------------------------------------------

        val resizedBitmap =
            Bitmap.createScaledBitmap(
                bitmap,
                resizedWidth,
                resizedHeight,
                true
            )

        // --------------------------------------------------------
        // CREATE 512 × 512 CANVAS
        // --------------------------------------------------------

        val outputBitmap =
            Bitmap.createBitmap(
                INPUT_SIZE,
                INPUT_SIZE,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(outputBitmap)

        // --------------------------------------------------------
        // YOLO PADDING
        // --------------------------------------------------------

        canvas.drawColor(
            Color.rgb(
                114,
                114,
                114
            )
        )

        // --------------------------------------------------------
        // CENTER IMAGE
        // --------------------------------------------------------

        val padX =
            (INPUT_SIZE - resizedWidth) / 2f

        val padY =
            (INPUT_SIZE - resizedHeight) / 2f

        canvas.drawBitmap(
            resizedBitmap,
            padX,
            padY,
            null
        )

        return LetterboxResult(
            bitmap = outputBitmap,
            scale = scale,
            padX = padX,
            padY = padY,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height
        )
    }

    // ============================================================
    // LETTERBOX RESULT
    // ============================================================

    private data class LetterboxResult(

        val bitmap: Bitmap,

        val scale: Float,

        val padX: Float,

        val padY: Float,

        val originalWidth: Int,

        val originalHeight: Int
    )

    // ============================================================
    // PREPROCESS
    // ============================================================

    /**
     * Converts:
     *
     * Bitmap
     *      ↓
     * [1, 3, 512, 512]
     *
     * Layout:
     *
     * RRRRR...
     * GGGGG...
     * BBBBB...
     *
     * This is NCHW.
     */
    private fun preprocess(
        bitmap: Bitmap
    ): ByteBuffer {

        val pixelCount =
            INPUT_SIZE * INPUT_SIZE

        val buffer =
            ByteBuffer
                .allocateDirect(
                    pixelCount *
                            3 *
                            4
                )
                .order(
                    ByteOrder.nativeOrder()
                )

        val pixels =
            IntArray(
                pixelCount
            )

        bitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        // ========================================================
        // RED
        // ========================================================

        for (pixel in pixels) {

            buffer.putFloat(
                ((pixel shr 16) and 0xFF) /
                        255.0f
            )
        }

        // ========================================================
        // GREEN
        // ========================================================

        for (pixel in pixels) {

            buffer.putFloat(
                ((pixel shr 8) and 0xFF) /
                        255.0f
            )
        }

        // ========================================================
        // BLUE
        // ========================================================

        for (pixel in pixels) {

            buffer.putFloat(
                (pixel and 0xFF) /
                        255.0f
            )
        }

        buffer.rewind()

        return buffer
    }

    // ============================================================
    // MAP YOLO BOX TO ORIGINAL CAMERA FRAME
    // ============================================================

    /**
     * YOLO26 TFLite end-to-end output:
     *
     * [x1, y1, x2, y2, confidence, classId]
     *
     * For the TFLite export, box coordinates are normalized
     * relative to the 512 × 512 model input.
     *
     * Therefore:
     *
     * normalized model coordinate
     *          ↓
     * 512 × 512 coordinate
     *          ↓
     * remove letterbox padding
     *          ↓
     * divide by resize scale
     *          ↓
     * original camera frame
     *          ↓
     * normalize to 0..1
     */
    private fun mapBoxToOriginalFrame(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        letterbox: LetterboxResult
    ): RectF {

        // ========================================================
        // STEP 1
        // NORMALIZED → 512 PIXELS
        // ========================================================

        val modelX1 = x1
        val modelY1 = y1
        val modelX2 = x2
        val modelY2 = y2

        // ========================================================
        // STEP 2
        // REMOVE LETTERBOX PADDING
        // ========================================================

        val originalX1 =
            (modelX1 - letterbox.padX) /
                    letterbox.scale

        val originalY1 =
            (modelY1 - letterbox.padY) /
                    letterbox.scale

        val originalX2 =
            (modelX2 - letterbox.padX) /
                    letterbox.scale

        val originalY2 =
            (modelY2 - letterbox.padY) /
                    letterbox.scale

        // ========================================================
        // STEP 3
        // ORIGINAL PIXELS → NORMALIZED 0..1
        // ========================================================

        val normalizedX1 =
            (
                    originalX1 /
                            letterbox.originalWidth
                    ).coerceIn(
                    0f,
                    1f
                )

        val normalizedY1 =
            (
                    originalY1 /
                            letterbox.originalHeight
                    ).coerceIn(
                    0f,
                    1f
                )

        val normalizedX2 =
            (
                    originalX2 /
                            letterbox.originalWidth
                    ).coerceIn(
                    0f,
                    1f
                )

        val normalizedY2 =
            (
                    originalY2 /
                            letterbox.originalHeight
                    ).coerceIn(
                    0f,
                    1f
                )

        // ========================================================
        // STEP 4
        // GUARANTEE CORRECT ORDER
        // ========================================================

        return RectF(

            min(
                normalizedX1,
                normalizedX2
            ),

            min(
                normalizedY1,
                normalizedY2
            ),

            max(
                normalizedX1,
                normalizedX2
            ),

            max(
                normalizedY1,
                normalizedY2
            )
        )
    }

    // ============================================================
    // CLOSE
    // ============================================================

    fun close() {

        try {

            interpreter.close()

            Log.d(
                TAG,
                "YOLO26m interpreter closed"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error closing YOLO26m interpreter",
                e
            )
        }
    }
}