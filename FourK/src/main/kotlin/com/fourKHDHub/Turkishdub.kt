package com.fourKHDHub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs

/**
 * 4KHDHub filmi için FullHDFilmizlesene'den Türkçe dublajlı kaynağı bulur.
 *
 * Akış:
 *  FourKHDHub.load()      -> linklerin sonuna "dub://?t=..&tr=..&y=.." işaretçisi eklenir
 *  FourKHDHub.loadLinks() -> işaretçi TurkishDub.load() ile çözülür
 *  TurkishDub.load()      -> site araması -> film sayfası -> rapidvid player -> _p8 çözümü
 *                            -> master m3u8 (Türkçe ses varsayılan) linki + altyazılar
 *
 * Not: Master'daki Türkçe/İngilizce ses playlist adresleri ayrıca çıkarılır ve
 * linkin extractorData alanına {"audioTr": "...", "audioEn": "..."} olarak yazılır.
 * İleride 4K video ile ses birleştirmesi yapılacaksa oradan okunabilir.
 */
object TurkishDub {
    const val PREFIX = "dub://"
    private const val TAG = "TRDub"
    private const val DEFAULT_SITE = "https://www.fullhdfilmizlesene.now"
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/gitlihub0/domainListesi/refs/heads/main/eklenti_domainleri.txt"

    @Volatile
    private var cachedSite: String? = null

    // ------------------------------------------------------------------ İşaretçi

    fun isMarker(s: String): Boolean = s.startsWith(PREFIX)

    fun marker(title: String, trTitle: String?, year: Int?): String =
        "$PREFIX?t=${enc(title)}&tr=${enc(trTitle.orEmpty())}&y=${year ?: ""}"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private data class Query(val title: String, val trTitle: String, val year: Int?)

    private fun parseMarker(m: String): Query {
        val params = m.substringAfter("?", "").split("&").mapNotNull {
            val k = it.substringBefore("=", "")
            if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
        }.toMap()
        return Query(params["t"].orEmpty(), params["tr"].orEmpty(), params["y"]?.toIntOrNull())
    }

