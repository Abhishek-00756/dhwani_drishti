package com.dhwanidrishti.app.ml

import com.dhwanidrishti.app.processing.DetectedObject

/**
 * Overall danger level of a detected object.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Result produced by RiskEngine for one detected object.
 *
 * score:
 * 0   -> practically no immediate risk
 * 100 -> extremely high immediate risk
 */
data class RiskResult(
    val level: RiskLevel,
    val score: Float,
    val reason: String
)

class RiskEngine {

    /**
     * Evaluate the immediate risk of a detected object.
     *
     * DetectedObject.distance semantics:
     * 0.0 = extremely close
     * 1.0 = far away
     *
     * The new YOLO26m model contains:
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
    fun evaluate(detectedObject: DetectedObject): RiskResult {

        val distance = detectedObject.distance.coerceIn(0f, 1f)
        val label = detectedObject.label.lowercase().trim()

        // ---------------------------------------------------------
        // 1. DISTANCE RISK
        //
        // Smaller distance = object is closer = greater danger.
        // ---------------------------------------------------------

        val distanceScore = when {
            distance <= 0.15f -> 80f
            distance <= 0.30f -> 65f
            distance <= 0.50f -> 45f
            distance <= 0.70f -> 25f
            else -> 10f
        }

        // ---------------------------------------------------------
        // 2. OBJECT IMPORTANCE
        //
        // These weights are based on the new 17-class model.
        //
        // Potholes and stairs receive especially high priority
        // because they are navigation hazards for a visually
        // impaired user.
        // ---------------------------------------------------------

        val objectWeight = when (label) {

            // -----------------------------------------------------
            // VERY HIGH PRIORITY NAVIGATION HAZARDS
            // -----------------------------------------------------

            "pothole" -> 35f

            "stair" -> 35f

            // -----------------------------------------------------
            // VEHICLES
            // -----------------------------------------------------

            "car" -> 30f
            "truck" -> 35f
            "motorcycle" -> 30f
            "bicycle" -> 25f

            // -----------------------------------------------------
            // HUMAN / ANIMAL
            // -----------------------------------------------------

            "person" -> 25f
            "dog" -> 18f

            // -----------------------------------------------------
            // TRAFFIC
            // -----------------------------------------------------

            "stop sign" -> 25f

            // -----------------------------------------------------
            // OBSTACLES
            // -----------------------------------------------------

            "bench" -> 18f
            "chair" -> 15f
            "bed" -> 10f

            // -----------------------------------------------------
            // OBJECTS
            // -----------------------------------------------------

            "bag" -> 12f
            "laptop" -> 8f
            "book" -> 5f

            // -----------------------------------------------------
            // ENVIRONMENT / STRUCTURE
            // -----------------------------------------------------

            "door" -> 15f
            "window" -> 10f

            // Unknown class
            else -> 5f
        }

        // ---------------------------------------------------------
        // 3. CENTER-ZONE RISK
        //
        // An object in the center of the camera is more likely
        // to be directly in the user's walking path.
        // ---------------------------------------------------------

        val zoneScore = when (detectedObject.zone.name) {

            "CENTER" -> 15f

            "LEFT",
            "RIGHT" -> 5f

            else -> 0f
        }

        // ---------------------------------------------------------
        // 4. SPECIAL HAZARD BONUS
        //
        // Stairs and potholes are dangerous even when they are
        // not extremely close.
        // ---------------------------------------------------------

        val hazardBonus = when (label) {

            "pothole" -> {
                when {
                    distance <= 0.30f -> 20f
                    distance <= 0.60f -> 15f
                    else -> 5f
                }
            }

            "stair" -> {
                when {
                    distance <= 0.30f -> 20f
                    distance <= 0.60f -> 15f
                    else -> 5f
                }
            }

            else -> 0f
        }

        // ---------------------------------------------------------
        // 5. CALCULATE TOTAL SCORE
        // ---------------------------------------------------------

        var score =
            distanceScore +
                    objectWeight +
                    zoneScore +
                    hazardBonus

        score = score.coerceIn(0f, 100f)

        // ---------------------------------------------------------
        // 6. CONVERT SCORE INTO RISK LEVEL
        // ---------------------------------------------------------

        val level = when {
            score >= 85f -> RiskLevel.CRITICAL
            score >= 60f -> RiskLevel.HIGH
            score >= 30f -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // ---------------------------------------------------------
        // 7. HUMAN-READABLE REASON
        //
        // Special wording is used for navigation hazards.
        // ---------------------------------------------------------

        val reason = when (label) {

            "pothole" -> when (level) {
                RiskLevel.CRITICAL ->
                    "Pothole extremely close"

                RiskLevel.HIGH ->
                    "Pothole ahead, be careful"

                RiskLevel.MEDIUM ->
                    "Pothole nearby"

                RiskLevel.LOW ->
                    "Pothole detected"
            }

            "stair" -> when (level) {
                RiskLevel.CRITICAL ->
                    "Stairs extremely close"

                RiskLevel.HIGH ->
                    "Stairs ahead, be careful"

                RiskLevel.MEDIUM ->
                    "Stairs nearby"

                RiskLevel.LOW ->
                    "Stairs detected"
            }

            "car",
            "truck",
            "motorcycle",
            "bicycle" -> when (level) {
                RiskLevel.CRITICAL ->
                    "$label extremely close"

                RiskLevel.HIGH ->
                    "$label very close"

                RiskLevel.MEDIUM ->
                    "$label nearby"

                RiskLevel.LOW ->
                    "$label detected"
            }

            "person" -> when (level) {
                RiskLevel.CRITICAL ->
                    "Person extremely close"

                RiskLevel.HIGH ->
                    "Person very close"

                RiskLevel.MEDIUM ->
                    "Person nearby"

                RiskLevel.LOW ->
                    "Person detected"
            }

            "stop sign" -> when (level) {
                RiskLevel.CRITICAL ->
                    "Stop sign very close"

                RiskLevel.HIGH ->
                    "Stop sign ahead"

                RiskLevel.MEDIUM ->
                    "Stop sign nearby"

                RiskLevel.LOW ->
                    "Stop sign detected"
            }

            "door" -> when (level) {
                RiskLevel.CRITICAL ->
                    "Door very close"

                RiskLevel.HIGH ->
                    "Door nearby"

                RiskLevel.MEDIUM ->
                    "Door nearby"

                RiskLevel.LOW ->
                    "Door detected"
            }

            "window" -> when (level) {
                RiskLevel.CRITICAL ->
                    "Window very close"

                RiskLevel.HIGH ->
                    "Window nearby"

                RiskLevel.MEDIUM ->
                    "Window nearby"

                RiskLevel.LOW ->
                    "Window detected"
            }

            else -> when (level) {
                RiskLevel.CRITICAL ->
                    "$label extremely close"

                RiskLevel.HIGH ->
                    "$label very close"

                RiskLevel.MEDIUM ->
                    "$label nearby"

                RiskLevel.LOW ->
                    "$label detected"
            }
        }

        return RiskResult(
            level = level,
            score = score,
            reason = reason
        )
    }
}