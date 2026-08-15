package com.dhwanidrishti.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.dhwanidrishti.app.audio.AnnouncementManager
import com.dhwanidrishti.app.ml.ObjectDetector
import com.dhwanidrishti.app.ml.RiskEngine
import com.dhwanidrishti.app.ml.RiskResult
import com.dhwanidrishti.app.processing.ObjectTracker
import com.dhwanidrishti.app.processing.fuseDetectionsWithDepth

/**
 * Mode B - Object-aware narrated assistance.
 *
 * Camera
 *    ↓
 * YOLO
 *    ↓
 * MiDaS depth
 *    ↓
 * Depth fusion
 *    ↓
 * Object tracking
 *    ↓
 * Risk evaluation
 *    ↓
 * Voice
 */
class ModeBEngine(
    context: Context,
    private val minDetectionIntervalMs: Long = 150L
) {

    // ---------------------------------------------------------
    // MODELS
    // ---------------------------------------------------------

    private val detector =
        ObjectDetector(context)

    private val tracker =
        ObjectTracker()

    private val riskEngine =
        RiskEngine()

    private val announcements =
        AnnouncementManager(context)

    // ---------------------------------------------------------
    // STATE
    // ---------------------------------------------------------

    @Volatile
    private var lastDetectionMs: Long = 0L

    /**
     * Latest tracked scene.
     *
     * VoiceCommandManager can ask:
     *
     * "What's in front of me?"
     *
     * and this list gives the answer.
     */
    @Volatile
    private var latestTrackedObjects:
            List<ObjectTracker.TrackedObject> =
        emptyList()

    @Volatile
    var trackedCount: Int = 0
        private set

    @Volatile
    var highestRisk: RiskResult? = null
        private set

    // ---------------------------------------------------------
    // HYBRID MODE
    // ---------------------------------------------------------

    val isSpeaking: Boolean
        get() = announcements.isSpeaking

    // =========================================================
    // PROCESS FRAME
    // =========================================================

    fun process(
        closeness: Array<FloatArray>,
        frame: Bitmap
    ) {

        val now =
            System.currentTimeMillis()

        // -----------------------------------------------------
        // Detection throttling
        // -----------------------------------------------------

        if (
            now - lastDetectionMs <
            minDetectionIntervalMs
        ) {
            return
        }

        lastDetectionMs = now

        // -----------------------------------------------------
        // 1. YOLO DETECTION
        // -----------------------------------------------------

        val rawDetections =
            detector.detect(frame)

        if (rawDetections.isEmpty()) {

            /*
             * Do not immediately destroy the last scene.
             *
             * Keeping the last tracked objects for a short time
             * makes voice commands more reliable when YOLO misses
             * one frame.
             */

            highestRisk = null

            return
        }

        // -----------------------------------------------------
        // 2. DEPTH FUSION
        // -----------------------------------------------------

        val detectedObjects =
            fuseDetectionsWithDepth(
                detections = rawDetections,
                depthMap = closeness
            )

        if (detectedObjects.isEmpty()) {
            return
        }

        // -----------------------------------------------------
        // 3. TRACKING
        // -----------------------------------------------------

        val trackedObjects =
            tracker.update(
                detectedObjects
            )

        latestTrackedObjects =
            trackedObjects.toList()

        trackedCount =
            trackedObjects.size

        // -----------------------------------------------------
        // 4. RISK
        // -----------------------------------------------------

        var mostDangerousRisk:
                RiskResult? = null

        for (
        detectedObject
        in detectedObjects
        ) {

            val risk =
                riskEngine.evaluate(
                    detectedObject
                )

            if (
                mostDangerousRisk == null ||
                risk.score >
                mostDangerousRisk!!.score
            ) {
                mostDangerousRisk = risk
            }
        }

        highestRisk =
            mostDangerousRisk

        // -----------------------------------------------------
        // 5. AUTOMATIC ANNOUNCEMENT
        // -----------------------------------------------------

        announcements.evaluate(
            tracked = trackedObjects,
            tracker = tracker
        )
    }

    // =========================================================
    // VOICE QUESTION
    // =========================================================

    /**
     * Called by VoiceCommandManager when the user says:
     *
     * "Hey Dhwani, what's in front of me?"
     */
    fun answerWhatIsInFront() {

        val scene =
            latestTrackedObjects

        announcements.announceWhatIsInFront(
            scene
        )
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    fun shutdown() {

        announcements.shutdown()

        detector.close()
    }
}