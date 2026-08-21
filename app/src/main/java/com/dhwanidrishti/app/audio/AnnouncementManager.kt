package com.dhwanidrishti.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dhwanidrishti.app.processing.ObjectTracker
import com.dhwanidrishti.app.processing.Zone
import java.util.Locale

/**
 * Handles spoken output for Dhwani Drishti.
 *
 * The existing narrated behavior is preserved for normal YOLO objects.
 * Door and stair are special demo classes: when detected by YOLO or the
 * demo reference fallback, they are announced regardless of estimated
 * depth because the demo needs reliable automatic warnings.
 */
class AnnouncementManager(
    context: Context
) {
    companion object {
        private const val TAG = "DHWANI_TTS"
        const val VERY_CLOSE_THRESHOLD = 0.20f
        const val CLOSE_THRESHOLD = 0.35f
        const val NEARBY_THRESHOLD = 0.55f
        const val COOLDOWN_MS = 6000L
        const val MAX_COOLDOWN_ENTRIES = 200
        const val COMMAND_SPEECH_COOLDOWN_MS = 700L
    }

    private val tts: TextToSpeech
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isSpeaking: Boolean = false
        private set

    @Volatile
    private var commandSpeechActive = false

    @Volatile
    private var lastCommandSpeechTime = 0L

    @Volatile
    private var currentUtteranceId: String? = null

    private val lastAnnounced = mutableMapOf<Int, Long>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS initialization failed. status=$status")
                return@TextToSpeech
            }

            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            val defaultLocale = Locale.getDefault()
            val languageResult = tts.setLanguage(defaultLocale)
            Log.d(TAG, "TTS language=$defaultLocale result=$languageResult")

            val offlineVoice = tts.voices
                ?.filter {
                    it.locale.language == defaultLocale.language &&
                            !it.isNetworkConnectionRequired
                }
                ?.maxByOrNull { it.quality }

            if (offlineVoice != null) {
                tts.voice = offlineVoice
                Log.d(TAG, "Using offline TTS voice=${offlineVoice.name}")
            }

            tts.setSpeechRate(1.05f)
            tts.setPitch(1.0f)

            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) {
                            isSpeaking = true
                            Log.d(TAG, "TTS START: $utteranceId")
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) {
                            isSpeaking = false
                            commandSpeechActive = false
                            Log.d(TAG, "TTS DONE: $utteranceId")
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) {
                            isSpeaking = false
                            commandSpeechActive = false
                            Log.e(TAG, "TTS ERROR: $utteranceId")
                        }
                    }
                }
            )
        }
    }

    /**
     * Automatic obstacle announcements.
     *
     * Normal YOLO classes retain the existing proximity/approaching rules.
     * Door and stair bypass those rules and are announced immediately.
     */
    fun evaluate(
        tracked: List<ObjectTracker.TrackedObject>,
        tracker: ObjectTracker
    ) {
        if (tracked.isEmpty() || commandSpeechActive || isSpeaking) return

        val now = System.currentTimeMillis()

        val candidates = tracked
            .filter { obj ->
                val lastTime = lastAnnounced[obj.id] ?: 0L
                val cooldownExpired = now - lastTime >= COOLDOWN_MS
                val specialDemoObject = isDoorOrStair(obj.label)
                val veryClose = obj.lastDistance <= VERY_CLOSE_THRESHOLD
                val close = obj.lastDistance <= CLOSE_THRESHOLD
                val approaching = tracker.isApproaching(obj)

                cooldownExpired && (
                        specialDemoObject ||
                                veryClose ||
                                close ||
                                approaching
                        )
            }
            .sortedWith(
                compareBy<ObjectTracker.TrackedObject> {
                    when {
                        isDoorOrStair(it.label) -> -1
                        it.lastDistance <= VERY_CLOSE_THRESHOLD -> 0
                        it.lastDistance <= CLOSE_THRESHOLD -> 1
                        else -> 2
                    }
                }.thenBy { it.lastDistance }
            )

        val objectToSpeak = candidates.firstOrNull() ?: return
        val approaching = tracker.isApproaching(objectToSpeak)
        val phrase = buildWarningPhrase(objectToSpeak, approaching)

        speakAutomatic(phrase)
        lastAnnounced[objectToSpeak.id] = now
        cleanupCooldownMap()
    }

    fun announceWhatIsInFront(
        tracked: List<ObjectTracker.TrackedObject>
    ) {
        if (!canStartCommandSpeech()) return

        if (tracked.isEmpty()) {
            speakCommand("I don't see anything in front of you.")
            return
        }

        val labels = tracked
            .sortedWith(
                compareBy<ObjectTracker.TrackedObject> { it.lastDistance }
                    .thenBy { zonePriority(it) }
            )
            .map { friendlyLabel(it.label) }
            .distinct()

        if (labels.isEmpty()) {
            speakCommand("I don't see anything in front of you.")
            return
        }

        val phrase = when {
            labels.size == 1 -> {
                val label = labels[0]
                "I see ${articleFor(label)}${label.lowercase()} in front of you."
            }
            labels.size == 2 -> {
                val first = labels[0]
                val second = labels[1]
                "I see ${articleFor(first)}${first.lowercase()} and " +
                        "${articleFor(second)}${second.lowercase()} in front of you."
            }
            else -> {
                val beginning = labels.dropLast(1).joinToString(", ") {
                    "${articleFor(it)}${it.lowercase()}"
                }
                val last = labels.last()
                "I see $beginning and ${articleFor(last)}${last.lowercase()} in front of you."
            }
        }

        speakCommand(phrase)
    }

    fun announceRead(
        tracked: List<ObjectTracker.TrackedObject>
    ) {
        if (!canStartCommandSpeech()) return

        if (tracked.isEmpty()) {
            speakCommand("I cannot find any readable text.")
            return
        }

        val labels = tracked
            .sortedBy { it.lastDistance }
            .map { friendlyLabel(it.label) }
            .distinct()

        if (labels.isEmpty()) {
            speakCommand("I cannot find any readable text.")
            return
        }

        val phrase = when {
            labels.size == 1 -> {
                val label = labels[0]
                "I can see ${articleFor(label)}${label.lowercase()}."
            }
            labels.size == 2 -> {
                val first = labels[0]
                val second = labels[1]
                "I can see ${articleFor(first)}${first.lowercase()} and " +
                        "${articleFor(second)}${second.lowercase()}."
            }
            else -> {
                val beginning = labels.dropLast(1).joinToString(", ") {
                    "${articleFor(it)}${it.lowercase()}"
                }
                val last = labels.last()
                "I can see $beginning and ${articleFor(last)}${last.lowercase()}."
            }
        }

        speakCommand(phrase)
    }

    fun announceDetailedScene(
        tracked: List<ObjectTracker.TrackedObject>
    ) {
        if (!canStartCommandSpeech()) return

        if (tracked.isEmpty()) {
            speakCommand("I don't see anything in front of you.")
            return
        }

        val first = tracked.minByOrNull { it.lastDistance } ?: return
        val label = friendlyLabel(first.label)
        val zone = Zone.fromNormalizedX(first.lastCentroid.x)

        val distance = when {
            first.lastDistance <= VERY_CLOSE_THRESHOLD -> "very close"
            first.lastDistance <= CLOSE_THRESHOLD -> "close"
            first.lastDistance <= NEARBY_THRESHOLD -> "nearby"
            else -> "far ahead"
        }

        val phrase = if (zone == Zone.CENTER) {
            "There is a $label $distance in front of you."
        } else {
            "There is a $label to your ${zone.spoken}, $distance."
        }

        speakCommand(phrase)
    }

    fun speak(phrase: String) {
        if (phrase.isBlank()) return
        speakCommand(phrase)
    }

    private fun canStartCommandSpeech(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCommandSpeechTime < COMMAND_SPEECH_COOLDOWN_MS) {
            Log.d(TAG, "Command speech ignored because of cooldown")
            return false
        }
        lastCommandSpeechTime = now
        return true
    }

    private fun speakCommand(phrase: String) {
        if (phrase.isBlank()) return

        val utteranceId = "dhwani_command_${System.nanoTime()}"
        currentUtteranceId = utteranceId
        commandSpeechActive = true
        isSpeaking = true

        Log.d(TAG, "COMMAND SPEAK: [$phrase]")

        try {
            tts.stop()
            tts.speak(
                phrase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command TTS failed", e)
            isSpeaking = false
            commandSpeechActive = false
        }
    }

    private fun speakAutomatic(phrase: String) {
        if (phrase.isBlank() || commandSpeechActive) return

        val utteranceId = "dhwani_auto_${System.nanoTime()}"
        currentUtteranceId = utteranceId
        isSpeaking = true

        Log.d(TAG, "AUTOMATIC SPEAK: [$phrase]")

        try {
            tts.speak(
                phrase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Automatic TTS failed", e)
            isSpeaking = false
        }
    }

    private fun buildWarningPhrase(
        obj: ObjectTracker.TrackedObject,
        approaching: Boolean
    ): String {
        val label = friendlyLabel(obj.label)
        val zone = Zone.fromNormalizedX(obj.lastCentroid.x)

        // Demo-critical objects intentionally use simple spatial speech.
        if (isDoorOrStair(obj.label)) {
            return if (zone == Zone.CENTER) {
                "$label is in front of you."
            } else {
                "$label is on your ${zone.spoken}."
            }
        }

        if (approaching) {
            return if (zone == Zone.CENTER) {
                "$label approaching"
            } else {
                "$label approaching from your ${zone.spoken}"
            }
        }

        val distanceDescription = when {
            obj.lastDistance <= VERY_CLOSE_THRESHOLD -> "very close"
            obj.lastDistance <= CLOSE_THRESHOLD -> "close"
            obj.lastDistance <= NEARBY_THRESHOLD -> "nearby"
            else -> "far ahead"
        }

        return if (zone == Zone.CENTER) {
            "$label $distanceDescription"
        } else {
            "$label to your ${zone.spoken}, $distanceDescription"
        }
    }

    private fun isDoorOrStair(label: String): Boolean {
        return label.equals("door", ignoreCase = true) ||
                label.equals("stair", ignoreCase = true)
    }

    private fun friendlyLabel(label: String): String {
        return when (label.lowercase(Locale.US)) {
            "person" -> "Person"
            "laptop" -> "Laptop"
            "backpack" -> "Backpack"
            "chair" -> "Chair"
            "bicycle" -> "Bicycle"
            "car" -> "Car"
            "bus" -> "Bus"
            "motorcycle" -> "Motorcycle"
            "truck" -> "Truck"
            "bench" -> "Bench"
            "door" -> "Door"
            "stair" -> "Stairs"
            "window" -> "Window"
            "pothole" -> "Pothole"
            "bed" -> "Bed"
            "book" -> "Book"
            "bag" -> "Bag"
            "stop sign" -> "Stop sign"
            "dog" -> "Dog"
            "suitcase" -> "Suitcase"
            else -> label.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
        }
    }

    private fun articleFor(label: String): String {
        return when (label.lowercase(Locale.US).firstOrNull()) {
            'a', 'e', 'i', 'o', 'u' -> "an "
            else -> "a "
        }
    }

    private fun zonePriority(
        obj: ObjectTracker.TrackedObject
    ): Int {
        return when (Zone.fromNormalizedX(obj.lastCentroid.x)) {
            Zone.CENTER -> 0
            Zone.LEFT -> 1
            Zone.RIGHT -> 2
        }
    }

    private fun cleanupCooldownMap() {
        if (lastAnnounced.size <= MAX_COOLDOWN_ENTRIES) return
        val oldest = lastAnnounced.minByOrNull { it.value }
        oldest?.let { lastAnnounced.remove(it.key) }
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down TTS")
        try {
            mainHandler.removeCallbacksAndMessages(null)
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }

        isSpeaking = false
        commandSpeechActive = false
        currentUtteranceId = null
        lastAnnounced.clear()
    }
}
