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
 * Pipeline:
 *
 * Camera frame
 *      ↓
 * YOLO object detection
 *      ↓
 * MiDaS depth fusion
 *      ↓
 * Object tracking
 *      ↓
 * Risk evaluation
 *      ↓
 * Voice announcement
 *
 * Example announcements:
 *
 * "Laptop, very close"
 * "Person, very close"
 * "Backpack to your left, nearby"
 * "Person is approaching from your right"
 */
class ModeBEngine(
    context: Context,
    private val minDetectionIntervalMs: Long = 150L
) {

    // ---------------------------------------------------------
    // Object detector
    // ---------------------------------------------------------

    private val detector = ObjectDetector(context)

    // ---------------------------------------------------------
    // Object tracker
    // ---------------------------------------------------------

    private val tracker = ObjectTracker()

    // ---------------------------------------------------------
    // Risk engine
    // ---------------------------------------------------------

    private val riskEngine = RiskEngine()

    // ---------------------------------------------------------
    // Voice announcement manager
    // ---------------------------------------------------------

    private val announcements = AnnouncementManager(context)

    // ---------------------------------------------------------
    // Detection throttling
    // ---------------------------------------------------------

    @Volatile
    private var lastDetectionMs: Long = 0L

    // ---------------------------------------------------------
    // Debug / UI information
    // ---------------------------------------------------------

    @Volatile
    var trackedCount: Int = 0
        private set

    @Volatile
    var highestRisk: RiskResult? = null
        private set

    // ---------------------------------------------------------
    // Used by Hybrid mode
    // ---------------------------------------------------------

    val isSpeaking: Boolean
        get() = announcements.isSpeaking

    /**
     * Process one camera frame.
     *
     * closeness:
     *      1.0 = closest
     *      0.0 = farthest
     */
    fun process(
        closeness: Array<FloatArray>,
        frame: Bitmap
    ) {

        val now = System.currentTimeMillis()

        // -----------------------------------------------------
        // Detection throttling
        // -----------------------------------------------------
        //
        // MiDaS/depth can run frequently.
        // YOLO does not need to run on every frame.
        //

        if (now - lastDetectionMs < minDetectionIntervalMs) {
            return
        }

        lastDetectionMs = now

        // -----------------------------------------------------
        // 1. OBJECT DETECTION
        // -----------------------------------------------------

        val rawDetections = detector.detect(frame)

        if (rawDetections.isEmpty()) {

            trackedCount = 0
            highestRisk = null

            return
        }

        // -----------------------------------------------------
        // 2. DEPTH FUSION
        // -----------------------------------------------------
        //
        // Converts:
        //
        // YOLO box
        //      +
        // MiDaS closeness
        //
        // into:
        //
        // DetectedObject
        //
        // containing:
        // label
        // boundingBox
        // confidence
        // distance
        // zone
        //

        val detectedObjects = fuseDetectionsWithDepth(
            detections = rawDetections,
            depthMap = closeness
        )

        if (detectedObjects.isEmpty()) {

            trackedCount = 0
            highestRisk = null

            return
        }

        // -----------------------------------------------------
        // 3. OBJECT TRACKING
        // -----------------------------------------------------

        val trackedObjects = tracker.update(detectedObjects)

        trackedCount = trackedObjects.size

        // -----------------------------------------------------
        // 4. RISK EVALUATION
        // -----------------------------------------------------

        var mostDangerousRisk: RiskResult? = null

        for (detectedObject in detectedObjects) {

            val risk = riskEngine.evaluate(detectedObject)

            if (
                mostDangerousRisk == null ||
                risk.score > mostDangerousRisk!!.score
            ) {
                mostDangerousRisk = risk
            }
        }

        highestRisk = mostDangerousRisk

        // -----------------------------------------------------
        // 5. VOICE ANNOUNCEMENT
        // -----------------------------------------------------
        //
        // AnnouncementManager decides:
        //
        // - Is the object close enough?
        // - Is it approaching?
        // - Has it already been announced recently?
        // - Which object should be announced first?
        // - Which side is it on?
        //

        announcements.evaluate(
            tracked = trackedObjects,
            tracker = tracker
        )
    }

    // ---------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------

    /**
     * Releases Mode B resources.
     */
    fun shutdown() {

        announcements.shutdown()

        detector.close()
    }
}