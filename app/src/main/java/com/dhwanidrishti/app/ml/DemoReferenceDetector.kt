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
 * The signatures below are generated from the supplied demo reference
 * images at a fixed 16x24 grayscale resolution. YOLO remains the primary
 * detector for COCO objects; this matcher only adds door/stair when YOLO
 * does not provide those labels.
 */
class DemoReferenceDetector {
    companion object {
        private const val TAG = "DemoReferenceDetector"
        private const val W = 16
        private const val H = 24
        private const val SIGNATURE_SIZE = W * H
        private const val MATCH_THRESHOLD = 0.78f
        private const val REQUIRED_FRAMES = 2
    }

    private data class Reference(val label: String, val signature: ByteArray)
    private var lastLabel: String? = null
    private var consecutiveMatches = 0

    private val references = listOf(
        Reference("door", decode("LjMR5+Ll6vH3+/387whOUS80Ffj29vwCBgkKBfIPT1AvNRn6/f8DCQ0PEAn0Fk5PMTUd/P4AAwkNDhEL9B5MTjE1If3///8ECQoPCvEjS0sxNSH+AAADCAwNEQnvJkpLMTMj/gABBAkNDRAH7itJSS8xIf8AAgYLDQ0PBe0uR0guLiAAAAMHDAwMDgHsMEVHKi0hAAACBgoLCwv/7DNERCouJP79AAQHCQoK/Ow1QUAqLif9+/8CBgcJCfvvNkA/DBIT+vj/AQUGBwb48DE4OOLm7vby+QIICQoJ+egLDQ3f5Orz4uPw+/3/APXmAgQD3uLo8+jx9/v9/vvu5QABAdzg5vD09vj8/v/96+T+/f3b3+Tu8vH09/n6+Ofj+vn42dvh6u/u7/Hz9PPj4vbz9Nja3ubq6+vt7/Du3+Hw6unY2dzj5Ojs7/Hz8dzg6NXQ1dfb4dzV3+Lk5+fY3928wNLU1Nvd3N/h5OTh0dnRs7PZ2tve4ePm5ufo5d3YxrKx")),
        Reference("door", decode("TTDc6vr///769O/t9CYvK1A54+r1/f7++vXw8PopMCtSQenp9wQGBQL99/P9KzIsVEfw5vQEBQQC/fr1/y01L1RN+OHxAwYGBgD89gEwNi9UUADe8QMICQgC/vkDMTUuVVEJ3fIECgoJBAD7BjEzLlZQE931BQsMCQQA/AgxMy5UThrd9AMLDAkDAf4LMjEtUUwi2vAACQwJAgD9DDIyLVJOLtrq/wkLCAL/+wswMS1TUDnf5/oJCgcC//oKMC8qSUU03+D4CAsHAf76BQb79BYVDdng9vcAAwD7+gDp3tsODQjd2+fw/Pz59fj95t3ZCwoH39np+P7/+/n2++Xd2QoIBuLY7v4A/vv39Pjj3NkIBgPl0+j7/fv38/H24tvZBQIA5s/k9vn39PDv9ODa2QIA/efM4PDz8e/s6/Hf2df//fvpyt7t8PDt6ufu39vYAP787MfZ5ujl4+Hh7NvW0/v38+XD0+Ln5+bl5eTX1tTx7Ofi4evs7O3q6Onn4d3Z")),
        Reference("door", decode("KSstKBQSGhobJD1DQ0JCQSkrLSoaFR0eHyYzNzUzNTIoKispHRIUEAsIBwQAACQrJikrKBnv7Onl5uns7fMeJSUoKSgb7Ofm4uPl6erwHCIlJygmHO/n5+Hi5enp7xsgJCUnJh7y5ufj5OXm5uwaHSMkJSUg9eTl3d3e4eDqGx4jIyQkIPjk49vb29zd6hoaIyMkJCD64uHa2NjZ2+kZGSMjIyMf/uDg2NfX19nqGBciIiMjHwDg3tjX1tXY6xUUIiIjIh4B3t7Y1tXV2+8TEiIiIiIdAt7c2NbV1dz1ERAiIiIgHATd3Nra2drb8w8NIiAgHxoG29nW1dXW1usNCiIgHx0YBtvY1tXV1dXV1OsEAR0aFxQRBdrV1dXV1dTsAf0aFhMSDwXb1NXV1dXU7QD7FhMREA0B7Ofl4d3a1+f09BQRDw0I+PP19/f18u7s6vISDwwKAfX29/b29vb2+Pj1")),

        // Stair references regenerated from the supplied stair photos.
        // Each decodes to exactly 16 * 24 = 384 bytes.
        Reference("stair", decode("1AwN3eDf2s/IxtLw4N/a4sv9/dzX3+DW0MvR4OPg3eTI5e3j2uXi08rJzc7b1+Hlta605Ojt6NXBvr29xc7e57elzv/x9vLizMbEwr/J7OvGtv0JAAH77tnQz83Mzvv3yNgCBgkHBAD69fDs6eb0/s33BgoVFhINCQYA9fHr8vrT7/b9Av/99fDr7Ork4e73wN/y+fr49fDr6u7u5+Pw9dgHGx8gHx0ZFAsKDQf89fjtEBgaHyEhHx0VDw0GAPr41d/p8wAA//4AAAD26+Tp8ePvBxYWDg4ODQsF9uje6u3uDxkiEwwNCw0MCwn96OLy9w8UFxAPEA8SEhEPDgj/GOn8BAcNFRENDxMSCgkHCx7wChUZHiQgHiIlIyMiIigy+RQbHSEjICMlJSMhJCgvOhkiHx4jJSQkKCQkJCguMjEhIx8eISIiIiYfHyIlMzIpGR0gHyIjIyQkHB8gIS4xLRUZHB4jJiUmIRseHh0pLikSFBkeJSclJB8dHRsYHB4g")),
        Reference("stair", decode("Fwri6eTd5+Db2tfU1wRAPBj72Onl5Ovn497Y1tz+REEb5sT18u7n8PDm39vd9UJGHta59QAABvXu9u7l4vI/TRzItOMQCwP69PHw7ervNFEXvrPICAwA+/j19fLt8SNQCrWyue4J/v/99e/p6/MTSfiys7HJ9/8A/PPr4+vzASfewtm0xOHs9vv27uHq9PkA1uHd1cna4uTs8u3p7fP0/+na8PTV3efp6u3v8vLt8f7f4wXy7Pz/AAADAgcF6vH/3AkF8gAKCQsPDQ0PDvPw/gYW//QIDhAUGBUWGBwF8f0hDfwAExMVGB4cHBseG/r7HQX5AhscFhsfHiEhJiX+9g4EAxUYHyMnKCwsJyQfDfgOCAslJCs2R15cTjksKxn5BwAIIB4iK0FmYEErJx7/6/wEExYZHh4oLSQYDw4F9e/yARMVGRsfJSopJBwQCQQA6PcGCg0QERQcGhUSCgUC/ODr7/P+AAEHDAkHA/n++ezx7+np6unp7Ovo5+bg4eDZ")),
        Reference("stair", decode("8+bTvbS0tsDEwsTN1+zk5fHr1b60tbbBxsXFx9bw4+ft8tbBtbW4w8jHyMnd8eTp6fbZyLq6u8LExMTG5u/m7Ob34c7GxMXLzMrJy+3t6e7h89/S0NDR2NfV1dXf3Oz53uTh3t7e3+Hi4+Tk5d7g8ubt7+zs7e7v8fj7+fXs6un2/QD8/QAAAwkKCgoJBgQC/gMC9vj8AAQSFBQTExAODvL6/Pf8AAMEBwgJCAgJBQL5AAQFCxIXGh0dHBsaGhYSAggQFxobHh4gIB0aGBQQC/b/ChAREBESDQsJBwUC/vsHFBkdHyMjIykrKikmIBUSJCcfHyAiIB0kJiMeGxcOCA4NCwsLCggHBQMB//3++PITFBUUFRkaGxsdHBgVFBEOGxwbHB0gIiIhIB0cGhcUERsaGhwbHR8fHR0cGxgXFREjHx4gHh0dGhcUERsaGhwbHR8fHR0cGxgXFREjHx4gHh0dHR0eHRwaFxUSJh8fHx4dHBsbHBwbGxgVEiYgHx8eHBsaGxwcGhkXFBIkISAdHRwbGRsYFxcVFhYT"))
    )