    /** TMDB'den Türkçe film adı (FullHD sitesinde Türkçe başlıkla arama daha isabetli). */
    suspend fun fetchTrTitle(tmdbId: Int): String? = runCatching {
        val url = "${FourKHDHub.TMDBAPI}/movie/$tmdbId?api_key=${FourKHDHub.TMDB_API_KEY}&language=tr-TR"
        JSONObject(app.get(url).text).optString("title").takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------------------ Domain

    private suspend fun site(): String {
        cachedSite?.let { return it }
        val found = runCatching {
            val text = app.get(CONFIG_URL).text.trim().removePrefix("\uFEFF")
            Regex(
                """["']?fullhdfilmizlesene["']?\s*[:=]\s*["']?([^\s"',}]+)""",
                RegexOption.IGNORE_CASE
            ).find(text)?.groupValues?.get(1)
        }.getOrNull()
        val d = (found ?: DEFAULT_SITE).trim().trimEnd('/')
            .let { if (it.startsWith("http")) it else "https://$it" }
        cachedSite = d
        return d
    }

    private fun absUrl(base: String, raw: String): String = when {
        raw.startsWith("http") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> base + raw
        else -> "$base/$raw"
    }

    // ------------------------------------------------------------------ Ana akış

    suspend fun load(
        marker: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val q = parseMarker(marker)
        val terms = listOf(q.trTitle, q.title).filter { it.isNotBlank() }.distinct()
        if (terms.isEmpty()) return false

        val base = site()

        // 1) Arama sonuçlarını başlık benzerliğine göre sırala
        val seen = LinkedHashSet<String>()
        val ranked = ArrayList<Pair<Int, String>>()
        for (term in terms) {
            val tn = norm(term)
            for ((title, href) in search(base, term)) {
                val n = norm(title)
                val score = when {
                    n == tn -> 0
                    n.isNotEmpty() && (n.contains(tn) || tn.contains(n)) -> 1
                    else -> 2
                }
                if (seen.add(href)) ranked += score to href
            }
        }
        if (ranked.isEmpty()) {
            Log.e(TAG, "Arama sonucu yok: $terms")
            return false
        }

        // 2) Adayları sırayla dene; yıl uyuşmayanı atla
        for ((score, href) in ranked.sortedBy { it.first }.take(4)) {
            if (score == 2 && q.year == null) continue
            val doc = runCatching { app.get(href, referer = "$base/").document }.getOrNull() ?: continue

            val pageYear = yearOf(doc)
            if (q.year != null && pageYear != null && abs(pageYear - q.year) > 1) continue
            if (score == 2 && pageYear == null) continue

            val players = playerUrls(doc, base)
            if (players.isEmpty()) continue

            var ok = false
            for (p in players) {
                if (resolvePlayer(p, "$base/", subtitleCallback, callback)) ok = true
            }
            if (ok) return true
        }
        Log.e(TAG, "Uygun kaynak bulunamadı: $terms")
        return false
    }

    // ------------------------------------------------------------------ Arama / sayfa

    private fun norm(s: String): String = s.lowercase()
        .replace("ı", "i").replace("ö", "o").replace("ü", "u")
        .replace("ş", "s").replace("ç", "c").replace("ğ", "g")
        .replace(Regex("""\s+izle\s*$"""), "")
        .replace(Regex("[^a-z0-9]"), "")

    private suspend fun search(base: String, term: String): List<Pair<String, String>> {
        val url = "$base/arama/${enc(term).replace("+", "%20")}"
        val doc = runCatching { app.get(url, referer = "$base/").document }.getOrNull()
            ?: return emptyList()
        return doc.select("li.film").mapNotNull { el ->
            val title = el.selectFirst("span.film-title")?.text()?.trim() ?: return@mapNotNull null
            val raw = el.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            title to absUrl(base, raw)
        }
    }

    private fun yearOf(doc: Document): Int? =
        doc.selectFirst("ol.breadcrumb li:nth-child(3) a span")?.text()
            ?.split(" ")?.firstOrNull()?.trim()?.toIntOrNull()

    // ------------------------------------------------------------------ Player linkleri

    private fun collect(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> out.add(node)
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
            is JSONObject -> node.keys().forEach { k ->
                if (k != "tt" && k != "order") collect(node.opt(k), out)
            }
            else -> {}
        }
    }

