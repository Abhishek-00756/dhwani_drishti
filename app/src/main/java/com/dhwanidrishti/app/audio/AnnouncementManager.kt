package com.dhwanidrishti.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dhwanidrishti.app.processing.ObjectTracker
import com.dhwanidrishti.app.processing.Zone
import java.util.Locale

/**
 * Mode B announcement manager.
 *
 * Responsibilities:
 *  - Decide which tracked obstacle should be announced.
 *  - Prioritize the closest / most urgent obstacle.
 *  - Detect approaching objects.
 *  - Avoid repeating the same object too frequently.
 *  - Speak concise accessibility-friendly messages using Android TTS.
 *
 * Distance semantics:
 *  0.0 = nearest
 *  1.0 = farthest
 */
class AnnouncementManager(
    context: Context
) {

    // Last time each tracked object ID was announced.
    private val lastAnnounced = mutableMapOf<Int, Long>()

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->

            if (status != TextToSpeech.SUCCESS) {
                return@TextToSpeech
            }

            // Accessibility-oriented audio usage.
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            // Prefer an offline voice when available.
            val offlineVoice = tts.voices
                ?.filter {
                    it.locale.language == Locale.getDefault().language &&
                            !it.isNetworkConnectionRequired
                }
                ?.maxByOrNull { it.quality }

            if (offlineVoice != null) {
                tts.voice = offlineVoice
            }

            // Slightly faster speech for real-time navigation.
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

                    @Deprecated("Deprecated in Android API")
                    override fun onError(
                        utteranceId: String?
                    ) {
                        isSpeaking = false
                    }
                }
            )
        }
    }

    /**
     * Evaluates tracked objects and announces the most urgent one.
     *
     * Examples:
     *
     *  "Laptop very close"
     *  "Person very close"
     *  "Backpack to your left, very close"
     *  "Person approaching from your right"
     */
    fun evaluate(
        tracked: List<ObjectTracker.TrackedObject>,
        tracker: ObjectTracker
    ) {

        if (tracked.isEmpty()) {
            return
        }

        // Don't interrupt an existing announcement.
        if (isSpeaking) {
            return
        }

        val now = System.currentTimeMillis()

        /*
         * Find objects that deserve an announcement.
         *
         * An object is considered urgent when:
         *  1. It is already very close, OR
         *  2. It is approaching the user.
         */
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
            /*
             * Smaller distance = closer.
             *
             * Therefore the most dangerous object comes first.
             */
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
            buildPhrase(
                obj = objectToSpeak,
                approaching = approaching
            )

        speak(phrase)

        lastAnnounced[objectToSpeak.id] = now

        /*
         * Remove old tracker IDs from the cooldown map so this map
         * doesn't grow forever during a long session.
         */
        if (lastAnnounced.size > MAX_COOLDOWN_ENTRIES) {

            val oldest =
                lastAnnounced.minByOrNull { it.value }

            oldest?.let {
                lastAnnounced.remove(it.key)
            }
        }
    }

    /**
     * Converts a tracked object into a short spoken warning.
     */
    private fun buildPhrase(
        obj: ObjectTracker.TrackedObject,
        approaching: Boolean
    ): String {

        val label =
            friendlyLabel(obj.label)

        val zone =
            Zone.fromNormalizedX(
                obj.lastCentroid.x
            )

        /*
         * Approaching has higher priority than normal distance
         * description because movement is important for navigation.
         */
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

    /**
     * Makes YOLO labels sound more natural through TTS.
     *
     * YOLO normally returns lowercase labels such as:
     * "laptop", "person", "backpack".
     */
    private fun friendlyLabel(
        label: String
    ): String {

        return when (label.lowercase(Locale.US)) {

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
                    if (it.isLowerCase()) {
                        it.titlecase(Locale.US)
                    } else {
                        it.toString()
                    }
                }
        }
    }

    /**
     * Sends a phrase to Android TextToSpeech.
     */
    private fun speak(
        phrase: String
    ) {

        isSpeaking = true

        tts.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "dhwani_${System.nanoTime()}"
        )
    }

    /**
     * Stop and release TTS resources.
     */
    fun shutdown() {

        isSpeaking = false

        tts.stop()
        tts.shutdown()

        lastAnnounced.clear()
    }

    companion object {

        /*
         * Distance semantics:
         *
         * 0.0 = nearest
         * 1.0 = farthest
         */

        /**
         * Extremely close obstacle.
         *
         * Example:
         * "Laptop very close"
         */
        const val VERY_CLOSE_THRESHOLD = 0.20f

        /**
         * Close enough to trigger an important announcement.
         */
        const val CLOSE_THRESHOLD = 0.35f

        /**
         * Nearby obstacle.
         */
        const val NEARBY_THRESHOLD = 0.55f

        /**
         * Same tracked object is not normally announced
         * more than once every 6 seconds.
         */
        const val COOLDOWN_MS = 6000L

        /**
         * Prevent the cooldown map from growing indefinitely.
         */
        const val MAX_COOLDOWN_ENTRIES = 200
    }
}