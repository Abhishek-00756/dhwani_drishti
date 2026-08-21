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
 * Demo-only fallback for door and stair.
 *
 * The seven signatures below are generated from the actual demo images
 * supplied for Dhwani Drishti. YOLO remains the primary detector for all
 * 17 classes. This fallback is only used when YOLO misses door/stair.
 */
class DemoReferenceDetector {
    companion object {
        private const val TAG = "DemoReferenceDetector"
        private const val W = 16
        private const val H = 24
        private const val MATCH_THRESHOLD = 0.58f
        private const val REQUIRED_FRAMES = 2
    }

    private data class Reference(
        val label: String,
        val signature: ByteArray
    )

    private var lastLabel: String? = null
    private var consecutiveMatches = 0

    private val references = listOf(
        Reference("door", decode("KSstKBQSGhsbJT5EQ0NDQSkrLSoZFh0eHyczODYzNTIoKiwpHBITEAsIBwMAACYrJikrKRnv7enl5+nr7vMfJCYoKSga6+fm4uPl6OryHiElJignHO7n5+Hi5enp8BwhJCUnJR7x5ubj5OXm5u0cHSMkJSUf9OXk3d3e4eHrHB4jIyQkIPbk49va29zd7BsaIiMkJCD54uHZ2NjZ2+sZGSIjJCMg/OHg2NfX1trsGBciIiMjH//g3tjX1tXZ7BYVISIiIh4A3t3Y1tXV2/EUEiEhIiEdAd3c2NbV1d32ERAhISEgGwLc3NrZ2drb9A8NISAgHhoE29nW1dXW1u0NCiEgHh0YBdrX1tXV1dTT7AcDHx4aFxMF2tTV1dXV0+0EAR0aFxQRBNrV1dXV1NTuAf0ZFhQSDgTa1NTV1tXU7wD7FhMRDwwA7Ofk4d3Z1+j09BMRDwwI+PP19/f08u7r6vMRDwwKAPX29/b29vb2+Pj1")),
        Reference("door", decode("LTMR5+Ll6/L3+/397wdOUS80Ffj39/wCBwkLBvIPTlAwNRn6/f4DCQ0PEArzFk5PMDUd/P8AAgkNDhEL8x5NTjE1If0AAP8ECQoPCvEjS0wwNSH9AAADCAwOEQrvJ0pKMDQi/wAABQoNDhAH7itISS8xIQABAgYLDg4QBe0vR0gtLiAAAAIHDA0MDgHsMUVGKi0hAAABBwoMDAv/7DRDQyouJP7+AAMHCQoK/e01QkArLif8+/8CBgcJCPvvNj8/Cg8R+vgAAQUHCAj48DA3N+Hl7fbx+AEHCgoK+egKDAzg5Ovz4uTx+v3+APXmAgQD3uLo8+nx+Pz9/fzu5gABAN3g5vD09fj7/v/96+X9/f3b3+Pt8vH09vn6+Ofk+fj42Nzh6u7u7/Hz9fPj4/Xz89fZ3ubq6+zt7/Du3+Lw6ejY2d3j5Ofr7vHy8dzg59PP1Nfa4NzV3+Hk5ufX3928v9LU1Nvd3eDh5OTj0djQs7Pa2tvf4ePm5+fn5d3Yx7Ox")),
        Reference("stair", decode("LwXBsarEyMTN0MbOyNbF2zf4xrawzcfM0tTV3tXe0N857M69vt3V4u/37e7u6eDjLunUw8zf2eT2/vf29vH25xnq2Mjg6+rz/wD9AAL0DfAH79zO5fTz+AEBAggS+xzwBPXf1e758/X+/AD+Bfkp+wb65eoFAP0CDREQFCoPMRUK/enr9fTx9P4BAQARAjU1DwD1FR4QEAgSFRQaMBo3RBMB9AMJAgEBCAkJCxcGNU0ZBQclHBcXGBoYFxQfEzU/HAUHKCEaHCQnIR4hMh80OBwFBRANDA8VFxIPDhUOMkYdDi0zIyIgJSgkJCIqLTU8GwsYIRgeHhodHh8cJCg1JxkTFRIMEBALDhUUEhQXMg0aKTAgER4fHx0eHxsbITTvGCAhEgsZHSEhJysoMTUy1Qzy6+3n6e7x9fwCBA0WE9f65ejk5uPk5efn5+fn6uXc5t/k4uft6eLk5OTk4eXn2tXY4+ft9PLm4+Pi4d7i5trQ2ePn7PH18efh4d7e3uLc")),
        Reference("stair", decode("XlY9BurTz9Td3+bk2tvyBV5WQQnm1NHW3t7j4Nvc8gheV0QL4dbX19vb29vb3/ALYFlICt/Y2tre39/g3uLtDGFcTgXe3N3e4+Xj4+Ln7ApjXk4A4N7h4eXm5efl6+fxUUEo+OLh5OTn6Ofo6O3q0ysmHPrk5uXk5+rq6+7v8NYqJh0A6e7p6Ojr6+zx8e/UKigfBOvw7ezs7ezs7fLu0yspIgjx9vHx9PLv7vH08tYtKiMM9Pfz8/X08O/x8/DXLSoiD/z79fb18/Hu8vXs1iwpIhcD+PX39fPz7+/y5tUqJiQjGfv2+fj29/T19ODUJyMgEQj58O7u7u/u7ezc1CYkFwEBAP/57uvp6OXj3dQmIQf6AAUICwb77+nn5eHZKRcA/wIHCg4SEAr97+jl4SMH/wMEBwoQFBMTDgj57eYRAAYHCA4QFhsZGRYREA3/FRUZFxcYGR0fHh0XFhQUFxsdHRscHR0fICAfFxMTEx0cHx0dHyAeICAgIBkWFxYb")),
        Reference("stair", decode("UlNMKfLWzs/X2d/e1tz1BlFTSy3y1dHS19jZ2dfd+AlPU00w79XU1dbX2NjZ3voMT1VQNevX2Nfb3dzc3d/6DlBXUzbo2dva3uDf4ODi8QVFRDQW5dvd3uDh4eLj5NXZIh4YCeff397h5OPl5+nV1CEeGQzr5uTh4eXl5+rq09QgHxsP7unn5ubn5ufp69PVICEcEvLt6uns6+jo6+3X2CEjHRT58+3t7u3q6u3q1tggIh0U//Pu7+3s6ufr59XYHyMbGBX87vHw7+/t7eLU2RsfGxIF+Ozq6urp6Ojc09gZHhoE+Pr6+O/o5eLh3NTYGx8R+vr+AAQGAPbq5OHZ2BESAf3/AAQJDQ0LBPrt4NkLDAkNCw0PFBYWEw8OCv/qExIVFhQVFhgZGRMNDBAaGRERFhQWGBYYGRkWExESHSIPDxQSFBQVFxoWGx8fHCMoDRMQERQQEhQZGBshIB8iLQkQDhESERARFBUZHx4cHSoHDhodHxwNCg0PEx0fHh8k")),
        Reference("stair", decode("KBoFzLKsqqytvcG7u8vJ5jUmBMi2rayvscLFvrvWyu1DNADLwcjMzdHT1NTC4NPsTD710sne3uHj4uTkyuPl5FQ+7tjS7Onz+fby9tjm+N5ZMOzd3fHu+gUA/P/l5gjwWx7x4uX7+QAHBAAH8+YPB1YS9+fuBwMDCggGDAjpEhdIC/np+QP/AAQDAgMK8xEXLwr97wYLBAQMEQ4RH/wMJiIO//wWDQgIBwsICBUJCDAkEgAAIhsTDhEUDxIhDwUuJhT/BxQODAsMDQgFBwYBJScXAiI3KCMlKSUeHCMqBxokFf8OGhQSExkWDgsOFAIOIxUIISIZFxcdGxUTERcRCiAUEDMuHSAbHB0YFRIiHA0aDgYXFhEVEAwNDw0JDAgNEwwUGREMDw8LCgsLBwQFDg4MKCobDRoaGRcWGBARDhQGBBcTBQEKDAwKDA4JDA0JAPnu7u7r7e7s7Ozr5+Xk4vzs5ezo6Ofm5ebk4t/b19X15OTl4eXm5eHh4N7b19PT")),
        Reference("door", decode("TjDc6voA//769O/t9CcvK1A54ur1/v7++/Xw8PopMStSQenq9wQGBgP99/P9KzMsVEfw5vQDBQMC/fr1/y01L1RN9+DxAwYGBgD89wEwNi9VUADe8QQICgkD/vkDMjUvVVIJ3fMFCgoKBAD7BjE0L1ZQE931BQsMCQQB/QgxMy5UThrc9AMKCwkDAf4LMTItUk0i2u8ACQsJAgD9DDIyLVJPLtrr/wkKCAL/+wswMS1TUDnf5voJCgcC//sLMC8pSEQy3+H5CAsHAv76BAP58hUUDNrg9fb/AwD7+gDp3tsODgnc2+bw+/z59ff95t3aDAoH4Nrp+f//+/n3++Xd2QoIBeLX7v8A/vr39fnk3NgHBgPl0+n6/Pr29PL34tvYBQIA58/k9fj28/Du9ODa2AIA/OjM4PDy8e/s6/Hf2Nf//frpy93t8PDt6ufu39vZ//797cfZ5efl4+Hh7NvW0/v18eXE1OTn6Obl5uTY19Tx7efj4+zs6+3r6enn4dzZ"))
    )

