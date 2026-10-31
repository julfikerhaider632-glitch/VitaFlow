package app.vitaflow.com.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records 16 kHz mono audio from the microphone. The audio stays in memory and is never saved to disk. */
class VoiceRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val MAX_SECONDS = 20
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<ShortArray>()
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        synchronized(lock) { chunks.clear() }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(1600)
            var total = 0
            while (running && total < SAMPLE_RATE * MAX_SECONDS) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(lock) { chunks.add(buf.copyOf(n)) }
                    total += n
                } else if (n < 0) {
                    break
                }
            }
        }.also { it.start() }
        return true
    }

    /** Stops recording and returns the samples as floats in -1..1, ready for the speech model. */
    fun stop(): FloatArray {
        running = false
        thread?.join(800)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val all = synchronized(lock) { chunks.toList().also { chunks.clear() } }
        val size = all.sumOf { it.size }
        val out = FloatArray(size)
        var pos = 0
        for (c in all) {
            for (s in c) out[pos++] = s / 32768f
        }
        return out
    }

    fun cancel() {
        stop()
    }
}
