package com.dhwanidrishti.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dhwanidrishti.app.processing.ObjectTracker
import com.dhwanidrishti.app.processing.Zone
import java.util.Locale

/**
 * Mode B (Narrated) announcement manager.
 */
class AnnouncementManager(context: Context) {

    private val lastAnnounced = mutableMapOf<Int, Long>()

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {

                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                val voices = tts.voices
                    ?.filter {
                        it.locale == Locale.getDefault() &&
                                !it.isNetworkConnectionRequired
                    }

                voices?.maxByOrNull { it.quality }?.let {
                    tts.voice = it
                }

                tts.setSpeechRate(1.05f)
                tts.setPitch(1.0f)

                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {
                            // Nothing needed here.
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                        }
                    }
                )
            }
        }
    }

    fun evaluate(
        tracked: List<ObjectTracker.TrackedObject>,
        tracker: ObjectTracker
    ) {
        val now = System.currentTimeMillis()

        val candidates = tracked
            .filter { obj ->
                val cooldownOk =
                    (now - (lastAnnounced[obj.id] ?: 0L)) > COOLDOWN_MS

                val isUrgent =
                    obj.lastDistance < NEAR_THRESHOLD ||
                            tracker.isApproaching(obj)

                cooldownOk && isUrgent
            }
            .sortedBy { it.lastDistance }

        val toSpeak = candidates.firstOrNull() ?: return

        if (isSpeaking) return

        val phrase =
            buildPhrase(toSpeak, tracker.isApproaching(toSpeak))

        speak(phrase)

        lastAnnounced[toSpeak.id] = now
    }

    private fun buildPhrase(
        obj: ObjectTracker.TrackedObject,
        approaching: Boolean
    ): String {

        val zone = Zone.fromNormalizedX(obj.lastCentroid.x)

        val distanceDesc = when {
            obj.lastDistance < 0.2f ->
                "very close"

            obj.lastDistance < 0.5f ->
                "${(obj.lastDistance * 5).toInt()} meters"

            else ->
                "ahead"
        }

        return when {
            approaching -> {
                if (obj.label == "person") {
                    "A person is coming from your ${zone.spoken}"
                } else {
                    "${obj.label} approaching from your ${zone.spoken}"
                }
            }

            zone == Zone.CENTER ->
                "${obj.label}, $distanceDesc"

            else ->
                "${obj.label} to your ${zone.spoken}, $distanceDesc"
        }
    }

    private fun speak(phrase: String) {
        isSpeaking = true

        tts.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "obj_${System.nanoTime()}"
        )
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        const val NEAR_THRESHOLD = 0.3f
        const val COOLDOWN_MS = 6000L
    }
}