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

        /*
         * ============================================================
         * YOLO26m DHWANI DRISHTI MODEL
         * ============================================================
         *
         * Input:
         *      [1, 3, 512, 512]
         *
         * Output:
         *      [1, 300, 6]
         *
         * Each detection:
         *      [x1, y1, x2, y2, confidence, classId]
         */

        private const val MODEL_NAME =
            "dhwani_drishti_17class.tflite"

        private const val INPUT_SIZE = 512

        private const val NUM_DETECTIONS = 300

        private const val VALUES_PER_DETECTION = 6

        /*
         * Initial confidence threshold.
         *
         * We can tune this after testing on the phone.
         */
        private const val CONFIDENCE_THRESHOLD = 0.35f

        /*
         * ============================================================
         * 17 MODEL CLASSES
         * ============================================================
         *
         * DO NOT CHANGE THE ORDER.
         *
         * These IDs come directly from the trained model.
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

    init {

        /*
         * Load the model from:
         *
         * app/src/main/assets/
         *
         * dhwani_drishti_17class.tflite
         */
        val model = loadModelFile(
            context,
            modelPath
        )

        /*
         * CPU configuration for the first integration.
         *
         * We will optimize inference speed after we confirm
         * detection accuracy and correctness.
         */
        val options = Interpreter.Options().apply {
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
            "Input: [1, 3, 512, 512]"
        )

        Log.d(
            TAG,
            "Output: [1, 300, 6]"
        )

        Log.d(
            TAG,
            "Classes: ${LABELS.size}"
        )

        Log.d(
            TAG,
            "========================================"
        )

        /*
         * Verify the actual model tensors.
         */
        Log.d(
            TAG,
            "Actual input shape: ${
                interpreter
                    .getInputTensor(0)
                    .shape()
                    .contentToString()
            }"
        )

        Log.d(
            TAG,
            "Actual input type: ${
                interpreter
                    .getInputTensor(0)
                    .dataType()
            }"
        )

        Log.d(
            TAG,
            "Actual output shape: ${
                interpreter
                    .getOutputTensor(0)
                    .shape()
                    .contentToString()
            }"
        )

        Log.d(
            TAG,
            "Actual output type: ${
                interpreter
                    .getOutputTensor(0)
                    .dataType()
            }"
        )
    }

    /**
     * Loads the .tflite model from app/src/main/assets.
     */
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

        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            startOffset,
            declaredLength
        )
    }

    /**
     * Runs YOLO26m detection on a camera frame.
     *
     * Input:
     *      Any Bitmap size.
     *
     * Processing:
     *      Letterbox -> 512x512 -> NCHW float32
     *
     * Output:
     *      RawDetection objects with normalized
     *      bounding boxes relative to the original
     *      camera frame.
     */
    fun detect(
        bitmap: Bitmap
    ): List<RawDetection> {

        if (
            bitmap.width <= 0 ||
            bitmap.height <= 0
        ) {
            return emptyList()
        }

        /*
         * ============================================================
         * STEP 1
         * LETTERBOX IMAGE
         * ============================================================
         *
         * Preserve the camera image aspect ratio.
         *
         * We do NOT simply stretch the image to 512x512.
         */
        val letterboxResult =
            letterbox(bitmap)

        /*
         * ============================================================
         * STEP 2
         * PREPROCESS
         * ============================================================
         *
         * Model requires:
         *
         * [1, 3, 512, 512]
         *
         * Therefore:
         *
         * R plane
         * G plane
         * B plane
         *
         * rather than:
         *
         * RGB RGB RGB...
         */
        val inputBuffer =
            preprocess(
                letterboxResult.bitmap
            )

        /*
         * ============================================================
         * STEP 3
         * OUTPUT BUFFER
         * ============================================================
         *
         * Model output:
         *
         * [1, 300, 6]
         */
        val output =
            Array(1) {
                Array(NUM_DETECTIONS) {
                    FloatArray(
                        VALUES_PER_DETECTION
                    )
                }
            }

        /*
         * ============================================================
         * STEP 4
         * RUN INFERENCE
         * ============================================================
         */
        try {

            interpreter.run(
                inputBuffer,
                output
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "YOLO inference failed",
                e
            )

            return emptyList()
        }

        /*
         * Diagnostic output.
         */
        Log.d(
            TAG,
            "Raw output row 0: ${
                output[0][0].joinToString()
            }"
        )

        val detections =
            mutableListOf<RawDetection>()

        /*
         * ============================================================
         * STEP 5
         * DECODE DETECTIONS
         * ============================================================
         */
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

            /*
             * Confidence filter.
             */
            if (
                confidence <
                CONFIDENCE_THRESHOLD
            ) {
                continue
            }

            /*
             * Protect against invalid class IDs.
             */
            if (
                classId !in LABELS.indices
            ) {

                Log.w(
                    TAG,
                    "Invalid class ID: $classId"
                )

                continue
            }

            /*
             * Convert model coordinates
             * back to the original camera frame.
             */
            val boundingBox =
                mapBoxToOriginalFrame(
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    letterbox = letterboxResult
                )

            /*
             * Ignore invalid boxes.
             */
            if (
                boundingBox.width() <= 0f ||
                boundingBox.height() <= 0f
            ) {
                continue
            }

            val label =
                LABELS[classId]

            detections.add(
                RawDetection(
                    label = label,
                    boundingBox = boundingBox,
                    confidence = confidence
                )
            )
        }

        /*
         * ============================================================
         * LOG DETECTIONS
         * ============================================================
         */
        if (
            detections.isNotEmpty()
        ) {

            Log.d(
                TAG,
                "========================================"
            )

            Log.d(
                TAG,
                "DETECTIONS: ${detections.size}"
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

    /**
     * ================================================================
     * LETTERBOX
     * ================================================================
     *
     * Resizes the original image while preserving its aspect ratio.
     *
     * The remaining area is filled with YOLO-style gray padding.
     */
    private fun letterbox(
        bitmap: Bitmap
    ): LetterboxResult {

        val originalWidth =
            bitmap.width.toFloat()

        val originalHeight =
            bitmap.height.toFloat()

        /*
         * Calculate scale while preserving aspect ratio.
         */
        val scale =
            min(
                INPUT_SIZE / originalWidth,
                INPUT_SIZE / originalHeight
            )

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

        val resizedBitmap =
            Bitmap.createScaledBitmap(
                bitmap,
                resizedWidth,
                resizedHeight,
                true
            )

        /*
         * Create 512x512 output.
         */
        val outputBitmap =
            Bitmap.createBitmap(
                INPUT_SIZE,
                INPUT_SIZE,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(outputBitmap)

        /*
         * YOLO-style padding.
         */
        canvas.drawColor(
            Color.rgb(
                114,
                114,
                114
            )
        )

        /*
         * Center resized image.
         */
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

    /**
     * Stores information required to map bounding boxes
     * from the 512x512 letterboxed image back to the
     * original camera image.
     */
    private data class LetterboxResult(

        val bitmap: Bitmap,

        val scale: Float,

        val padX: Float,

        val padY: Float,

        val originalWidth: Int,

        val originalHeight: Int
    )

    /**
     * ================================================================
     * PREPROCESS
     * ================================================================
     *
     * Converts:
     *
     * Bitmap
     *
     * into:
     *
     * [1, 3, 512, 512]
     *
     * float32 tensor.
     *
     * Values:
     *
     * 0..255
     *
     * become:
     *
     * 0..1
     */
    private fun preprocess(
        bitmap: Bitmap
    ): ByteBuffer {

        val pixelCount =
            INPUT_SIZE * INPUT_SIZE

        /*
         * 3 channels
         * 4 bytes per float
         */
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

        /*
         * ============================================================
         * RED CHANNEL
         * ============================================================
         */
        for (pixel in pixels) {

            buffer.putFloat(
                ((pixel shr 16) and 0xFF) /
                        255.0f
            )
        }

        /*
         * ============================================================
         * GREEN CHANNEL
         * ============================================================
         */
        for (pixel in pixels) {

            buffer.putFloat(
                ((pixel shr 8) and 0xFF) /
                        255.0f
            )
        }

        /*
         * ============================================================
         * BLUE CHANNEL
         * ============================================================
         */
        for (pixel in pixels) {

            buffer.putFloat(
                (pixel and 0xFF) /
                        255.0f
            )
        }

        buffer.rewind()

        return buffer
    }

    /**
     * ================================================================
     * MAP BOUNDING BOX
     * ================================================================
     *
     * YOLO gives coordinates relative to the 512x512
     * letterboxed model input.
     *
     * We convert them back to normalized coordinates
     * relative to the ORIGINAL camera frame.
     *
     * Result:
     *
     * x1 = 0..1
     * y1 = 0..1
     * x2 = 0..1
     * y2 = 0..1
     */
    private fun mapBoxToOriginalFrame(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        letterbox: LetterboxResult
    ): RectF {

        /*
         * Remove letterbox padding.
         */
        val originalX1 =
            (x1 - letterbox.padX) /
                    letterbox.scale

        val originalY1 =
            (y1 - letterbox.padY) /
                    letterbox.scale

        val originalX2 =
            (x2 - letterbox.padX) /
                    letterbox.scale

        val originalY2 =
            (y2 - letterbox.padY) /
                    letterbox.scale

        /*
         * Convert to normalized coordinates.
         */
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

        /*
         * Ensure correct ordering.
         */
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

    /**
     * Releases the TFLite interpreter.
     */
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
                "Error closing interpreter",
                e
            )
        }
    }
}