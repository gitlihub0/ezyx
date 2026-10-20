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

    /** Son aramanın durumu: loadLinks'te teşhis satırı olarak gösterilir (geçici, hata ayıklama için). */
    @Volatile var status: String = ""
    private fun fail(msg: String) { status = msg; Log.e(TAG, msg) }

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
            // Orijinal ad önce: FullHD başlıkları genelde İngilizce adı da içeriyor.
            val titles = listOf(meta.optString("original_title"), meta.optString("title"))
                .filter { it.isNotBlank() }.distinct()
            val year = meta.optString("release_date").substringBefore("-").toIntOrNull()
            Log.d(TAG, "[1] TMDB $tmdbId -> başlıklar=$titles yıl=$year")
            if (titles.isEmpty()) return null

            val base = domain()
            Log.d(TAG, "[2] FullHD domain=$base")
            val doc = findFilmPage(base, titles, year)
            if (doc == null) { fail("film FullHD'de bulunamadı (terimler=$titles, domain=$base)"); return null }
            val pageUrl = doc.location().ifBlank { base }
            Log.d(TAG, "[3] film sayfası=$pageUrl")

            val rapids = rapidLinks(doc)
            Log.d(TAG, "[4] rapidvid linkleri=$rapids")
            for (rapid in rapids) {
                val audio = extractTurkishAudio(rapid, pageUrl)
                if (audio != null) { Log.d(TAG, "[5] TR ses=${audio.url}"); return audio }
            }
            if (rapids.isEmpty()) fail("sayfada rapidvid linki yok (${pageUrl.takeLast(40)})")
            else if (status.isBlank()) fail("rapidvid'den TR ses çıkmadı")
            null
        } catch (e: Exception) {
            fail("hata: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------ Film sayfasını bul
    /** Türkçe harfleri ASCII'ye indirger (İ/ı/ğ/ü/ş/ö/ç), sonra harf-rakam dışını atar. */
    private fun norm(s: String): String {
        val folded = s.replace('İ', 'I').replace('ı', 'i')
        val noDiacritics = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noDiacritics.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    }

    private suspend fun findFilmPage(base: String, titles: List<String>, year: Int?): Document? {
        // Site başlığı TMDB'den farklı olabilir (ör. "Örümcek-Adam: Yepyeni Bir Gün"), bu yüzden
        // ":" sonrası alt başlık da ayrıca aranır ve eşleşme hem başlığa hem adres slug'ına bakar.
        val wanted = titles.map { norm(it) }.filter { it.length >= 4 }
        val subtitles = titles.mapNotNull { it.substringAfter(":", "").trim().takeIf { s -> s.length >= 4 } }
        val searchTerms = (titles + subtitles).distinct()
        Log.d(TAG, "arama terimleri=$searchTerms")

        for (title in searchTerms) {
            val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val results = runCatching { app.get("$base/arama/$q").document }
                .onFailure { Log.e(TAG, "arama isteği hata ($title): $it") }
                .getOrNull() ?: continue
            Log.d(TAG, "arama '$title' -> ${results.select("li.film").size} sonuç")
            val cands = results.select("li.film").mapNotNull { li ->
                val t = li.selectFirst("span.film-title")?.text() ?: return@mapNotNull null
                val href = li.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = if (href.startsWith("http")) href else base + "/" + href.trimStart('/')
                t to url
            }.filter { (t, url) ->
                val a = norm(t.replace(Regex("""\s+izle\s*$""", RegexOption.IGNORE_CASE), ""))
                val slug = norm(url.substringAfterLast("/film/", url).trimEnd('/'))
                a.isNotEmpty() && wanted.any { w ->
                    a == w || a.contains(w) || w.contains(a) || slug.contains(w)
                }
            }.take(5)
            Log.d(TAG, "eşleşen adaylar=${cands.map { it.first }}")

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
        val res = runCatching { app.get(rapidUrl, referer = referer) }
            .onFailure { Log.e(TAG, "rapidvid sayfa hatası ($rapidUrl): $it") }
            .getOrNull() ?: return null
        val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
        if (p8 == null) { fail("rapidvid sayfasında _p8 yok (HTTP ${res.code})"); return null }
        val cm = runCatching { JSONObject(decodeSecret(p8)).optString("cm") }
            .onFailure { Log.e(TAG, "p8 çözme hatası: $it") }
            .getOrNull()?.takeIf { it.startsWith("http") }
        if (cm == null) { fail("_p8 çözülemedi veya cm yok"); return null }

        val master = runCatching { app.get(cm, referer = RAPID_REFERER).text }
            .onFailure { Log.e(TAG, "master isteği hata: $it") }
            .getOrNull() ?: return null
        if (!master.trimStart().startsWith("#EXTM3U")) { fail("master HLS değil"); return null }

        // Önek (mq/m6/ma...) sabit varsayılmaz: her seferinde master'dan okunur.
        val line = master.lineSequence().firstOrNull {
            it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") &&
                Regex("""LANGUAGE="tr"""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        if (line == null) { fail("master'da LANGUAGE=\"tr\" ses satırı yok"); return null }
        val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
        if (uri == null) { fail("TR ses satırında URI yok"); return null }

        val absolute = runCatching { URI(cm).resolve(uri).toString() }.getOrNull() ?: return null
        return DubAudio(absolute, RAPID_REFERER, "tr")
    }
}