    private fun decodeLink(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("http")) return t
        if (t.startsWith("//")) return "https:$t"
        val attempts: List<() -> String> = listOf(
            { base64Decode(pen(t)) },
            { base64Decode(t) },
            { base64Decode(t.reversed()) }
        )
        for (f in attempts) {
            val r = runCatching { f().trim() }.getOrNull() ?: continue
            if (r.startsWith("http")) return r
            if (r.startsWith("//")) return "https:$r"
        }
        return null
    }

    private fun playerUrls(doc: Document, base: String): List<String> {
        val urls = LinkedHashSet<String>()

        // scx = {...} içindeki (şifreli olabilen) kaynaklar
        for (script in doc.select("script").map { it.data() }) {
            if (!script.contains("scx")) continue
            val json = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(script)?.groupValues?.get(1) ?: continue
            val candidates = ArrayList<String>()
            runCatching { collect(JSONObject(json), candidates) }
            candidates.mapNotNull { decodeLink(it) }.forEach { urls.add(it) }
        }

        // iframe / embed
        doc.select("iframe, embed").forEach { f ->
            val src = f.attr("data-src").ifBlank { f.attr("src") }
            if (src.isNotBlank()) urls.add(absUrl(base, src))
        }

        // Sayfa kaynağında düz yazılmış rapidvid adresleri
        Regex("""https?://rapidvid\.[a-z]+/[^\s"'<>\\]+""").findAll(doc.html())
            .forEach { urls.add(it.value) }

        return urls.filter { it.contains("rapidvid", ignoreCase = true) }
    }

    // ------------------------------------------------------------------ RapidVid (_p8)

    private fun decodeSecret(encoded: String): String {
        val t = base64Decode(encoded.reversed())
        val key = "K9L"
        val sb = StringBuilder(t.length)
        for (i in t.indices) {
            val offset = key[i % key.length].code % 5 + 1
            sb.append((t[i].code - offset).toChar())
        }
        return base64Decode(sb.toString())
    }

    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching { app.get(playerUrl, referer = referer) }.getOrNull() ?: return false
        val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
        if (p8 == null) {
            Log.e(TAG, "_p8 bulunamadı (eski player olabilir): $playerUrl")
            return false
        }
        val json = runCatching { JSONObject(decodeSecret(p8)) }.getOrElse {
            Log.e(TAG, "_p8 çözülemedi: ${it.message}")
            return false
        }
        val origin = runCatching { URI(playerUrl).let { "${it.scheme}://${it.host}/" } }
            .getOrDefault(referer)

        // Altyazılar (Türkçe / İngilizce)
        json.optJSONArray("ct")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = o.optString("file")
                val label = o.optString("label").trim()
                if (file.startsWith("http") && label.isNotEmpty()) {
                    subtitleCallback(newSubtitleFile(label, file))
                }
            }
        }

        // cm = ana (H.264) master, tm = AV1 master
        var added = false
        for ((tag, key) in listOf("" to "cm", " AV1" to "tm")) {
            val master = json.optString(key)
            if (master.startsWith("http") && emitMaster(master, tag, origin, callback)) added = true
        }
        return added
    }

    private fun attrs(line: String): Map<String, String> =
        Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
            .findAll(line.substringAfter(":"))
            .associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groupValues[2]) }

    private fun qualityFromWidth(w: Int): Int = when {
        w >= 3200 -> Qualities.P2160.value
        w >= 2100 -> Qualities.P1440.value
        w >= 1500 -> Qualities.P1080.value
        w >= 1000 -> Qualities.P720.value
        w >= 750 -> Qualities.P480.value
        else -> Qualities.P360.value
    }

    private suspend fun emitMaster(
        master: String,
        tag: String,
        origin: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = runCatching {
            val r = app.get(master, referer = origin)
            if (r.code == 200) r.text else null
        }.getOrNull()
        if (text == null || !text.trimStart().startsWith("#EXTM3U")) {
            Log.e(TAG, "Geçersiz master$tag: $master")
            return false
        }

        // Ses parçaları (Türkçe / İngilizce). URI'ler göreli olabilir.
        fun toAbs(u: String): String = runCatching { URI(master).resolve(u).toString() }.getOrDefault(u)
        val audios = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*""").findAll(text).map { attrs(it.value) }.toList()
        val audioTr = audios.firstOrNull { it["LANGUAGE"].equals("tr", true) }?.get("URI")?.let { toAbs(it) }
        val audioEn = audios.firstOrNull { it["LANGUAGE"].equals("en", true) }?.get("URI")?.let { toAbs(it) }

        // Çözünürlükler (teşhis + kalite etiketi). 4K var mı buradan anlaşılır.
        val sizes = Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(text)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        val maxW = sizes.maxOfOrNull { it.first } ?: 0
        Log.d(TAG, "master$tag çözünürlükler=$sizes ses(tr)=$audioTr ses(en)=$audioEn")

        val data = JSONObject().apply {
            put("audioTr", audioTr.orEmpty())
            put("audioEn", audioEn.orEmpty())
        }.toString()

        callback(
            newExtractorLink("TR Dublaj$tag", "FullHD Türkçe Dublaj$tag", master, ExtractorLinkType.M3U8) {
                this.referer = origin
                this.quality = qualityFromWidth(maxW)
                this.headers = mapOf("Referer" to origin)
                this.extractorData = data
            }
        )
        return true
    }
}
