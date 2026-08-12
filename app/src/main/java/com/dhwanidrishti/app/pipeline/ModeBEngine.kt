package com.dhwanidrishti.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.dhwanidrishti.app.audio.AnnouncementManager
import com.dhwanidrishti.app.ml.ObjectDetector
import com.dhwanidrishti.app.processing.ObjectTracker
import com.dhwanidrishti.app.processing.fuseDetectionsWithDepth

/**
 * Mode B (Narrated): object detection fused with the depth map, tracked across
 * frames, and announced only on meaningful events.
 *
 * Runs on the same inference thread as Mode A but throttled to ~5-8 fps —
 * objects don't appear/disappear as fast as depth changes, and detection is
 * heavier than depth estimation.
 */
class ModeBEngine(
    context: Context,
    private val minDetectionIntervalMs: Long = 150L,
) {

    private val detector = ObjectDetector(context)
    private val tracker = ObjectTracker()
    private val announcements = AnnouncementManager(context)

    @Volatile private var lastDetectionMs = 0L

    /** Latest number of live tracks, read by the UI debug overlay. */
    @Volatile var trackedCount: Int = 0
        private set

    /** True while an announcement is being spoken; drives hybrid ducking. */
    val isSpeaking: Boolean
        get() = announcements.isSpeaking

    /**
     * Called from the inference thread. [closeness] is the current normalized
     * depth map (1 = nearest); [frame] is the camera frame that produced it.
     */
    fun process(closeness: Array<FloatArray>, frame: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionMs < minDetectionIntervalMs) return
        lastDetectionMs = now

        val raw = detector.detect(frame)
        val fused = fuseDetectionsWithDepth(raw, closeness)
        val tracked = tracker.update(fused)
        trackedCount = tracked.size
        announcements.evaluate(tracked, tracker)
    }

    fun shutdown() {
        announcements.shutdown()
    }
}
