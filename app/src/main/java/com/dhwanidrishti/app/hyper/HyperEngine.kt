package com.dhwanidrishti.app.hyper

import android.graphics.Bitmap
import android.util.Log
import com.dhwanidrishti.app.audio.AnnouncementManager
import com.dhwanidrishti.app.gemini.GeminiVisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hyper mode is independent of local YOLO/MiDaS inference.
 * Camera frames are buffered, but Gemini is contacted only when
 * the user explicitly asks a visual question.
 */
class HyperEngine(
    private val announcer: AnnouncementManager
) {
    companion object {
        private const val TAG = "HYPER_MODE"
    }

    private val gemini = GeminiVisionEngine()
    private val frameBuffer = HyperFrameBuffer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestInFlight = AtomicBoolean(false)

    fun submitFrame(bitmap: Bitmap) {
        frameBuffer.submit(bitmap)
    }

    fun ask(question: String) {
        if (question.isBlank()) return
        if (!requestInFlight.compareAndSet(false, true)) {
            announcer.speak("Please wait for my previous answer.")
            return
        }

        val frame = frameBuffer.snapshot()
        if (frame == null) {
            requestInFlight.set(false)
            announcer.speak("I don't have a camera frame yet.")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Sending one frame to Gemini for: $question")
                val result = gemini.analyzeImage(frame, question)
                result.onSuccess { answer ->
                    announcer.speak(answer)
                }.onFailure { error ->
                    Log.e(TAG, "Gemini request failed", error)
                    announcer.speak("I couldn't analyze the scene right now.")
                }
            } finally {
                if (!frame.isRecycled) frame.recycle()
                requestInFlight.set(false)
            }
        }
    }

    fun stop() {
        frameBuffer.clear()
        scope.coroutineContext.cancel()
    }
}
