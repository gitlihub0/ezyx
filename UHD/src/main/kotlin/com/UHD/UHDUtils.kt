package com.UHD

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

suspend fun fetchTurkishDomains(): Map<String, String> {
    val url = "https://raw.githubusercontent.com/gitlihub0/domainListesi/refs/heads/main/eklenti_domainleri.txt"
    return runCatching {
        val text = app.get(url).text
        val map = mutableMapOf<String, String>()
        text.lines().forEach { line ->
            val cleanLine = line.trim()
            if (cleanLine.startsWith("|")) {
                val parts = cleanLine.drop(1).split(":", limit = 2)
                if (parts.size == 2) {
                    map[parts[0]] = parts[1].trim()
                }
            }
        }
        map
    }.getOrDefault(emptyMap())
}

suspend fun extractDiziboxSubtitles(iframeUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
    try {
        val html = app.get(iframeUrl).text
        val playerRegex = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val match = playerRegex.find(html) ?: return
        
        val playerJson = JSONObject(match.groupValues[1])
        val subsArray = playerJson.optJSONArray("subs") ?: return
        
        for (i in 0 until subsArray.length()) {
            val subObj = subsArray.getJSONObject(i)
            val lang = subObj.optString("lang")
            val label = subObj.optString("label")
            val src = subObj.optString("src")
            if (src.isNotBlank()) subtitleCallback.invoke(SubtitleFile("Dizibox - $label ($lang)", src))
        }
    } catch (e: Exception) { Log.e("UHD", "Dizibox Error: ${e.message}") }
}

suspend fun extractDiziPalSubtitles(embedUrl: String, mainUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
    try {
        val embedSource = app.get(embedUrl, referer = "$mainUrl/").text
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource) ?: return
        
        Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).findAll(tracksBlockMatch.groupValues[1]).forEach { itemMatch ->
            val itemStr = itemMatch.groupValues[1]
            val fileUrl = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)?.groupValues?.getOrNull(1)
            val label = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)?.groupValues?.getOrNull(1) ?: "Türkçe"
            if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                val finalUrl = if (fileUrl.startsWith("http")) fileUrl else "$mainUrl$fileUrl"
                subtitleCallback.invoke(SubtitleFile("DiziPal - $label", finalUrl))
            }
        }
    } catch (e: Exception) { Log.e("UHD", "DiziPal Error: ${e.message}") }
}

private val REDIRECT_REGEX = Regex("s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'")

suspend fun getRedirectLinks(url: String): String {
    val html = runCatching { app.get(url).text }.getOrElse { return "" }
    val combined = StringBuilder(128)
    for (m in REDIRECT_REGEX.findAll(html)) {
        m.groups[1]?.value?.let(combined::append) ?: m.groups[2]?.value?.let(combined::append)
    }
    if (combined.isEmpty()) return ""

    return runCatching {
        val decoded = base64Decode(pen(base64Decode(base64Decode(combined.toString()))))
        val json    = JSONObject(decoded)
        val encodedUrl = base64Decode(json.optString("o"))
        if (encodedUrl.isNotBlank()) return@runCatching encodedUrl.trim()

        val data = encode(json.optString("data"))
        val wp   = json.optString("blog_url")
        if (wp.isBlank() || data.isBlank()) return@runCatching ""
        app.get("$wp?re=$data").document.text().trim()
    }.getOrElse { url }
}

fun encode(value: String): String = if (value.isEmpty()) "" else String(Base64.decode(value, Base64.DEFAULT))

fun pen(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) {
        out.append(
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                else        -> c
            }
        )
    }
    return out.toString()
}

suspend fun fetchtmdb(title: String, isMovie: Boolean): Int? {
    val url = "${UHD.TMDBAPI}/search/multi?api_key=${UHD.TMDB_API_KEY}&query=" + URLEncoder.encode(title.trim(), "UTF-8")
    val json = JSONObject(app.get(url).text)
    val results = json.optJSONArray("results") ?: return null

    val targetType = if (isMovie) "movie" else "tv"
    fun normalize(s: String?): String = s?.lowercase()?.replace("[^a-z0-9]".toRegex(), "")?.trim() ?: ""

    val inputNorm = normalize(title)
    var fallback: Int? = null

    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        if (item.optString("media_type") != targetType) continue
        val resultTitle = if (isMovie) item.optString("title") else item.optString("name")
        val resultNorm = normalize(resultTitle)
        if (resultNorm.isEmpty()) continue
        if (fallback == null) fallback = item.optInt("id")
        if (resultNorm == inputNorm || resultNorm.contains(inputNorm) || inputNorm.contains(resultNorm))
            return item.optInt("id")
    }
    return fallback
}

fun parseCredits(jsonText: String?): List<ActorData> {
    if (jsonText.isNullOrBlank()) return emptyList()
    val list = ArrayList<ActorData>()
    val root = JSONObject(jsonText)
    val castArr = root.optJSONArray("cast") ?: return list
    for (i in 0 until castArr.length()) {
        val c = castArr.optJSONObject(i) ?: continue
        val name = c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("original_name").orEmpty()
        val profile = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "${UHD.TMDBIMAGEBASEURL}$it" }
        val character = c.optString("character").takeIf { it.isNotBlank() }
        list += ActorData(Actor(name, profile), roleString = character)
    }
    return list
}

fun safeScoreFrom10(value: Double?): Score? = value?.takeIf { !it.isNaN() && it > 0.0 }?.let { Score.from10(it) }

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    quality: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink("${link.source} $source", "${link.source} $source", link.url) {
                    this.quality = quality ?: link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}