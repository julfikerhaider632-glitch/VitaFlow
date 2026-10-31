package app.vitaflow.com.ai

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The assistant's ears: turns speech into text on the phone, no Android SpeechRecognizer involved. */
class LocalAsr(private val models: ModelManager) {
    private val recognizers = HashMap<String, OfflineRecognizer>()
    private val mutex = Mutex()

    fun isReady(lang: String): Boolean = models.packFiles("$lang-stt") != null

    /** [lang] is "bn" or "en". Throws a readable error if the model has not been added yet. */
    suspend fun transcribe(samples: FloatArray, lang: String): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val recognizer = recognizers.getOrPut(lang) {
                val (model, tokens) = models.packFiles("$lang-stt")
                    ?: error("${if (lang == "bn") "বাংলা" else "English"} শোনার model যোগ করা নেই")
                OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = VoiceRecorder.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(model = model),
                            tokens = tokens,
                            numThreads = 3,
                            provider = "cpu"
                        )
                    )
                )
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, VoiceRecorder.SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    suspend fun release() = mutex.withLock {
        recognizers.values.forEach { it.release() }
        recognizers.clear()
    }
}
