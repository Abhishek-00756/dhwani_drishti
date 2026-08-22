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
 * The signatures below were regenerated from the supplied demo reference
 * images at a fixed 16x24 grayscale resolution. YOLO remains the primary
 * detector for COCO objects; this matcher only adds door/stair when YOLO
 * does not provide those labels.
 *
 * A match is deliberately confirmed only after two separate detect() calls.
 * ObjectDetector therefore calls this matcher once per camera frame rather
 * than twice on the same bitmap.
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
        Reference("door", decode("KSstKBQSGhobJD1DQ0JCQSkrLSoaFR0eHyYzNzUzNTIoKispHRIUEAsIBwQAACQrJikrKBnv7Onl5uns7fMeJSUoKSgb7Ofm4uPl6erwHCIlJygmHO/n5+Hi5enp7xsgJCUnJh7y5ufj5OXm5uwaHSMkJSUg9eTl3d3e4eDqGx4jIyQkIPjk49vb29zd6hoaIyMkJCD64uHa2NjZ2+kZGSMjIyMf/uDg2NfX19nqGBciIiMjHwDg3tjX1tXY6xUUIiIjIh4B3t7Y1tXV2+8TEiIiIiIdAt7c2NbV1dz1ERAiIiIgHATd3Nra2drb8w8NIiAgHxoG29nW1dXW1usNCiIgHx0YBtvY1tXV1NPqCQYhHh0aFgfa1tXV1dTT6gcDHx4aFxMG2tXV1dXV1OsEAR0aFxQRBdrV1dXV1dTsAf0aFhMSDwXb1NXV1dXU7QD7FhMREA0B7Ofl4d3a1+f09BQRDw0I+PP19/f18u7s6vISDwwKAfX29/b29vb2+Pj1")),
        Reference("stair", decode("UlNMKfLWzs/X2eDe1932B1BTSy3y1dHS19jZ2dfd+ApPVE0w8NXU1dbY2NjZ3voMT1VQNOvX2Njc3tzc3d/6D1BYUzbo2dvb3uDf3+Di8gZGRjUX5tvd3d/h4eLj5NbaIx4YCuff39/h4+Pl5+nV1CEeGQzr5uTh4uXl5+rq1NUgHxoP7unn5eXn5ubo69PVICEcEvLt6uns6ujp6+3X1yEiHhT59O3t7u3q6e3q1tggIh0T/vPu7+3s6ejr5tXZHyMbGBT87vHw7+/t7eLU2RsgGxIG+Ozq6urq6ejc09gZHRoE+Pr69+7o5eLg3NXYGx8S+vn+AAQFAPTq5ODZ1xIUAv3/AAQJDQ0LA/ns4NkLCwgMCg0PExYVFA8NCg0PExYVFA8NCf3oEhIVFhQVFhgZGRMNDA8ZFxISFhUWGBYYGRkWExERHSIPDxQTFBUVFxoXGx8eHCIoDRMREhUQEhUZGBsiIR8jLQoQDhASEBASFBYaHx4dHioHDhkcHhsOCw0QEx0eHR8m")),
        Reference("stair", decode("KRkGzrOtqqytvcG8usvI5TUmBcm3rqywscLFvrrWyexCNADMwMjNzdHT1NXB39PqTD730snd3eDj4uPly+Hm4lM/79jR7Onz+vby99nk+d1ZM+3d2/Hu+QUA/P/n5QjxXCDy4+T7+QAHBAEH9eUOBlcT+OftBgMDCgcGCwnpERlKDPrq+QL/AAQDAgMK8w8ZMQv97wYLBQQMEQ4QH/wLJiMP//0WDQkJBwsICBQKBzAlEgD/IxsTDhEUDxIgEQQtJxUABxUODAoLDQgFBgcBIygZBCA3KSMkKSUeGyIqCBgkFv8NGhQRExkWDgoOFAMMIxYIISIYFxcdGxUTERYSCSAUDjMuHB8bHB0XFREhHgwbDgYXFhAUEAwNDgwIDAkMFAsTGREMDw8LCgsLBgQFDQ4MJyobDBoaGRYVFxARDhMHBRcSBQIKDAwKDA4KCw0JAPru7u/r7e7s6+zs6OXj4vzt5uzo6Ofm5eXk4d7b2Nb15OTl4uXn5eDg397b2NPT")),
        Reference("stair", decode("LwXBsarDyMTN0MfNx9XF3Df4xraxzMjM0tTV3dXe0d857M69vt3V4u/37e7u6eDjL+nUw8ze2OX4//f29vH05hrq1sjh6+nz///9AALzDO8I8NzO5fPz+QEAAQYS+xzwA/Tg1O758/b//AAABfgo+gf55eoFAP4BDRAQEykPMBQJ/erq9fPw9f4BAQASAjU0DwD1FR4PDwgRFBMZMRo3RBMB9AILAgEBCAoJDBgINU0ZBQYiGhUVFhkXFRMcETQ/HAYIKiIcHSUnIh4iMx80OB0EBA8MCw8UGBIPDRUPM0UeDywyIiEfJicjIyIoKzU9GgsaIhkeHhseHR8cHRkZHzPxFyEkFQ0cHyIjKCwpMjUz1g307e3p6/Dz+P8GBxAZFtb75Oji5eLk5ejn6Ojn6ubd59/k4Obr5+Hk4uTl4ebm2dbY4+fr8/Hl4uLh4d7h59nR2ePm6/Hz8Obg4N7f3+Hd"))
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

        if (score > MATCH_THRESHOLD) {
            reset()
            return emptyList()
        }

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

    private fun signature(bitmap: Bitmap): ByteArray {
        val targetAspect = W.toFloat() / H.toFloat()
        val sourceAspect = bitmap.width.toFloat() / max(1, bitmap.height).toFloat()
        val crop = if (sourceAspect > targetAspect) {
            val cropWidth = (bitmap.height * targetAspect).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(bitmap, (bitmap.width - cropWidth) / 2, 0, cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetAspect).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(bitmap, 0, (bitmap.height - cropHeight) / 2, bitmap.width, cropHeight)
        }
        val scaled = Bitmap.createScaledBitmap(crop, W, H, true)
        if (crop !== bitmap) crop.recycle()
        val pixels = IntArray(SIGNATURE_SIZE)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        scaled.recycle()

        val values = FloatArray(SIGNATURE_SIZE)
        var mean = 0f
        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
            values[i] = gray
            mean += gray
        }
        mean /= values.size
        var variance = 0f
        for (v in values) { val d = v - mean; variance += d * d }
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
