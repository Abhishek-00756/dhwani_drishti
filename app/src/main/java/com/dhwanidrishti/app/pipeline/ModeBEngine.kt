package com.dhwanidrishti.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 * YOLO26m object detection
 *      ↓
 * MiDaS depth fusion
 *      ↓
 * Object tracking
 *      ↓
 * Risk evaluation
 *      ↓
 * Automatic voice announcement
 *
 * Voice commands:
 *
 * "Hey Dhwani, what's in front of me?"
 * "Hey Dhwani, what is in front of me?"
 * "Hey Dhwani, where is the door?"
 * "Hey Dhwani, where is the person?"
 * "Hey Dhwani, read"
 */
class ModeBEngine(
    context: Context,
    private val minDetectionIntervalMs: Long = 150L
) {

    companion object {
        private const val TAG = "ModeBEngine"

        /**
         * Your new 17-class YOLO26m LiteRT model.
         *
         * This file must exist in:
         *
         * app/src/main/assets/dhwani_drishti_17class.tflite
         */
        private const val MODEL_FILE =
            "dhwani_drishti_17class.tflite"
    }

    // =========================================================
    // MODELS
    // =========================================================

    /**
     * YOLO26m 17-class object detector.
     *
     * Model classes:
     *
     * 0  person
     * 1  bicycle
     * 2  car
     * 3  motorcycle
     * 4  truck
     * 5  stop sign
     * 6  bench
     * 7  dog
     * 8  chair
     * 9  bed
     * 10 laptop
     * 11 book
     * 12 bag
     * 13 door
     * 14 window
     * 15 stair
     * 16 pothole
     */
    private val detector =
        ObjectDetector(
            context = context,
            modelPath = MODEL_FILE
        )

    /**
     * Tracks detected objects between frames.
     *
     * Used for:
     *
     * - object identity
     * - approaching detection
     * - movement detection
     * - distance history
     */
    private val tracker =
        ObjectTracker()

    /**
     * Evaluates obstacle risk.
     */
    private val riskEngine =
        RiskEngine()

    /**
     * Handles spoken output.
     */
    private val announcements =
        AnnouncementManager(context)

    // =========================================================
    // DETECTION THROTTLING
    // =========================================================

    @Volatile
    private var lastDetectionMs: Long = 0L

    // =========================================================
    // LATEST SCENE
    // =========================================================

    /**
     * Most recent successfully tracked objects.
     *
     * This is deliberately preserved when YOLO temporarily
     * misses a frame.
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
    // SPEAKING STATE
    // =========================================================

    /**
     * Used by DhwaniPipeline to know when TTS is speaking.
     */
    val isSpeaking: Boolean
        get() = announcements.isSpeaking

    // =========================================================
    // PROCESS CAMERA FRAME
    // =========================================================

    /**
     * Processes one camera frame.
     *
     * Pipeline:
     *
     * 1. YOLO detection
     * 2. MiDaS depth fusion
     * 3. Object tracking
     * 4. Risk evaluation
     * 5. Automatic announcement
     *
     * @param closeness
     * MiDaS normalized closeness map.
     *
     * 1.0 = closest
     * 0.0 = farthest
     *
     * @param frame
     * Original camera frame.
     */
    fun process(
        closeness: Array<FloatArray>,
        frame: Bitmap
    ) {

        val now =
            System.currentTimeMillis()

        // =====================================================
        // 1. DETECTION THROTTLING
        // =====================================================

        if (
            now - lastDetectionMs <
            minDetectionIntervalMs
        ) {
            return
        }

        lastDetectionMs =
            now

        // =====================================================
        // 2. YOLO OBJECT DETECTION
        // =====================================================

        val rawDetections =
            try {

                detector.detect(frame)

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "YOLO detection failed",
                    e
                )

                emptyList()
            }

        Log.d(
            TAG,
            "YOLO detections: ${rawDetections.size}"
        )

        // -----------------------------------------------------
        // If YOLO sees nothing, keep the previous scene.
        // -----------------------------------------------------

        if (rawDetections.isEmpty()) {

            highestRisk =
                null

            return
        }

        // =====================================================
        // 3. DEPTH FUSION
        // =====================================================

        val detectedObjects =
            try {

                fuseDetectionsWithDepth(
                    detections = rawDetections,
                    depthMap = closeness
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Depth fusion failed",
                    e
                )

                emptyList()
            }

        if (detectedObjects.isEmpty()) {

            highestRisk =
                null

            return
        }

        Log.d(
            TAG,
            "Depth-fused objects: ${detectedObjects.size}"
        )

        // =====================================================
        // 4. OBJECT TRACKING
        // =====================================================

        val trackedObjects =
            try {

                tracker.update(
                    detectedObjects
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Object tracking failed",
                    e
                )

                emptyList()
            }

        // -----------------------------------------------------
        // Save latest valid scene.
        // -----------------------------------------------------

        latestTrackedObjects =
            trackedObjects.toList()

        trackedCount =
            trackedObjects.size

        Log.d(
            TAG,
            "Tracked objects: $trackedCount"
        )

        // =====================================================
        // 5. RISK EVALUATION
        // =====================================================

        var mostDangerousRisk:
                RiskResult? = null

        for (
        detectedObject
        in detectedObjects
        ) {

            val risk =
                try {

                    riskEngine.evaluate(
                        detectedObject
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Risk evaluation failed",
                        e
                    )

                    continue
                }

            if (
                mostDangerousRisk == null ||
                risk.score >
                mostDangerousRisk!!.score
            ) {

                mostDangerousRisk =
                    risk
            }
        }

        highestRisk =
            mostDangerousRisk

        // =====================================================
        // 6. AUTOMATIC ANNOUNCEMENT
        // =====================================================

        try {

            announcements.evaluate(
                tracked = trackedObjects,
                tracker = tracker
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Automatic announcement failed",
                e
            )
        }
    }

    // =========================================================
    // VOICE COMMAND
    //
    // "HEY DHWANI, WHAT'S IN FRONT OF ME?"
    // =========================================================

    /**
     * Describes the currently visible scene.
     *
     * Example:
     *
     * "I see a person and a laptop in front of you."
     */
    fun answerWhatIsInFront() {

        Log.d(
            TAG,
            "Voice command: what's in front of me"
        )

        val scene =
            latestTrackedObjects

        if (scene.isEmpty()) {

            announcements.speak(
                "I don't see any objects in front of you."
            )

            return
        }

        announcements.announceWhatIsInFront(
            scene
        )
    }

    // =========================================================
    // VOICE COMMAND
    //
    // "HEY DHWANI, WHERE IS THE DOOR?"
    // =========================================================

    /**
     * Finds a requested object in the latest scene and
     * announces its position.
     *
     * Examples:
     *
     * "Where is the door?"
     * -> "Door is on your left."
     *
     * "Where is the person?"
     * -> "Person is in front of you."
     *
     * "Where is the laptop?"
     * -> "Laptop is on your right."
     */
    fun answerWhereIs(
        objectName: String
    ) {

        Log.d(
            TAG,
            "Location query received: [$objectName]"
        )

        // -----------------------------------------------------
        // Normalize user's requested object name.
        // -----------------------------------------------------

        val requestedObject =
            objectName
                .trim()
                .lowercase()

        if (requestedObject.isEmpty()) {

            announcements.speak(
                "What object are you looking for?"
            )

            return
        }

        // -----------------------------------------------------
        // Get latest scene.
        // -----------------------------------------------------

        val scene =
            latestTrackedObjects

        if (scene.isEmpty()) {

            announcements.speak(
                "I don't currently see any objects."
            )

            return
        }

        // -----------------------------------------------------
        // Find matching objects.
        //
        // contains() makes the command more tolerant.
        //
        // Example:
        //
        // "door"
        // matches "door"
        //
        // "laptop"
        // matches "laptop"
        // -----------------------------------------------------

        val matchingObjects =
            scene.filter { trackedObject ->

                trackedObject.label
                    .trim()
                    .lowercase()
                    .contains(
                        requestedObject
                    )
            }

        // -----------------------------------------------------
        // Object not found.
        // -----------------------------------------------------

        if (matchingObjects.isEmpty()) {

            val spokenName =
                requestedObject
                    .replaceFirstChar {
                        it.uppercase()
                    }

            val message =
                "I don't see a $spokenName."

            Log.d(
                TAG,
                "Location query: object not found"
            )

            announcements.speak(
                message
            )

            return
        }

        // -----------------------------------------------------
        // If multiple matching objects exist,
        // select the closest one.
        //
        // lastDistance:
        //
        // 0.0 = closest
        // 1.0 = farthest
        // -----------------------------------------------------

        val trackedObject =
            matchingObjects.minByOrNull {
                it.lastDistance
            }

        if (trackedObject == null) {

            announcements.speak(
                "I found the object, but I cannot determine its position."
            )

            return
        }

        // =====================================================
        // DETERMINE LEFT / CENTER / RIGHT
        // =====================================================

        val position =
            when (
                trackedObject.lastZone.name
                    .uppercase()
            ) {

                "LEFT" ->
                    "on your left"

                "RIGHT" ->
                    "on your right"

                "CENTER" ->
                    "in front of you"

                else ->
                    "in your view"
            }

        // -----------------------------------------------------
        // Human-readable object name.
        // -----------------------------------------------------

        val spokenName =
            trackedObject.label
                .replaceFirstChar {
                    it.uppercase()
                }

        // -----------------------------------------------------
        // Final response.
        // -----------------------------------------------------

        val message =
            "$spokenName is $position."

        Log.d(
            TAG,
            "Location query result: $message"
        )

        announcements.speak(
            message
        )
    }

    // =========================================================
    // VOICE COMMAND
    //
    // "HEY DHWANI, READ"
    // =========================================================

    /**
     * Requests the current reading/OCR operation.
     */
    fun answerRead() {

        Log.d(
            TAG,
            "Voice command: read"
        )

        val scene =
            latestTrackedObjects

        announcements.announceRead(
            scene
        )
    }

    // =========================================================
    // DIRECT SPEECH
    // =========================================================

    /**
     * Allows other parts of the application, especially OCR,
     * to directly speak a message.
     */
    fun speak(
        text: String
    ) {

        if (text.isBlank()) {
            return
        }

        Log.d(
            TAG,
            "TTS -> [$text]"
        )

        announcements.speak(
            text
        )
    }

    // =========================================================
    // PUBLIC SCENE ACCESS
    // =========================================================

    /**
     * Returns the latest tracked scene.
     */
    fun getLatestTrackedObjects():
            List<ObjectTracker.TrackedObject> {

        return latestTrackedObjects
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    /**
     * Releases all Mode B resources.
     */
    fun shutdown() {

        Log.d(
            TAG,
            "Shutting down Mode B"
        )

        latestTrackedObjects =
            emptyList()

        trackedCount =
            0

        highestRisk =
            null

        try {

            announcements.shutdown()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error shutting down announcements",
                e
            )
        }

        try {

            detector.close()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error closing detector",
                e
            )
        }

        Log.d(
            TAG,
            "Mode B shutdown complete"
        )
    }
}