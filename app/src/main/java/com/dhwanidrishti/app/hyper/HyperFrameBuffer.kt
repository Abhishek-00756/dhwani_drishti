package com.dhwanidrishti.app.hyper

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns only the newest frame for Hyper mode.
 *
 * The buffer keeps its own bitmap copy so replacing/clearing a Hyper frame
 * can never recycle a bitmap that the normal camera or inference pipeline
 * is still using.
 */
class HyperFrameBuffer {
    private val latest = AtomicReference<Bitmap?>(null)

    fun submit(bitmap: Bitmap) {
        val ownedCopy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            return
        }

        latest.getAndSet(ownedCopy)?.let { old ->
            if (!old.isRecycled) {
                old.recycle()
            }
        }
    }

    /**
     * Returns an independent copy for the asynchronous Gemini request.
     */
    fun snapshot(): Bitmap? {
        val source = latest.get() ?: return null

        return try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        latest.getAndSet(null)?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }
}
