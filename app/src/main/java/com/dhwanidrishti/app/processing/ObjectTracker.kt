package com.dhwanidrishti.app.processing

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

/**
 * Tracks detected objects across consecutive camera frames.
 *
 * The detector gives us independent detections on every frame.
 * This class gives those detections stable identities so we can determine:
 *
 * - whether an object is the same object as in the previous frame
 * - whether an object is getting closer
 * - whether an object is moving left/right
 * - whether an object has disappeared
 *
 * Tracking is intentionally lightweight because this runs continuously
 * on an Android device.
 */
class ObjectTracker {

    companion object {

        /**
         * Maximum normalized centroid distance for matching.
         *
         * Coordinates are normalized:
         *
         * 0.0 = left/top
         * 1.0 = right/bottom
         *
         * 0.15 means two detections can be at most ~15% of the frame
         * apart to be considered the same object.
         */
        const val MATCH_DISTANCE = 0.15f

        /**
         * Number of distance samples retained for approach detection.
         */
        const val HISTORY_SIZE = 8

        /**
         * Remove an object if it has not been detected for this long.
         */
        const val TRACK_TIMEOUT_MS = 2000L

        /**
         * Minimum distance change required before we say that an object
         * is actually approaching.
         *
         * Distance semantics:
         *
         * 0.0 = closest
         * 1.0 = farthest
         *
         * Therefore:
         *
         * decreasing distance = approaching
         */
        const val APPROACH_DELTA = 0.05f

        /**
         * Number of recent samples used to determine approach.
         */
        const val APPROACH_SAMPLE_COUNT = 4

        /**
         * Object distance thresholds.
         *
         * These are relative depth values, NOT metres.
         *
         * 0.0 = closest
         * 1.0 = farthest
         */
        const val VERY_CLOSE_DISTANCE = 0.18f
        const val CLOSE_DISTANCE = 0.30f
        const val MEDIUM_DISTANCE = 0.60f

        /**
         * Minimum normalized horizontal movement before we classify
         * an object as moving left/right.
         */
        const val HORIZONTAL_MOVEMENT_DELTA = 0.08f
    }

    /**
     * All currently tracked objects.
     */
    private val tracked = mutableMapOf<Int, TrackedObject>()

    /**
     * ID assigned to the next new object.
     */
    private var nextId = 0

    /**
     * Internal representation of an object being tracked.
     */
    data class TrackedObject(
        val id: Int,

        /**
         * Detector class name.
         *
         * Examples:
         * person
         * laptop
         * door
         * stair
         * pothole
         */
        var label: String,

        /**
         * Distance estimate.
         *
         * 0.0 = closest
         * 1.0 = farthest
         */
        var lastDistance: Float,

        /**
         * Current normalized centroid.
         */
        var lastCentroid: PointF,

        /**
         * Last time this object was detected.
         */
        var lastSeenMs: Long,

        /**
         * Last normalized bounding box.
         *
         * Useful for determining whether an object occupies
         * a large part of the camera view.
         */
        var lastBoundingBox: RectF,

        /**
         * Current confidence.
         */
        var lastConfidence: Float,

        /**
         * Current spatial zone.
         *
         * LEFT / CENTER / RIGHT.
         */
        var lastZone: Zone,

        /**
         * Recent distance values.
         *
         * Used to determine whether the object is approaching.
         */
        val distanceHistory: ArrayDeque<Float> = ArrayDeque(),

        /**
         * Recent centroid positions.
         *
         * Used to estimate horizontal movement.
         */
        val centroidHistory: ArrayDeque<PointF> = ArrayDeque()
    )

