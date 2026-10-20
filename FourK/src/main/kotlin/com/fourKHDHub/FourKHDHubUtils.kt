package com.fourKHDHub

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
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
import org.json.JSONArray
import java.net.URLEncoder
import java.text.Normalizer

private val REDIRECT_REGEX = Regex("s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'")

suspend fun getRedirectLinks(url: String): String {
    val html = runCatching { app.get(url).text }.getOrElse {
        Log.e("4KHDHub", "Failed redirect page: ${it.message}")
        return ""
    }

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
    }.getOrElse {
        Log.e("4KHDHub", "Redirect processing error: ${it.message}")
        url
    }
}

fun encode(value: String): String =
    if (value.isEmpty()) "" else String(Base64.decode(value, Base64.DEFAULT))

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
    val url     = "${FourKHDHub.TMDBAPI}/search/multi?api_key=${FourKHDHub.TMDB_API_KEY}&query=" + URLEncoder.encode(title.trim(), "UTF-8")
    val json    = JSONObject(app.get(url).text)
    val results = json.optJSONArray("results") ?: return null

    val targetType = if (isMovie) "movie" else "tv"
    fun normalize(s: String?): String = s?.lowercase()?.replace("[^a-z0-9]".toRegex(), "")?.trim() ?: ""

    val inputNorm = normalize(title)
    var fallback: Int? = null

    for (i in 0 until results.length()) {
        val item       = results.optJSONObject(i) ?: continue
        if (item.optString("media_type") != targetType) continue
        val resultTitle = if (isMovie) item.optString("title") else item.optString("name")
        val resultNorm  = normalize(resultTitle)
        if (resultNorm.isEmpty()) continue
        if (fallback == null) fallback = item.optInt("id")
        if (resultNorm == inputNorm || resultNorm.contains(inputNorm) || inputNorm.contains(resultNorm))
            return item.optInt("id")
    }
    return fallback
}

fun parseCredits(jsonText: String?): List<ActorData> {
    if (jsonText.isNullOrBlank()) return emptyList()
    val list     = ArrayList<ActorData>()
    val root     = JSONObject(jsonText)
    val castArr  = root.optJSONArray("cast") ?: return list
    for (i in 0 until castArr.length()) {
        val c         = castArr.optJSONObject(i) ?: continue
        val name      = c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("original_name").orEmpty()
        val profile   = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "${FourKHDHub.TMDBIMAGEBASEURL}$it" }
        val character = c.optString("character").takeIf { it.isNotBlank() }
        list += ActorData(Actor(name, profile), roleString = character)
    }
    return list
}

fun safeScoreFrom10(value: Double?): Score? =
    value?.takeIf { !it.isNaN() && it > 0.0 }?.let { Score.from10(it) }

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
                    this.quality       = quality ?: link.quality
                    this.type          = link.type
                    this.referer       = link.referer
                    this.headers       = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

// ==========================================
// FULLHDFILMİZLESENE TR SES BİRLEŞTİRİCİ
// ==========================================
object FullHDFilmMerger {

