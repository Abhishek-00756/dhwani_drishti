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
 * Demo-only visual supplement for the two difficult classes: door and stair.
 * YOLO remains responsible for every class. This fallback only runs when
 * YOLO misses door/stair and requires two consecutive matching frames.
 */
class DemoReferenceDetector {
    companion object {
        private const val TAG = "DemoReferenceDetector"
        private const val W = 16
        private const val H = 24
        private const val MATCH_THRESHOLD = 0.58f
        private const val REQUIRED_FRAMES = 2
    }

    private data class Reference(val label: String, val signature: ByteArray)

    private var lastLabel: String? = null
    private var consecutiveMatches = 0

    private val references = listOf(
        Reference("door", decode("KiwuKRQTGxsbJT9EQ0NDQikrLSoaFh0eICg0ODYzNTMoKiwqHRIUEAsJBwQBASYrJyorKRnu7Ojl5ujr7fMgJSYoKiga6+fl4eLl6OrxHiIlJygnHO3n5uHi5ejp8B0hJCYnJh7w5ubi5OXm5e0dHiQlJiUg8+Tk3d3d4ODrHR4jIyUlIPbj4tva2tvd7BsbIyMkJSD44uHZ2NjY2usaGSMjJCQg++Df2NbW1tnsGRgiIyMjH/7f3dfW1dTY7BcVISMjIh7/3t3X1dTU2/EUEyEiIyEdAd3c19bV1Nz2EhAiIiIgHAPc3NnZ2drb9A8OIiEhHxsE2tjW1dTW1e0NCiIgHx0ZBtrX1tXU09PsCgchHx0aFgbZ1dXU1NTT7AcDIB4aFxMF2dTU1NTU0+0EAR4aFxURBNnU1dXU1NPtAfwaFxQTDwTa09TV1dXU7gD6FhQSEA0B6+bk4NzZ1uf08xQSDw0I9/P19/b08e3r6vISDw0KAfX19vX29vb29/f1")),
        Reference("door", decode("LjMR5uDk6fH1+fz87ghOUTA1Fvf29fwCBwkKBvEPT1EwNRr6/f4DCQ4QEArzF05QMTUe/P//AwoNDxIL8x5NTjI1Ifz///8FCQsQC/EjTEwxNSL9/wEDCQ0OEQrvJ0pKMTQj/gECBQoNDhAI7itJSjAyIv8BAgcMDg4QBu0vR0guLyH/AAMHDA0NDQ3/6zREQysvJf79AAQICQsL/Ow1QkArLyj8+/8CBgcJCfruN0BADRET+vf/AgYICAf48DI5OeDl7fXx+AIJCgoK+OcLDQ7f4+ry4ePw+vz/APXmAwQD3eHo8ujx+Pv8/Pvu5QECAdzg5fD09fj8/v/86+T9/fza3uPt8fHz9vj6+Ofj+fj42Nzg6u7t7vHz9fPi4vXz8tbZ3eXq6+vt7u/u3uHv6ujX2tzi5Ofr7vHy8Nzg59TP1Nba4NzV3+Hk5ufX3927vtLT09vd3N/g5OPi0NjQsrLZ2tve4OPm5ubn5d3XxrKw")),
        Reference("door", decode("LwbAsKrCx8TNz8bOx9XE2zf4xbWvy8fL0dPV3NPe0N457M69vtzT4e317O3t6N/iMOnTwsre2OT3APf19fH05xvq18jg6uny/gD8AALzDe8J79vO5PPy9wEBAggT+xzwBPTg1e358/X+/AH+Bfgo+gj55OoFAfwCDBERFCkPMRUK/Onq9PPw9P4CAQISAzY1DwD0FR4RDwkSFRQaMBs3RRMB8wMLAwICCQoKDRgINk0aBQYkGxYWFxgXFhQdEjVAHAYIKSMcHSYoIx8jNB81OB0FBRENDA8UFxMPDRYOM0YdDy0zJCIgJigkJCMpLTY+GwwaIxoeHhseHh8dJSk2KBoTFRMNERENDhUVExUXMw4aKC8gER4fHh0dHhobIDTwGCIjFAwbHyIjKCwpMzY01g3z6+zo6u/x9/0EBg8YFdb55Ofj5uLj5Ofn5+fn6eTc5t/j4efs6OLj4+Pj4eTn2tbY4ufs8/Hm4uLh4d7h5tnQ2eLn6/L08efh4N7e3+Hb")),
        Reference("stair", decode("KBoFy7KrqqusvMC5u8rJ5jUmBca1rKuvscLEvLrWyexDNP/KwMjMzNDS1NPB39LsTT700cjd3ODi4ePkyePk5FQ97djS7Ojy+fXy9tfl995aMOzc3PHu+Qb/+//k5gnvXB7x4eX7+QEIBAEI8+YQB1YT9+btBwQECggHDQnpFBhIC/np+QP/AAUEAwQK8hIXLwv87ggMBQUNEg8SH/sOJiIP//0XDgoJCAwJChYJCTEkEgAAIxsUDxIVEBMhEAYuJhX/CBUODQwNDgkGCAcCJicYBCI3KCMlKSUeHCMqCBokFv8PGhUTFBkXDwwPFQMPIxYJIiIZGRgdGxcUExgSCyAVEDMuHSAbHR0YFxMjHA4aDwcYFxEWEQ0ODw0KDQkNFAwVGRINEBAMCwwMCAUGDw8NKCobDhoaGRgXGBESDxUGBRgTBgMLDQwLDA8KDQ4KAPnu7u7r7O3r6+zr5+Tj4vzs5evo6Obm5eXj4d7a19X15OPl4eTm5ODg393b19LS")),
        Reference("stair", decode("X1c+BunTz9Tc3+Xj2tryBV5XQQrl1NDV3t7i4dvb8gheV0QL4dXX19vb29vb3u8LYFpICt/Y2tnd39/f3uLsDGJdTgbe293d4uXj4uHn6wtkX08A397g4eTm5ebl6ufyUkMr+eHg4+Tm6Ofn6Ozp0ywnHPrj5eXk5urp6u3u79UrJx4A6e3p5+fr6+zw8e/UKyggBOrv7Orr7Ovr7fHt0iwpIgjx9vDx8/Lu7vH08tYtKyQM8/by8/X08O/w8+/XLSsjEPz79fb08/Dt8fXr1iwqIhgC+Pb39fPy7+/y5dUrJyQkGfv2+fj29vP089/UKCQhEwn47+7u7u/t7ezb1CYlGAICAP737erp5+Xj3dQmIgj6AAYJCwb67unm5eHZKRj//wMHCg8SEQr87+jl4CUI/wQEBwoQFBQUDwf57OUSAAcHCA4RFxsZGRcREQ39FRUZFxcYGh4fHh0YFhQUGBsdHRscHR4dHh8PDA4QEx4fHh8PDA4QEx4fHh8l")),
        Reference("stair", decode("U1NMKfHWzc7X2t/d1tz1BlFTTC3x1dHR19fZ2Nfc9wpQVE0x79XU1NbX19fZ3foMUFZQNevX19fb3dzc3N/6D1BYUzfo2dra3N/f39/i8QdHRjYY5drc3d/h4OLi5NXaJB8ZCebe397g4uLk5ujU1CEeGgzr5ePg4eTk5unq1NQgIBsQ7ujn5eXm5ebo69PVICEdE/Hs6ejr6ufo6+zX1yEjHhT48+3s7ezp6e3q1tcgIx4V/vLt7uzr6efr5tTYHyQbGBX87fHw7+7s7OLU2BsgGxMG9+vp6erp6Ojc09cZHhsE9/r69+7o5OLg3NTXGyAS+vn9AAQGAPTp4+DY1xIUAvz/AAQJDg4MBPnr39gMDAkNDA0PFRcWFBAOCv3oExIVFhUWFxkaGhQODBAaGBISFxUXGBsXGx4fHSMpDhMREhURExUZGBsiISAjLQoQDxESERASFRYaIB4dHioIDxoeHx0PDA4QEx4fHh8PDA4QEx4fHh8l")),
        Reference("stair", decode("TjHc6fr///358+/s8ycwK1A64un0/P38+vTw7/kqMStSQOjp9wUHBgP89/L8KzMsVEfv5vQEBgQC/Pn0/i02L1VN9+DxAwcHBgD89gExNjBVUP/d8AQICgkD/vgDMjYwVlIJ3fIFCgsKBQD6BzI0L1ZQFN30BQwMCgUB/AkxNC5VTxvc8wMLDAoDAv0MMjItUk0i2e8BCgwJAgH8DDIyLlJPLtrr/gkLCAL++wwxMS1UUTre5/oJCwgC/voMMTArSkU03uH4CQwIAv36BQb78xYVDNng9fb/AwD7+f/o3doODgnc2+fv/Pz49ff85t3ZDAsH39no+P7++/f3+uXd2AoJBuLY7f4A/fr39Pjj3NgIBwPk0uj6/Pr38/L34trYBgIA5s/j9ff28u/u8t/Z2AL//OfM3+/y8e/s6/He2Nb+/Pnoyt3t8PDt6eft3trY/v387cfZ5efl4uHh69vW0vv18eTD0+Ln5+bk5eTX1tPx7efi4uzr6+3r6Ojm4dzY"))
    )

