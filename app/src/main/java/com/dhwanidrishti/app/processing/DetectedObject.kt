package com.dhwanidrishti.app.processing

import android.graphics.RectF

/**
 * A single detection emitted by [com.dhwanidrishti.app.ml.ObjectDetector],
 * before depth fusion.
 */
data class RawDetection(
    val label: String,
    /** Normalized (0..1) coordinates relative to the source frame. */
    val boundingBox: RectF,
    val confidence: Float,
)

/**
 * A detection fused with the depth map so it can be announced with distance.
 *
 * [distance] uses *distance* semantics (0 = nearest, 1 = farthest), i.e. the
 * inverse of Mode A's closeness map, so that "smaller is closer" holds for
 * phrase building and the approaching detector. MiDaS gives inverse depth, so
 * fusing the closeness map and inverting is the honest reading of the raw
 * model output.
 */
data class DetectedObject(
    val label: String,
    val boundingBox: RectF,
    val confidence: Float,
    val distance: Float,
    val zone: Zone,
)

fun zoneFromX(normalizedX: Float): Zone = Zone.fromNormalizedX(normalizedX)

/**
 * Fuses raw detections with the depth map underneath each box centroid.
 *
 * [depthMap] is the Mode A closeness map (larger = closer, already normalized
 * by CalibrationManager). Low-confidence boxes are dropped first.
 */
fun fuseDetectionsWithDepth(
    detections: List<RawDetection>,
    depthMap: Array<FloatArray>,
): List<DetectedObject> {
    if (detections.isEmpty() || depthMap.isEmpty() || depthMap[0].isEmpty()) return emptyList()

    val height = depthMap.size
    val width = depthMap[0].size

    return detections
        .filter { it.confidence > CONFIDENCE_THRESHOLD }
        .map { d ->
            val cx = (d.boundingBox.centerX() * width).toInt()
            val cy = (d.boundingBox.centerY() * height).toInt()
            val closeness = depthMap[cy.coerceIn(0, height - 1)][cx.coerceIn(0, width - 1)]
            DetectedObject(
                label = d.label,
                boundingBox = d.boundingBox,
                confidence = d.confidence,
                distance = (1f - closeness).coerceIn(0f, 1f),
                zone = zoneFromX(d.boundingBox.centerX()),
            )
        }
}

private const val CONFIDENCE_THRESHOLD = 0.5f
