package com.dhwanidrishti.app.ml

import com.dhwanidrishti.app.processing.DetectedObject
import kotlin.math.abs

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class RiskResult(
    val level: RiskLevel,
    val score: Float,
    val reason: String
)

class RiskEngine {

    fun evaluate(detectedObject: DetectedObject): RiskResult {

        val distance = detectedObject.distance
        val label = detectedObject.label.lowercase()

        // ---------------------------------------------------------
        // 1. Distance risk
        //
        // distance:
        // 0.0 = extremely close
        // 1.0 = far away
        // ---------------------------------------------------------

        val distanceScore = when {
            distance <= 0.15f -> 80f
            distance <= 0.30f -> 65f
            distance <= 0.50f -> 45f
            distance <= 0.70f -> 25f
            else -> 10f
        }

        // ---------------------------------------------------------
        // 2. Object importance
        // ---------------------------------------------------------

        val objectWeight = when (label) {

            // Highest priority
            "person" -> 20f
            "car" -> 25f
            "motorcycle" -> 25f
            "bus" -> 30f
            "truck" -> 30f
            "bicycle" -> 20f

            // Medium priority
            "chair" -> 12f
            "table" -> 12f
            "couch" -> 10f
            "bench" -> 15f

            // Personal objects
            "backpack" -> 10f
            "suitcase" -> 10f
            "laptop" -> 8f
            "handbag" -> 8f

            else -> 5f
        }

        // ---------------------------------------------------------
        // 3. Combine distance + object importance
        // ---------------------------------------------------------

        var score = distanceScore + objectWeight

        // ---------------------------------------------------------
        // 4. Center objects are more dangerous because they are
        // directly in the walking path.
        // ---------------------------------------------------------

        if (detectedObject.zone.name == "CENTER") {
            score += 10f
        }

        // ---------------------------------------------------------
        // 5. Clamp score
        // ---------------------------------------------------------

        score = score.coerceIn(0f, 100f)

        // ---------------------------------------------------------
        // 6. Convert score -> risk level
        // ---------------------------------------------------------

        val level = when {
            score >= 75f -> RiskLevel.CRITICAL
            score >= 50f -> RiskLevel.HIGH
            score >= 25f -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // ---------------------------------------------------------
        // 7. Human-readable reason
        // ---------------------------------------------------------

        val reason = when (level) {
            RiskLevel.CRITICAL ->
                "$label is extremely close"

            RiskLevel.HIGH ->
                "$label is very close"

            RiskLevel.MEDIUM ->
                "$label is nearby"

            RiskLevel.LOW ->
                "$label is far away"
        }

        return RiskResult(
            level = level,
            score = score,
            reason = reason
        )
    }
}