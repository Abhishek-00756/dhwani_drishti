package com.dhwanidrishti.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.dhwanidrishti.app.processing.ZoneDistances
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Continuous-tone soundscape rendered with AudioTrack in MODE_STREAM.
 *
 * SoundPool is wrong here: it plays short, fixed sound effects and cannot
 * glide pitch/pan smoothly. A phase accumulator whose slope simply changes
 * gives click-free frequency glides at 44.1kHz.
 *
 * Runs on its own thread and never blocks on the camera/inference thread.
 */
class SonificationEngine {

    @Volatile private var targetFreqL = MIN_FREQ
    @Volatile private var targetFreqR = MIN_FREQ
    @Volatile private var gainL = 0.5f
    @Volatile private var gainR = 0.5f
    @Volatile private var masterGain = 1f

    /** When true (Narrated mode), the tone is silenced but the loop keeps running. */
    @Volatile var muted: Boolean = false

    /**
     * Volume multiplier applied while Mode B is speaking, so a narrated
     * announcement can duck the tone instead of shouting over it. Set to
     * [DUCK_LEVEL] (0.2f) during speech, restored to 1.0f after.
     */
    @Volatile var duckMultiplier: Float = 1f

    private var phaseL = 0.0
    private var phaseR = 0.0

    @Volatile private var started = false

    private val audioTrack: AudioTrack by lazy {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBufferSize, minBufferSize * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private val minBufferSize = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val framesPerBuffer = max(256, minBufferSize / 8)

    fun start() {
        if (started) return
        started = true
        Thread(::audioLoop, "sonification").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            audioTrack.stop()
        } catch (_: Throwable) {
        }
        try {
            audioTrack.release()
        } catch (_: Throwable) {
        }
    }

    /**
     * Ducks the tone to [DUCK_LEVEL] while Mode B is speaking (hybrid mode),
     * or restores it to full volume when speech finishes.
     */
    fun setDucking(ducking: Boolean) {
        duckMultiplier = if (ducking) DUCK_LEVEL else 1f
    }

    /**
     * Called from the inference thread with the latest smoothed zones.
     * Nearer zone -> higher pitch on the matching ear; the closer side is
     * louder; center closeness scales overall loudness.
     */
    fun updateFromZones(zones: ZoneDistances) {
        targetFreqL = closenessToPitch(zones.left)
        targetFreqR = closenessToPitch(zones.right)
        val balance = panBalance(zones)
        gainL = 1f - balance
        gainR = balance
        masterGain = closenessToGain(zones.center)
    }

    /** closeness 1 (nearest) -> MAX_FREQ; 0 -> MIN_FREQ. */
    private fun closenessToPitch(closeness: Float): Float {
        val c = closeness.coerceIn(0f, 1f)
        return MIN_FREQ + c * (MAX_FREQ - MIN_FREQ)
    }

    /** Center closeness controls overall loudness: near is salient, far is faint. */
    private fun closenessToGain(closeness: Float): Float {
        val c = closeness.coerceIn(0f, 1f)
        return 0.25f + 0.75f * c
    }

    /** 0 = obstacle mostly left, 0.5 = balanced, 1 = mostly right. */
    private fun panBalance(zones: ZoneDistances): Float {
        val l = zones.left.coerceIn(0f, 1f)
        val r = zones.right.coerceIn(0f, 1f)
        return ((r - l) + 1f) / 2f
    }

    private fun audioLoop() {
        audioTrack.play()
        val buffer = ShortArray(framesPerBuffer * 2)
        while (started) {
            try {
                val fL = targetFreqL
                val fR = targetFreqR
                val gL = if (muted) 0f else gainL * masterGain * duckMultiplier
                val gR = if (muted) 0f else gainR * masterGain * duckMultiplier
                for (i in buffer.indices step 2) {
                    phaseL += TWO_PI * fL / SAMPLE_RATE
                    phaseR += TWO_PI * fR / SAMPLE_RATE
                    buffer[i] = (sin(phaseL) * Short.MAX_VALUE * gL).toInt().toShort()
                    buffer[i + 1] = (sin(phaseR) * Short.MAX_VALUE * gR).toInt().toShort()
                }
                val written = audioTrack.write(buffer, 0, buffer.size)
                if (written < 0) break
            } catch (t: Throwable) {
                break
            }
        }
        started = false
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val MIN_FREQ = 220f
        const val MAX_FREQ = 880f
        const val DUCK_LEVEL = 0.2f
        val TWO_PI = 2.0 * PI
    }
}
