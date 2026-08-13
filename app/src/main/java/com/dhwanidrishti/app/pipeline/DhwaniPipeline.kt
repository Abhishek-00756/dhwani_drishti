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

/**
 * Producer-consumer split:
 *  - Camera thread overwrites [latestFrame] (STRATEGY_KEEP_ONLY_LATEST, so
 *    stale frames are never queued).
 *  - Inference thread takes the newest frame, runs depth -> zones -> audio.
 *  - Audio thread reads whatever the inference thread last wrote, lock-free.
 *
 * Modes:
 *  - SOUNDSCAPE (default): continuous tone from zone closeness.
 *  - NARRATED: continuous tone muted; Mode B speaks object announcements.
 *  - HYBRID: both run together.
 *
 * Mode B is created lazily on first use so Mode A-only sessions don't pay the
 * cost of loading the second model (or fail if yolov8n_fp16.tflite is missing).
 */
class DhwaniPipeline(
    private val context: Context,
    private val onStats: (PipelineStats) -> Unit = {},
    private val onCalibrationSample: (CalibrationPoint) -> Unit = {},
) {

    private val depthEstimator = DepthEstimator(context)

    /** Public so the UI can show calibration status and drive the flow. */
    val calibration = CalibrationManager(context)
    private val sonification = SonificationEngine()

    private val latestFrame = AtomicReference<Bitmap?>(null)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    private var smoothed = ZoneDistances(0.5f, 0.5f, 0.5f)
    private val stats = PipelineStats()

    @Volatile private var running = true
    @Volatile private var pendingCalibrationPoint: CalibrationPoint? = null

    /** Switched from the UI thread; read every frame by the inference thread. */
    @Volatile var mode: AppMode = AppMode.SOUNDSCAPE

    private var modeB: ModeBEngine? = null

    init {
        sonification.start()
        inferenceExecutor.execute { inferenceLoop() }
    }

    /** Camera thread. */
    fun submitFrame(bitmap: Bitmap) {
        latestFrame.set(bitmap)
    }

    /**
     * Requests the next processed frame to capture the raw model output as the
     * "near" calibration sample (Step 9). Cheap: computes one max over the depth
     * map, so it only happens on demand, not every frame.
     */
    fun recordCalibrationNear() {
        pendingCalibrationPoint = CalibrationPoint.NEAR
    }

    fun recordCalibrationFar() {
        pendingCalibrationPoint = CalibrationPoint.FAR
    }

    private fun inferenceLoop() {
        while (running) {
            val frame = latestFrame.getAndSet(null) ?: continue

            val tStart = System.nanoTime()
            val rawDepth = depthEstimator.runInference(frame)
            val tModel = System.nanoTime()

            // One-shot raw capture for the calibration flow (Step 9).
            pendingCalibrationPoint?.let { point ->
                val raw = maxRawValue(rawDepth)
                when (point) {
                    CalibrationPoint.NEAR -> calibration.recordNear(raw)
                    CalibrationPoint.FAR -> calibration.recordFar(raw)
                }
                pendingCalibrationPoint = null
                onCalibrationSample(point)
            }

            val closeness = calibration.normalize(rawDepth)

            if (mode == AppMode.NARRATED) {
                // Mode B only: keep the depth map flowing, silence the tone.
                sonification.muted = true
            } else {
                sonification.muted = false
                // Hybrid ducking: lower the tone to 20% while Mode B speaks,
                // restore it afterwards (Stage 4).
                sonification.setDucking(modeB?.isSpeaking == true)
                val zones = ZoneProcessor.processZones(closeness)
                // EMA across frames prevents audio jitter from frame-to-frame noise.
                smoothed = ZoneDistances(
                    left = 0.6f * smoothed.left + 0.4f * zones.left,
                    center = 0.6f * smoothed.center + 0.4f * zones.center,
                    right = 0.6f * smoothed.right + 0.4f * zones.right,
                )
                sonification.updateFromZones(smoothed)
            }

            if (mode == AppMode.SOUNDSCAPE) {
                modeBEngine().process(closeness, frame)
            }

            val tEnd = System.nanoTime()

            // Per-stage timing (Step 8): model inference vs zone/audio update.
            stats.inferenceMs = (tModel - tStart) / 1_000_000f
            stats.processMs = (tEnd - tModel) / 1_000_000f
            stats.totalMs = (tEnd - tStart) / 1_000_000f
            stats.objectsTracked = modeB?.trackedCount ?: 0
            stats.frames++
            onStats(stats)
        }
    }

    /** Lazy: creating ModeBEngine loads yolov8n_fp16.tflite + TTS. */
    private fun modeBEngine(): ModeBEngine =
        modeB ?: ModeBEngine(context).also { modeB = it }

    /** MiDaS inverse depth: larger value = closer, so max = nearest pixel. */
    private fun maxRawValue(depth: Array<FloatArray>): Float {
        var max = -Float.MAX_VALUE
        for (row in depth) {
            for (v in row) {
                if (v.isFinite() && v > max) max = v
            }
        }
        return max
    }

    fun stop() {
        running = false
        inferenceExecutor.shutdown()
        sonification.stop()
        depthEstimator.close()
        modeB?.shutdown()
        modeB = null
    }
}

/** Which output mode the pipeline is in. */
enum class AppMode { SOUNDSCAPE, NARRATED, HYBRID }

/** Rolling performance counters, read from the UI thread via [onStats]. */
class PipelineStats {
    @Volatile var inferenceMs: Float = 0f
    @Volatile var processMs: Float = 0f
    @Volatile var totalMs: Float = 0f
    @Volatile var frames: Long = 0L
    @Volatile var objectsTracked: Int = 0
}

enum class CalibrationPoint { NEAR, FAR }
