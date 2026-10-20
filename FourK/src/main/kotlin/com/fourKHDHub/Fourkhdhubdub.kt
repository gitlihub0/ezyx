package com.fourKHDHub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/** Türkçe dublaj ses parçası: HLS audio playlist URL'si + gerekli Referer. */
data class DubAudio(val url: String, val referer: String, val language: String = "tr") {
    /**
     * CloudStream'in standart ExtractorLink.audioTracks alanı için AudioFile.
     * Oynatıcı ses kaynağına yalnızca AudioFile.headers'ı uyguladığı için Referer burada verilir.
     */
    suspend fun toAudioFile(): AudioFile = newAudioFile(url) {
        headers = mapOf("Referer" to referer)
    }
}

/**
 * FullHDFilmizlesene sitesinde filmi bulur, RapidVid master'ından
 * LANGUAGE="tr" ses playlist'inin mutlak URL'sini çıkarır.
 * Not: com.fullhdfilm paketi ayrı bir eklenti olduğu için sınıflarına erişilemez; mantık burada kopyalandı.
 */
object FourKHDHubDub {
    private const val TAG = "4KHDHubDub"
    private const val DEFAULT_DOMAIN = "https://www.fullhdfilmizlesene.now"
    private const val MANUAL_DOMAIN_KEY = "fullhdfilm_manual_domain"   // FullHD eklentisinin ayar anahtarı (aynı uygulama deposu)
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/gitlihub0/domainListesi/refs/heads/main/eklenti_domainleri.txt"
    private const val RAPID_REFERER = "https://rapidvid.org/"

    @Volatile private var cachedDomain: String? = null

    // ------------------------------------------------------------ Domain
    private suspend fun domain(): String {
        getKey<String>(MANUAL_DOMAIN_KEY)?.takeIf { it.isNotBlank() }?.let { return it.trim().trimEnd('/') }
        cachedDomain?.let { return it }
        val fromGithub = runCatching {
            val body = app.get(CONFIG_URL, timeout = 5).text.trim().removePrefix("\uFEFF")
            val m = Regex("""["']?fullhdfilmizlesene["']?\s*[:=]\s*["']?([^\s"',}]+)""", RegexOption.IGNORE_CASE).find(body)
            m?.groupValues?.get(1)?.trim()?.trimEnd('/')?.let { if (it.startsWith("http")) it else "https://$it" }
        }.getOrNull()
        return (fromGithub ?: DEFAULT_DOMAIN).also { cachedDomain = it }
    }

    // ------------------------------------------------------------ Ana giriş
    /** TMDB ID'den başlık/yıl alır, FullHD'de filmi bulur, Türkçe ses URL'sini döner. Bulamazsa null. */
    suspend fun findTurkishAudio(tmdbId: Int, apiBase: String, apiKey: String): DubAudio? {
        return try {
            val meta = JSONObject(app.get("$apiBase/movie/$tmdbId?api_key=$apiKey&language=tr-TR").text)
            val titles = listOf(meta.optString("title"), meta.optString("original_title"))
                .filter { it.isNotBlank() }.distinct()
            val year = meta.optString("release_date").substringBefore("-").toIntOrNull()
            if (titles.isEmpty()) return null

            val base = domain()
            val doc = findFilmPage(base, titles, year) ?: return null
            val pageUrl = doc.location().ifBlank { base }

            for (rapid in rapidLinks(doc)) {
                val audio = extractTurkishAudio(rapid, pageUrl)
                if (audio != null) return audio
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "findTurkishAudio hatası: $e")
            null
        }
    }

