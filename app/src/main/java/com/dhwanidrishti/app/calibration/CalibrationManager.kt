package com.dhwanidrishti.app.calibration

import android.content.Context
import android.content.SharedPreferences

/**
 * MiDaS outputs *relative inverse depth* (larger = closer), not metric meters.
 * On first launch the user points the phone at a wall ~0.5m and ~3m away; the
 * raw model outputs are stored here and used to normalize each frame to a
 * closeness value in [0,1] (1 = nearest). Before calibration, falls back to
 * per-frame min/max normalization.
 */
class CalibrationManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isCalibrated: Boolean
        get() = prefs.contains(KEY_NEAR) && prefs.contains(KEY_FAR)

    var nearRaw: Float
        get() = prefs.getFloat(KEY_NEAR, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_NEAR, value).apply()

    var farRaw: Float
        get() = prefs.getFloat(KEY_FAR, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_FAR, value).apply()

    fun recordNear(value: Float) {
        nearRaw = value
    }

    fun recordFar(value: Float) {
        farRaw = value
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Maps a raw inverse-depth frame to closeness in [0,1]; 1 = nearest. */
    fun normalize(rawDepth: Array<FloatArray>): Array<FloatArray> {
        if (isCalibrated) {
            val near = nearRaw
            val far = farRaw
            if (near.isFinite() && far.isFinite() && near != far) {
                return calibratedNormalize(rawDepth, near, far)
            }
        }
        return perFrameNormalize(rawDepth)
    }

    private fun calibratedNormalize(
        rawDepth: Array<FloatArray>,
        near: Float,
        far: Float,
    ): Array<FloatArray> {
        val height = rawDepth.size
        val width = rawDepth[0].size
        val out = Array(height) { FloatArray(width) }
        val scale = 1f / (near - far)
        for (r in 0 until height) {
            for (c in 0 until width) {
                val v = rawDepth[r][c]
                out[r][c] = if (v.isFinite()) {
                    ((v - far) * scale).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
        return out
    }

    private fun perFrameNormalize(rawDepth: Array<FloatArray>): Array<FloatArray> {
        val height = rawDepth.size
        val width = rawDepth[0].size

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (r in 0 until height) {
            for (c in 0 until width) {
                val v = rawDepth[r][c]
                if (v.isFinite()) {
                    if (v < min) min = v
                    if (v > max) max = v
                }
            }
        }

        val out = Array(height) { FloatArray(width) }
        val range = max - min
        if (range <= 0f) return out

        for (r in 0 until height) {
            for (c in 0 until width) {
                val v = rawDepth[r][c]
                out[r][c] = if (v.isFinite()) {
                    ((v - min) / range).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
        return out
    }

    private companion object {
        const val PREFS_NAME = "depth_calibration"
        const val KEY_NEAR = "near_raw"
        const val KEY_FAR = "far_raw"
    }
}
