package app.vitaflow.com.ai

import app.vitaflow.com.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class WebHit(val title: String, val url: String, val snippet: String)

sealed interface WebResult {
    data class Ok(val hits: List<WebHit>) : WebResult
    data class Err(val message: String) : WebResult
}

/** Web search through Tavily. Only the short search phrase leaves the phone, nothing else. */
class TavilyClient(private val secrets: SecretStore, private val settings: SettingsStore) {
    companion object {
        const val DAILY_LIMIT = 30
    }

    suspend fun search(query: String): WebResult = withContext(Dispatchers.IO) {
        if (!settings.webSearchEnabled.first()) {
            return@withContext WebResult.Err("ওয়েব সার্চ বন্ধ আছে। সেটিংস থেকে চালু করুন।")
        }
        val key = secrets.tavilyKey()
            ?: return@withContext WebResult.Err("Tavily key বসানো নেই। সেটিংস থেকে বসান।")
        if (!settings.takeSearchSlot(DAILY_LIMIT)) {
            return@withContext WebResult.Err("আজকের $DAILY_LIMIT টা web search শেষ হয়ে গেছে।")
        }
        try {
            val conn = URL("https://api.tavily.com/search").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            val body = JSONObject()
                .put("query", query.take(300))
                .put("search_depth", "basic")
                .put("max_results", 4)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return@withContext WebResult.Err("web search হয়নি (কোড $code)। key ঠিক আছে কি না দেখুন।")
            }
            val arr = JSONObject(text).optJSONArray("results")
            val hits = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(WebHit(o.optString("title"), o.optString("url"), o.optString("content")))
                    }
                }
            }
            WebResult.Ok(hits)
        } catch (e: IOException) {
            WebResult.Err("ইন্টারনেট সংযোগ নেই।")
        } catch (e: org.json.JSONException) {
            WebResult.Err("search-এর উত্তর পড়া যায়নি।")
        }
    }
}