    // ------------------------------------------------------------ Film sayfasını bul
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9ığüşöçİĞÜŞÖÇ]"), "")

    private suspend fun findFilmPage(base: String, titles: List<String>, year: Int?): Document? {
        for (title in titles) {
            val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val results = runCatching { app.get("$base/arama/$q").document }.getOrNull() ?: continue
            val cands = results.select("li.film").mapNotNull { li ->
                val t = li.selectFirst("span.film-title")?.text() ?: return@mapNotNull null
                val href = li.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = if (href.startsWith("http")) href else base + "/" + href.trimStart('/')
                t to url
            }.filter { (t, _) ->
                val a = norm(t.replace(Regex("""\s+izle\s*$""", RegexOption.IGNORE_CASE), ""))
                val b = norm(title)
                a.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
            }.take(5)

            for ((_, url) in cands) {
                val doc = runCatching { app.get(url).document }.getOrNull() ?: continue
                // Yıl doğrulaması: sayfadan okunabiliyorsa ve uyuşmuyorsa atla (yanlış film riskine karşı)
                val pageYear = doc.selectFirst("ol.breadcrumb li:nth-child(3) a span")?.text()
                    ?.split(" ")?.firstOrNull()?.trim()?.toIntOrNull()
                if (year != null && pageYear != null && pageYear != year) continue
                return doc
            }
        }
        return null
    }

    // ------------------------------------------------------------ Sayfadan RapidVid linkleri
    private fun atob(s: String): String = base64Decode(s)

    private fun rot13(s: String) = s.map { c ->
        when (c) {
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun smartDecode(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("http") || t.startsWith("//")) return t
        val attempts = listOf<() -> String>(
            { atob(rot13(t)) },
            { atob(t) },
            { atob(t.reversed()) }
        )
        for (a in attempts) {
            val d = runCatching { a().trim() }.getOrNull() ?: continue
            if (d.startsWith("http") || d.startsWith("//")) return d
        }
        return null
    }

    private fun collectStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> out.add(node)
            is JSONArray -> for (i in 0 until node.length()) collectStrings(node.opt(i), out)
            is JSONObject -> node.keys().forEach { k -> if (k != "tt" && k != "order") collectStrings(node.opt(k), out) }
        }
    }

    private fun rapidLinks(doc: Document): List<String> {
        val found = linkedSetOf<String>()

        for (script in doc.select("script").map { it.data() }) {
            if (!script.contains("scx")) continue
            val json = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(script)?.groupValues?.get(1) ?: continue
            val strings = mutableListOf<String>()
            runCatching { collectStrings(JSONObject(json), strings) }
            strings.mapNotNull { smartDecode(it) }.forEach { found.add(it) }
        }
        doc.select("iframe, embed").forEach { f ->
            val src = f.attr("data-src").ifBlank { f.attr("src") }
            if (src.isNotBlank()) found.add(src)
        }
        Regex("""https?://[^\s"'<>]*rapidvid[^\s"'<>]+""").findAll(doc.html()).forEach { found.add(it.value) }

        return found.map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.contains("rapidvid.") }
    }

    // ------------------------------------------------------------ RapidVid -> master -> TR ses
    private fun decodeSecret(encoded: String): String {
        val t = base64Decode(encoded.reversed())
        val key = "K9L"
        val sb = StringBuilder()
        for (i in t.indices) {
            val offset = key[i % key.length].code % 5 + 1
            sb.append((t[i].code - offset).toChar())
        }
        return base64Decode(sb.toString())
    }

    private suspend fun extractTurkishAudio(rapidUrl: String, referer: String): DubAudio? {
        val res = runCatching { app.get(rapidUrl, referer = referer) }.getOrNull() ?: return null
        val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1) ?: return null
        val cm = runCatching { JSONObject(decodeSecret(p8)).optString("cm") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return null

        val master = runCatching { app.get(cm, referer = RAPID_REFERER).text }.getOrNull() ?: return null
        if (!master.trimStart().startsWith("#EXTM3U")) return null

        // Önek (mq/m6/ma...) sabit varsayılmaz: her seferinde master'dan okunur.
        val line = master.lineSequence().firstOrNull {
            it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") &&
                Regex("""LANGUAGE="tr"""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        } ?: return null
        val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return null

        val absolute = runCatching { URI(cm).resolve(uri).toString() }.getOrNull() ?: return null
        return DubAudio(absolute, RAPID_REFERER, "tr")
    }
}
