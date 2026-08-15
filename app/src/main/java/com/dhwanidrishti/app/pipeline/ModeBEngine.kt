package com.dhwanidrishti.app.pipeline
import android.util.Log
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
 * Automatic examples:
 *
 * "Person very close"
 * "Laptop to your left, nearby"
 * "Person approaching from your right"
 *
 * Voice-command examples:
 *
 * "Hey Dhwani, what's in front of me?"
 * "Hey Dhwani, read"
 */
class ModeBEngine(
    context: Context,
    private val minDetectionIntervalMs: Long = 150L
) {

    // =========================================================
    // MODELS
    // =========================================================

    /**
     * YOLOv8 object detector.
     *
     * Loaded when Mode B is first activated.
     */
    private val detector = ObjectDetector(context)

    /**
     * Lightweight centroid tracker.
     *
     * Keeps object identity between frames and allows us to
     * determine whether an object is approaching.
     */
    private val tracker = ObjectTracker()

    /**
     * Determines obstacle risk from the detected object.
     */
    private val riskEngine = RiskEngine()

    /**
     * Handles all spoken output.
     *
     * This includes:
     *
     * 1. Automatic obstacle warnings
     * 2. "What's in front of me?"
     * 3. "Read"
     */
    private val announcements = AnnouncementManager(context)

    // =========================================================
    // DETECTION THROTTLING
    // =========================================================

    @Volatile
    private var lastDetectionMs: Long = 0L

    // =========================================================
    // LATEST SCENE
    // =========================================================

    /**
     * Latest successfully tracked scene.
     *
     * This is intentionally kept even when YOLO misses a frame.
     *
     * That makes voice commands more reliable because:
     *
     * "Hey Dhwani, what's in front of me?"
     *
     * can use the most recent valid scene.
     */
    @Volatile
    private var latestTrackedObjects:
            List<ObjectTracker.TrackedObject> = emptyList()

    // =========================================================
    // DEBUG / UI STATE
    // =========================================================

    @Volatile
    var trackedCount: Int = 0
        private set

    @Volatile
    var highestRisk: RiskResult? = null
        private set

    // =========================================================
    // HYBRID MODE
    // =========================================================

    /**
     * Used by DhwaniPipeline to duck the soundscape while TTS
     * is speaking.
     */
    val isSpeaking: Boolean
        get() = announcements.isSpeaking

    // =========================================================
    // PROCESS CAMERA FRAME
    // =========================================================

    /**
     * Processes one camera frame.
     *
     * @param closeness
     * MiDaS normalized depth map.
     *
     * 1.0 = closest
     * 0.0 = farthest
     *
     * @param frame
     * Original camera frame used by YOLO.
     */
    fun process(
        closeness: Array<FloatArray>,
        frame: Bitmap
    ) {

        val now = System.currentTimeMillis()

        // =====================================================
        // DETECTION THROTTLING
        // =====================================================

        /**
         * Depth can run at a higher frequency.
         *
         * YOLO does not need to run on every camera frame.
         *
         * Example:
         *
         * Camera:
         * 20 FPS
         *
         * YOLO:
         * ~6-7 FPS
         */
        if (now - lastDetectionMs < minDetectionIntervalMs) {
            return
        }

        lastDetectionMs = now

        // =====================================================
        // 1. YOLO OBJECT DETECTION
        // =====================================================

        val rawDetections = try {
            detector.detect(frame)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        /**
         * Important:
         *
         * Do NOT clear latestTrackedObjects here.
         *
         * YOLO can occasionally miss a frame.
         *
         * Keeping the last valid scene makes:
         *
         * "What's in front of me?"
         *
         * more reliable.
         */
        if (rawDetections.isEmpty()) {
            highestRisk = null

            return
        }

        // =====================================================
        // 2. DEPTH FUSION
        // =====================================================

        /**
         * Combines:
         *
         * YOLO bounding boxes
         * +
         * MiDaS depth
         *
         * Result:
         *
         * DetectedObject
         *
         * containing:
         *
         * - label
         * - boundingBox
         * - confidence
         * - distance
         */
        val detectedObjects = try {
            fuseDetectionsWithDepth(
                detections = rawDetections,
                depthMap = closeness
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        if (detectedObjects.isEmpty()) {
            highestRisk = null
            return
        }

        // =====================================================
        // 3. OBJECT TRACKING
        // =====================================================

        val trackedObjects = tracker.update(
            detectedObjects
        )

        /**
         * Save a copy of the latest valid scene.
         *
         * Voice commands will read this list.
         */
        latestTrackedObjects =
            trackedObjects.toList()

        trackedCount =
            trackedObjects.size

        // =====================================================
        // 4. RISK EVALUATION
        // =====================================================

        var mostDangerousRisk: RiskResult? = null

        for (detectedObject in detectedObjects) {

            val risk = try {
                riskEngine.evaluate(
                    detectedObject
                )
            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }

            if (
                mostDangerousRisk == null ||
                risk.score > mostDangerousRisk!!.score
            ) {
                mostDangerousRisk = risk
            }
        }

        highestRisk = mostDangerousRisk

        // =====================================================
        // 5. AUTOMATIC ANNOUNCEMENT
        // =====================================================

        /**
         * AnnouncementManager decides:
         *
         * - whether the object is close enough
         * - whether it is approaching
         * - cooldown
         * - priority
         * - left / center / right
         */
        announcements.evaluate(
            tracked = trackedObjects,
            tracker = tracker
        )
    }

    // =========================================================
    // VOICE COMMAND:
    //
    // "HEY DHWANI, WHAT'S IN FRONT OF ME?"
    // =========================================================

    /**
     * Gives the user an immediate description of the current
     * camera scene.
     *
     * Example:
     *
     * "I see a laptop and a person in front of you."
     *
     * If nothing is detected:
     *
     * "I don't see anything in front of you."
     */
    fun answerWhatIsInFront() {

        val scene =
            latestTrackedObjects

        announcements.announceWhatIsInFront(
            scene
        )
    }

    // =========================================================
    // VOICE COMMAND:
    //
    // "HEY DHWANI, READ"
    // =========================================================

    /**
     * Reads text visible in front of the camera.
     *
     * The actual OCR/TTS implementation belongs to
     * AnnouncementManager.
     */
    fun answerRead() {

        val scene =
            latestTrackedObjects

        announcements.announceRead(
            scene
        )
    }
    fun speak(
        text: String
    ) {

        Log.d(
            "DHWANI_OCR",
            "TTS -> [$text]"
        )

        announcements.speak(text)
    }
    // =========================================================
    // OPTIONAL PUBLIC SCENE ACCESS
    // =========================================================

    /**
     * Returns the latest tracked scene.
     *
     * Useful for debugging or future UI features.
     */
    fun getLatestTrackedObjects():
            List<ObjectTracker.TrackedObject> {

        return latestTrackedObjects
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    /**
     * Releases Mode B resources.
     */
    fun shutdown() {

        latestTrackedObjects =
            emptyList()

        trackedCount = 0

        highestRisk = null

        announcements.shutdown()

        detector.close()
    }
}