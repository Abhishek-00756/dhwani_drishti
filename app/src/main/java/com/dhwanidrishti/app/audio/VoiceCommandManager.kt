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
 * Continuous voice command listener for Dhwani.
 *
 * Supported commands:
 *
 * 1. "Hey Dhwani, what's in front of me?"
 * 2. "Hey Dhwani, read"
 * 3. "Hey Dhwani, read this"
 *
 * Speech recognition continuously restarts after each session.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onWhatIsInFront: () -> Unit,
    private val onRead: () -> Unit
) {

    companion object {
        private const val TAG = "DHWANI_VOICE"

        private const val RESTART_DELAY_MS = 700L
        private const val COMMAND_COOLDOWN_MS = 2500L
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var listening = false

    @Volatile
    private var commandTriggered = false

    private var lastCommandTime = 0L

    private val restartRunnable = Runnable {
        if (listening) {
            Log.d(
                TAG,
                "Restarting speech recognition..."
            )

            startListeningInternal()
        }
    }

    // =========================================================
    // START
    // =========================================================

    fun start() {

        Log.d(TAG, "================================")
        Log.d(TAG, "VoiceCommandManager.start()")
        Log.d(TAG, "================================")

        if (listening) {
            Log.d(TAG, "Already listening")
            return
        }

        val permission =
            ContextCompat.checkSelfPermission(
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

        Log.d(
            TAG,
            "RECORD_AUDIO permission = GRANTED"
        )

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {

            Log.e(
                TAG,
                "Speech recognition is NOT available."
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

    // =========================================================
    // CREATE RECOGNIZER
    // =========================================================

    private fun createRecognizer() {

        Log.d(
            TAG,
            "Creating SpeechRecognizer..."
        )

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
                        "BEGINNING OF SPEECH"
                    )
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {

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

                // =====================================================
                // FINAL RESULTS
                // =====================================================

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

                        when {

                            isReadCommand(text) -> {

                                Log.d(
                                    TAG,
                                    "COMMAND MATCHED: READ"
                                )

                                triggerRead()

                                return@forEach
                            }

                            isWhatIsInFrontCommand(text) -> {

                                Log.d(
                                    TAG,
                                    "COMMAND MATCHED: WHAT IS IN FRONT"
                                )

                                triggerWhatIsInFront()

                                return@forEach
                            }
                        }
                    }

                    scheduleRestart()
                }

                // =====================================================
                // PARTIAL RESULTS
                // =====================================================

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

                            when {

                                isReadCommand(text) -> {

                                    Log.d(
                                        TAG,
                                        "PARTIAL COMMAND MATCHED: READ"
                                    )

                                    triggerRead()

                                    return@forEach
                                }

                                isWhatIsInFrontCommand(text) -> {

                                    Log.d(
                                        TAG,
                                        "PARTIAL COMMAND MATCHED: WHAT IS IN FRONT"
                                    )

                                    triggerWhatIsInFront()

                                    return@forEach
                                }
                            }
                        }
                    }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {
                }
            }
        )

        Log.d(
            TAG,
            "SpeechRecognizer created"
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

        val permission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            )

        if (permission != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                TAG,
                "RECORD_AUDIO permission missing"
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

                    // Online recognition is allowed.
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
                "SECURITY EXCEPTION",
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

    // =========================================================
    // NORMALIZE TEXT
    // =========================================================

    private fun normalize(
        text: String
    ): String {

        return text
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
    }

    // =========================================================
    // WAKE WORD
    // =========================================================

    private fun hasWakeWord(
        normalized: String
    ): Boolean {

        return normalized.contains("hey dhwani") ||
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
    }

    // =========================================================
    // WHAT IS IN FRONT
    // =========================================================

    private fun isWhatIsInFrontCommand(
        text: String
    ): Boolean {

        val normalized =
            normalize(text)

        Log.d(
            TAG,
            "Checking front command: [$normalized]"
        )

        if (!hasWakeWord(normalized)) {
            return false
        }

        val asksWhatIsInFront =
            normalized.contains(
                "what s in front of me"
            ) ||
                    normalized.contains(
                        "whats in front of me"
                    ) ||
                    normalized.contains(
                        "what is in front of me"
                    ) ||
                    normalized.contains(
                        "what in front of me"
                    ) ||
                    normalized.contains(
                        "whats in front"
                    ) ||
                    normalized.contains(
                        "what is in front"
                    ) ||
                    normalized.contains(
                        "in front of me"
                    ) ||
                    normalized.contains(
                        "front of me"
                    )

        Log.d(
            TAG,
            "Front command = $asksWhatIsInFront"
        )

        return asksWhatIsInFront
    }

    // =========================================================
    // READ COMMAND
    // =========================================================

    private fun isReadCommand(
        text: String
    ): Boolean {

        val normalized =
            normalize(text)

        Log.d(
            TAG,
            "Checking read command: [$normalized]"
        )

        if (!hasWakeWord(normalized)) {
            return false
        }

        /*
         * Accept:
         *
         * "Hey Dhwani read"
         * "Hey Dhwani read this"
         * "Hey Dhwani please read"
         * "Dhwani read"
         * "Hi Dhwani read this"
         *
         * Also tolerate speech-recognition variations.
         */

        val asksToRead =
            normalized == "read" ||
                    normalized.endsWith(" read") ||
                    normalized.contains(" read ") ||
                    normalized.endsWith(" read this") ||
                    normalized.contains(" read this") ||
                    normalized.contains("please read") ||
                    normalized.contains("read it") ||
                    normalized.contains("read this")

        Log.d(
            TAG,
            "Read command = $asksToRead"
        )

        return asksToRead
    }

    // =========================================================
    // TRIGGER WHAT IS IN FRONT
    // =========================================================

    private fun triggerWhatIsInFront() {

        if (!canTriggerCommand()) {
            return
        }

        commandTriggered = true

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "WHAT IS IN FRONT COMMAND TRIGGERED"
        )

        stopCurrentRecognition()

        onWhatIsInFront()

        finishCommandCooldown()
    }

    // =========================================================
    // TRIGGER READ
    // =========================================================

    private fun triggerRead() {

        if (!canTriggerCommand()) {
            return
        }

        commandTriggered = true

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "READ COMMAND TRIGGERED"
        )

        stopCurrentRecognition()

        onRead()

        finishCommandCooldown()
    }

    // =========================================================
    // COMMAND COOLDOWN
    // =========================================================

    private fun canTriggerCommand(): Boolean {

        val now =
            System.currentTimeMillis()

        if (
            now - lastCommandTime <
            COMMAND_COOLDOWN_MS
        ) {

            Log.d(
                TAG,
                "Ignoring duplicate command because of cooldown"
            )

            return false
        }

        if (commandTriggered) {

            Log.d(
                TAG,
                "Command already triggered"
            )

            return false
        }

        lastCommandTime = now

        return true
    }

    private fun finishCommandCooldown() {

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

    // =========================================================
    // STOP CURRENT RECOGNITION
    // =========================================================

    private fun stopCurrentRecognition() {

        try {

            speechRecognizer?.stopListening()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping recognizer",
                e
            )
        }
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
    // ERROR STRING
    // =========================================================

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

    // =========================================================
    // STOP
    // =========================================================

    fun stop() {

        Log.d(
            TAG,
            "Stopping VoiceCommandManager"
        )

        listening = false

        commandTriggered = false

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