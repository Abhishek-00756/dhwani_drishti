package com.dhwanidrishti.app.processing

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * Lightweight centroid tracker. Gives detections identity across frames so
 * the app can know whether the same object is getting closer, instead of
 * treating every frame's boxes as independent sightings. No re-ID model
 * needed — proximity + same label within a small distance is enough for
 * obstacle navigation.
 */
class ObjectTracker {

    private val tracked = mutableMapOf<Int, TrackedObject>()
    private var nextId = 0

    data class TrackedObject(
        val id: Int,
        var label: String,
        /** 0 = nearest, 1 = farthest. */
        var lastDistance: Float,
        var lastCentroid: PointF,
        var lastSeenMs: Long,
        val distanceHistory: ArrayDeque<Float> = ArrayDeque(),
    )

    /**
     * Matches each detection to the nearest existing track of the same label
     * within [MATCH_DISTANCE] (normalized frame distance), otherwise starts a
     * new track. Tracks not seen for [TRACK_TIMEOUT_MS] are dropped.
     */
    fun update(detections: List<DetectedObject>): List<TrackedObject> {
        val now = System.currentTimeMillis()
        val matched = mutableSetOf<Int>()

        for (det in detections) {
            val centroid = PointF(det.boundingBox.centerX(), det.boundingBox.centerY())
            val match = nearestMatch(det.label, centroid)
            if (match != null) {
                match.distanceHistory.addLast(det.distance)
                if (match.distanceHistory.size > HISTORY_SIZE) {
                    match.distanceHistory.removeFirst()
                }
                match.lastDistance = det.distance
                match.lastCentroid = centroid
                match.lastSeenMs = now
                matched.add(match.id)
            } else {
                val id = nextId++
                tracked[id] = TrackedObject(id, det.label, det.distance, centroid, now)
                matched.add(id)
            }
        }

        tracked.entries.removeAll { now - it.value.lastSeenMs > TRACK_TIMEOUT_MS }
        return tracked.values.toList()
    }

    private fun nearestMatch(label: String, centroid: PointF): TrackedObject? {
        var best: TrackedObject? = null
        var bestDistance = MATCH_DISTANCE
        for (obj in tracked.values) {
            if (obj.label != label) continue
            val d = distance(obj.lastCentroid, centroid)
            if (d < bestDistance) {
                bestDistance = d
                best = obj
            }
        }
        return best
    }

    /** Positive = getting closer, based on the recent distance trend. */
    fun isApproaching(obj: TrackedObject): Boolean {
        if (obj.distanceHistory.size < 4) return false
        val recent = obj.distanceHistory.takeLast(4)
        return recent.last() < recent.first() - APPROACH_DELTA
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        const val MATCH_DISTANCE = 0.15f
        const val HISTORY_SIZE = 8
        const val TRACK_TIMEOUT_MS = 2000L
        const val APPROACH_DELTA = 0.05f
    }
}