    /**
     * Update the tracker using detections from the latest frame.
     *
     * Each detection is either:
     *
     * 1. matched to an existing object, or
     * 2. used to create a new tracked object.
     *
     * Existing objects that have disappeared for too long are removed.
     */
    fun update(
        detections: List<DetectedObject>
    ): List<TrackedObject> {

        val now = System.currentTimeMillis()

        /*
         * IDs already assigned during this frame.
         *
         * Once a track is matched, it cannot be reused for another
         * detection in the same frame.
         */
        val matchedTrackIds = mutableSetOf<Int>()

        /*
         * Process detections in confidence order.
         *
         * Higher-confidence detections get first access to existing tracks.
         */
        val sortedDetections = detections
            .filter { it.confidence > 0f }
            .sortedByDescending { it.confidence }

        for (det in sortedDetections) {

            val centroid = PointF(
                det.boundingBox.centerX(),
                det.boundingBox.centerY()
            )

            /*
             * Find the closest compatible track that has not already
             * been used in this frame.
             */
            val match = nearestMatch(
                label = det.label,
                centroid = centroid,
                excludedIds = matchedTrackIds
            )

            if (match != null) {

                /*
                 * Existing object.
                 */
                updateTrack(
                    track = match,
                    detection = det,
                    centroid = centroid,
                    now = now
                )

                matchedTrackIds.add(match.id)

            } else {

                /*
                 * New object.
                 */
                val newTrack = createTrack(
                    detection = det,
                    centroid = centroid,
                    now = now
                )

                tracked[newTrack.id] = newTrack
                matchedTrackIds.add(newTrack.id)
            }
        }

        /*
         * Remove objects that haven't been seen recently.
         */
        val iterator = tracked.iterator()

        while (iterator.hasNext()) {

            val entry = iterator.next()

            if (now - entry.value.lastSeenMs > TRACK_TIMEOUT_MS) {
                iterator.remove()
            }
        }

        /*
         * Return currently active tracks.
         */
        return tracked.values.toList()
    }

    /**
     * Update an existing tracked object with a new detection.
     */
    private fun updateTrack(
        track: TrackedObject,
        detection: DetectedObject,
        centroid: PointF,
        now: Long
    ) {

        /*
         * Add new distance sample.
         */
        track.distanceHistory.addLast(
            detection.distance.coerceIn(0f, 1f)
        )

        /*
         * Keep history bounded.
         */
        while (track.distanceHistory.size > HISTORY_SIZE) {
            track.distanceHistory.removeFirst()
        }

        /*
         * Add centroid sample.
         */
        track.centroidHistory.addLast(
            PointF(centroid.x, centroid.y)
        )

        while (track.centroidHistory.size > HISTORY_SIZE) {
            track.centroidHistory.removeFirst()
        }

        /*
         * Update current state.
         */
        track.lastDistance =
            detection.distance.coerceIn(0f, 1f)

        track.lastCentroid =
            PointF(centroid.x, centroid.y)

        track.lastBoundingBox =
            RectF(detection.boundingBox)

        track.lastConfidence =
            detection.confidence

        track.lastZone =
            detection.zone

        track.lastSeenMs =
            now
    }

    /**
     * Create a brand-new tracked object.
     */
    private fun createTrack(
        detection: DetectedObject,
        centroid: PointF,
        now: Long
    ): TrackedObject {

        val initialDistance =
            detection.distance.coerceIn(0f, 1f)

        val initialCentroid =
            PointF(centroid.x, centroid.y)

        return TrackedObject(
            id = nextId++,

            label = detection.label,

            lastDistance = initialDistance,

            lastCentroid = initialCentroid,

            lastSeenMs = now,

            lastBoundingBox =
                RectF(detection.boundingBox),

            lastConfidence =
                detection.confidence,

            lastZone =
                detection.zone,

            distanceHistory =
                ArrayDeque<Float>().apply {
                    addLast(initialDistance)
                },

            centroidHistory =
                ArrayDeque<PointF>().apply {
                    addLast(initialCentroid)
                }
        )
    }

    /**
     * Find the closest existing track matching a detection.
     *
     * Matching requires:
     *
     * 1. Same class label.
     * 2. Track not already matched this frame.
     * 3. Centroid distance below MATCH_DISTANCE.
     */
    private fun nearestMatch(
        label: String,
        centroid: PointF,
        excludedIds: Set<Int>
    ): TrackedObject? {

        var best: TrackedObject? = null

        var bestDistance =
            MATCH_DISTANCE

        for (obj in tracked.values) {

            /*
             * Never match different object classes.
             */
            if (obj.label != label) {
                continue
            }

            /*
             * Don't reuse a track in the same frame.
             */
            if (obj.id in excludedIds) {
                continue
            }

            val centroidDistance =
                distance(
                    obj.lastCentroid,
                    centroid
                )

            if (centroidDistance < bestDistance) {

                bestDistance =
                    centroidDistance

                best =
                    obj
            }
        }

        return best
    }