    init {
        references.forEach { require(it.signature.size == SIGNATURE_SIZE) { "Invalid ${it.label} reference signature size=${it.signature.size}" } }
        Log.d(TAG, "Loaded ${references.size} reference signatures")
    }

    fun detect(frame: Bitmap): List<RawDetection> {
        val frameSignature = signature(frame)
        val best = references.minByOrNull { distance(frameSignature, it.signature) } ?: return emptyList()
        val score = distance(frameSignature, best.signature)
        Log.d(TAG, "best=${best.label} score=${"%.3f".format(score)}")
        if (score > MATCH_THRESHOLD) { reset(); return emptyList() }
        if (best.label == lastLabel) consecutiveMatches++ else { lastLabel = best.label; consecutiveMatches = 1 }
        if (consecutiveMatches < REQUIRED_FRAMES) return emptyList()
        return listOf(RawDetection(best.label, RectF(0.08f, 0.05f, 0.92f, 0.97f), 0.90f))
    }

    fun reset() { lastLabel = null; consecutiveMatches = 0 }

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
            val gray = 0.299f * ((pixel shr 16) and 255) + 0.587f * ((pixel shr 8) and 255) + 0.114f * (pixel and 255)
            values[i] = gray; mean += gray
        }
        mean /= values.size
        var variance = 0f
        for (value in values) { val delta = value - mean; variance += delta * delta }
        val std = sqrt(variance / values.size).coerceAtLeast(1f)
        return ByteArray(SIGNATURE_SIZE) { i -> (((values[i] - mean) / std * 32f).coerceIn(-127f, 127f).toInt()).toByte() }
    }

    private fun distance(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size) return Float.POSITIVE_INFINITY
        var total = 0f
        for (i in a.indices) total += abs(a[i].toInt() - b[i].toInt())
        return total / a.size / 32f
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
}
