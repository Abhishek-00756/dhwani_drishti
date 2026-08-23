package com.dhwanidrishti.app.ml

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.dhwanidrishti.app.processing.RawDetection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Demo/reference pothole detector.
 *
 * Uses the 8 supplied road-damage reference images. This is intentionally
 * aggressive for the Dhwani demo: low-confidence visual matches are accepted
 * so the app prefers warning the user over silently missing the road hazard.
 */
class PotholeReferenceDetector {
    companion object {
        private const val TAG = "PotholeReferenceDetector"
        private const val W = 16
        private const val H = 24
        private const val SIGNATURE_SIZE = W * H

        // Lower is a better visual match. A relaxed threshold is intentional
        // because camera framing, distance and lighting will differ from the
        // supplied reference photos.
        private const val MATCH_THRESHOLD = 1.10f

        // Announce/detect after the first matching frame. The normal narrated
        // pipeline already throttles camera inference, so requiring two frames
        // was unnecessarily likely to miss the hazard.
        private const val REQUIRED_FRAMES = 1

        // 8 supplied road-damage reference images, concatenated as
        // 8 * 384-byte grayscale signatures.
        private const val POTHOLE_SIGNATURES_B64 = "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"
    }

    private var lastMatch = false
    private var consecutiveMatches = 0

    init {
        val decoded = Base64.decode(POTHOLE_SIGNATURES_B64, Base64.DEFAULT)
        require(decoded.size % SIGNATURE_SIZE == 0) {
            "Invalid pothole signature block size=${decoded.size}"
        }
        Log.d(TAG, "Loaded ${decoded.size / SIGNATURE_SIZE} pothole reference signatures")
    }

    fun detect(frame: Bitmap): List<RawDetection> {
        if (frame.width <= 0 || frame.height <= 0) {
            reset()
            return emptyList()
        }

        val frameSignature = signature(frame)
        val references = unpack(POTHOLE_SIGNATURES_B64)
        val bestScore = references.minOfOrNull { distance(frameSignature, it) } ?: return emptyList()

        Log.d(TAG, "best pothole reference score=${"%.3f".format(bestScore)} threshold=$MATCH_THRESHOLD")

        if (bestScore > MATCH_THRESHOLD) {
            reset()
            return emptyList()
        }

        if (lastMatch) consecutiveMatches++ else {
            lastMatch = true
            consecutiveMatches = 1
        }

        if (consecutiveMatches < REQUIRED_FRAMES) return emptyList()

        return listOf(
            RawDetection(
                label = "pothole",
                boundingBox = RectF(0.05f, 0.35f, 0.95f, 0.98f),
                // Keep this above zero so the existing tracker and narrated
                // pipeline never discard a confirmed reference match.
                confidence = 0.55f
            )
        )
    }

    fun reset() {
        lastMatch = false
        consecutiveMatches = 0
    }

    private fun unpack(encoded: String): List<ByteArray> {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return (0 until bytes.size / SIGNATURE_SIZE).map { index ->
            val start = index * SIGNATURE_SIZE
            bytes.copyOfRange(start, start + SIGNATURE_SIZE)
        }
    }

    private fun signature(bitmap: Bitmap): ByteArray {
        val targetAspect = W.toFloat() / H.toFloat()
        val sourceAspect = bitmap.width.toFloat() / max(1, bitmap.height).toFloat()

        val crop = if (sourceAspect > targetAspect) {
            val cropWidth = (bitmap.height * targetAspect).toInt().coerceAtLeast(1)
            val left = (bitmap.width - cropWidth) / 2
            Bitmap.createBitmap(bitmap, left, 0, cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetAspect).toInt().coerceAtLeast(1)
            val top = (bitmap.height - cropHeight) / 2
            Bitmap.createBitmap(bitmap, 0, top, bitmap.width, cropHeight)
        }

        val scaled = Bitmap.createScaledBitmap(crop, W, H, true)
        if (crop !== bitmap) crop.recycle()

        val pixels = IntArray(SIGNATURE_SIZE)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        scaled.recycle()

        val values = FloatArray(SIGNATURE_SIZE)
        var mean = 0f
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val gray = 0.299f * ((pixel shr 16) and 255) +
                0.587f * ((pixel shr 8) and 255) +
                0.114f * (pixel and 255)
            values[i] = gray
            mean += gray
        }
        mean /= values.size

        var variance = 0f
        for (value in values) {
            val delta = value - mean
            variance += delta * delta
        }
        val std = sqrt(variance / values.size).coerceAtLeast(1f)

        return ByteArray(SIGNATURE_SIZE) { i ->
            (((values[i] - mean) / std * 32f)
                .coerceIn(-127f, 127f)
                .toInt()).toByte()
        }
    }

    private fun distance(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size) return Float.POSITIVE_INFINITY
        var total = 0f
        for (i in a.indices) total += abs(a[i].toInt() - b[i].toInt())
        return total / a.size / 32f
    }
}
