package com.dhwanidrishti.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

import com.dhwanidrishti.app.audio.AnnouncementManager
import com.dhwanidrishti.app.audio.SonificationEngine
import com.dhwanidrishti.app.audio.TextReader
import com.dhwanidrishti.app.calibration.CalibrationManager
import com.dhwanidrishti.app.hyper.HyperEngine
import com.dhwanidrishti.app.ml.DepthEstimator
import com.dhwanidrishti.app.processing.ZoneDistances
import com.dhwanidrishti.app.processing.ZoneProcessor

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


class DhwaniPipeline(
    private val context: Context,
    private val onStats: (PipelineStats) -> Unit = {},
    private val onCalibrationSample: (CalibrationPoint) -> Unit = {},
) {

    companion object {
        private const val TAG = "DHWANI_PIPELINE"
    }

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
    // SOUNDSCAPE ENGINE
    // =========================================================

    /**
     * Soundscape is responsible only for spatial audio.
     *
     * It does NOT perform:
     *
     * - YOLO detection
     * - object tracking
     * - risk evaluation
     * - narrated announcements
     */
    private val sonification =
        SonificationEngine()


    // =========================================================
    // HYPER ENGINE
    // =========================================================

    /**
     * Hyper is deliberately independent from Soundscape and Narrated.
     *
     * It only keeps the newest frame while Hyper mode is active and
     * contacts Gemini after an explicit visual question.
     */
    private val hyper =
        HyperEngine(
            AnnouncementManager(context)
        )


    // =========================================================
    // FRAME BUFFER
    // =========================================================

    /**
     * Camera continuously submits frames here.
     *
     * Only the newest frame is kept.
     */
    private val latestFrame =
        AtomicReference<Bitmap?>(null)


    // =========================================================
    // INFERENCE THREAD
    // =========================================================

    /**
     * One worker processes the latest camera frame.
     *
     * Soundscape and Narrated use the same depth inference
     * worker, but their processing branches are completely
     * separated.
     *
     * Hyper deliberately bypasses this inference work and
     * only buffers the recent frame for on-demand Gemini calls.
     */
    private val inferenceExecutor =
        Executors.newSingleThreadExecutor()


    // =========================================================
    // OCR
    // =========================================================

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

    /**
     * Current application mode.
     *
     * SOUNDSCAPE and NARRATED retain their existing processing paths.
     * HYPER is an independent on-demand visual reasoning mode.
     */
    @Volatile
    var mode: AppMode =
        AppMode.SOUNDSCAPE
        set(value) {

            if (field == value) {
                return
            }

            val oldMode =
                field

            field = value

            Log.d(
                TAG,
                "========================================"
            )

            Log.d(
                TAG,
                "MODE CHANGE: $oldMode -> $value"
            )

            when (value) {

                AppMode.SOUNDSCAPE -> {

                    Log.d(
                        TAG,
                        "SOUNDSCAPE MODE ACTIVATED"
                    )

                    /**
                     * Soundscape is active.
                     *
                     * Narrated object processing will stop
                     * automatically in inferenceLoop().
                     */
                    sonification.muted =
                        false
                }

                AppMode.NARRATED -> {

                    Log.d(
                        TAG,
                        "NARRATED MODE ACTIVATED"
                    )

                    /**
                     * Narrated mode does not produce the
                     * continuous soundscape audio.
                     */
                    sonification.muted =
                        true
                }

                AppMode.HYPER -> {

                    Log.d(
                        TAG,
                        "HYPER MODE ACTIVATED"
                    )

                    /**
                     * Hyper does not use the continuous soundscape.
                     * It also does not run YOLO or MiDaS.
                     */
                    sonification.muted =
                        true
                }
            }

            Log.d(
                TAG,
                "========================================"
            )
        }


    // =========================================================
    // MODE B / NARRATED ENGINE
    // =========================================================

    /**
     * Mode B is loaded lazily.
     *
     * This prevents YOLO + TTS from loading when the user
     * only uses Soundscape mode.
     *
     * Once created, it remains available so switching:
     *
     * SOUNDSCAPE
     *      ↓
     * NARRATED
     *
     * does not unnecessarily recreate the detector/tracker.
     */
    private var modeB:
            ModeBEngine? = null


    // =========================================================
    // INITIALIZATION
    // =========================================================

    init {

        Log.d(
            TAG,
            "========================================"
        )

        Log.d(
            TAG,
            "DhwaniPipeline initialized"
        )

        Log.d(
            TAG,
            "Initial mode = $mode"
        )

        /**
         * Start the soundscape engine.
         *
         * It can be muted while Narrated or Hyper mode is active.
         */
        sonification.start()

        /**
         * Start the single inference worker.
         */
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
     * Only the latest frame is retained by the normal pipeline.
     * Hyper receives its own copy only while Hyper mode is active.
     */
    fun submitFrame(
        bitmap: Bitmap
    ) {

        latestFrame.set(
            bitmap
        )

        if (mode == AppMode.HYPER) {
            hyper.submitFrame(
                bitmap
            )
        }
    }


    // =========================================================
    // CALIBRATION
    // =========================================================

    /**
     * Request the next processed frame to be used as the
     * NEAR calibration sample.
     */
    fun recordCalibrationNear() {

        Log.d(
            TAG,
            "Calibration NEAR requested"
        )

        pendingCalibrationPoint =
            CalibrationPoint.NEAR
    }


    /**
     * Request the next processed frame to be used as the
     * FAR calibration sample.
     */
    fun recordCalibrationFar() {

        Log.d(
            TAG,
            "Calibration FAR requested"
        )

        pendingCalibrationPoint =
            CalibrationPoint.FAR
    }


    // =========================================================
    // VOICE QUESTION
    //
    // "HEY DHWANI, WHAT'S IN FRONT OF ME?"
    // =========================================================

    /**
     * Answers:
     *
     * "Hey Dhwani, what's in front of me?"
     *
     * Narrated mode keeps its existing local YOLO answer.
     * Hyper mode sends the latest frame to Gemini instead.
     */
    fun answerWhatIsInFront() {

        Log.d(
            TAG,
            "Voice request: WHAT IS IN FRONT"
        )

        if (mode == AppMode.HYPER) {
            hyper.ask(
                "What is in front of me?"
            )
            return
        }

        modeBEngine()
            .answerWhatIsInFront()
    }


    // =========================================================
    // VOICE QUESTION
    //
    // "HEY DHWANI, WHERE IS THE DOOR?"
    // =========================================================

    /**
     * Answers object-location questions.
     *
     * Narrated mode keeps its existing local YOLO answer.
     * Hyper mode uses Gemini's visual reasoning on the recent frame.
     */
    fun answerWhereIs(
        objectName: String
    ) {

        Log.d(
            TAG,
            "Location request: [$objectName]"
        )

        if (mode == AppMode.HYPER) {
            hyper.ask(
                "Where is the $objectName?"
            )
            return
        }

        modeBEngine()
            .answerWhereIs(
                objectName
            )
    }


    // =========================================================
    // VOICE QUESTION
    //
    // "HEY DHWANI, READ"
    // =========================================================

    /**
     * Schedules OCR on the next camera frame.
     *
     * This existing OCR behavior is retained in all modes.
     */
    fun answerRead() {

        Log.d(
            "DHWANI_OCR",
            "READ REQUEST RECEIVED"
        )

        pendingRead.set(
            true
        )

        Log.d(
            "DHWANI_OCR",
            "OCR scheduled for next camera frame"
        )
    }


    // =========================================================
    // MAIN INFERENCE LOOP
    // =========================================================

    private fun inferenceLoop() {

        while (running) {

            // =================================================
            // GET LATEST CAMERA FRAME
            // =================================================

            val frame =
                latestFrame.getAndSet(null)
                    ?: continue


            // =================================================
            // VOICE READ -> OCR
            // =================================================

            if (
                pendingRead.compareAndSet(
                    true,
                    false
                )
            ) {

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
                                .speak(
                                    text
                                )
                        }

                        try {

                            ocrBitmap.recycle()

                        } catch (_: Exception) {
                        }
                    }
                }
            }


            // =================================================
            // HYPER MODE
            // =================================================

            /**
             * Hyper is intentionally independent from the local
             * perception pipeline.
             *
             * Do NOT run:
             *
             * - MiDaS
             * - YOLO
             * - depth normalization
             * - tracking
             * - risk evaluation
             *
             * The frame was already copied into HyperFrameBuffer
             * from submitFrame(). Gemini runs only when a voice
             * question calls answerWhatIsInFront()/answerWhereIs().
             */
            if (mode == AppMode.HYPER) {

                sonification.muted =
                    true

                Log.d(
                    TAG,
                    "Processing HYPER frame: buffer only, no local inference"
                )

                stats.inferenceMs =
                    0f

                stats.processMs =
                    0f

                stats.totalMs =
                    0f

                stats.objectsTracked =
                    0

                stats.frames++

                onStats(
                    stats
                )

                continue
            }


            // =================================================
            // DEPTH ESTIMATION
            // =================================================

            val tStart =
                System.nanoTime()


            // -------------------------------------------------
            // MiDaS
            // -------------------------------------------------

            val rawDepth =
                try {

                    depthEstimator.runInference(
                        frame
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Depth inference failed",
                        e
                    )

                    continue
                }


            val tModel =
                System.nanoTime()


            // =================================================
            // CALIBRATION
            // =================================================

            pendingCalibrationPoint?.let { point ->

                val raw =
                    maxRawValue(
                        rawDepth
                    )

                when (point) {

                    CalibrationPoint.NEAR -> {

                        calibration.recordNear(
                            raw
                        )
                    }

                    CalibrationPoint.FAR -> {

                        calibration.recordFar(
                            raw
                        )
                    }
                }

                pendingCalibrationPoint =
                    null

                onCalibrationSample(
                    point
                )

                Log.d(
                    TAG,
                    "Calibration sample recorded: $point"
                )
            }


            // =================================================
            // NORMALIZE DEPTH
            // =================================================

            /**
             * Converts raw MiDaS depth into:
             *
             * 0.0 = far
             * 1.0 = close
             */
            val closeness =
                try {

                    calibration.normalize(
                        rawDepth
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Depth normalization failed",
                        e
                    )

                    continue
                }


            // =================================================
            // MODE-SPECIFIC PROCESSING
            // =================================================

            when (mode) {

                // =================================================
                // SOUNDSCAPE MODE
                // =================================================

                AppMode.SOUNDSCAPE -> {

                    /**
                     * IMPORTANT:
                     *
                     * Soundscape mode does NOT run:
                     *
                     * - YOLO
                     * - ObjectTracker
                     * - RiskEngine
                     * - Narrated announcements
                     *
                     * It only processes the depth map into
                     * directional sound.
                     */

                    sonification.muted =
                        false

                    Log.d(
                        TAG,
                        "Processing SOUNDSCAPE frame"
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
                    // UPDATE SOUND
                    // -------------------------------------------------

                    sonification.updateFromZones(
                        smoothed
                    )
                }


                // =================================================
                // NARRATED MODE
                // =================================================

                AppMode.NARRATED -> {

                    /**
                     * IMPORTANT:
                     *
                     * Narrated mode does NOT run the continuous
                     * soundscape processing.
                     *
                     * It uses:
                     *
                     * Camera
                     *    ↓
                     * MiDaS
                     *    ↓
                     * YOLO26m
                     *    ↓
                     * Depth Fusion
                     *    ↓
                     * Object Tracker
                     *    ↓
                     * Risk Engine
                     *    ↓
                     * Announcement Manager
                     */

                    sonification.muted =
                        true

                    Log.d(
                        TAG,
                        "Processing NARRATED frame"
                    )

                    modeBEngine()
                        .process(
                            closeness,
                            frame
                        )
                }

                // HYPER is handled before depth estimation above.
                AppMode.HYPER -> {
                    // Intentionally unreachable.
                }
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

            /**
             * Only Narrated mode has tracked objects.
             *
             * Soundscape and Hyper therefore report zero here.
             */
            stats.objectsTracked =
                if (mode == AppMode.NARRATED) {
                    modeB?.trackedCount ?: 0
                } else {
                    0
                }


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
     * - YOLO26m
     * - ObjectTracker
     * - RiskEngine
     * - AnnouncementManager
     * - TTS
     *
     * The same instance is retained while the application
     * is running.
     */
    private fun modeBEngine():
            ModeBEngine {

        return modeB
            ?: ModeBEngine(
                context
            ).also {

                Log.d(
                    TAG,
                    "Creating ModeBEngine / YOLO26m"
                )

                modeB =
                    it
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
     * Therefore the maximum finite value represents the
     * nearest point.
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

                    max =
                        value
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

        Log.d(
            TAG,
            "Stopping DhwaniPipeline"
        )

        running =
            false

        // -----------------------------------------------------
        // Stop inference worker
        // -----------------------------------------------------

        inferenceExecutor.shutdown()


        // -----------------------------------------------------
        // Stop soundscape
        // -----------------------------------------------------

        sonification.stop()


        // -----------------------------------------------------
        // Release MiDaS
        // -----------------------------------------------------

        depthEstimator.close()


        // -----------------------------------------------------
        // Release Narrated / YOLO
        // -----------------------------------------------------

        modeB?.shutdown()

        modeB =
            null


        // -----------------------------------------------------
        // Release Hyper
        // -----------------------------------------------------

        hyper.stop()

        Log.d(
            TAG,
            "DhwaniPipeline stopped"
        )
    }
}


// =============================================================
// APP MODE
// =============================================================

enum class AppMode {

    /**
     * Continuous spatial sound.
     *
     * Pipeline:
     *
     * Camera
     *   ↓
     * MiDaS
     *   ↓
     * Zones
     *   ↓
     * Soundscape
     */
    SOUNDSCAPE,


    /**
     * Spoken object descriptions.
     *
     * Pipeline:
     *
     * Camera
     *   ↓
     * MiDaS
     *   ↓
     * YOLO26m
     *   ↓
     * Depth Fusion
     *   ↓
     * Object Tracking
     *   ↓
     * Risk
     *   ↓
     * Voice
     */
    NARRATED,


    /**
     * On-demand visual reasoning.
     *
     * Pipeline:
     *
     * Camera
     *   ↓
     * Latest-frame buffer
     *   ↓
     * User question
     *   ↓
     * Gemini Vision
     *   ↓
     * Voice
     *
     * No continuous YOLO or MiDaS inference.
     */
    HYPER
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