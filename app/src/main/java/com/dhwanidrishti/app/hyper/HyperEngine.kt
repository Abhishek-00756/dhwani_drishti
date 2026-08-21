package com.dhwanidrishti.app.hyper

import android.graphics.Bitmap
import android.util.Log
import com.dhwanidrishti.app.audio.AnnouncementManager
import com.dhwanidrishti.app.gemini.GeminiVisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hyper is an on-demand visual reasoning mode.
 *
 * It is deliberately independent from Soundscape and Narrated:
 *
 * - no YOLO
 * - no MiDaS
 * - no object tracking
 * - no continuous scene processing
 *
 * The latest camera frame is buffered only while Hyper mode is active.
 * Gemini is contacted only after the user asks a visual question.
 */
class HyperEngine(
    private val announcer: AnnouncementManager
) {
    companion object {
        private const val TAG = "HYPER_MODE"

        private const val CONTEXT = """
            You are Dhwani Hyper, an assistive visual reasoning assistant
            for a visually impaired user.

            Use the supplied camera frame as the primary source of truth.
            Describe only information that can actually be determined from
            the image. Prioritize objects, people, obstacles, signs, text,
            spatial position, and obvious hazards when relevant to the user's
            question.

            Do not invent objects or details. Do not claim exact physical
            distances from a single image. When position is relevant, use
            simple spoken descriptions such as left, center, or right.

            Keep answers concise, natural, and suitable for text-to-speech.
        """.trimIndent()
    }

    private val gemini = GeminiVisionEngine()
    private val frameBuffer = HyperFrameBuffer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestInFlight = AtomicBoolean(false)

    /**
     * Stores the newest frame for Hyper queries.
     * The buffer owns its copy, so the normal camera/inference pipeline
     * remains free to manage its own bitmap lifecycle.
     */
    fun submitFrame(bitmap: Bitmap) {
        frameBuffer.submit(bitmap)
    }

    /**
     * Sends exactly one recent frame plus the user's question to Gemini.
     * No continuous Gemini inference occurs here.
     */
    fun ask(question: String) {
        val cleanQuestion = question.trim()

        if (cleanQuestion.isBlank()) {
            return
        }

        if (!requestInFlight.compareAndSet(false, true)) {
            announcer.speak("Please wait for my previous answer.")
            return
        }

        val frame = frameBuffer.snapshot()
        if (frame == null) {
            requestInFlight.set(false)
            announcer.speak("I don't have a recent camera frame yet.")
            return
        }

        scope.launch {
            try {
                val contextualQuestion = """
                    $CONTEXT

                    User's question:
                    $cleanQuestion
                """.trimIndent()

                Log.d(
                    TAG,
                    "Sending one recent frame to Gemini for: $cleanQuestion"
                )

                val result =
                    gemini.analyzeImage(
                        frame,
                        contextualQuestion
                    )

                result.onSuccess { answer ->
                    announcer.speak(answer)
                }.onFailure { error ->
                    Log.e(TAG, "Gemini request failed", error)
                    announcer.speak("I couldn't analyze the scene right now.")
                }
            } finally {
                if (!frame.isRecycled) {
                    frame.recycle()
                }
                requestInFlight.set(false)
            }
        }
    }

    fun stop() {
        frameBuffer.clear()
        scope.cancel()
    }
}
