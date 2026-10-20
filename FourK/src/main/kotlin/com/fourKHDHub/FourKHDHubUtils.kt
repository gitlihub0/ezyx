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
        if (trimmed.contains("rapidvid.org") || trimmed.contains("turkeyplayer")) return trimmed
        
        fun extract(s: String): String? {
            val url = Regex("""(https?:)?//[^\s"'<>]+(?:rapidvid|turkeyplayer)[^\s"'<>]+""").find(s)?.value
            if (url != null) {
                return if (url.startsWith("//")) "https:$url" else url
            }
            return null
        }
        
        try { extract(atob(rtt(trimmed)))?.let { return it } } catch (_: Exception) {}
        try { extract(atob(trimmed))?.let { return it } } catch (_: Exception) {}
        
        return null
    }

    suspend fun getTurkishAudio(title: String): String? {
        try {
            val cleanTitle = title.replace(Regex("""\(\d{4}\)"""), "").trim().replace(Regex("""\s+"""), "+")
            val searchUrl = "https://www.fullhdfilmizlesene.now/arama/$cleanTitle"
            
            val searchDoc = app.get(searchUrl).document
            val moviePath = searchDoc.selectFirst("li.film a")?.attr("href") ?: return null
            val movieUrl = if (moviePath.startsWith("http")) moviePath else "https://www.fullhdfilmizlesene.now$moviePath"

            val movieDoc = app.get(movieUrl).document
            val rawCandidates = mutableListOf<String>()

            movieDoc.select("[data-code]").forEach { rawCandidates.add(it.attr("data-code")) }
            
            movieDoc.select("script").forEach {
                val scxJsonString = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(it.data())?.groupValues?.get(1)
                if (scxJsonString != null) {
                    Regex("""["']([^"']+)["']""").findAll(scxJsonString).forEach { match ->
                        rawCandidates.add(match.groupValues[1])
                    }
                }
            }

            movieDoc.select("iframe").forEach { rawCandidates.add(it.attr("data-src").ifBlank { it.attr("src") }) }

            var masterM3u8: String? = null
            var targetReferer = "https://www.fullhdfilmizlesene.now/"
            
            for (candidate in rawCandidates) {
                if (candidate.isBlank()) continue
                val decoded = smartDecodeLink(candidate) ?: candidate
                
                if (decoded.contains("rapidvid")) {
                    val res = app.get(decoded, referer = targetReferer).text
                    val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res)?.groupValues?.get(1)
                    if (p8 != null) {
                        val json = JSONObject(decodeSecret(p8))
                        val cmUrl = json.optString("cm")
                        if (cmUrl.isNotBlank() && cmUrl.startsWith("http")) {
                            masterM3u8 = cmUrl
                            targetReferer = "https://rapidvid.org/"
                            break
                        }
                    }
                } else if (decoded.contains("turkeyplayer")) {
                    val res = app.get(decoded, referer = targetReferer).text
                    val videoJsonRaw = Regex("""var\s+video\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(res)?.groupValues?.get(1)
                    if (videoJsonRaw != null) {
                        val vd = JSONObject(videoJsonRaw)
                        val md5 = vd.optString("md5")
                        val id = vd.optString("id")
                        if (md5.isNotBlank()) {
                            masterM3u8 = "https://watch.turkeyplayer.com/m3u8/8/$md5/master.txt?s=1&id=$id&cache=1"
                            targetReferer = "https://watch.turkeyplayer.com/"
                            break
                        }
                    }
                }
            }

            if (masterM3u8 != null) {
                return resolveMediaPlaylist(masterM3u8, targetReferer)
            }

        } catch (e: Exception) {
            Log.e("K_MERGER", "TR Ses getirme hatasi: $e")
        }
        return null
    }

    private suspend fun resolveMediaPlaylist(masterUrl: String, referer: String): String {
        try {
            val res = app.get(masterUrl, referer = referer)
            val content = res.text
            
            // Eger bu dosya bir TS/MP4 ise veya EXT-M3U basligi tasimiyorsa direkt don
            if (!content.trimStart().startsWith("#EXTM3U")) {
                Log.d("K_MERGER", "Ham Video Dosyasi bulundu: $masterUrl")
                return masterUrl
            }
            
            if (content.contains("#EXTINF")) return masterUrl
            
            val audioRegex = Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?URI=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            val audioMatches = audioRegex.findAll(content).toList()
            
            var trUri: String? = null
            var fallbackUri: String? = null
            
            for (match in audioMatches) {
                val fullLine = match.value.lowercase()
                val uri = match.groupValues[1]
                if (fallbackUri == null) fallbackUri = uri
                
                if (fullLine.contains("tr") || fullLine.contains("tur") || fullLine.contains("dublaj")) {
                    trUri = uri
                    break
                }
            }
            
            val selectedAudioUri = trUri ?: fallbackUri
            if (selectedAudioUri != null) {
                Log.d("K_MERGER", "Ozel Ses Kanali Bulundu: $selectedAudioUri")
                return fixUrl(selectedAudioUri, masterUrl)
            }
            
            val streamRegex = Regex("""#EXT-X-STREAM-INF.*?[\r\n]+([^\r\n]+)""")
            val streams = streamRegex.findAll(content).toList()
            if (streams.isNotEmpty()) {
                val lowestQualityStream = streams.last().groupValues[1]
                Log.d("K_MERGER", "Alt Video Akisi Ses İcin Secildi: $lowestQualityStream")
                return fixUrl(lowestQualityStream, masterUrl)
            }
            
        } catch (e: Exception) {
            Log.e("K_MERGER", "Media Playlist Cozumleme Hatasi: $e")
        }
        return masterUrl
    }

    private fun fixUrl(path: String, base: String): String {
        if (path.startsWith("http")) return path
        val baseUrl = base.substringBeforeLast("/")
        return "$baseUrl/$path"
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

    fun buildMergedM3U8(videoUrl: String, isVideoM3u8: Boolean, audioUrl: String): String {
        // SES Dosyasi (MP4/TS) icin Sanal M3U8 olustur
        val audioPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:14400
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:14400.0,
            $audioUrl
            #EXT-X-ENDLIST
        """.trimIndent()
        val audioB64 = Base64.encodeToString(audioPlaylist.toByteArray(), Base64.NO_WRAP)
        val audioDataUri = "data:application/vnd.apple.mpegurl;base64,$audioB64"

        // GORUNTU eger MP4 ise (M3u8 degilse) onu da sarmala
        val finalVideoUri = if (!isVideoM3u8) {
            val videoPlaylist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:14400
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:14400.0,
                $videoUrl
                #EXT-X-ENDLIST
            """.trimIndent()
            val videoB64 = Base64.encodeToString(videoPlaylist.toByteArray(), Base64.NO_WRAP)
            "data:application/vnd.apple.mpegurl;base64,$videoB64"
        } else {
            videoUrl
        }

        // ANA PLAYLIST
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="tr-audio",NAME="Türkçe Dublaj",DEFAULT=YES,AUTOSELECT=YES,URI="$audioDataUri"
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160,AUDIO="tr-audio"
            $finalVideoUri
        """.trimIndent()
        
        val masterB64 = Base64.encodeToString(masterM3u8.toByteArray(), Base64.NO_WRAP)
        return "data:application/vnd.apple.mpegurl;base64,${masterB64.replace("\n", "")}"
    }
}
