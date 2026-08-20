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
 * Supported:
 *
 * "Hey Dhwani, what's in front of me?"
 * "Hey Dhwani, what is in front of me?"
 *
 * "Hey Dhwani, read"
 * "Hey Dhwani, read this"
 * "Hey Dhwani, read it"
 *
 * Object-location commands:
 *
 * "Hey Dhwani, where is the door?"
 * "Hey Dhwani, where's the door?"
 * "Hey Dhwani, find the door"
 * "Hey Dhwani, locate the door"
 *
 * "Hey Dhwani, where is the pothole?"
 * "Hey Dhwani, where are the stairs?"
 * "Hey Dhwani, where is the person?"
 *
 * Speech recognition continuously restarts after every session.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onWhatIsInFront: () -> Unit,
    private val onRead: () -> Unit,

    /**
     * Called when the user asks where a particular object is.
     *
     * Example:
     *
     * "where is the door?"
     *
     * callback receives:
     *
     * "door"
     */
    private val onLocateObject: (String) -> Unit
) {

    companion object {

        private const val TAG = "DHWANI_VOICE"

        private const val RESTART_DELAY_MS = 700L

        private const val COMMAND_COOLDOWN_MS = 2500L
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private var speechRecognizer:
            SpeechRecognizer? = null

    @Volatile
    private var listening = false

    @Volatile
    private var commandTriggered = false

    private var lastCommandTime = 0L

    // =========================================================
    // RESTART
    // =========================================================

    private val restartRunnable =
        Runnable {

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

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "VoiceCommandManager.start()"
        )

        Log.d(
            TAG,
            "================================"
        )

        if (listening) {

            Log.d(
                TAG,
                "Already listening"
            )

            return
        }

        // -----------------------------------------------------
        // MICROPHONE PERMISSION
        // -----------------------------------------------------

        val permission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            )

        if (
            permission !=
            PackageManager.PERMISSION_GRANTED
        ) {

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

        // -----------------------------------------------------
        // SPEECH RECOGNITION
        // -----------------------------------------------------

        if (
            !SpeechRecognizer
                .isRecognitionAvailable(context)
        ) {

            Log.e(
                TAG,
                "Speech recognition is NOT available"
            )

            return
        }

        Log.d(
            TAG,
            "Speech recognition service is available"
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
            SpeechRecognizer
                .createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(

            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {

                    Log.d(
                        TAG,
                        "READY FOR SPEECH - microphone active"
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

                // =================================================
                // ERROR
                // =================================================

                override fun onError(
                    error: Int
                ) {

                    Log.e(
                        TAG,
                        "SpeechRecognizer ERROR = " +
                                "$error " +
                                "(${errorToString(error)})"
                    )

                    commandTriggered = false

                    scheduleRestart()
                }

                // =================================================
                // FINAL RESULT
                // =================================================

                override fun onResults(
                    results: Bundle?
                ) {

                    val matches =
                        results?.getStringArrayList(
                            SpeechRecognizer
                                .RESULTS_RECOGNITION
                        )

                    Log.d(
                        TAG,
                        "FINAL RESULTS = $matches"
                    )

                    if (
                        !matches.isNullOrEmpty()
                    ) {

                        for (text in matches) {

                            Log.d(
                                TAG,
                                "Recognized text: [$text]"
                            )

                            if (
                                handleCommand(text)
                            ) {

                                break
                            }
                        }
                    }

                    scheduleRestart()
                }

                // =================================================
                // PARTIAL RESULT
                // =================================================

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {

                    val matches =
                        partialResults
                            ?.getStringArrayList(
                                SpeechRecognizer
                                    .RESULTS_RECOGNITION
                            )

                    Log.d(
                        TAG,
                        "PARTIAL RESULTS = $matches"
                    )

                    if (
                        !matches.isNullOrEmpty()
                    ) {

                        for (text in matches) {

                            if (
                                handleCommand(text)
                            ) {

                                break
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

        if (
            speechRecognizer == null
        ) {

            Log.d(
                TAG,
                "Recognizer was null. Creating again."
            )

            createRecognizer()
        }

        val permission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            )

        if (
            permission !=
            PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                "Cannot start recognition: " +
                        "RECORD_AUDIO missing"
            )

            return
        }

        try {

            val intent =
                Intent(
                    RecognizerIntent
                        .ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent
                            .EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent
                            .LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_LANGUAGE,
                        Locale.getDefault()
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_LANGUAGE_PREFERENCE,
                        Locale.getDefault()
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1200L
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        800L
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        500L
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_MAX_RESULTS,
                        5
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_PREFER_OFFLINE,
                        false
                    )

                    putExtra(
                        RecognizerIntent
                            .EXTRA_CALLING_PACKAGE,
                        context.packageName
                    )
                }

            Log.d(
                TAG,
                "Calling SpeechRecognizer.startListening()"
            )

            speechRecognizer
                ?.startListening(intent)

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
                "EXCEPTION starting recognition",
                e
            )

            scheduleRestart()
        }
    }

    // =========================================================
    // HANDLE COMMAND
    // =========================================================

    private fun handleCommand(
        originalText: String
    ): Boolean {

        if (commandTriggered) {

            Log.d(
                TAG,
                "Command already triggered"
            )

            return true
        }

        val normalized =
            normalizeText(originalText)

        if (normalized.isBlank()) {
            return false
        }

        Log.d(
            TAG,
            "Checking command: [$normalized]"
        )

        // -----------------------------------------------------
        // WAKE WORD
        // -----------------------------------------------------

        val wakeWordDetected =
            containsWakeWord(normalized)

        if (!wakeWordDetected) {

            Log.d(
                TAG,
                "No Dhwani wake word"
            )

            return false
        }

        // -----------------------------------------------------
        // READ
        // -----------------------------------------------------

        if (
            isReadCommand(normalized)
        ) {

            Log.d(
                TAG,
                "READ COMMAND MATCHED"
            )

            triggerRead()

            return true
        }

        // -----------------------------------------------------
        // WHAT IS IN FRONT
        // -----------------------------------------------------

        if (
            isWhatIsInFrontCommand(
                normalized
            )
        ) {

            Log.d(
                TAG,
                "WHAT IS IN FRONT COMMAND MATCHED"
            )

            triggerWhatIsInFront()

            return true
        }

        // -----------------------------------------------------
        // LOCATE OBJECT
        // -----------------------------------------------------

        val objectName =
            extractLocateObject(normalized)

        if (
            objectName != null
        ) {

            Log.d(
                TAG,
                "LOCATE OBJECT COMMAND MATCHED"
            )

            Log.d(
                TAG,
                "Requested object = [$objectName]"
            )

            triggerLocateObject(
                objectName
            )

            return true
        }

        return false
    }

    // =========================================================
    // NORMALIZE
    // =========================================================

    private fun normalizeText(
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

    private fun containsWakeWord(
        normalized: String
    ): Boolean {

        return normalized.contains(
            "hey dhwani"
        ) ||
                normalized.contains(
                    "hey dhvani"
                ) ||
                normalized.contains(
                    "hey dhvni"
                ) ||
                normalized.contains(
                    "hi dhwani"
                ) ||
                normalized.contains(
                    "hi dhvani"
                ) ||
                normalized.contains(
                    "hi dhvni"
                ) ||
                normalized.contains(
                    "hey dhoni"
                ) ||
                normalized.contains(
                    "hi dhoni"
                ) ||
                normalized.contains(
                    "dhwani"
                ) ||
                normalized.contains(
                    "dhvani"
                ) ||
                normalized.contains(
                    "dhvni"
                ) ||
                normalized.contains(
                    "dhoni"
                )
    }

    // =========================================================
    // READ COMMAND
    // =========================================================

    private fun isReadCommand(
        normalized: String
    ): Boolean {

        val commandPart =
            removeWakeWords(
                normalized
            )

        Log.d(
            TAG,
            "Read command part: [$commandPart]"
        )

        if (
            commandPart == "read"
        ) {
            return true
        }

        if (
            commandPart == "read this"
        ) {
            return true
        }

        if (
            commandPart == "read it"
        ) {
            return true
        }

        if (
            commandPart == "please read"
        ) {
            return true
        }

        if (
            commandPart.startsWith(
                "read this "
            )
        ) {
            return true
        }

        if (
            commandPart.startsWith(
                "read it "
            )
        ) {
            return true
        }

        return commandPart.contains(
            Regex("\\bread\\b")
        )
    }

    // =========================================================
    // WHAT IS IN FRONT
    // =========================================================

    private fun isWhatIsInFrontCommand(
        normalized: String
    ): Boolean {

        val commandPart =
            removeWakeWords(
                normalized
            )

        Log.d(
            TAG,
            "Front command part: [$commandPart]"
        )

        return commandPart.contains(
            "what s in front of me"
        ) ||
                commandPart.contains(
                    "whats in front of me"
                ) ||
                commandPart.contains(
                    "what is in front of me"
                ) ||
                commandPart.contains(
                    "what in front of me"
                ) ||
                commandPart.contains(
                    "whats in front"
                ) ||
                commandPart.contains(
                    "what is in front"
                ) ||
                commandPart.contains(
                    "in front of me"
                ) ||
                commandPart.contains(
                    "front of me"
                ) ||
                commandPart.contains(
                    "what do i have in front"
                ) ||
                commandPart.contains(
                    "what do you see"
                ) ||
                commandPart.contains(
                    "what can you see"
                )
    }

    // =========================================================
    // LOCATE OBJECT COMMAND
    // =========================================================

    /**
     * Extracts an object name from commands such as:
     *
     * "where is the door"
     * "where's the door"
     * "find the door"
     * "locate the door"
     * "where is pothole"
     * "find stairs"
     */
    private fun extractLocateObject(
        normalized: String
    ): String? {

        val commandPart =
            removeWakeWords(
                normalized
            )

        Log.d(
            TAG,
            "Locate command part: [$commandPart]"
        )

        val patterns =
            listOf(

                // where is X
                Regex(
                    "^where is (?:the |a |an )?(.+)$"
                ),

                // where are X
                Regex(
                    "^where are (?:the |some )?(.+)$"
                ),

                // where's X
                Regex(
                    "^wheres (?:the |a |an )?(.+)$"
                ),

                // find X
                Regex(
                    "^find (?:the |a |an )?(.+)$"
                ),

                // locate X
                Regex(
                    "^locate (?:the |a |an )?(.+)$"
                ),

                // tell me where X is
                Regex(
                    "^tell me where (?:the |a |an )?(.+) is$"
                ),

                // can you find X
                Regex(
                    "^can you find (?:the |a |an )?(.+)$"
                ),

                // can you locate X
                Regex(
                    "^can you locate (?:the |a |an )?(.+)$"
                )
            )

        for (pattern in patterns) {

            val match =
                pattern.find(commandPart)

            if (match != null) {

                var objectName =
                    match.groupValues[1]
                        .trim()

                objectName =
                    cleanObjectName(
                        objectName
                    )

                if (
                    objectName.isNotBlank()
                ) {

                    return objectName
                }
            }
        }

        return null
    }

    // =========================================================
    // CLEAN OBJECT NAME
    // =========================================================

    private fun cleanObjectName(
        text: String
    ): String {

        var result =
            text
                .lowercase(Locale.US)
                .trim()

        // Remove polite trailing phrases.
        result =
            result
                .removeSuffix(" please")
                .removeSuffix(" for me")
                .trim()

        // Remove common articles.
        result =
            result
                .removePrefix("the ")
                .removePrefix("a ")
                .removePrefix("an ")
                .trim()

        return result
    }

    // =========================================================
    // REMOVE WAKE WORD
    // =========================================================

    private fun removeWakeWords(
        text: String
    ): String {

        var result = text

        val wakeWords =
            listOf(
                "hey dhwani",
                "hey dhvani",
                "hey dhvni",
                "hey dhoni",
                "hi dhwani",
                "hi dhvani",
                "hi dhvni",
                "hi dhoni"
            )

        for (wakeWord in wakeWords) {

            result =
                result.replace(
                    wakeWord,
                    " "
                )
        }

        result =
            result.replace(
                Regex("\\bdhwani\\b"),
                " "
            )

        result =
            result.replace(
                Regex("\\bdhvani\\b"),
                " "
            )

        result =
            result.replace(
                Regex("\\bdhvni\\b"),
                " "
            )

        result =
            result.replace(
                Regex("\\bdhoni\\b"),
                " "
            )

        return result
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // =========================================================
    // TRIGGER READ
    // =========================================================

    private fun triggerRead() {

        if (
            !canTriggerCommand()
        ) {
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

        try {

            speechRecognizer
                ?.stopListening()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping recognizer",
                e
            )
        }

        try {

            onRead()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERROR inside onRead()",
                e
            )
        }

        resetCommandAfterCooldown()
    }

    // =========================================================
    // TRIGGER FRONT
    // =========================================================

    private fun triggerWhatIsInFront() {

        if (
            !canTriggerCommand()
        ) {
            return
        }

        commandTriggered = true

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "WHAT IS IN FRONT TRIGGERED"
        )

        try {

            speechRecognizer
                ?.stopListening()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping recognizer",
                e
            )
        }

        try {

            onWhatIsInFront()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERROR inside onWhatIsInFront()",
                e
            )
        }

        resetCommandAfterCooldown()
    }

    // =========================================================
    // TRIGGER LOCATE OBJECT
    // =========================================================

    private fun triggerLocateObject(
        objectName: String
    ) {

        if (
            !canTriggerCommand()
        ) {
            return
        }

        commandTriggered = true

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "LOCATE OBJECT TRIGGERED"
        )

        Log.d(
            TAG,
            "Object = [$objectName]"
        )

        try {

            speechRecognizer
                ?.stopListening()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping recognizer",
                e
            )
        }

        try {

            onLocateObject(
                objectName
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERROR inside onLocateObject()",
                e
            )
        }

        resetCommandAfterCooldown()
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
                "Ignoring duplicate command"
            )

            return false
        }

        lastCommandTime =
            now

        return true
    }

    private fun resetCommandAfterCooldown() {

        handler.postDelayed(

            {

                commandTriggered =
                    false

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
    // ERROR TEXT
    // =========================================================

    private fun errorToString(
        error: Int
    ): String {

        return when (error) {

            SpeechRecognizer.ERROR_AUDIO ->
                "ERROR_AUDIO"

            SpeechRecognizer.ERROR_CLIENT ->
                "ERROR_CLIENT"

            SpeechRecognizer
                .ERROR_INSUFFICIENT_PERMISSIONS ->
                "ERROR_INSUFFICIENT_PERMISSIONS"

            SpeechRecognizer.ERROR_NETWORK ->
                "ERROR_NETWORK"

            SpeechRecognizer
                .ERROR_NETWORK_TIMEOUT ->
                "ERROR_NETWORK_TIMEOUT"

            SpeechRecognizer.ERROR_NO_MATCH ->
                "ERROR_NO_MATCH"

            SpeechRecognizer
                .ERROR_RECOGNIZER_BUSY ->
                "ERROR_RECOGNIZER_BUSY"

            SpeechRecognizer.ERROR_SERVER ->
                "ERROR_SERVER"

            SpeechRecognizer
                .ERROR_SPEECH_TIMEOUT ->
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

            speechRecognizer
                ?.stopListening()

        } catch (_: Exception) {
        }

        try {

            speechRecognizer
                ?.cancel()

        } catch (_: Exception) {
        }

        speechRecognizer?.destroy()

        speechRecognizer = null
    }
}