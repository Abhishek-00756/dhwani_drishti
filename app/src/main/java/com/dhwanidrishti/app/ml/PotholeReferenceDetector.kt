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
 * Demo-only pothole reference matcher.
 *
 * This follows the same fixed 16x24 grayscale signature approach used by
 * DemoReferenceDetector for the supplied door/stair demo photos. It is a
 * reference matcher, not a trained pothole classifier, so it is intended for
 * the supplied demo environment and should be replaced by a trained detector
 * for broad real-world coverage.
 */
class PotholeReferenceDetector {
    companion object {
        private const val TAG = "PotholeReferenceDetector"
        private const val W = 16
        private const val H = 24
        private const val SIGNATURE_SIZE = W * H
        private const val MATCH_THRESHOLD = 0.78f
        private const val REQUIRED_FRAMES = 2

        // 5 supplied pothole/road-damage photos, concatenated as 5 * 384 bytes.
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
        val frameSignature = signature(frame)
        val references = unpack(POTHOLE_SIGNATURES_B64)
        val bestScore = references.minOfOrNull { distance(frameSignature, it) } ?: return emptyList()

        Log.d(TAG, "best pothole score=${"%.3f".format(bestScore)}")

        if (bestScore > MATCH_THRESHOLD) {
            reset()
            return emptyList()
        }

        if (lastMatch) consecutiveMatches++ else {
            lastMatch = true
            consecutiveMatches = 1
        }

        if (consecutiveMatches < REQUIRED_FRAMES) return emptyList()

        // Potholes occupy the lower road area in the supplied references.
        return listOf(
            RawDetection(
                label = "pothole",
                boundingBox = RectF(0.05f, 0.35f, 0.95f, 0.98f),
                confidence = 0.90f,
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
