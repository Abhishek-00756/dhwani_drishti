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
 * Supported commands include:
 * - Hey Dhwani, what's in front of me?
 * - Hey Dhwani, read
 * - Hey Dhwani, where is the door?
 * - stop
 * - stop read
 * - stop reading
 *
 * The stop command intentionally does NOT require the Dhwani wake word so
 * the user can interrupt speech naturally while Dhwani is talking.
 */
class VoiceCommandManager(
    private val context: Context,
    private val onWhatIsInFront: () -> Unit,
    private val onRead: () -> Unit,
    private val onLocateObject: (String) -> Unit
) {

    companion object {
        private const val TAG = "DHWANI_VOICE"
        private const val RESTART_DELAY_MS = 700L
        private const val READ_RESTART_DELAY_MS = 350L
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
        Log.d(TAG, "VoiceCommandManager.start()")

        if (listening) return

        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission NOT GRANTED")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is NOT available")
            return
        }

        listening = true
        createRecognizer()
        startListeningInternal()
    }

    private fun createRecognizer() {
        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "READY FOR SPEECH - microphone active")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "BEGINNING OF SPEECH")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > -5f) Log.d(TAG, "MIC RMS = $rmsdB")
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    Log.d(TAG, "END OF SPEECH")
                }

                override fun onError(error: Int) {
                    Log.e(
                        TAG,
                        "SpeechRecognizer ERROR = $error (${errorToString(error)})"
                    )
                    commandTriggered = false
                    scheduleRestart()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    Log.d(TAG, "FINAL RESULTS = $matches")

                    if (!matches.isNullOrEmpty()) {
                        for (text in matches) {
                            Log.d(TAG, "Recognized text: [$text]")
                            if (handleCommand(text)) break
                        }
                    }

                    if (!commandTriggered) scheduleRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    if (!matches.isNullOrEmpty()) {
                        for (text in matches) {
                            if (handleCommand(text)) break
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    Log.d(TAG, "Speech event = $eventType")
                }
            }
        )
    }

    private fun startListeningInternal() {
        if (!listening) return

        if (speechRecognizer == null) createRecognizer()

        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start recognition: RECORD_AUDIO missing")
            return
        }

        try {
            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    Locale.getDefault()
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1200L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    800L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    500L
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "SECURITY EXCEPTION", e)
            scheduleRestart()
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION starting recognition", e)
            scheduleRestart()
        }
    }

    private fun handleCommand(originalText: String): Boolean {
        val normalized = normalizeText(originalText)
        if (normalized.isBlank()) return false

        Log.d(TAG, "Checking command: [$normalized]")

        // Stop is checked before commandTriggered and before wake-word validation.
        if (isStopReadingCommand(normalized)) {
            Log.d(TAG, "STOP READING COMMAND MATCHED")
            triggerStopReading()
            return true
        }

        if (commandTriggered) {
            Log.d(TAG, "Command already triggered")
            return true
        }

        if (!containsWakeWord(normalized)) return false

        val commandPart = removeWakeWords(normalized)
        Log.d(TAG, "Command after wake word removal: [$commandPart]")
        if (commandPart.isBlank()) return false

        val objectName = extractLocateObject(commandPart)
        if (objectName != null) {
            Log.d(TAG, "LOCATE OBJECT COMMAND MATCHED: [$objectName]")
            triggerLocateObject(objectName)
            return true
        }

        if (isReadCommand(commandPart)) {
            Log.d(TAG, "READ COMMAND MATCHED")
            triggerRead()
            return true
        }

        if (isWhatIsInFrontCommand(commandPart)) {
            Log.d(TAG, "WHAT IS IN FRONT COMMAND MATCHED")
            triggerWhatIsInFront()
            return true
        }

        return false
    }

    private fun normalizeText(text: String): String {
        return text
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsWakeWord(normalized: String): Boolean {
        val wakeWords = listOf(
            "hey dhwani", "hey dhvani", "hey dhvni", "hey dhoni",
            "hi dhwani", "hi dhvani", "hi dhvni", "hi dhoni",
            "dhwani", "dhvani", "dhvni", "dhoni"
        )
        return wakeWords.any { normalized.contains(it) }
    }

    private fun removeWakeWords(text: String): String {
        var result = text

        listOf(
            "hey dhwani", "hey dhvani", "hey dhvni", "hey dhoni",
            "hi dhwani", "hi dhvani", "hi dhvni", "hi dhoni"
        ).forEach { phrase ->
            result = result.replace(phrase, " ")
        }

        listOf("dhwani", "dhvani", "dhvni", "dhoni").forEach { word ->
            result = result.replace(Regex("\\b$word\\b"), " ")
        }

        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun isStopReadingCommand(command: String): Boolean {
        val value = command.trim()
        return value == "stop" ||
                value == "please stop" ||
                value == "stop read" ||
                value == "stop reading" ||
                value == "please stop read" ||
                value == "please stop reading" ||
                value.startsWith("stop reading ") ||
                value.startsWith("stop read ")
    }

    private fun isReadCommand(commandPart: String): Boolean {
        val command = commandPart.trim()

        if (
            command == "read" ||
            command == "read this" ||
            command == "read it" ||
            command == "please read"
        ) return true

        return command.startsWith("read this ") ||
                command.startsWith("read it ") ||
                command.startsWith("please read ") ||
                command.contains(Regex("\\bread\\b"))
    }

    private fun isWhatIsInFrontCommand(commandPart: String): Boolean {
        val command = commandPart.trim()
        return command.contains("what s in front of me") ||
                command.contains("whats in front of me") ||
                command.contains("what is in front of me") ||
                command.contains("what in front of me") ||
                command.contains("whats in front") ||
                command.contains("what is in front") ||
                command.contains("in front of me") ||
                command.contains("front of me") ||
                command.contains("what do i have in front") ||
                command.contains("what do you see") ||
                command.contains("what can you see")
    }

    private fun extractLocateObject(commandPart: String): String? {
        val command = commandPart.trim()

        val patterns = listOf(
            Regex("^where is (?:the |a |an )?(.+)$"),
            Regex("^where are (?:the |some )?(.+)$"),
            Regex("^wheres (?:the |a |an )?(.+)$"),
            Regex("^where is (?:the |a |an )?(.+) located$"),
            Regex("^where can i find (?:the |a |an )?(.+)$"),
            Regex("^where can i see (?:the |a |an )?(.+)$"),
            Regex("^find (?:the |a |an )?(.+)$"),
            Regex("^find (?:the |a |an )?(.+) for me$"),
            Regex("^locate (?:the |a |an )?(.+)$"),
            Regex("^tell me where (?:the |a |an )?(.+) is$"),
            Regex("^tell me where (?:the |a |an )?(.+) is located$"),
            Regex("^can you find (?:the |a |an )?(.+)$"),
            Regex("^can you locate (?:the |a |an )?(.+)$"),
            Regex("^can you tell me where (?:the |a |an )?(.+) is$"),
            Regex("^do you know where (?:the |a |an )?(.+) is$"),
            Regex("^i want to know where (?:the |a |an )?(.+) is$"),
            Regex("^show me where (?:the |a |an )?(.+) is$")
        )

        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val objectName = cleanObjectName(match.groupValues[1])
            if (objectName.isNotBlank()) return objectName
        }

        return null
    }

    private fun cleanObjectName(text: String): String {
        return text
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" please")
            .removeSuffix(" for me")
            .removePrefix("the ")
            .removePrefix("a ")
            .removePrefix("an ")
            .trim()
    }

    private fun triggerStopReading() {
        Log.d(TAG, "================================")
        Log.d(TAG, "STOP READING TRIGGERED")

        lastCommandTime = System.currentTimeMillis()
        commandTriggered = false

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer for stop command", e)
        }

        // AnnouncementManager owns the actual TextToSpeech instances.
        AnnouncementManager.stopAllSpeech()

        scheduleRestart(READ_RESTART_DELAY_MS)
    }

    private fun triggerRead() {
        if (!canTriggerCommand()) return

        commandTriggered = true
        lastCommandTime = System.currentTimeMillis()

        Log.d(TAG, "READ COMMAND TRIGGERED")

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer", e)
        }

        try {
            onRead()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR inside onRead()", e)
        }

        // Restart early so a plain "stop" can interrupt a long OCR result.
        scheduleRestart(READ_RESTART_DELAY_MS)
        resetCommandAfterCooldown()
    }

    private fun triggerWhatIsInFront() {
        if (!canTriggerCommand()) return

        commandTriggered = true
        lastCommandTime = System.currentTimeMillis()

        Log.d(TAG, "WHAT IS IN FRONT TRIGGERED")

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer", e)
        }

        try {
            onWhatIsInFront()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR inside onWhatIsInFront()", e)
        }

        resetCommandAfterCooldown()
    }

    private fun triggerLocateObject(objectName: String) {
        if (!canTriggerCommand()) return

        commandTriggered = true
        lastCommandTime = System.currentTimeMillis()

        Log.d(TAG, "LOCATE OBJECT TRIGGERED: [$objectName]")

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer", e)
        }

        try {
            onLocateObject(objectName)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR inside onLocateObject()", e)
        }

        resetCommandAfterCooldown()
    }

    private fun canTriggerCommand(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < COMMAND_COOLDOWN_MS) {
            Log.d(TAG, "Ignoring duplicate command")
            return false
        }
        return true
    }

    private fun resetCommandAfterCooldown() {
        handler.postDelayed(
            {
                commandTriggered = false
                if (listening) {
                    Log.d(TAG, "Command cooldown finished")
                    scheduleRestart()
                }
            },
            COMMAND_COOLDOWN_MS
        )
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!listening) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun errorToString(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "UNKNOWN_ERROR"
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping VoiceCommandManager")

        listening = false
        commandTriggered = false

        handler.removeCallbacksAndMessages(null)

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
