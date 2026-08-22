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
 * Demo-only reference matcher for door and stair.
 *
 * Door and stair signatures are generated from supplied demo photos at a
 * fixed 16x24 grayscale resolution. YOLO remains the primary detector for
 * COCO objects; this matcher only adds door/stair when YOLO does not provide
 * those labels.
 */
class DemoReferenceDetector {
    companion object {
        private const val TAG = "DemoReferenceDetector"
        private const val W = 16
        private const val H = 24
        private const val SIGNATURE_SIZE = W * H
        private const val MATCH_THRESHOLD = 0.78f
        private const val REQUIRED_FRAMES = 2

        // 11 supplied door photos, concatenated as 11 * 384 bytes.
        private const val DOOR_SIGNATURES_B64 = "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"

        // 3 existing stair references, concatenated as 3 * 384 bytes.
        private const val STAIR_SIGNATURES_B64 = "1AwN3eDf2s/IxtLw4N/a4sv9/dzX3+DW0MvR4OPg3eTI5e3j2uXi08rJzc7b1+Hlta605Ojt6NXBvr29xc7e57elzv/x9vLizMbEwr/J7OvGtv0JAAH77tnQz83Mzvv3yNgCBgkHBAD69fDs6eb0/s33BgoVFhINCQYA9fHr8vrT7/b9Av/99fDr7Ork4e73wN/y+fr49fDr6u7u5+Pw9dgHGx8gHx0ZFAsKDQf89fjtEBgaHyEhHx0VDw0GAPr41d/p8wAA//4AAAD26+Tp8ePvBxYWDg4ODQsF9uje6u3uDxkiEwwNCw0MCwn96OLy9w8UFxAPEA8SEhEPDgj/GOn8BAcNFRENDxMSCgkHCx7wChUZHiQgHiIlIyMiIigy+RQbHSEjICMlJSMhJCgvOhkiHx4jJSQkKCQkJCguMjEhIx8eISIiIiYfHyIlMzIpGR0gHyIjIyQkHB8gIS4xLRUZHB4jJiUmIRseHh0pLikSFBkeJSclJB8dHRsYHB4gFwri6eTd5+Db2tfU1wRAPBj72Onl5Ovn497Y1tz+REEb5sT18u7n8PDm39vd9UJGHta59QAABvXu9u7l4vI/TRzItOMQCwP69PHw7ervNFEXvrPICAwA+/j19fLt8SNQCrWyue4J/v/99e/p6/MTSfiys7HJ9/8A/PPr4+vzASfewtm0xOHs9vv27uHq9PkA1uHd1cna4uTs8u3p7fP0/+na8PTV3efp6u3v8vLt8f7f4wXy7Pz/AAADAgcF6vH/3AkF8gAKCQsPDQ0PDvPw/gYW//QIDhAUGBUWGBwF8f0hDfwAExMVGB4cHBseG/r7HQX5AhscFhsfHiEhJiX+9g4EAxUYHyMnKCwsJyQfDfgOCAslJCs2R15cTjksKxn5BwAIIB4iK0FmYEErJx7/6/wEExYZHh4oLSQYDw4F9e/yARMVGRsfJSopJBwQCQQA6PcGCg0QERQcGhUSCgUC/ODr7/P+AAEHDAkHA/n++ezx7+np6unp7Ovo5+bg4eDZ8+bTvbS0tsDEwsTN1+zk5fHr1b60tbbBxsXFx9bw4+ft8tbBtbW4w8jHyMnd8eTp6fbZyLq6u8LExMTG5u/m7Ob34c7GxMXLzMrJy+3t6e7h89/S0NDR2NfV1dXf3Oz53uTh3t7e3+Hi4+Tk5d7g8ubt7+zs7e7v8fj7+fXs6un2/QD8/QAAAwkKCgoJBgQC/gMC9vj8AAQSFBQTExAODvL6/Pf8AAMEBwgJCAgJBQL5AAQFCxIXGh0dHBsaGhYSAggQFxobHh4gIB0aGBQQC/b/ChAREBESDQsJBwUC/vsHFBkdHyMjIykrKikmIBUSJCcfHyAiIB0kJiMeGxcOCA4NCwsLCggHBQMB//3++PITFBUUFRkaGxsdHBgVFBEOGxwbHR8fHR0cGxgXFREjHx4gHh0dGhcUERsaGhwbHR8fHR0cGxgXFREjHx4gHh0dHR0eHRwaFxUSJh8fHx4dHBsbHBwbGxgVEiYgHx8eHBsaGxwcGhkXFBIkISAdHRwbGRsYFxcVFhYT"
    }

    private data class Reference(val label: String, val signature: ByteArray)
    private var lastLabel: String? = null
    private var consecutiveMatches = 0

    private val references: List<Reference> by lazy {
        unpack("door", DOOR_SIGNATURES_B64) + unpack("stair", STAIR_SIGNATURES_B64)
    }

    init {
        references.forEach {
            require(it.signature.size == SIGNATURE_SIZE) {
                "Invalid ${it.label} reference signature size=${it.signature.size}"
            }
        }
        Log.d(TAG, "Loaded ${references.size} reference signatures")
    }

    fun detect(frame: Bitmap): List<RawDetection> {
        val frameSignature = signature(frame)
        val best = references.minByOrNull { distance(frameSignature, it.signature) } ?: return emptyList()
        val score = distance(frameSignature, best.signature)
        Log.d(TAG, "best=${best.label} score=${"%.3f".format(score)}")
        if (score > MATCH_THRESHOLD) { reset(); return emptyList() }
        if (best.label == lastLabel) consecutiveMatches++ else {
            lastLabel = best.label
            consecutiveMatches = 1
        }
        if (consecutiveMatches < REQUIRED_FRAMES) return emptyList()
        return listOf(RawDetection(best.label, RectF(0.08f, 0.05f, 0.92f, 0.97f), 0.90f))
    }

    fun reset() {
        lastLabel = null
        consecutiveMatches = 0
    }

    private fun unpack(label: String, encoded: String): List<Reference> {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size % SIGNATURE_SIZE == 0) {
            "Invalid $label signature block size=${bytes.size}"
        }
        return (0 until bytes.size step SIGNATURE_SIZE).map { start ->
            Reference(label, bytes.copyOfRange(start, start + SIGNATURE_SIZE))
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