    /**
     * Determines whether an object is approaching the user.
     *
     * Distance semantics:
     *
     * 0 = close
     * 1 = far
     *
     * Therefore:
     *
     * 0.80 -> 0.70 -> 0.60
     *
     * means the object is approaching.
     *
     * 0.20 -> 0.30 -> 0.40
     *
     * means the object is moving away.
     */
    fun isApproaching(
        obj: TrackedObject
    ): Boolean {

        if (
            obj.distanceHistory.size <
            APPROACH_SAMPLE_COUNT
        ) {
            return false
        }

        val recent =
            obj.distanceHistory
                .takeLast(APPROACH_SAMPLE_COUNT)

        val first =
            recent.first()

        val last =
            recent.last()

        /*
         * Object is approaching if distance has decreased
         * by at least APPROACH_DELTA.
         */
        return last <
                first - APPROACH_DELTA
    }

    /**
     * Returns true if an object is moving away.
     */
    fun isMovingAway(
        obj: TrackedObject
    ): Boolean {

        if (
            obj.distanceHistory.size <
            APPROACH_SAMPLE_COUNT
        ) {
            return false
        }

        val recent =
            obj.distanceHistory
                .takeLast(APPROACH_SAMPLE_COUNT)

        val first =
            recent.first()

        val last =
            recent.last()

        return last >
                first + APPROACH_DELTA
    }

    /**
     * Returns true when an object is currently very close.
     *
     * This can be used by RiskEngine to produce messages such as:
     *
     * "Laptop very close."
     * "Person very close."
     */
    fun isVeryClose(
        obj: TrackedObject
    ): Boolean {

        return obj.lastDistance <=
                VERY_CLOSE_DISTANCE
    }

    /**
     * Returns true when an object is at an intermediate distance.
     */
    fun isClose(
        obj: TrackedObject
    ): Boolean {

        return obj.lastDistance <=
                CLOSE_DISTANCE
    }

    /**
     * Returns a human-readable distance category.
     *
     * This is deliberately relative rather than pretending that
     * MiDaS gives us an exact physical distance in metres.
     */
    fun distanceCategory(
        obj: TrackedObject
    ): DistanceCategory {

        return when {

            obj.lastDistance <=
                    VERY_CLOSE_DISTANCE ->
                DistanceCategory.VERY_CLOSE

            obj.lastDistance <=
                    CLOSE_DISTANCE ->
                DistanceCategory.CLOSE

            obj.lastDistance <=
                    MEDIUM_DISTANCE ->
                DistanceCategory.MEDIUM

            else ->
                DistanceCategory.FAR
        }
    }

    /**
     * Returns the horizontal movement direction of an object.
     *
     * Positive X = moving right.
     * Negative X = moving left.
     *
     * Small movement is classified as STABLE.
     */
    fun horizontalMovement(
        obj: TrackedObject
    ): HorizontalMovement {

        if (obj.centroidHistory.size < 3) {
            return HorizontalMovement.STABLE
        }

        val first =
            obj.centroidHistory.first()

        val last =
            obj.centroidHistory.last()

        val deltaX =
            last.x - first.x

        return when {

            deltaX <= -HORIZONTAL_MOVEMENT_DELTA ->
                HorizontalMovement.LEFT

            deltaX >= HORIZONTAL_MOVEMENT_DELTA ->
                HorizontalMovement.RIGHT

            else ->
                HorizontalMovement.STABLE
        }
    }

    /**
     * Returns the number of currently tracked objects.
     */
    fun activeCount(): Int {
        return tracked.size
    }

    /**
     * Clears all tracks.
     *
     * Useful when:
     *
     * - camera mode changes
     * - pipeline restarts
     * - app resumes
     * - calibration changes significantly
     */
    fun clear() {
        tracked.clear()
    }

    /**
     * Euclidean distance between two normalized points.
     */
    private fun distance(
        a: PointF,
        b: PointF
    ): Float {

        val dx =
            a.x - b.x

        val dy =
            a.y - b.y

        return sqrt(
            dx * dx + dy * dy
        )
    }

    /**
     * Relative distance categories.
     *
     * These are NOT metres.
     *
     * They are based on the normalized relative depth produced by
     * the current depth-fusion pipeline.
     */
    enum class DistanceCategory {

        VERY_CLOSE,
        CLOSE,
        MEDIUM,
        FAR
    }

    /**
     * Horizontal movement of an object.
     */
    enum class HorizontalMovement {

        LEFT,
        RIGHT,
        STABLE
    }
}