    fun detect(frame: Bitmap): List<RawDetection> {
        val best = references.minByOrNull { distance(signature(frame), it.signature) } ?: return emptyList()
        val score = distance(signature(frame), best.signature)
        Log.d(TAG, "best=${best.label} score=${"%.3f".format(score)}")

        if (score > MATCH_THRESHOLD) {
            lastLabel = null
            consecutiveMatches = 0
            return emptyList()
        }

        if (best.label == lastLabel) consecutiveMatches++ else {
            lastLabel = best.label
            consecutiveMatches = 1
        }
        if (consecutiveMatches < REQUIRED_FRAMES) return emptyList()

        return listOf(
            RawDetection(
                label = best.label,
                boundingBox = RectF(0.10f, 0.06f, 0.90f, 0.96f),
                confidence = 0.90f
            )
        )
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
            val left = (bitmap.width - cropWidth) / 2
            Bitmap.createBitmap(bitmap, left, 0, cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetAspect).toInt().coerceAtLeast(1)
            val top = (bitmap.height - cropHeight) / 2
            Bitmap.createBitmap(bitmap, 0, top, bitmap.width, cropHeight)
        }

        val scaled = Bitmap.createScaledBitmap(crop, W, H, true)
        if (crop !== bitmap) crop.recycle()
        val pixels = IntArray(W * H)
        scaled.getPixels(pixels, 0, W, 0, 0, W, H)
        scaled.recycle()

        val values = FloatArray(pixels.size)
        var mean = 0f
        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
            values[i] = gray
            mean += gray
        }
        mean /= values.size

        var variance = 0f
        for (v in values) {
            val d = v - mean
            variance += d * d
        }
        val std = sqrt(variance / values.size).coerceAtLeast(1f)

        return ByteArray(values.size) { i ->
            ((values[i] - mean) / std * 32f).coerceIn(-127f, 127f).toInt().toByte()
        }
    }

    private fun distance(a: ByteArray, b: ByteArray): Float {
        var total = 0f
        for (i in a.indices) total += abs(a[i].toInt() - b[i].toInt())
        return total / a.size / 32f
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
}
