package app.vitaflow.com.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hands-free listening: opens the microphone, waits for someone to speak, and returns one utterance
 * once they stop talking. It only measures loudness (no model, no network) and keeps audio in memory.
 */
class LiveListener {
    private val sampleRate = 16000
    private val frameMs = 100

    private fun rms(f: ShortArray): Double {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s
        return sqrt(sum / max(1, f.size))
    }

    /** Returns the audio of one utterance, or null if nobody spoke within [waitMs] (or listening was cancelled). */
    @SuppressLint("MissingPermission")
    suspend fun listen(waitMs: Int, maxSpeechMs: Int, silenceMs: Int): FloatArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(min, sampleRate * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return@withContext null
        }
        try {
            rec.startRecording()
            val frame = ShortArray(sampleRate * frameMs / 1000)
            val preRoll = ArrayDeque<ShortArray>()
            val speech = ArrayList<ShortArray>()
            var noise = 250.0
            var speaking = false
            var loud = 0
            var waited = 0
            var spoken = 0
            var quiet = 0
            while (isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                val f = frame.copyOf(n)
                val level = rms(f)
                val threshold = max(noise * 3.0, 450.0)
                if (!speaking) {
                    if (level <= threshold) noise = noise * 0.95 + level * 0.05
                    loud = if (level > threshold) loud + 1 else 0
                    preRoll.addLast(f)
                    if (preRoll.size > 3) preRoll.removeFirst()
                    if (loud >= 2) {
                        speaking = true
                        speech.addAll(preRoll)
                        preRoll.clear()
                        quiet = 0
                    }
                    waited += frameMs
                    if (waited >= waitMs) return@withContext null
                } else {
                    speech.add(f)
                    spoken += frameMs
                    quiet = if (level > threshold * 0.7) 0 else quiet + frameMs
                    if (quiet >= silenceMs || spoken >= maxSpeechMs) break
                }
            }
            if (!speaking) return@withContext null
            val total = speech.sumOf { it.size }
            val out = FloatArray(total)
            var pos = 0
            for (c in speech) for (s in c) out[pos++] = s / 32768f
            out
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
