package com.dhwanidrishti.app.pipeline
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.dhwanidrishti.app.audio.SonificationEngine
import com.dhwanidrishti.app.calibration.CalibrationManager
import com.dhwanidrishti.app.ml.DepthEstimator
import com.dhwanidrishti.app.processing.ZoneDistances
import com.dhwanidrishti.app.processing.ZoneProcessor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import com.dhwanidrishti.app.audio.TextReader
import java.util.concurrent.atomic.AtomicBoolean

class DhwaniPipeline(
    private val context: Context,
    private val onStats: (PipelineStats) -> Unit = {},
    private val onCalibrationSample: (CalibrationPoint) -> Unit = {},
) {

    // =========================================================
    // DEPTH ESTIMATOR
    // =========================================================

    private val depthEstimator =
        DepthEstimator(context)

    // =========================================================
    // CALIBRATION
    // =========================================================

    val calibration =
        CalibrationManager(context)

    // =========================================================
    // SOUNDSCAPE
    // =========================================================

    private val sonification =
        SonificationEngine()

    // =========================================================
    // FRAME BUFFER
    // =========================================================

    /**
     * Camera continuously submits frames here.
     *
     * Only the newest frame is kept.
     * Old frames are discarded.
     */
    private val latestFrame =
        AtomicReference<Bitmap?>(null)

    // =========================================================
    // INFERENCE THREAD
    // =========================================================

    private val inferenceExecutor =
        Executors.newSingleThreadExecutor()

    private val textReader =
        TextReader()

    private val pendingRead =
        AtomicBoolean(false)

    // =========================================================
    // SMOOTHED ZONE VALUES
    // =========================================================

    private var smoothed =
        ZoneDistances(
            0.5f,
            0.5f,
            0.5f
        )

    // =========================================================
    // PERFORMANCE STATS
    // =========================================================

    private val stats =
        PipelineStats()

    // =========================================================
    // PIPELINE STATE
    // =========================================================

    @Volatile
    private var running = true

    // =========================================================
    // CALIBRATION REQUEST
    // =========================================================

    @Volatile
    private var pendingCalibrationPoint:
            CalibrationPoint? = null

    // =========================================================
    // APP MODE
    // =========================================================

    @Volatile
    var mode: AppMode =
        AppMode.SOUNDSCAPE

    // =========================================================
    // MODE B
    // =========================================================

    /**
     * Mode B is loaded lazily.
     *
     * This prevents YOLO + TTS from loading
     * when the user only uses Soundscape mode.
     */
    private var modeB:
            ModeBEngine? = null

    // =========================================================
    // INITIALIZATION
    // =========================================================

    init {

        // Start continuous soundscape engine.
        sonification.start()

        // Start inference worker.
        inferenceExecutor.execute {
            inferenceLoop()
        }
    }

    // =========================================================
    // CAMERA
    // =========================================================

    /**
     * Called by CameraController.
     *
     * The newest frame replaces the previous one.
     */
    fun submitFrame(
        bitmap: Bitmap
    ) {

        latestFrame.set(
            bitmap
        )
    }

    // =========================================================
    // CALIBRATION
    // =========================================================

    /**
     * Request the next processed frame to be used
     * as the NEAR calibration sample.
     */
    fun recordCalibrationNear() {

        pendingCalibrationPoint =
            CalibrationPoint.NEAR
    }

    /**
     * Request the next processed frame to be used
     * as the FAR calibration sample.
     */
    fun recordCalibrationFar() {

        pendingCalibrationPoint =
            CalibrationPoint.FAR
    }

    // =========================================================
    // VOICE QUESTION
    // =========================================================

    /**
     * Answers:
     *
     * "Hey Dhwani, what's in front of me?"
     *
     * This works independently of the current mode.
     *
     * If Mode B has not been initialized yet,
     * it will be created here.
     */
    fun answerWhatIsInFront() {

        modeBEngine()
            .answerWhatIsInFront()
    }

    // =========================================================
    // INFERENCE LOOP
    // =========================================================
// =========================================================
// VOICE QUESTION:
//
// "HEY DHWANI, READ"
// =========================================================

    /**
     * Reads text visible in front of the camera.
     *
     * This is triggered by:
     *
     * "Hey Dhwani, read"
     */
    fun answerRead() {

        Log.d(
            "DHWANI_OCR",
            "READ REQUEST RECEIVED"
        )

        pendingRead.set(true)

        Log.d(
            "DHWANI_OCR",
            "OCR scheduled for next camera frame"
        )
    }
    private fun inferenceLoop() {

        while (running) {

            // -------------------------------------------------
            // GET LATEST CAMERA FRAME
            // -------------------------------------------------

            val frame =
                latestFrame.getAndSet(null)
                    ?: continue

            // =================================================
// VOICE READ -> OCR
// =================================================

            if (pendingRead.compareAndSet(true, false)) {

                Log.d(
                    "DHWANI_OCR",
                    "Running OCR on current camera frame"
                )

                val ocrBitmap =
                    try {
                        frame.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                        )
                    } catch (e: Exception) {

                        Log.e(
                            "DHWANI_OCR",
                            "Could not copy frame for OCR",
                            e
                        )

                        null
                    }

                if (ocrBitmap != null) {

                    textReader.read(
                        ocrBitmap
                    ) { text ->

                        if (text.isNullOrBlank()) {

                            Log.d(
                                "DHWANI_OCR",
                                "NO TEXT FOUND"
                            )

                            modeBEngine()
                                .speak(
                                    "I cannot find any readable text."
                                )

                        } else {

                            Log.d(
                                "DHWANI_OCR",
                                "TEXT FOUND = [$text]"
                            )

                            modeBEngine()
                                .speak(text)
                        }

                        try {
                            ocrBitmap.recycle()
                        } catch (_: Exception) {
                        }
                    }
                }
            }




            val tStart =
                System.nanoTime()

            // -------------------------------------------------
            // MiDaS DEPTH
            // -------------------------------------------------

            val rawDepth =
                depthEstimator.runInference(
                    frame
                )

            val tModel =
                System.nanoTime()

            // -------------------------------------------------
            // CALIBRATION
            // -------------------------------------------------

            pendingCalibrationPoint?.let { point ->

                val raw =
                    maxRawValue(
                        rawDepth
                    )

                when (point) {

                    CalibrationPoint.NEAR ->
                        calibration.recordNear(
                            raw
                        )

                    CalibrationPoint.FAR ->
                        calibration.recordFar(
                            raw
                        )
                }

                pendingCalibrationPoint =
                    null

                onCalibrationSample(
                    point
                )
            }

            // -------------------------------------------------
            // NORMALIZE DEPTH
            // -------------------------------------------------

            /**
             * Converts raw MiDaS depth into:
             *
             * 0.0 = far
             * 1.0 = close
             */
            val closeness =
                calibration.normalize(
                    rawDepth
                )

            // =================================================
            // MODE A / HYBRID
            // =================================================

            if (mode == AppMode.NARRATED) {

                // Narrated mode does not need the continuous
                // soundscape tone.
                sonification.muted =
                    true

            } else {

                sonification.muted =
                    false

                // -------------------------------------------------
                // HYBRID DUCKING
                // -------------------------------------------------

                /**
                 * When voice is speaking,
                 * reduce soundscape volume.
                 */
                sonification.setDucking(
                    modeB?.isSpeaking == true
                )

                // -------------------------------------------------
                // ZONE PROCESSING
                // -------------------------------------------------

                val zones =
                    ZoneProcessor.processZones(
                        closeness
                    )

                // -------------------------------------------------
                // EMA SMOOTHING
                // -------------------------------------------------

                /**
                 * Prevents sound from jumping around
                 * because of small depth fluctuations.
                 */
                smoothed =
                    ZoneDistances(

                        left =
                            0.6f * smoothed.left +
                                    0.4f * zones.left,

                        center =
                            0.6f * smoothed.center +
                                    0.4f * zones.center,

                        right =
                            0.6f * smoothed.right +
                                    0.4f * zones.right
                    )

                // -------------------------------------------------
                // UPDATE AUDIO
                // -------------------------------------------------

                sonification.updateFromZones(
                    smoothed
                )
            }

            // =================================================
            // MODE B
            // =================================================

            if (
                mode == AppMode.NARRATED ||
                mode == AppMode.HYBRID
            ) {

                modeBEngine()
                    .process(
                        closeness,
                        frame
                    )
            }

            // =================================================
            // TIMING
            // =================================================

            val tEnd =
                System.nanoTime()

            // -------------------------------------------------
            // MODEL TIME
            // -------------------------------------------------

            stats.inferenceMs =
                (
                        tModel - tStart
                        ) / 1_000_000f

            // -------------------------------------------------
            // PROCESSING TIME
            // -------------------------------------------------

            stats.processMs =
                (
                        tEnd - tModel
                        ) / 1_000_000f

            // -------------------------------------------------
            // TOTAL TIME
            // -------------------------------------------------

            stats.totalMs =
                (
                        tEnd - tStart
                        ) / 1_000_000f

            // -------------------------------------------------
            // TRACKED OBJECTS
            // -------------------------------------------------

            stats.objectsTracked =
                modeB?.trackedCount ?: 0

            // -------------------------------------------------
            // FRAME COUNTER
            // -------------------------------------------------

            stats.frames++

            // -------------------------------------------------
            // SEND STATS TO UI
            // -------------------------------------------------

            onStats(
                stats
            )
        }
    }

    // =========================================================
    // MODE B LAZY INITIALIZATION
    // =========================================================

    /**
     * Creates ModeBEngine only when required.
     *
     * Mode B contains:
     *
     * YOLO
     * ObjectTracker
     * RiskEngine
     * AnnouncementManager
     * TTS
     */
    private fun modeBEngine():
            ModeBEngine {

        return modeB
            ?: ModeBEngine(
                context
            ).also {

                modeB = it
            }
    }

    // =========================================================
    // DEPTH UTILITIES
    // =========================================================

    /**
     * MiDaS produces inverse depth.
     *
     * Larger raw value = closer.
     *
     * Therefore the maximum finite value
     * represents the nearest point.
     */
    private fun maxRawValue(
        depth: Array<FloatArray>
    ): Float {

        var max =
            -Float.MAX_VALUE

        for (row in depth) {

            for (value in row) {

                if (
                    value.isFinite() &&
                    value > max
                ) {

                    max = value
                }
            }
        }

        return max
    }

    // =========================================================
    // STOP
    // =========================================================

    /**
     * Releases all resources.
     */
    fun stop() {

        running = false

        // Stop inference worker.
        inferenceExecutor.shutdown()

        // Stop soundscape.
        sonification.stop()

        // Release MiDaS.
        depthEstimator.close()

        // Release Mode B if it was created.
        modeB?.shutdown()

        modeB = null
    }
}

// =============================================================
// APP MODE
// =============================================================

enum class AppMode {

    /**
     * Continuous spatial sound.
     */
    SOUNDSCAPE,

    /**
     * Spoken object descriptions.
     */
    NARRATED,

    /**
     * Soundscape + spoken descriptions.
     */
    HYBRID
}

// =============================================================
// PIPELINE STATS
// =============================================================

class PipelineStats {

    @Volatile
    var inferenceMs: Float = 0f

    @Volatile
    var processMs: Float = 0f

    @Volatile
    var totalMs: Float = 0f

    @Volatile
    var frames: Long = 0L

    @Volatile
    var objectsTracked: Int = 0
}

// =============================================================
// CALIBRATION POINT
// =============================================================

enum class CalibrationPoint {

    NEAR,

    FAR
}