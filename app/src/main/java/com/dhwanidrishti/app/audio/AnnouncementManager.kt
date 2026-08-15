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
 * Handles all spoken output for Dhwani Drishti.
 *
 * Responsibilities:
 *
 * 1. Automatic obstacle warnings
 * 2. "What's in front of me?"
 * 3. OCR text output
 * 4. Reliable TTS state management
 *
 * IMPORTANT:
 *
 * Voice-command speech has higher priority than automatic
 * obstacle announcements.
 */
class AnnouncementManager(
    context: Context
) {

    companion object {

        private const val TAG = "DHWANI_TTS"

        /**
         * Extremely close.
         *
         * 0.0 = nearest
         * 1.0 = farthest
         */
        const val VERY_CLOSE_THRESHOLD = 0.20f

        /**
         * Close obstacle.
         */
        const val CLOSE_THRESHOLD = 0.35f

        /**
         * Nearby obstacle.
         */
        const val NEARBY_THRESHOLD = 0.55f

        /**
         * Same tracked object should not be announced
         * repeatedly.
         */
        const val COOLDOWN_MS = 6000L

        /**
         * Maximum number of tracker IDs kept in cooldown map.
         */
        const val MAX_COOLDOWN_ENTRIES = 200

        /**
         * Prevents two voice commands from firing immediately
         * one after another.
         */
        const val COMMAND_SPEECH_COOLDOWN_MS = 700L
    }

    // =========================================================
    // TTS
    // =========================================================

    private val tts: TextToSpeech

    private val mainHandler =
        Handler(Looper.getMainLooper())

    /**
     * True while Dhwani is currently speaking.
     */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    /**
     * True when the current speech was requested directly
     * by the user.
     *
     * Example:
     *
     * "Hey Dhwani, what's in front of me?"
     *
     * or
     *
     * "Hey Dhwani, read"
     */
    @Volatile
    private var commandSpeechActive: Boolean = false

    /**
     * Last time an explicit command response was spoken.
     */
    @Volatile
    private var lastCommandSpeechTime: Long = 0L

    /**
     * Used to prevent old TTS callbacks from incorrectly
     * changing isSpeaking for a newer utterance.
     */
    @Volatile
    private var currentUtteranceId: String? = null

    // =========================================================
    // AUTOMATIC ANNOUNCEMENT COOLDOWN
    // =========================================================

    private val lastAnnounced =
        mutableMapOf<Int, Long>()

    // =========================================================
    // INITIALIZATION
    // =========================================================

    init {

        tts = TextToSpeech(context.applicationContext) { status ->

            if (status != TextToSpeech.SUCCESS) {

                Log.e(
                    TAG,
                    "TTS initialization failed. status=$status"
                )

                return@TextToSpeech
            }

            Log.d(
                TAG,
                "TTS initialized successfully"
            )

            // -------------------------------------------------
            // Accessibility audio
            // -------------------------------------------------

            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()
            )

            // -------------------------------------------------
            // Language
            // -------------------------------------------------

            val defaultLocale =
                Locale.getDefault()

            val languageResult =
                tts.setLanguage(defaultLocale)

            Log.d(
                TAG,
                "TTS language=$defaultLocale result=$languageResult"
            )

            // -------------------------------------------------
            // Prefer offline voice
            // -------------------------------------------------

            val offlineVoice =
                tts.voices
                    ?.filter {
                        it.locale.language ==
                                defaultLocale.language &&
                                !it.isNetworkConnectionRequired
                    }
                    ?.maxByOrNull {
                        it.quality
                    }

            if (offlineVoice != null) {

                tts.voice = offlineVoice

                Log.d(
                    TAG,
                    "Using offline TTS voice=${offlineVoice.name}"
                )
            }

            // -------------------------------------------------
            // Navigation-friendly speech
            // -------------------------------------------------

            tts.setSpeechRate(1.05f)
            tts.setPitch(1.0f)

            // -------------------------------------------------
            // TTS callbacks
            // -------------------------------------------------

            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(
                        utteranceId: String?
                    ) {

                        if (
                            utteranceId ==
                            currentUtteranceId
                        ) {

                            isSpeaking = true

                            Log.d(
                                TAG,
                                "TTS START: $utteranceId"
                            )
                        }
                    }

                    override fun onDone(
                        utteranceId: String?
                    ) {

                        if (
                            utteranceId ==
                            currentUtteranceId
                        ) {

                            isSpeaking = false
                            commandSpeechActive = false

                            Log.d(
                                TAG,
                                "TTS DONE: $utteranceId"
                            )
                        }
                    }

                    override fun onError(
                        utteranceId: String?
                    ) {

                        if (
                            utteranceId ==
                            currentUtteranceId
                        ) {

                            isSpeaking = false
                            commandSpeechActive = false

                            Log.e(
                                TAG,
                                "TTS ERROR: $utteranceId"
                            )
                        }
                    }
                }
            )
        }
    }

    // =========================================================
    // AUTOMATIC OBSTACLE ANNOUNCEMENT
    // =========================================================

    /**
     * Automatically announces the most important obstacle.
     *
     * Automatic announcements are suppressed while an explicit
     * user command is being answered.
     */
    fun evaluate(
        tracked: List<ObjectTracker.TrackedObject>,
        tracker: ObjectTracker
    ) {

        if (tracked.isEmpty()) {
            return
        }

        // Never interrupt an explicit voice response.
        if (commandSpeechActive) {
            return
        }

        // Don't start another announcement while speaking.
        if (isSpeaking) {
            return
        }

        val now =
            System.currentTimeMillis()

        val candidates =
            tracked
                .filter { obj ->

                    val lastTime =
                        lastAnnounced[obj.id] ?: 0L

                    val cooldownExpired =
                        now - lastTime >= COOLDOWN_MS

                    val veryClose =
                        obj.lastDistance <=
                                VERY_CLOSE_THRESHOLD

                    val close =
                        obj.lastDistance <=
                                CLOSE_THRESHOLD

                    val approaching =
                        tracker.isApproaching(obj)

                    cooldownExpired &&
                            (
                                    veryClose ||
                                            close ||
                                            approaching
                                    )
                }
                .sortedWith(
                    compareBy<ObjectTracker.TrackedObject> {

                        when {

                            it.lastDistance <=
                                    VERY_CLOSE_THRESHOLD ->
                                0

                            it.lastDistance <=
                                    CLOSE_THRESHOLD ->
                                1

                            else ->
                                2
                        }

                    }.thenBy {

                        it.lastDistance

                    }
                )

        val objectToSpeak =
            candidates.firstOrNull()
                ?: return

        val approaching =
            tracker.isApproaching(
                objectToSpeak
            )

        val phrase =
            buildWarningPhrase(
                obj = objectToSpeak,
                approaching = approaching
            )

        speakAutomatic(
            phrase
        )

        lastAnnounced[
            objectToSpeak.id
        ] = now

        cleanupCooldownMap()
    }

    // =========================================================
    // "WHAT'S IN FRONT OF ME?"
    // =========================================================

    /**
     * Answers:
     *
     * "Hey Dhwani, what's in front of me?"
     */
    fun announceWhatIsInFront(
        tracked: List<ObjectTracker.TrackedObject>
    ) {

        if (
            !canStartCommandSpeech()
        ) {
            return
        }

        if (tracked.isEmpty()) {

            speakCommand(
                "I don't see anything in front of you."
            )

            return
        }

        val sortedObjects =
            tracked.sortedWith(
                compareBy<ObjectTracker.TrackedObject> {

                    it.lastDistance

                }.thenBy {

                    zonePriority(it)

                }
            )

        val labels =
            sortedObjects
                .map {
                    friendlyLabel(
                        it.label
                    )
                }
                .distinct()

        if (labels.isEmpty()) {

            speakCommand(
                "I don't see anything in front of you."
            )

            return
        }

        val phrase =
            when {

                labels.size == 1 -> {

                    val label =
                        labels[0]

                    "I see ${
                        articleFor(label)
                    }${
                        label.lowercase()
                    } in front of you."
                }

                labels.size == 2 -> {

                    val first =
                        labels[0]

                    val second =
                        labels[1]

                    "I see ${
                        articleFor(first)
                    }${
                        first.lowercase()
                    } and ${
                        articleFor(second)
                    }${
                        second.lowercase()
                    } in front of you."
                }

                else -> {

                    val beginning =
                        labels
                            .dropLast(1)
                            .joinToString(", ") {

                                "${
                                    articleFor(it)
                                }${
                                    it.lowercase()
                                }"
                            }

                    val last =
                        labels.last()

                    "I see $beginning and ${
                        articleFor(last)
                    }${
                        last.lowercase()
                    } in front of you."
                }
            }

        speakCommand(
            phrase
        )
    }

    // =========================================================
    // READ COMMAND FALLBACK
    // =========================================================

    /**
     * This method is only a fallback description.
     *
     * Actual OCR is handled by DhwaniPipeline -> TextReader.
     *
     * If OCR finds text, DhwaniPipeline should call:
     *
     *     modeBEngine().speak(text)
     *
     * If OCR finds nothing:
     *
     *     modeBEngine().speak(
     *         "I cannot find any readable text."
     *     )
     */
    fun announceRead(
        tracked: List<ObjectTracker.TrackedObject>
    ) {

        if (
            !canStartCommandSpeech()
        ) {
            return
        }

        if (tracked.isEmpty()) {

            speakCommand(
                "I cannot find any readable text."
            )

            return
        }

        val labels =
            tracked
                .sortedBy {
                    it.lastDistance
                }
                .map {
                    friendlyLabel(
                        it.label
                    )
                }
                .distinct()

        if (labels.isEmpty()) {

            speakCommand(
                "I cannot find any readable text."
            )

            return
        }

        val phrase =
            when {

                labels.size == 1 -> {

                    val label =
                        labels[0]

                    "I can see ${
                        articleFor(label)
                    }${
                        label.lowercase()
                    }."
                }

                labels.size == 2 -> {

                    val first =
                        labels[0]

                    val second =
                        labels[1]

                    "I can see ${
                        articleFor(first)
                    }${
                        first.lowercase()
                    } and ${
                        articleFor(second)
                    }${
                        second.lowercase()
                    }."
                }

                else -> {

                    val beginning =
                        labels
                            .dropLast(1)
                            .joinToString(", ") {

                                "${
                                    articleFor(it)
                                }${
                                    it.lowercase()
                                }"
                            }

                    val last =
                        labels.last()

                    "I can see $beginning and ${
                        articleFor(last)
                    }${
                        last.lowercase()
                    }."
                }
            }

        speakCommand(
            phrase
        )
    }

    // =========================================================
    // DETAILED SCENE
    // =========================================================

    fun announceDetailedScene(
        tracked: List<ObjectTracker.TrackedObject>
    ) {

        if (
            !canStartCommandSpeech()
        ) {
            return
        }

        if (tracked.isEmpty()) {

            speakCommand(
                "I don't see anything in front of you."
            )

            return
        }

        val first =
            tracked.minByOrNull {
                it.lastDistance
            }
                ?: return

        val label =
            friendlyLabel(
                first.label
            )

        val zone =
            Zone.fromNormalizedX(
                first.lastCentroid.x
            )

        val distance =
            when {

                first.lastDistance <=
                        VERY_CLOSE_THRESHOLD ->
                    "very close"

                first.lastDistance <=
                        CLOSE_THRESHOLD ->
                    "close"

                first.lastDistance <=
                        NEARBY_THRESHOLD ->
                    "nearby"

                else ->
                    "far ahead"
            }

        val phrase =
            if (zone == Zone.CENTER) {

                "There is a $label $distance in front of you."

            } else {

                "There is a $label to your ${
                    zone.spoken
                }, $distance."
            }

        speakCommand(
            phrase
        )
    }

    // =========================================================
    // PUBLIC SPEAK
    // =========================================================

    /**
     * Public speech entry point.
     *
     * Use this for:
     *
     * - OCR result
     * - "I cannot find any readable text"
     * - other explicit responses
     */
    fun speak(
        phrase: String
    ) {

        if (phrase.isBlank()) {
            return
        }

        speakCommand(
            phrase
        )
    }

    // =========================================================
    // COMMAND SPEECH
    // =========================================================

    /**
     * Checks whether a new explicit response can start.
     */
    private fun canStartCommandSpeech(): Boolean {

        val now =
            System.currentTimeMillis()

        if (
            now - lastCommandSpeechTime <
            COMMAND_SPEECH_COOLDOWN_MS
        ) {

            Log.d(
                TAG,
                "Command speech ignored because of cooldown"
            )

            return false
        }

        lastCommandSpeechTime =
            now

        return true
    }

    /**
     * Speaks a user-requested response.
     *
     * QUEUE_FLUSH is intentional.
     *
     * If an automatic warning is currently playing,
     * the user's command response takes priority.
     */
    private fun speakCommand(
        phrase: String
    ) {

        if (phrase.isBlank()) {
            return
        }

        val utteranceId =
            "dhwani_command_${System.nanoTime()}"

        currentUtteranceId =
            utteranceId

        commandSpeechActive =
            true

        isSpeaking =
            true

        Log.d(
            TAG,
            "COMMAND SPEAK: [$phrase]"
        )

        try {

            tts.stop()

            tts.speak(
                phrase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Command TTS failed",
                e
            )

            isSpeaking =
                false

            commandSpeechActive =
                false
        }
    }

    // =========================================================
    // AUTOMATIC SPEECH
    // =========================================================

    private fun speakAutomatic(
        phrase: String
    ) {

        if (phrase.isBlank()) {
            return
        }

        // Explicit command always wins.
        if (commandSpeechActive) {
            return
        }

        val utteranceId =
            "dhwani_auto_${System.nanoTime()}"

        currentUtteranceId =
            utteranceId

        isSpeaking =
            true

        Log.d(
            TAG,
            "AUTOMATIC SPEAK: [$phrase]"
        )

        try {

            tts.speak(
                phrase,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Automatic TTS failed",
                e
            )

            isSpeaking =
                false
        }
    }

    // =========================================================
    // WARNING PHRASE
    // =========================================================

    private fun buildWarningPhrase(
        obj: ObjectTracker.TrackedObject,
        approaching: Boolean
    ): String {

        val label =
            friendlyLabel(
                obj.label
            )

        val zone =
            Zone.fromNormalizedX(
                obj.lastCentroid.x
            )

        if (approaching) {

            return if (
                zone == Zone.CENTER
            ) {

                "$label approaching"

            } else {

                "$label approaching from your ${
                    zone.spoken
                }"
            }
        }

        val distanceDescription =
            when {

                obj.lastDistance <=
                        VERY_CLOSE_THRESHOLD ->
                    "very close"

                obj.lastDistance <=
                        CLOSE_THRESHOLD ->
                    "close"

                obj.lastDistance <=
                        NEARBY_THRESHOLD ->
                    "nearby"

                else ->
                    "far ahead"
            }

        return if (
            zone == Zone.CENTER
        ) {

            "$label $distanceDescription"

        } else {

            "$label to your ${
                zone.spoken
            }, $distanceDescription"
        }
    }

    // =========================================================
    // FRIENDLY LABEL
    // =========================================================

    private fun friendlyLabel(
        label: String
    ): String {

        return when (
            label.lowercase(Locale.US)
        ) {

            "person" ->
                "Person"

            "laptop" ->
                "Laptop"

            "backpack" ->
                "Backpack"

            "chair" ->
                "Chair"

            "bicycle" ->
                "Bicycle"

            "car" ->
                "Car"

            "bus" ->
                "Bus"

            "motorcycle" ->
                "Motorcycle"

            "truck" ->
                "Truck"

            "bench" ->
                "Bench"

            "door" ->
                "Door"

            "suitcase" ->
                "Suitcase"

            else ->
                label.replaceFirstChar {

                    if (
                        it.isLowerCase()
                    ) {

                        it.titlecase(
                            Locale.US
                        )

                    } else {

                        it.toString()
                    }
                }
        }
    }

    // =========================================================
    // ARTICLE
    // =========================================================

    private fun articleFor(
        label: String
    ): String {

        return when (
            label.lowercase(Locale.US)
                .firstOrNull()
        ) {

            'a',
            'e',
            'i',
            'o',
            'u' ->
                "an "

            else ->
                "a "
        }
    }

    // =========================================================
    // ZONE PRIORITY
    // =========================================================

    private fun zonePriority(
        obj: ObjectTracker.TrackedObject
    ): Int {

        return when (
            Zone.fromNormalizedX(
                obj.lastCentroid.x
            )
        ) {

            Zone.CENTER ->
                0

            Zone.LEFT ->
                1

            Zone.RIGHT ->
                2
        }
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    private fun cleanupCooldownMap() {

        if (
            lastAnnounced.size <=
            MAX_COOLDOWN_ENTRIES
        ) {
            return
        }

        val oldest =
            lastAnnounced.minByOrNull {
                it.value
            }

        oldest?.let {
            lastAnnounced.remove(
                it.key
            )
        }
    }

    // =========================================================
    // SHUTDOWN
    // =========================================================

    fun shutdown() {

        Log.d(
            TAG,
            "Shutting down TTS"
        )

        try {

            mainHandler.removeCallbacksAndMessages(
                null
            )

            tts.stop()

            tts.shutdown()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error shutting down TTS",
                e
            )
        }

        isSpeaking =
            false

        commandSpeechActive =
            false

        currentUtteranceId =
            null

        lastAnnounced.clear()
    }
}