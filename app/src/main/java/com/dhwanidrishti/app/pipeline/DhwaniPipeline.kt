package com.dhwanidrishti.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.dhwanidrishti.app.audio.SonificationEngine
import com.dhwanidrishti.app.calibration.CalibrationManager
import com.dhwanidrishti.app.ml.DepthEstimator
import com.dhwanidrishti.app.processing.ZoneDistances
import com.dhwanidrishti.app.processing.ZoneProcessor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class DhwaniPipeline(
    private val context: Context,
    private val onStats: (PipelineStats) -> Unit = {},
    private val onCalibrationSample: (CalibrationPoint) -> Unit = {},
) {

    private val depthEstimator =
        DepthEstimator(context)

    val calibration =
        CalibrationManager(context)

    private val sonification =
        SonificationEngine()

    private val latestFrame =
        AtomicReference<Bitmap?>(null)

    private val inferenceExecutor =
        Executors.newSingleThreadExecutor()

    private var smoothed =
        ZoneDistances(
            0.5f,
            0.5f,
            0.5f
        )

    private val stats =
        PipelineStats()

    @Volatile
    private var running = true

    @Volatile
    private var pendingCalibrationPoint:
            CalibrationPoint? = null

    @Volatile
    var mode: AppMode =
        AppMode.SOUNDSCAPE

    private var modeB:
            ModeBEngine? = null

    // =========================================================
    // INITIALIZATION
    // =========================================================

    init {

        sonification.start()

        inferenceExecutor.execute {
            inferenceLoop()
        }
    }

    // =========================================================
    // CAMERA
    // =========================================================

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

    fun recordCalibrationNear() {

        pendingCalibrationPoint =
            CalibrationPoint.NEAR
    }

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
     */
    fun answerWhatIsInFront() {

        /*
         * If Mode B hasn't been initialized yet, initialize it.
         *
         * This also means the voice command works even if the
         * user has not manually switched to Narrated mode.
         */
        modeBEngine()
            .answerWhatIsInFront()
    }

    // =========================================================
    // INFERENCE LOOP
    // =========================================================

    private fun inferenceLoop() {

        while (running) {

            val frame =
                latestFrame.getAndSet(null)
                    ?: continue

            val tStart =
                System.nanoTime()

            // -------------------------------------------------
            // MiDaS
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

            val closeness =
                calibration.normalize(
                    rawDepth
                )

            // -------------------------------------------------
            // MODE A / HYBRID
            // -------------------------------------------------

            if (mode == AppMode.NARRATED) {

                sonification.muted =
                    true

            } else {

                sonification.muted =
                    false

                sonification.setDucking(
                    modeB?.isSpeaking == true
                )

                val zones =
                    ZoneProcessor.processZones(
                        closeness
                    )

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

                sonification.updateFromZones(
                    smoothed
                )
            }

            // -------------------------------------------------
            // MODE B
            // -------------------------------------------------

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

            val tEnd =
                System.nanoTime()

            // -------------------------------------------------
            // STATS
            // -------------------------------------------------

            stats.inferenceMs =
                (
                        tModel - tStart
                        ) / 1_000_000f

            stats.processMs =
                (
                        tEnd - tModel
                        ) / 1_000_000f

            stats.totalMs =
                (
                        tEnd - tStart
                        ) / 1_000_000f

            stats.objectsTracked =
                modeB?.trackedCount ?: 0

            stats.frames++

            onStats(
                stats
            )
        }
    }

    // =========================================================
    // MODE B LAZY INITIALIZATION
    // =========================================================

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
    // DEPTH
    // =========================================================

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

    fun stop() {

        running = false

        inferenceExecutor.shutdown()

        sonification.stop()

        depthEstimator.close()

        modeB?.shutdown()

        modeB = null
    }
}

// =============================================================
// APP MODE
// =============================================================

enum class AppMode {

    SOUNDSCAPE,

    NARRATED,

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
// CALIBRATION
// =============================================================

enum class CalibrationPoint {

    NEAR,

    FAR
}