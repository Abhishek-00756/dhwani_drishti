package com.dhwanidrishti.app.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * In-app voice command listener for Dhwani.
 *
 * Supported command:
 *
 * "Hey Dhwani, what's in front of me?"
 *
 * The recognizer continuously restarts after each recognition session.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onWhatIsInFront: () -> Unit
) {

    companion object {
        private const val TAG = "DHWANI_VOICE"
        private const val RESTART_DELAY_MS = 700L
        private const val COMMAND_COOLDOWN_MS = 2500L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var listening = false

    @Volatile
    private var commandTriggered = false

    private var lastCommandTime = 0L

    private val restartRunnable = Runnable {
        if (listening) {
            Log.d(TAG, "Restarting speech recognition...")
            startListeningInternal()
        }
    }

    fun start() {

        Log.d(TAG, "================================")
        Log.d(TAG, "VoiceCommandManager.start()")
        Log.d(TAG, "================================")

        if (listening) {
            Log.d(TAG, "Already listening")
            return
        }

        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                TAG,
                "RECORD_AUDIO permission NOT GRANTED"
            )

            return
        }

        Log.d(TAG, "RECORD_AUDIO permission = GRANTED")

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {

            Log.e(
                TAG,
                "Speech recognition is NOT available on this device."
            )

            return
        }

        Log.d(
            TAG,
            "Speech recognition service is available."
        )

        listening = true

        createRecognizer()

        startListeningInternal()
    }

    private fun createRecognizer() {

        Log.d(TAG, "Creating SpeechRecognizer...")

        speechRecognizer?.destroy()

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {

                    Log.d(
                        TAG,
                        "READY FOR SPEECH - microphone should be active"
                    )
                }

                override fun onBeginningOfSpeech() {

                    Log.d(
                        TAG,
                        "BEGINNING OF SPEECH - user is speaking"
                    )
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {

                    /*
                     * Useful diagnostic.
                     *
                     * If this keeps changing while you speak,
                     * Android is receiving microphone audio.
                     */
                    if (rmsdB > -5f) {
                        Log.d(
                            TAG,
                            "MIC RMS = $rmsdB"
                        )
                    }
                }

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {

                    Log.d(
                        TAG,
                        "Audio buffer received"
                    )
                }

                override fun onEndOfSpeech() {

                    Log.d(
                        TAG,
                        "END OF SPEECH"
                    )
                }

                override fun onError(
                    error: Int
                ) {

                    Log.e(
                        TAG,
                        "SpeechRecognizer ERROR = $error (${errorToString(error)})"
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

                    Log.d(
                        TAG,
                        "FINAL RESULTS = $matches"
                    )

                    matches?.forEach { text ->

                        Log.d(
                            TAG,
                            "Recognized text: [$text]"
                        )

                        if (isWhatIsInFrontCommand(text)) {

                            Log.d(
                                TAG,
                                "COMMAND MATCHED: WHAT IS IN FRONT"
                            )

                            triggerWhatIsInFront()

                            return@forEach
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

                    if (!matches.isNullOrEmpty()) {

                        Log.d(
                            TAG,
                            "PARTIAL RESULTS = $matches"
                        )

                        matches.forEach { text ->

                            if (isWhatIsInFrontCommand(text)) {

                                Log.d(
                                    TAG,
                                    "PARTIAL COMMAND MATCHED"
                                )

                                triggerWhatIsInFront()

                                return@forEach
                            }
                        }
                    }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {

                    Log.d(
                        TAG,
                        "Speech event = $eventType"
                    )
                }
            }
        )

        Log.d(TAG, "SpeechRecognizer created")
    }

    private fun startListeningInternal() {

        if (!listening) {
            return
        }

        if (speechRecognizer == null) {

            Log.d(
                TAG,
                "Recognizer was null. Creating again."
            )

            createRecognizer()
        }

        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                TAG,
                "Cannot start recognition: RECORD_AUDIO permission missing"
            )

            return
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
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
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
                     * Do NOT force offline recognition.
                     *
                     * The phone may have better online speech
                     * recognition available.
                     */
                    putExtra(
                        RecognizerIntent.EXTRA_PREFER_OFFLINE,
                        false
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        context.packageName
                    )
                }

            Log.d(
                TAG,
                "Calling SpeechRecognizer.startListening()"
            )

            speechRecognizer?.startListening(intent)

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "SECURITY EXCEPTION while starting microphone",
                e
            )

            scheduleRestart()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "EXCEPTION while starting speech recognition",
                e
            )

            scheduleRestart()
        }
    }

    private fun isWhatIsInFrontCommand(
        text: String
    ): Boolean {

        val normalized = text
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        Log.d(TAG, "Normalized command: [$normalized]")

        /*
         * Speech recognition often mishears:
         *
         * "Hey Dhwani"
         *      -> "hi dhoni"
         *      -> "hi dhvni"
         *      -> "hey dhwani"
         *      -> "hey dhvani"
         *      -> "hey dhvni"
         *
         * So we don't require an exact wake phrase.
         */

        val wakeWordDetected =
            normalized.contains("hey dhwani") ||
                    normalized.contains("hey dhvani") ||
                    normalized.contains("hey dhvni") ||
                    normalized.contains("hi dhwani") ||
                    normalized.contains("hi dhvani") ||
                    normalized.contains("hi dhvni") ||
                    normalized.contains("hey dhoni") ||
                    normalized.contains("hi dhoni") ||
                    normalized.contains("dhwani") ||
                    normalized.contains("dhvani") ||
                    normalized.contains("dhvni") ||
                    normalized.contains("dhoni")

        if (!wakeWordDetected) {
            return false
        }

        /*
         * The important part of the command.
         *
         * Accept variations such as:
         *
         * "what's in front of me"
         * "whats in front of me"
         * "what is in front of me"
         * "what's in front"
         * "in front of me"
         * "front of me"
         */

        val asksWhatIsInFront =
            normalized.contains("what s in front of me") ||
                    normalized.contains("whats in front of me") ||
                    normalized.contains("what is in front of me") ||
                    normalized.contains("what in front of me") ||
                    normalized.contains("whats in front") ||
                    normalized.contains("what is in front") ||
                    normalized.contains("in front of me") ||
                    normalized.contains("front of me")

        Log.d(
            TAG,
            "Wake word detected=$wakeWordDetected, front question=$asksWhatIsInFront"
        )

        return asksWhatIsInFront
    }

    private fun triggerWhatIsInFront() {

        val now = System.currentTimeMillis()

        if (now - lastCommandTime < COMMAND_COOLDOWN_MS) {

            Log.d(
                TAG,
                "Ignoring duplicate command because of cooldown"
            )

            return
        }

        if (commandTriggered) {

            Log.d(
                TAG,
                "Command already triggered"
            )

            return
        }

        commandTriggered = true
        lastCommandTime = now

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "WHAT IS IN FRONT COMMAND TRIGGERED"
        )

        Log.d(
            TAG,
            "Calling pipeline callback..."
        )

        try {

            speechRecognizer?.stopListening()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping recognizer",
                e
            )
        }

        onWhatIsInFront()

        handler.postDelayed(
            {

                commandTriggered = false

                if (listening) {

                    Log.d(
                        TAG,
                        "Command cooldown finished"
                    )

                    scheduleRestart()
                }

            },
            COMMAND_COOLDOWN_MS
        )
    }

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

    private fun errorToString(
        error: Int
    ): String {

        return when (error) {

            SpeechRecognizer.ERROR_AUDIO ->
                "ERROR_AUDIO"

            SpeechRecognizer.ERROR_CLIENT ->
                "ERROR_CLIENT"

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "ERROR_INSUFFICIENT_PERMISSIONS"

            SpeechRecognizer.ERROR_NETWORK ->
                "ERROR_NETWORK"

            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "ERROR_NETWORK_TIMEOUT"

            SpeechRecognizer.ERROR_NO_MATCH ->
                "ERROR_NO_MATCH"

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "ERROR_RECOGNIZER_BUSY"

            SpeechRecognizer.ERROR_SERVER ->
                "ERROR_SERVER"

            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "ERROR_SPEECH_TIMEOUT"

            else ->
                "UNKNOWN_ERROR"
        }
    }

    fun stop() {

        Log.d(
            TAG,
            "Stopping VoiceCommandManager"
        )

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