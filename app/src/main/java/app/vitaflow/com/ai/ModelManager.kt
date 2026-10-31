package app.vitaflow.com.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Keeps the big model files in the app's private storage and checks that they are intact. */
class ModelManager(private val context: Context) {
    companion object {
        val PACKS = listOf("bn-stt", "en-stt", "bn-tts", "en-tts")
        const val LLM = "gemma-llm"
        private const val LLM_FILE = "gemma.litertlm"
    }

    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed

    private fun scan(): Set<String> {
        val s = mutableSetOf<String>()
        PACKS.forEach { if (packFiles(it) != null) s += it }
        if (llmFile() != null) s += LLM
        return s
    }

    fun refresh() {
        _installed.value = scan()
    }

    /** Returns (model path, tokens path) of an installed speech pack, or null. */
    fun packFiles(name: String): Pair<String, String>? {
        val dir = File(root, name)
        val mf = File(dir, "manifest.json")
        if (!mf.exists()) return null
        return runCatching {
            val files = JSONObject(mf.readText()).getJSONObject("files")
            val model = File(dir, files.getString("model"))
            val tokens = File(dir, files.getString("tokens"))
            if (model.exists() && tokens.exists()) model.absolutePath to tokens.absolutePath else null
        }.getOrNull()
    }

    fun llmFile(): File? = File(root, LLM_FILE).takeIf { it.exists() && it.length() > 1_000_000 }

    fun delete(name: String) {
        if (name == LLM) File(root, LLM_FILE).delete() else File(root, name).deleteRecursively()
        refresh()
    }

    /** Imports a .itpack zip (manifest.json, tokens.txt, model file) after checking its SHA-256 sums. */
    suspend fun importPack(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(root.usableSpace > 700L * 1024 * 1024) { "ফোনে যথেষ্ট খালি জায়গা নেই" }
            val tmp = File(root, ".tmp-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val digests = HashMap<String, String>()
                val input = context.contentResolver.openInputStream(uri) ?: error("ফাইল খোলা যায়নি")
                input.use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val fname = entry.name
                            if (!entry.isDirectory) {
                                require(!fname.contains('/') && !fname.contains("..")) { "অচেনা ফাইল: $fname" }
                                val md = MessageDigest.getInstance("SHA-256")
                                File(tmp, fname).outputStream().use { out ->
                                    val buf = ByteArray(1 shl 16)
                                    while (true) {
                                        val n = zip.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        md.update(buf, 0, n)
                                    }
                                }
                                digests[fname] = md.digest().joinToString("") { "%02x".format(it) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                val mf = File(tmp, "manifest.json")
                require(mf.exists()) { "এটা ঠিক model pack নয় (manifest.json নেই)" }
                val manifest = JSONObject(mf.readText())
                val name = manifest.getString("name")
                require(name in PACKS) { "এই pack চেনা যায়নি: $name" }
                val sums = manifest.optJSONObject("sha256")
                if (sums != null) {
                    for (k in sums.keys()) {
                        require(digests[k] == sums.getString(k)) { "$k ফাইল নষ্ট, আবার copy করুন" }
                    }
                }
                val target = File(root, name)
                target.deleteRecursively()
                require(tmp.renameTo(target)) { "সংরক্ষণ করা যায়নি" }
                refresh()
                name
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    private fun nameOf(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun sizeOf(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    /** Copies the Gemma .litertlm file (about 2.6 GB) into private storage. */
    suspend fun importLlm(uri: Uri, onProgress: (done: Long, total: Long) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileName = nameOf(uri)
                require(fileName == null || fileName.endsWith(".litertlm", ignoreCase = true)) {
                    "এই ফাইলটা LiteRT-LM format-এর (.litertlm) নয়। .gguf ফাইল এই app-এ চলবে না। gemma-4-E2B-it.litertlm বেছে নিন।"
                }
                val total = sizeOf(uri)
                require(total <= 0 || root.usableSpace > total + 300L * 1024 * 1024) { "ফোনে যথেষ্ট খালি জায়গা নেই" }
                val part = File(root, "$LLM_FILE.part")
                part.delete()
                val input = context.contentResolver.openInputStream(uri) ?: error("ফাইল খোলা যায়নি")
                input.use { src ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                val dest = File(root, LLM_FILE)
                dest.delete()
                require(part.renameTo(dest)) { "সংরক্ষণ করা যায়নি" }
                refresh()
            }
        }
}