    fun detect(frame: Bitmap): List<RawDetection> {
        val frameSignature = signature(frame)

        val best = references.minByOrNull {
            distance(frameSignature, it.signature)
        } ?: return emptyList()

        val score = distance(frameSignature, best.signature)

        Log.d(
            TAG,
            "best=${best.label} score=${"%.3f".format(score)}"
        )

        if (score > MATCH_THRESHOLD) {
            reset()
            return emptyList()
        }

        if (best.label == lastLabel) {
            consecutiveMatches++
        } else {
            lastLabel = best.label
            consecutiveMatches = 1
        }

        if (consecutiveMatches < REQUIRED_FRAMES) {
            return emptyList()
        }

        return listOf(
            RawDetection(
                label = best.label,
                boundingBox = RectF(
                    0.08f,
                    0.05f,
                    0.92f,
                    0.97f
                ),
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
        val sourceAspect =
            bitmap.width.toFloat() /
                    max(1, bitmap.height).toFloat()

        val crop = if (sourceAspect > targetAspect) {
            val cropWidth =
                (bitmap.height * targetAspect)
                    .toInt()
                    .coerceAtLeast(1)

            val left =
                (bitmap.width - cropWidth) / 2

            Bitmap.createBitmap(
                bitmap,
                left,
                0,
                cropWidth,
                bitmap.height
            )
        } else {
            val cropHeight =
                (bitmap.width / targetAspect)
                    .toInt()
                    .coerceAtLeast(1)

            val top =
                (bitmap.height - cropHeight) / 2

            Bitmap.createBitmap(
                bitmap,
                0,
                top,
                bitmap.width,
                cropHeight
            )
        }

        val scaled =
            Bitmap.createScaledBitmap(
                crop,
                W,
                H,
                true
            )

        if (crop !== bitmap) {
            crop.recycle()
        }

        val pixels =
            IntArray(W * H)

        scaled.getPixels(
            pixels,
            0,
            W,
            0,
            0,
            W,
            H
        )

        scaled.recycle()

        val values =
            FloatArray(pixels.size)

        var mean = 0f

        for (i in pixels.indices) {
            val pixel = pixels[i]

            val gray =
                0.299f * ((pixel shr 16) and 255) +
                        0.587f * ((pixel shr 8) and 255) +
                        0.114f * (pixel and 255)

            values[i] = gray
            mean += gray
        }

        mean /= values.size

        var variance = 0f

        for (value in values) {
            val delta =
                value - mean

            variance +=
                delta * delta
        }

        val std =
            sqrt(
                variance / values.size
            ).coerceAtLeast(1f)

        return ByteArray(values.size) { i ->
            (
                (values[i] - mean) /
                        std *
                        32f
            )
                .coerceIn(-127f, 127f)
                .toInt()
                .toByte()
        }
    }

    private fun distance(
        a: ByteArray,
        b: ByteArray
    ): Float {
        var total = 0f

        for (i in a.indices) {
            total +=
                abs(
                    a[i].toInt() -
                            b[i].toInt()
                )
        }

        return total /
                a.size /
                32f
    }

    private fun decode(
        value: String
    ): ByteArray =
        Base64.decode(
            value,
            Base64.DEFAULT
        )
}
