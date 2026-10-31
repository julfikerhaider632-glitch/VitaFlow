package app.vitaflow.com.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A photo or file sent with a message. [kind] is "IMAGE" or "TEXT". */
data class Attachment(val path: String, val kind: String, val name: String)

object AttachmentJson {
    fun encode(list: List<Attachment>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("path", it.path).put("kind", it.kind).put("name", it.name)) }
        return arr.toString()
    }

    fun decode(json: String): List<Attachment> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Attachment(o.optString("path"), o.optString("kind"), o.optString("name"))
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Brings a photo, a PDF or a text file into the app's private storage in a form the AI can use:
 * photos are shrunk, PDF pages become pictures, text files are copied as text.
 */
class AttachmentManager(private val context: Context) {
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun isText(mime: String, name: String): Boolean =
        mime.startsWith("text/") || mime == "application/json" || mime == "application/csv" ||
            Regex("\\.(txt|md|csv|json|log)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    suspend fun import(uri: Uri): Result<List<Attachment>> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val name = displayName(uri) ?: "file"
            when {
                mime.startsWith("image/") -> listOf(saveImage(uri, name))
                mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> renderPdf(uri, name)
                isText(mime, name) -> listOf(saveText(uri, name))
                else -> error("এই ধরনের ফাইল এখনো বোঝা যায় না। ছবি, PDF বা লেখার ফাইল (.txt .md .csv .json) দিন।")
            }
        }
    }

    private fun saveJpeg(bmp: Bitmap, prefix: String): File {
        val f = File(dir, "$prefix-${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return f
    }

    private fun saveImage(uri: Uri, name: String): Attachment {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, 1024f / maxOf(w, h, 1))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return Attachment(saveJpeg(bmp, "img").absolutePath, "IMAGE", name)
    }

    private fun renderPdf(uri: Uri, name: String): List<Attachment> {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("ফাইল খোলা যায়নি")
        val out = ArrayList<Attachment>()
        fd.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, 2)) {
                    renderer.openPage(i).use { page ->
                        val width = 1024
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out += Attachment(saveJpeg(bmp, "pdf").absolutePath, "IMAGE", "$name (পাতা ${i + 1})")
                    }
                }
            }
        }
        if (out.isEmpty()) error("PDF-এ কোনো পাতা পাওয়া যায়নি")
        return out
    }

    private fun saveText(uri: Uri, name: String): Attachment {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
            val buf = CharArray(20000)
            val n = r.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        } ?: error("ফাইল খোলা যায়নি")
        val f = File(dir, "txt-${System.nanoTime()}.txt")
        f.writeText(text)
        return Attachment(f.absolutePath, "TEXT", name)
    }
}
