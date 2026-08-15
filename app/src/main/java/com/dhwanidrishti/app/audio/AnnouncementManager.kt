package com.dhwanidrishti.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dhwanidrishti.app.processing.ObjectTracker
import com.dhwanidrishti.app.processing.Zone
import java.util.Locale

/**
 * Handles all spoken output for Dhwani Drishti.
 *
 * Two types of speech:
 *
 * 1. Automatic obstacle warnings
 *    Example:
 *    "Laptop very close"
 *    "Person approaching from your right"
 *
 * 2. On-demand scene description
 *    Triggered by:
 *    "Hey Dhwani, what's in front of me?"
 *
 *    Example:
 *    "I see a laptop and a person in front of you."
 */
class AnnouncementManager(
    context: Context
) {

    // ---------------------------------------------------------
    // TTS
    // ---------------------------------------------------------

    private val tts: TextToSpeech

    @Volatile
    var isSpeaking: Boolean = false
        private set

    // ---------------------------------------------------------
    // Automatic announcement cooldown
    // ---------------------------------------------------------

    private val lastAnnounced = mutableMapOf<Int, Long>()

    init {

        tts = TextToSpeech(context) { status ->

            if (status != TextToSpeech.SUCCESS) {
                return@TextToSpeech
            }

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

            // Prefer offline voice when available.
            val offlineVoice = tts.voices
                ?.filter {
                    it.locale.language ==
                            Locale.getDefault().language &&
                            !it.isNetworkConnectionRequired
                }
                ?.maxByOrNull { it.quality }

            if (offlineVoice != null) {
                tts.voice = offlineVoice
            }

            // Slightly faster for navigation.
            tts.setSpeechRate(1.05f)
            tts.setPitch(1.0f)

            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(
                        utteranceId: String?
                    ) {
                        isSpeaking = true
                    }

                    override fun onDone(
                        utteranceId: String?
                    ) {
                        isSpeaking = false
                    }

                    override fun onError(
                        utteranceId: String?
                    ) {
                        isSpeaking = false
                    }
                }
            )
        }
    }

    // =========================================================
    // AUTOMATIC OBSTACLE ANNOUNCEMENT
    // =========================================================

    /**
     * Automatically announces the most important tracked object.
     */
    fun evaluate(
        tracked: List<ObjectTracker.TrackedObject>,
        tracker: ObjectTracker
    ) {

        if (tracked.isEmpty()) {
            return
        }

        if (isSpeaking) {
            return
        }

        val now = System.currentTimeMillis()

        val candidates = tracked
            .filter { obj ->

                val lastTime =
                    lastAnnounced[obj.id] ?: 0L

                val cooldownExpired =
                    now - lastTime >= COOLDOWN_MS

                val veryClose =
                    obj.lastDistance <= VERY_CLOSE_THRESHOLD

                val close =
                    obj.lastDistance <= CLOSE_THRESHOLD

                val approaching =
                    tracker.isApproaching(obj)

                cooldownExpired &&
                        (veryClose || close || approaching)
            }
            .sortedWith(
                compareBy<ObjectTracker.TrackedObject> {

                    when {
                        it.lastDistance <= VERY_CLOSE_THRESHOLD -> 0
                        it.lastDistance <= CLOSE_THRESHOLD -> 1
                        else -> 2
                    }

                }.thenBy {

                    it.lastDistance
                }
            )

        val objectToSpeak =
            candidates.firstOrNull()
                ?: return

        val approaching =
            tracker.isApproaching(objectToSpeak)

        val phrase =
            buildWarningPhrase(
                obj = objectToSpeak,
                approaching = approaching
            )

        speak(phrase)

        lastAnnounced[
            objectToSpeak.id
        ] = now

        cleanupCooldownMap()
    }

    // =========================================================
    // "WHAT'S IN FRONT OF ME?"
    // =========================================================

    /**
     * Gives an on-demand description of the current scene.
     *
     * This is called when the user says:
     *
     * "Hey Dhwani, what's in front of me?"
     *
     * Example:
     *
     * "I see a laptop and a person in front of you."
     */
    fun announceWhatIsInFront(
        tracked: List<ObjectTracker.TrackedObject>
    ) {

        if (isSpeaking) {
            return
        }

        if (tracked.isEmpty()) {

            speak(
                "I don't see anything in front of you."
            )

            return
        }

        /*
         * We consider the complete camera view as the scene
         * in front of the user.
         *
         * Objects are sorted:
         *
         * closest first
         * then center
         * then left/right
         */
        val sortedObjects =
            tracked
                .sortedWith(
                    compareBy<ObjectTracker.TrackedObject> {

                        it.lastDistance

                    }.thenBy {

                        zonePriority(it)
                    }
                )

        /*
         * Avoid announcing the same object type multiple times.
         *
         * Example:
         *
         * laptop
         * laptop
         * laptop
         *
         * becomes:
         *
         * "a laptop"
         */
        val labels =
            sortedObjects
                .map {
                    friendlyLabel(it.label)
                }
                .distinct()

        val phrase =
            when (labels.size) {

                1 -> {
                    "I see a ${articleFor(labels[0])}${labels[0].lowercase()} in front of you."
                }

                2 -> {
                    "I see a ${articleFor(labels[0])}${labels[0].lowercase()} and a ${articleFor(labels[1])}${labels[1].lowercase()} in front of you."
                }

                else -> {

                    val beginning =
                        labels
                            .dropLast(1)
                            .joinToString(", ") {
                                "a ${articleFor(it)}${it.lowercase()}"
                            }

                    val last =
                        "a ${articleFor(labels.last())}${labels.last().lowercase()}"

                    "I see $beginning and $last in front of you."
                }
            }

        speak(phrase)
    }

    /**
     * Gives a slightly more useful spatial answer when there
     * is only one object.
     */
    fun announceDetailedScene(
        tracked: List<ObjectTracker.TrackedObject>
    ) {

        if (isSpeaking) {
            return
        }

        if (tracked.isEmpty()) {
            speak("I don't see anything in front of you.")
            return
        }

        val sorted =
            tracked.sortedBy {
                it.lastDistance
            }

        val first =
            sorted.first()

        val label =
            friendlyLabel(first.label)

        val zone =
            Zone.fromNormalizedX(
                first.lastCentroid.x
            )

        val distance =
            when {

                first.lastDistance <= VERY_CLOSE_THRESHOLD ->
                    "very close"

                first.lastDistance <= CLOSE_THRESHOLD ->
                    "close"

                first.lastDistance <= NEARBY_THRESHOLD ->
                    "nearby"

                else ->
                    "far ahead"
            }

        val phrase =
            if (zone == Zone.CENTER) {

                "There is a $label $distance in front of you."

            } else {

                "There is a $label to your ${zone.spoken}, $distance."
            }

        speak(phrase)
    }

    // =========================================================
    // WARNING PHRASE
    // =========================================================

    private fun buildWarningPhrase(
        obj: ObjectTracker.TrackedObject,
        approaching: Boolean
    ): String {

        val label =
            friendlyLabel(obj.label)

        val zone =
            Zone.fromNormalizedX(
                obj.lastCentroid.x
            )

        if (approaching) {

            return if (zone == Zone.CENTER) {

                "$label approaching"

            } else {

                "$label approaching from your ${zone.spoken}"
            }
        }

        val distanceDescription =
            when {

                obj.lastDistance <= VERY_CLOSE_THRESHOLD ->
                    "very close"

                obj.lastDistance <= CLOSE_THRESHOLD ->
                    "close"

                obj.lastDistance <= NEARBY_THRESHOLD ->
                    "nearby"

                else ->
                    "far ahead"
            }

        return if (zone == Zone.CENTER) {

            "$label $distanceDescription"

        } else {

            "$label to your ${zone.spoken}, $distanceDescription"
        }
    }

    // =========================================================
    // FRIENDLY LABELS
    // =========================================================

    private fun friendlyLabel(
        label: String
    ): String {

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
            "suitcase" -> "Suitcase"
            "refrigerator" -> "Refrigerator"
            "cell phone" -> "Cell phone"
            "keyboard" -> "Keyboard"
            "mouse" -> "Mouse"
            "book" -> "Book"
            "bottle" -> "Bottle"
            "cup" -> "Cup"
            "chair" -> "Chair"
            "couch" -> "Couch"
            "dining table" -> "Dining table"
            "tv" -> "TV"
            "microwave" -> "Microwave"
            "oven" -> "Oven"
            "sink" -> "Sink"

            else ->
                label.replaceFirstChar {
                    if (it.isLowerCase()) {
                        it.titlecase(Locale.US)
                    } else {
                        it.toString()
                    }
                }
        }
    }

    private fun articleFor(
        label: String
    ): String {

        return when (label.lowercase(Locale.US).firstOrNull()) {

            'a',
            'e',
            'i',
            'o',
            'u' -> "an "

            else -> "a "
        }
    }

    private fun zonePriority(
        obj: ObjectTracker.TrackedObject
    ): Int {

        return when (
            Zone.fromNormalizedX(
                obj.lastCentroid.x
            )
        ) {
            Zone.CENTER -> 0
            Zone.LEFT -> 1
            Zone.RIGHT -> 2
        }
    }

    // =========================================================
    // TTS
    // =========================================================

    fun speak(
        phrase: String
    ) {

        if (phrase.isBlank()) {
            return
        }

        isSpeaking = true

        tts.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "dhwani_${System.nanoTime()}"
        )
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    private fun cleanupCooldownMap() {

        if (lastAnnounced.size <= MAX_COOLDOWN_ENTRIES) {
            return
        }

        val oldest =
            lastAnnounced.minByOrNull {
                it.value
            }

        oldest?.let {
            lastAnnounced.remove(it.key)
        }
    }

    fun shutdown() {

        isSpeaking = false

        tts.stop()
        tts.shutdown()

        lastAnnounced.clear()
    }

    companion object {

        const val VERY_CLOSE_THRESHOLD = 0.20f

        const val CLOSE_THRESHOLD = 0.35f

        const val NEARBY_THRESHOLD = 0.55f

        const val COOLDOWN_MS = 6000L

        const val MAX_COOLDOWN_ENTRIES = 200
    }
}