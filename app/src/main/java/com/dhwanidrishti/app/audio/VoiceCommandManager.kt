package com.dhwanidrishti.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Listens for voice commands such as:
 *
 * "Hey Dhwani, what's in front of me?"
 *
 * It continuously restarts SpeechRecognizer after each recognition
 * session so the user does not need to press a button every time.
 *
 * This is an in-app voice trigger.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onWhatIsInFront: () -> Unit
) {

    companion object {

        private const val TAG = "VoiceCommandManager"

        private const val RESTART_DELAY_MS = 500L
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var listening = false

    @Volatile
    private var commandTriggered = false

    private val restartRunnable =
        Runnable {
            if (listening) {
                startListeningInternal()
            }
        }

    // =========================================================
    // START
    // =========================================================

    fun start() {

        if (listening) {
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {

            Log.e(
                TAG,
                "Speech recognition is not available on this device."
            )

            return
        }

        listening = true

        createRecognizer()

        startListeningInternal()
    }

    // =========================================================
    // CREATE SPEECH RECOGNIZER
    // =========================================================

    private fun createRecognizer() {

        if (speechRecognizer != null) {
            return
        }

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(
                context
            )

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "User started speaking")
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {
                    // Not needed.
                }

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {
                    // Not needed.
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "User stopped speaking")
                }

                override fun onError(
                    error: Int
                ) {

                    Log.d(
                        TAG,
                        "Speech error: $error"
                    )

                    scheduleRestart()
                }

                override fun onResults(
                    results: Bundle?
                ) {

                    val matches =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    if (matches != null) {

                        for (text in matches) {

                            Log.d(
                                TAG,
                                "Recognized: $text"
                            )

                            if (isWhatIsInFrontCommand(text)) {

                                triggerWhatIsInFront()

                                break
                            }
                        }
                    }

                    scheduleRestart()
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {

                    val matches =
                        partialResults?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    if (matches != null) {

                        for (text in matches) {

                            /*
                             * We check partial results too.
                             *
                             * This means the command can trigger
                             * before the recognizer finishes the
                             * entire sentence on some devices.
                             */
                            if (isWhatIsInFrontCommand(text)) {

                                triggerWhatIsInFront()

                                return
                            }
                        }
                    }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {
                    // Not needed.
                }
            }
        )
    }

    // =========================================================
    // START LISTENING
    // =========================================================

    private fun startListeningInternal() {

        if (!listening) {
            return
        }

        if (speechRecognizer == null) {
            createRecognizer()
        }

        try {

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        Locale.getDefault()
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )

                    /*
                     * False means the recognizer may use network
                     * recognition if necessary.
                     *
                     * This is generally more reliable than forcing
                     * offline recognition on devices without a
                     * downloaded speech model.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_PREFER_OFFLINE,
                        false
                    )
                }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start speech recognition",
                e
            )

            scheduleRestart()
        }
    }

    // =========================================================
    // COMMAND DETECTION
    // =========================================================

    private fun isWhatIsInFrontCommand(
        text: String
    ): Boolean {

        val normalized =
            text
                .lowercase(Locale.US)
                .replace(
                    Regex("[^a-z0-9 ]"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        /*
         * Supported examples:
         *
         * "hey dhwani what's in front of me"
         * "hey dhwani what is in front of me"
         * "dhwani what's in front of me"
         * "dhwani what is in front of me"
         *
         * We also allow small recognition mistakes where
         * "hey" is omitted.
         */

        val hasDhwani =
            normalized.contains("dhwani") ||
                    normalized.contains("dhvani")

        val hasFrontQuestion =
            normalized.contains("what is in front of me") ||
                    normalized.contains("whats in front of me") ||
                    normalized.contains("what's in front of me") ||
                    normalized.contains("what in front of me") ||
                    normalized.contains("what do you see in front of me")

        return hasDhwani && hasFrontQuestion
    }

    // =========================================================
    // TRIGGER
    // =========================================================

    private fun triggerWhatIsInFront() {

        /*
         * Prevent the same partial/final result from triggering
         * multiple times.
         */
        if (commandTriggered) {
            return
        }

        commandTriggered = true

        Log.d(
            TAG,
            "WHAT IS IN FRONT COMMAND TRIGGERED"
        )

        /*
         * Stop recognition temporarily so TTS does not get
         * interpreted as another user command.
         */
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }

        onWhatIsInFront()

        /*
         * Allow another command after a short delay.
         */
        handler.postDelayed(
            {
                commandTriggered = false
            },
            2000L
        )
    }

    // =========================================================
    // RESTART
    // =========================================================

    private fun scheduleRestart() {

        if (!listening) {
            return
        }

        handler.removeCallbacks(
            restartRunnable
        )

        handler.postDelayed(
            restartRunnable,
            RESTART_DELAY_MS
        )
    }

    // =========================================================
    // STOP
    // =========================================================

    fun stop() {

        listening = false

        handler.removeCallbacks(
            restartRunnable
        )

        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}