package com.dhwanidrishti.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.dhwanidrishti.app.processing.RawDetection
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class ObjectDetector(context: Context) {

    companion object {
        private const val TAG = "DhwaniDrishti.Detect"

        /*
         * Start with a low threshold while testing.
         * Once detection works, you can increase this to 0.30f or 0.40f.
         */
        private const val CONFIDENCE_THRESHOLD = 0.15f

        private const val NMS_THRESHOLD = 0.45f

        private const val RGB_CHANNELS = 3

        /*
         * Standard YOLOv8 COCO labels.
         */
        private val COCO_LABELS = arrayOf(
            "person",
            "bicycle",
            "car",
            "motorcycle",
            "airplane",
            "bus",
            "train",
            "truck",
            "boat",
            "traffic light",
            "fire hydrant",
            "stop sign",
            "parking meter",
            "bench",
            "bird",
            "cat",
            "dog",
            "horse",
            "sheep",
            "cow",
            "elephant",
            "bear",
            "zebra",
            "giraffe",
            "backpack",
            "umbrella",
            "handbag",
            "tie",
            "suitcase",
            "frisbee",
            "skis",
            "snowboard",
            "sports ball",
            "kite",
            "baseball bat",
            "baseball glove",
            "skateboard",
            "surfboard",
            "tennis racket",
            "bottle",
            "wine glass",
            "cup",
            "fork",
            "knife",
            "spoon",
            "bowl",
            "banana",
            "apple",
            "sandwich",
            "orange",
            "broccoli",
            "carrot",
            "hot dog",
            "pizza",
            "donut",
            "cake",
            "chair",
            "couch",
            "potted plant",
            "bed",
            "dining table",
            "toilet",
            "tv",
            "laptop",
            "mouse",
            "remote",
            "keyboard",
            "cell phone",
            "microwave",
            "oven",
            "toaster",
            "sink",
            "refrigerator",
            "book",
            "clock",
            "vase",
            "scissors",
            "teddy bear",
            "hair drier",
            "toothbrush"
        )
    }

    private val interpreter: Interpreter
    private val gpuDelegate: Delegate?

    private val inputWidth: Int
    private val inputHeight: Int

    private val inputIsNCHW: Boolean

    private val outputShape: IntArray
    private val outputBuffer: Array<Array<FloatArray>>

    private val inputBuffer: ByteBuffer

    private val resizedBitmap: Bitmap
    private val resizeCanvas: Canvas
    private val resizePaint: Paint

    /*
     * Information required to undo letterboxing.
     */
    private var sourceWidth = 1
    private var sourceHeight = 1

    private var letterboxScale = 1f
    private var letterboxPadX = 0f
    private var letterboxPadY = 0f

    init {

        /*
         * ------------------------------------------------------------
         * GPU
         * ------------------------------------------------------------
         */
        gpuDelegate = buildGpuDelegate()

        val options = Interpreter.Options().apply {

            if (gpuDelegate != null) {
                addDelegate(gpuDelegate)
            }

            setNumThreads(4)
        }

        /*
         * ------------------------------------------------------------
         * LOAD MODEL
         * ------------------------------------------------------------
         */
        val model = FileUtil.loadMappedFile(
            context,
            "yolov8n_fp16.tflite"
        )

        interpreter = Interpreter(model, options)

        /*
         * ------------------------------------------------------------
         * INPUT
         * ------------------------------------------------------------
         */
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()

        Log.i(
            TAG,
            "INPUT shape = ${inputShape.contentToString()}"
        )

        Log.i(
            TAG,
            "INPUT type = ${inputTensor.dataType()}"
        )

        /*
         * Most Ultralytics TFLite models are:
         *
         * [1, 640, 640, 3]
         *
         * But we also support:
         *
         * [1, 3, 640, 640]
         */
        if (inputShape.size != 4) {
            throw IllegalStateException(
                "Unsupported YOLO input shape: ${inputShape.contentToString()}"
            )
        }

        inputIsNCHW =
            inputShape[1] == RGB_CHANNELS

        if (inputIsNCHW) {
            inputHeight = inputShape[2]
            inputWidth = inputShape[3]
        } else {
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
        }

        /*
         * ------------------------------------------------------------
         * OUTPUT
         * ------------------------------------------------------------
         */
        val outputTensor = interpreter.getOutputTensor(0)

        outputShape = outputTensor.shape()

        Log.i(
            TAG,
            "OUTPUT shape = ${outputShape.contentToString()}"
        )

        Log.i(
            TAG,
            "OUTPUT type = ${outputTensor.dataType()}"
        )

        if (outputShape.size != 3) {
            throw IllegalStateException(
                "Unsupported YOLO output shape: ${outputShape.contentToString()}"
            )
        }

        val outputA = outputShape[1]
        val outputB = outputShape[2]

        outputBuffer =
            Array(1) {
                Array(outputA) {
                    FloatArray(outputB)
                }
            }

        /*
         * ------------------------------------------------------------
         * INPUT BUFFER
         * ------------------------------------------------------------
         *
         * FLOAT32 RGB values.
         */
        inputBuffer =
            ByteBuffer.allocateDirect(
                inputWidth *
                        inputHeight *
                        RGB_CHANNELS *
                        4
            ).apply {
                order(ByteOrder.nativeOrder())
            }

        /*
         * ------------------------------------------------------------
         * BITMAP
         * ------------------------------------------------------------
         */
        resizedBitmap = Bitmap.createBitmap(
            inputWidth,
            inputHeight,
            Bitmap.Config.ARGB_8888
        )

        resizeCanvas = Canvas(resizedBitmap)

        resizePaint = Paint(
            Paint.ANTI_ALIAS_FLAG or
                    Paint.FILTER_BITMAP_FLAG
        )

        Log.i(
            TAG,
            "Detector initialized: ${inputWidth}x${inputHeight}"
        )

        Log.i(
            TAG,
            "Input layout = ${
                if (inputIsNCHW) "NCHW" else "NHWC"
            }"
        )

        Log.i(
            TAG,
            "Output shape = ${outputShape.contentToString()}"
        )

        Log.i(
            TAG,
            if (gpuDelegate != null)
                "GPU delegate enabled"
            else
                "GPU delegate unavailable - CPU mode"
        )
    }

    /*
     * ================================================================
     * PUBLIC DETECT METHOD
     * ================================================================
     */
    fun detect(source: Bitmap): List<RawDetection> {

        if (source.width <= 0 || source.height <= 0) {
            return emptyList()
        }

        /*
         * 1. Letterbox image.
         */
        letterbox(source)

        /*
         * 2. Convert bitmap -> FLOAT32 RGB.
         */
        fillInputBuffer()

        /*
         * 3. Run YOLO.
         */
        try {

            interpreter.run(
                inputBuffer,
                outputBuffer
            )

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "YOLO inference failed",
                t
            )

            return emptyList()
        }

        /*
         * 4. Parse output.
         */
        return parseOutput()
    }

    /*
     * ================================================================
     * LETTERBOX
     * ================================================================
     */
    private fun letterbox(source: Bitmap) {

        sourceWidth = source.width
        sourceHeight = source.height

        val scale =
            min(
                inputWidth.toFloat() / sourceWidth.toFloat(),
                inputHeight.toFloat() / sourceHeight.toFloat()
            )

        val resizedWidth =
            max(
                1,
                (sourceWidth * scale).toInt()
            )

        val resizedHeight =
            max(
                1,
                (sourceHeight * scale).toInt()
            )

        letterboxScale = scale

        letterboxPadX =
            (inputWidth - resizedWidth) / 2f

        letterboxPadY =
            (inputHeight - resizedHeight) / 2f

        /*
         * IMPORTANT:
         *
         * Clear previous frame.
         *
         * YOLO/Ultralytics letterbox commonly uses
         * RGB 114,114,114 padding.
         */
        resizeCanvas.drawColor(
            Color.rgb(114, 114, 114)
        )

        resizeCanvas.drawBitmap(
            source,
            null,
            Rect(
                letterboxPadX.toInt(),
                letterboxPadY.toInt(),
                letterboxPadX.toInt() + resizedWidth,
                letterboxPadY.toInt() + resizedHeight
            ),
            resizePaint
        )
    }

    /*
     * ================================================================
     * BITMAP -> MODEL INPUT
     * ================================================================
     */
    private fun fillInputBuffer() {

        val pixels =
            IntArray(
                inputWidth * inputHeight
            )

        resizedBitmap.getPixels(
            pixels,
            0,
            inputWidth,
            0,
            0,
            inputWidth,
            inputHeight
        )

        inputBuffer.rewind()

        if (!inputIsNCHW) {

            /*
             * NHWC
             *
             * [height][width][RGB]
             */
            for (pixel in pixels) {

                val r =
                    ((pixel shr 16) and 0xFF) / 255f

                val g =
                    ((pixel shr 8) and 0xFF) / 255f

                val b =
                    (pixel and 0xFF) / 255f

                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }

        } else {

            /*
             * NCHW
             *
             * [R plane]
             * [G plane]
             * [B plane]
             */

            for (pixel in pixels) {

                val r =
                    ((pixel shr 16) and 0xFF) / 255f

                inputBuffer.putFloat(r)
            }

            for (pixel in pixels) {

                val g =
                    ((pixel shr 8) and 0xFF) / 255f

                inputBuffer.putFloat(g)
            }

            for (pixel in pixels) {

                val b =
                    (pixel and 0xFF) / 255f

                inputBuffer.putFloat(b)
            }
        }

        inputBuffer.rewind()
    }

    /*
     * ================================================================
     * PARSE YOLO OUTPUT
     * ================================================================
     *
     * Supported formats:
     *
     * 1. NMS output
     *
     * [1, 6, N]
     * [1, N, 6]
     *
     * x1,y1,x2,y2,confidence,class
     *
     *
     * 2. Standard YOLOv8 output
     *
     * [1, 84, 8400]
     * [1, 8400, 84]
     *
     * 4 box values + 80 class values
     *
     * xywh + class scores
     */
    private fun parseOutput(): List<RawDetection> {

        val second =
            outputShape[1]

        val third =
            outputShape[2]

        Log.d(
            TAG,
            "Parsing output: ${outputShape.contentToString()}"
        )

        /*
         * ------------------------------------------------------------
         * NMS OUTPUT
         * ------------------------------------------------------------
         */
        if (second == 6 || third == 6) {

            return parseNmsOutput()
        }

        /*
         * ------------------------------------------------------------
         * STANDARD YOLO OUTPUT
         * ------------------------------------------------------------
         */
        if (
            second >= 5 &&
            second <= COCO_LABELS.size + 5
        ) {

            return parseRawOutputChannelsFirst()
        }

        if (
            third >= 5 &&
            third <= COCO_LABELS.size + 5
        ) {

            return parseRawOutputChannelsLast()
        }

        Log.e(
            TAG,
            "Unknown YOLO output shape: ${
                outputShape.contentToString()
            }"
        )

        return emptyList()
    }

    /*
     * ================================================================
     * NMS OUTPUT
     * ================================================================
     */
    private fun parseNmsOutput(): List<RawDetection> {

        val detections =
            ArrayList<RawDetection>()

        val channelsFirst =
            outputShape[1] == 6

        val count =
            if (channelsFirst) {
                outputShape[2]
            } else {
                outputShape[1]
            }

        for (i in 0 until count) {

            val x1 =
                outputValue(
                    i,
                    0,
                    channelsFirst
                )

            val y1 =
                outputValue(
                    i,
                    1,
                    channelsFirst
                )

            val x2 =
                outputValue(
                    i,
                    2,
                    channelsFirst
                )

            val y2 =
                outputValue(
                    i,
                    3,
                    channelsFirst
                )

            val confidence =
                outputValue(
                    i,
                    4,
                    channelsFirst
                )

            val classId =
                outputValue(
                    i,
                    5,
                    channelsFirst
                ).toInt()

            if (
                confidence.isNaN() ||
                confidence < CONFIDENCE_THRESHOLD
            ) {
                continue
            }

            if (
                classId !in COCO_LABELS.indices
            ) {
                continue
            }

            val box =
                convertBoxToNormalized(
                    x1,
                    y1,
                    x2,
                    y2
                )

            if (box.width() <= 0f || box.height() <= 0f) {
                continue
            }

            detections.add(
                RawDetection(
                    label = COCO_LABELS[classId],
                    boundingBox = box,
                    confidence = confidence
                )
            )
        }

        Log.d(
            TAG,
            "NMS detections before NMS = ${detections.size}"
        )

        return applyNms(detections)
    }

    /*
     * ================================================================
     * RAW YOLO [1,84,N]
     * ================================================================
     */
    private fun parseRawOutputChannelsFirst():
            List<RawDetection> {

        val detections =
            ArrayList<RawDetection>()

        val attributes =
            outputShape[1]

        val count =
            outputShape[2]

        val classCount =
            min(
                COCO_LABELS.size,
                attributes - 4
            )

        for (i in 0 until count) {

            val centerX =
                outputBuffer[0][0][i]

            val centerY =
                outputBuffer[0][1][i]

            val width =
                outputBuffer[0][2][i]

            val height =
                outputBuffer[0][3][i]

            if (
                !centerX.isFinite() ||
                !centerY.isFinite() ||
                !width.isFinite() ||
                !height.isFinite()
            ) {
                continue
            }

            var bestClass = -1
            var bestScore = 0f

            for (classId in 0 until classCount) {

                val score =
                    outputBuffer[0][4 + classId][i]

                if (
                    score.isFinite() &&
                    score > bestScore
                ) {
                    bestScore = score
                    bestClass = classId
                }
            }

            if (
                bestClass < 0 ||
                bestScore < CONFIDENCE_THRESHOLD
            ) {
                continue
            }

            val box =
                convertCenterBoxToNormalized(
                    centerX,
                    centerY,
                    width,
                    height
                )

            if (
                box.width() <= 0f ||
                box.height() <= 0f
            ) {
                continue
            }

            detections.add(
                RawDetection(
                    label = COCO_LABELS[bestClass],
                    boundingBox = box,
                    confidence = bestScore
                )
            )
        }

        Log.d(
            TAG,
            "Raw YOLO detections before NMS = ${detections.size}"
        )

        return applyNms(detections)
    }

    /*
     * ================================================================
     * RAW YOLO [1,N,84]
     * ================================================================
     */
    private fun parseRawOutputChannelsLast():
            List<RawDetection> {

        val detections =
            ArrayList<RawDetection>()

        val count =
            outputShape[1]

        val attributes =
            outputShape[2]

        val classCount =
            min(
                COCO_LABELS.size,
                attributes - 4
            )

        for (i in 0 until count) {

            val centerX =
                outputBuffer[0][i][0]

            val centerY =
                outputBuffer[0][i][1]

            val width =
                outputBuffer[0][i][2]

            val height =
                outputBuffer[0][i][3]

            if (
                !centerX.isFinite() ||
                !centerY.isFinite() ||
                !width.isFinite() ||
                !height.isFinite()
            ) {
                continue
            }

            var bestClass = -1
            var bestScore = 0f

            for (classId in 0 until classCount) {

                val score =
                    outputBuffer[0][i][4 + classId]

                if (
                    score.isFinite() &&
                    score > bestScore
                ) {
                    bestScore = score
                    bestClass = classId
                }
            }

            if (
                bestClass < 0 ||
                bestScore < CONFIDENCE_THRESHOLD
            ) {
                continue
            }

            val box =
                convertCenterBoxToNormalized(
                    centerX,
                    centerY,
                    width,
                    height
                )

            if (
                box.width() <= 0f ||
                box.height() <= 0f
            ) {
                continue
            }

            detections.add(
                RawDetection(
                    label = COCO_LABELS[bestClass],
                    boundingBox = box,
                    confidence = bestScore
                )
            )
        }

        Log.d(
            TAG,
            "Raw YOLO detections before NMS = ${detections.size}"
        )

        return applyNms(detections)
    }

    /*
     * ================================================================
     * OUTPUT VALUE
     * ================================================================
     */
    private fun outputValue(
        detectionIndex: Int,
        channel: Int,
        channelsFirst: Boolean
    ): Float {

        return if (channelsFirst) {

            outputBuffer[0]
            [channel]
            [detectionIndex]

        } else {

            outputBuffer[0]
            [detectionIndex]
            [channel]
        }
    }

    /*
     * ================================================================
     * CONVERT XYXY BOX
     * ================================================================
     */
    private fun convertBoxToNormalized(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): RectF {

        /*
         * YOLO NMS output normally uses model-input pixels.
         *
         * We also tolerate normalized 0..1 output.
         */
        val looksNormalized =
            absMax(
                x1,
                y1,
                x2,
                y2
            ) <= 1.5f

        val modelX1 =
            if (looksNormalized) {
                x1 * inputWidth
            } else {
                x1
            }

        val modelY1 =
            if (looksNormalized) {
                y1 * inputHeight
            } else {
                y1
            }

        val modelX2 =
            if (looksNormalized) {
                x2 * inputWidth
            } else {
                x2
            }

        val modelY2 =
            if (looksNormalized) {
                y2 * inputHeight
            } else {
                y2
            }

        return modelBoxToSourceNormalized(
            modelX1,
            modelY1,
            modelX2,
            modelY2
        )
    }

    /*
     * ================================================================
     * CONVERT XYWH BOX
     * ================================================================
     */
    private fun convertCenterBoxToNormalized(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float
    ): RectF {

        /*
         * Standard Ultralytics YOLO output uses
         * model-input pixel coordinates.
         *
         * If the model returns normalized values,
         * convert them to pixels.
         */
        val maxValue =
            max(
                max(
                    absValue(centerX),
                    absValue(centerY)
                ),
                max(
                    absValue(width),
                    absValue(height)
                )
            )

        val normalized =
            maxValue <= 1.5f

        val cx =
            if (normalized) {
                centerX * inputWidth
            } else {
                centerX
            }

        val cy =
            if (normalized) {
                centerY * inputHeight
            } else {
                centerY
            }

        val w =
            if (normalized) {
                width * inputWidth
            } else {
                width
            }

        val h =
            if (normalized) {
                height * inputHeight
            } else {
                height
            }

        val x1 =
            cx - w / 2f

        val y1 =
            cy - h / 2f

        val x2 =
            cx + w / 2f

        val y2 =
            cy + h / 2f

        return modelBoxToSourceNormalized(
            x1,
            y1,
            x2,
            y2
        )
    }

    /*
     * ================================================================
     * MODEL BOX -> ORIGINAL IMAGE
     * ================================================================
     */
    private fun modelBoxToSourceNormalized(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): RectF {

        /*
         * Remove letterbox padding.
         */
        val sourceX1 =
            (x1 - letterboxPadX) /
                    letterboxScale

        val sourceY1 =
            (y1 - letterboxPadY) /
                    letterboxScale

        val sourceX2 =
            (x2 - letterboxPadX) /
                    letterboxScale

        val sourceY2 =
            (y2 - letterboxPadY) /
                    letterboxScale

        /*
         * Convert to 0..1.
         */
        val left =
            (
                    sourceX1 /
                            sourceWidth.toFloat()
                    ).coerceIn(0f, 1f)

        val top =
            (
                    sourceY1 /
                            sourceHeight.toFloat()
                    ).coerceIn(0f, 1f)

        val right =
            (
                    sourceX2 /
                            sourceWidth.toFloat()
                    ).coerceIn(0f, 1f)

        val bottom =
            (
                    sourceY2 /
                            sourceHeight.toFloat()
                    ).coerceIn(0f, 1f)

        return RectF(
            left,
            top,
            right,
            bottom
        )
    }

    /*
     * ================================================================
     * NON-MAXIMUM SUPPRESSION
     * ================================================================
     */
    private fun applyNms(
        input: List<RawDetection>
    ): List<RawDetection> {

        if (input.size <= 1) {
            return input
        }

        val sorted =
            input.sortedByDescending {
                it.confidence
            }

        val selected =
            ArrayList<RawDetection>()

        for (candidate in sorted) {

            var keep = true

            for (existing in selected) {

                /*
                 * Only suppress same class.
                 */
                if (
                    candidate.label !=
                    existing.label
                ) {
                    continue
                }

                val overlap =
                    calculateIoU(
                        candidate.boundingBox,
                        existing.boundingBox
                    )

                if (overlap > NMS_THRESHOLD) {
                    keep = false
                    break
                }
            }

            if (keep) {
                selected.add(candidate)
            }
        }

        return selected
    }

    /*
     * ================================================================
     * IOU
     * ================================================================
     */
    private fun calculateIoU(
        a: RectF,
        b: RectF
    ): Float {

        val left =
            max(
                a.left,
                b.left
            )

        val top =
            max(
                a.top,
                b.top
            )

        val right =
            min(
                a.right,
                b.right
            )

        val bottom =
            min(
                a.bottom,
                b.bottom
            )

        if (
            right <= left ||
            bottom <= top
        ) {
            return 0f
        }

        val intersection =
            (right - left) *
                    (bottom - top)

        val areaA =
            a.width() *
                    a.height()

        val areaB =
            b.width() *
                    b.height()

        val union =
            areaA +
                    areaB -
                    intersection

        if (union <= 0f) {
            return 0f
        }

        return intersection / union
    }

    /*
     * ================================================================
     * GPU
     * ================================================================
     */
    private fun buildGpuDelegate(): Delegate? {

        return try {

            val compatibilityList =
                CompatibilityList()

            if (
                compatibilityList
                    .isDelegateSupportedOnThisDevice
            ) {

                GpuDelegate()

            } else {

                null
            }

        } catch (t: Throwable) {

            Log.w(
                TAG,
                "GPU delegate unavailable",
                t
            )

            null
        }
    }

    /*
     * ================================================================
     * HELPERS
     * ================================================================
     */
    private fun absValue(
        value: Float
    ): Float {

        return if (value < 0f) {
            -value
        } else {
            value
        }
    }

    private fun absMax(
        a: Float,
        b: Float,
        c: Float,
        d: Float
    ): Float {

        return max(
            max(
                absValue(a),
                absValue(b)
            ),
            max(
                absValue(c),
                absValue(d)
            )
        )
    }

    /*
     * ================================================================
     * CLOSE
     * ================================================================
     */
    fun close() {

        try {
            interpreter.close()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Error closing interpreter",
                t
            )
        }

        try {
            gpuDelegate?.close()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Error closing GPU delegate",
                t
            )
        }
    }
}