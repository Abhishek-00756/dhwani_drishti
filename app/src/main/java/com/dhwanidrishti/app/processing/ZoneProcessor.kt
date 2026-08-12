package com.dhwanidrishti.app.processing

import java.util.Arrays

/**
 * Per-zone "closeness" values, in [0,1] where 1 = nearest. Derived from the
 * normalized inverse-depth map produced by CalibrationManager.
 */
data class ZoneDistances(val left: Float, val center: Float, val right: Float)

/**
 * Splits the depth map into 3 vertical sectors (L/C/R). The per-zone value is
 * the closest cluster, taken as the 95th percentile of closeness rather than
 * the strict max: a single hot pixel must not spike the reading.
 */
object ZoneProcessor {

    private const val CLOSEST_PERCENTILE = 95

    fun processZones(closeness: Array<FloatArray>): ZoneDistances {
        val height = closeness.size
        val width = closeness[0].size
        val third = width / 3

        val left = FloatArray(height * third)
        val center = FloatArray(height * third)
        val right = FloatArray(height * (width - third * 2))
        var li = 0
        var ci = 0
        var ri = 0

        for (row in 0 until height) {
            val line = closeness[row]
            for (x in 0 until width) {
                val d = line[x]
                if (!d.isFinite()) continue
                when {
                    x < third -> left[li++] = d
                    x < third * 2 -> center[ci++] = d
                    else -> right[ri++] = d
                }
            }
        }

        return ZoneDistances(
            left = percentile(left, li),
            center = percentile(center, ci),
            right = percentile(right, ri),
        )
    }

    private fun percentile(values: FloatArray, size: Int): Float {
        if (size == 0) return 0.5f
        Arrays.sort(values, 0, size)
        val index = ((size - 1) * CLOSEST_PERCENTILE) / 100
        return values[index.coerceIn(0, size - 1)]
    }
}
