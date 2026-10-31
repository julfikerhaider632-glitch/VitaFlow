package app.vitaflow.com.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The assistant's mouth: local neural voice, played with AudioTrack. No Android TextToSpeech. */
class LocalTts(private val models: ModelManager) {
    private val engines = HashMap<String, OfflineTts>()
    private val allowed = HashMap<String, Set<Char>>()
    private val mutex = Mutex()
    private val phraseCache = HashMap<String, Pair<Int, List<FloatArray>>>()
    @Volatile private var cancelled = false
    @Volatile private var track: AudioTrack? = null

    fun isReady(lang: String): Boolean = models.packFiles("$lang-tts") != null

    private fun engine(lang: String): OfflineTts? {
        engines[lang]?.let { return it }
        val (model, tokens) = models.packFiles("$lang-tts") ?: return null
        val tts = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = model, tokens = tokens),
                    numThreads = 3,
                    provider = "cpu"
                )
            )
        )
        engines[lang] = tts
        allowed[lang] = readTokenChars(tokens)
        return tts
    }

    private fun readTokenChars(tokensPath: String): Set<Char> {
        val set = mutableSetOf(' ')
        File(tokensPath).forEachLine { line ->
            val tok = line.substringBeforeLast(' ', "")
            if (tok.length == 1) set += tok[0]
        }
        return set
    }

    private fun detectLang(s: String, fallback: String): String {
        var bn = 0
        var latin = 0
        for (c in s) {
            if (c in '\u0980'..'\u09FF') bn++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++
        }
        return when {
            bn == 0 && latin == 0 -> fallback
            bn >= latin -> "bn"
            else -> "en"
        }
    }

    private fun clean(text: String, lang: String): String {
        val ok = allowed[lang] ?: return text
        val base = text.map { c ->
            if (c in '\u09E6'..'\u09EF') '0' + (c - '\u09E6') else c
        }.joinToString("")
        val normalized = if (lang == "en") base.lowercase(Locale.ROOT) else base
        return normalized.filter { it in ok }.replace(Regex("\\s+"), " ").trim()
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[।.!?\\n])\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Speaks [text], switching between the Bengali and English voice sentence by sentence. */
    /** [cache] = true keeps the generated audio, so short fixed phrases (like "working on it") replay without heavy CPU work. */
    suspend fun speak(text: String, speed: Float = 1.0f, cache: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            cancelled = false
            for (sentence in sentences(text)) {
                if (cancelled) break
                val lang = detectLang(sentence, "bn")
                val tts = engine(lang) ?: continue
                val cleaned = clean(sentence, lang)
                if (cleaned.isBlank()) continue
                playOne(tts, cleaned, speed, if (cache) "$lang|$cleaned" else null)
            }
        }
    }

    private fun playOne(tts: OfflineTts, text: String, speed: Float, cacheKey: String?) {
        val cached = cacheKey?.let { phraseCache[it] }
        val rate = cached?.first ?: tts.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            if (cached != null) {
                for (chunk in cached.second) {
                    if (cancelled) break
                    t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }
            } else {
                val chunks = if (cacheKey != null) ArrayList<FloatArray>() else null
                tts.generateWithCallback(text = text, sid = 0, speed = speed) { samples ->
                    if (cancelled) {
                        0
                    } else {
                        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        chunks?.add(samples.copyOf())
                        1
                    }
                }
                if (!cancelled && cacheKey != null && chunks != null) phraseCache[cacheKey] = rate to chunks
            }
            if (!cancelled) t.stop()
        } finally {
            runCatching { t.release() }
            track = null
        }
    }

    /** Stops the voice right away (the user pressed stop or sent a new command). */
    fun stop() {
        cancelled = true
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    suspend fun release() = mutex.withLock {
        engines.values.forEach { it.release() }
        engines.clear()
    }
}