    private fun atob(s: String): String = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)

    private fun rtt(s: String): String {
        return s.map { c ->
            when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun smartDecodeLink(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("//")) {
            return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        }
        try {
            val rotDecoded = atob(rtt(trimmed)).trim()
            if (rotDecoded.startsWith("http") || rotDecoded.startsWith("//")) return if (rotDecoded.startsWith("//")) "https:$rotDecoded" else rotDecoded
        } catch (_: Exception) { }

        try {
            val b64Decoded = atob(trimmed).trim()
            if (b64Decoded.startsWith("http") || b64Decoded.startsWith("//")) return if (b64Decoded.startsWith("//")) "https:$b64Decoded" else b64Decoded
        } catch (_: Exception) { }

        try {
            val revDecoded = atob(trimmed.reversed()).trim()
            if (revDecoded.startsWith("http") || revDecoded.startsWith("//")) return if (revDecoded.startsWith("//")) "https:$revDecoded" else revDecoded
        } catch (_: Exception) { }

        return null
    }

    private fun collectStringsRecursiveJSON(prefix: String, node: Any?, out: MutableList<Pair<String, String>>) {
        when (node) {
            is String -> out.add(Pair(prefix, node))
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectStringsRecursiveJSON(prefix, node.opt(i), out)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "tt" || k == "order") continue
                    val nextPrefix = if (k == "sx" || k == "t" || k == "p" || k.isBlank()) {
                        prefix
                    } else {
                        if (prefix.isBlank()) k else "${prefix}_$k"
                    }
                    collectStringsRecursiveJSON(nextPrefix, node.opt(k), out)
                }
            }
        }
    }

    suspend fun getTurkishAudio(title: String): String? {
        try {
            // Başlıktaki yılı vs. temizle ki arama daha isabetli olsun (Örn: Spider-Man (2025) -> Spider-Man)
            val cleanTitle = title.replace(Regex("""\(\d{4}\)"""), "").trim().replace(Regex("""\s+"""), "+")
            val searchUrl = "https://www.fullhdfilmizlesene.now/arama/$cleanTitle"
            
            val searchDoc = app.get(searchUrl).document
            val moviePath = searchDoc.selectFirst("li.film a")?.attr("href") ?: return null
            val movieUrl = if (moviePath.startsWith("http")) moviePath else "https://www.fullhdfilmizlesene.now$moviePath"

            val movieDoc = app.get(movieUrl).document

            // "scx" JSON değişkeninden şifreli linkleri ayıkla
            val scripts = movieDoc.select("script").map { it.data() }
            val rawCandidates = mutableListOf<Pair<String, String>>()

            for (script in scripts) {
                if (!script.contains("scx")) continue
                val scxJsonString = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                    .find(script)?.groupValues?.get(1) ?: continue
                try {
                    val scxObj = JSONObject(scxJsonString)
                    val keys = scxObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        collectStringsRecursiveJSON(k, scxObj.opt(k), rawCandidates)
                    }
                } catch (e: Exception) {
                    Log.e("K_MERGER", "SCX okuma hatası: $e")
                }
            }

            var rapidVidUrl: String? = null
            
            // ÖNCELİK: İçinde "tr" (Türkçe Dublaj) geçen etiketleri (parçaları) filtrele!
            val trCandidates = rawCandidates.filter { it.first.contains("tr", ignoreCase = true) }
            val searchList = trCandidates.ifEmpty { rawCandidates }

            for ((label, candidate) in searchList) {
                val decoded = smartDecodeLink(candidate)
                if (decoded != null && decoded.contains("rapidvid")) {
                    Log.d("K_MERGER", "Seçilen RapidVid URL ($label): $decoded")
                    rapidVidUrl = decoded
                    break
                }
            }

            if (rapidVidUrl != null) {
                val res = app.get(rapidVidUrl, referer = "https://www.fullhdfilmizlesene.now/").text
                val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res)?.groupValues?.get(1)
                
                if (p8 != null) {
                    val json = JSONObject(decodeSecret(p8))
                    val cmUrl = json.optString("cm") // cm = ana M3U8 stream bağlantısı
                    if (cmUrl.isNotBlank() && cmUrl.startsWith("http")) {
                        Log.d("K_MERGER", "TR Ses M3U8 (cm) Bulundu: $cmUrl")
                        return cmUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("K_MERGER", "TR Ses çekilirken hata: $e")
        }
        return null
    }

    private fun decodeSecret(encodedString: String): String {
        val reversedBase64Input = encodedString.reversed()
        val tString = base64Decode(reversedBase64Input)
        val oBuilder = StringBuilder()
        val key = "K9L"
        for (index in tString.indices) {
            val offset = key[index % key.length].code % 5 + 1
            oBuilder.append((tString[index].code - offset).toChar())
        }
        return base64Decode(oBuilder.toString())
    }

    fun buildMergedM3U8(videoUrl: String, audioUrl: String): String {
        val m3u8 = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="tr-audio",NAME="Türkçe Dublaj",DEFAULT=YES,AUTOSELECT=YES,URI="$audioUrl"
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160,AUDIO="tr-audio"
            $videoUrl
        """.trimIndent()
        // ExoPlayer'ın okuyabilmesi için M3U8 metnini Base64 Data URI'ye çeviriyoruz
        val b64 = Base64.encodeToString(m3u8.toByteArray(), Base64.NO_WRAP)
        return "data:application/vnd.apple.mpegurl;base64,${b64.replace("\n", "")}"
    }
}
