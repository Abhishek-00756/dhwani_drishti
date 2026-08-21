package com.dhwanidrishti.app.hyper

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/** Keeps only the newest camera frame for Hyper mode. */
class HyperFrameBuffer {
    private val latest = AtomicReference<Bitmap?>(null)

    fun submit(bitmap: Bitmap) {
        latest.getAndSet(bitmap)?.let { old ->
            if (old !== bitmap && !old.isRecycled) old.recycle()
        }
    }

    fun snapshot(): Bitmap? {
        val source = latest.get() ?: return null
        return try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        latest.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
    }
}
