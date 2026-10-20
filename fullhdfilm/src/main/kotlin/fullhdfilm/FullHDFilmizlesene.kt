package com.fullhdfilm

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.net.URLEncoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = FullHDFilmDomain.DEFAULT
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ---------------------------------------------------------------- Domain Sistemi
    private var githubChecked = false

    private suspend fun ensureInit() {
        if (FullHDFilmDomain.manual() == null && !githubChecked) {
            githubChecked = true
            try {
                val text = app.get(FullHDFilmDomain.CONFIG_URL, timeout = 5).text
                FullHDFilmDomain.parse(text)?.let { FullHDFilmDomain.githubDomain = it }
            } catch (_: Exception) { }
        }
        mainUrl = FullHDFilmDomain.current()
    }

    private fun rewriteToCurrent(url: String): String {
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (url.startsWith(mainUrl)) url else mainUrl + url.removePrefix(host)
    }

    override val mainPage = mainPageOf(
        "yeni-filmler"                      to "Yeni Filmler",
        "en-cok-izlenen-filmler"            to "En Çok İzlenen Filmler",
        "filmizle/aile-filmleri"            to "Aile Filmleri",
        "filmizle/aksiyon-filmleri"         to "Aksiyon Filmleri",
        "filmizle/animasyon-filmleri"       to "Animasyon Filmleri",
        "filmizle/belgesel-filmleri"        to "Belgeseller",
        "filmizle/bilim-kurgu-filmleri"     to "Bilim Kurgu Filmleri",
        "filmizle/bluray-filmler"           to "Blu Ray Filmler",
        "filmizle/cizgi-filmler"            to "Çizgi Filmler",
        "filmizle/dram-filmler-izle"        to "Dram Filmleri",
        "filmizle/fantastik-filmler"        to "Fantastik Filmler",
        "filmizle/gerilim-filmleri"         to "Gerilim Filmleri",
        "filmizle/gizem-filmleri"           to "Gizem Filmleri",
        "filmizle/hint-filmleri"            to "Hint Filmleri",
        "filmizle/komedi-filmleri"          to "Komedi Filmleri",
        "filmizle/korku-filmleri"           to "Korku Filmleri",
        "filmizle/macera-filmleri"          to "Macera Filmleri",
        "filmizle/muzikal-filmler"          to "Müzikal Filmler",
        "filmizle/polisiye-filmleri"        to "Polisiye Filmleri",
        "filmizle/psikolojik-filmler"       to "Psikolojik Filmler",
        "filmizle/romantik-filmler"         to "Romantik Filmler",
        "filmizle/savas-filmleri"           to "Savaş Filmleri",
        "filmizle/suc-filmleri"             to "Suç Filmleri",
        "filmizle/tarih-filmleri"           to "Tarih Filmleri",
        "filmizle/western-filmler"          to "Western Filmler",
        "filmizle/yerli-filmler"            to "Yerli Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val pageUrl = if (request.data.startsWith("http")) rewriteToCurrent(request.data)
                      else "$mainUrl/${request.data.removePrefix("/")}"
        val url = if (page == 1) pageUrl.removeSuffix("/") else "${pageUrl.removeSuffix("/")}/$page"
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        val home     = document.select("li.film").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    // ---------------------------------------------------------------- TMDB Oyuncular
    // https://www.themoviedb.org/settings/api adresinden ücretsiz alınan API anahtarını yaz
    private val TMDB_API_KEY = "2c2de094cd5f2dbd52f686bdcb41055c"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSearchItem(@JsonProperty("id") val id: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSearch(@JsonProperty("results") val results: List<TmdbSearchItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null)

    private suspend fun fetchTmdbActors(title: String, year: Int?): List<Actor> {
        if (TMDB_API_KEY.startsWith("BURAYA")) return emptyList()
        return try {
            val cleanTitle = title.replace(Regex("""\s+izle\s*$""", RegexOption.IGNORE_CASE), "").trim()
            val q = URLEncoder.encode(cleanTitle, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""
            val search = app.get(
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&language=tr-TR&query=$q$yearParam"
            ).parsedSafe<TmdbSearch>()
            val id = search?.results?.firstOrNull()?.id ?: return emptyList()

            val credits = app.get(
                "https://api.themoviedb.org/3/movie/$id/credits?api_key=$TMDB_API_KEY&language=tr-TR"
            ).parsedSafe<TmdbCredits>()

            credits?.cast.orEmpty().take(15).mapNotNull { c ->
                val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val img  = c.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                Actor(name, img)
            }
        } catch (e: Exception) {
            Log.e("K_FHD", "TMDB oyuncu hatası: $e")
            emptyList()
        }
    }

    // ---------------------------------------------------------------- IMDb Puanı
    // "7.8", "IMDb 7,8", "7.8/10" gibi metinlerden puanı ayıklar
    private fun parseScore(text: String?): Score? {
        val num = Regex("""(\d{1,2}(?:[.,]\d)?)""").find(text.orEmpty())
            ?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
            ?: return null
        if (num <= 0.0 || num > 10.0) return null
        return Score.from10(num)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span.film-title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")
        )
        val score = parseScore(
            this.selectFirst("span.imdb, .imdb, .imdb-puan, span.rating, .puan")?.text()
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score     = score
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val document = app.get("$mainUrl/arama/${query}").document
        return document.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val fixedPageUrl = rewriteToCurrent(url)
        val document = app.get(fixedPageUrl).document

        val title       = document.selectFirst("div[class=izle-titles] h1 a")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("picture img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val year        = document.selectFirst("ol.breadcrumb li:nth-child(3) a span")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.ozet-ic > p")?.text()?.trim()
        val tags        = document.select("a[rel='category tag']").map { it.text() }
        val duration    = document.selectFirst("span.sure")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val trailer     = Regex("""embedUrl": "(.*)"""").find(document.html())?.groupValues?.get(1)
        val siteActors  = document.select("div.film-info ul li:nth-child(2) a > span").map { Actor(it.text()) }
        val actors      = fetchTmdbActors(title, year).ifEmpty { siteActors }
        val score       = parseScore(
            document.selectFirst("div.puanx-puan, span.imdb, .imdb-puan, .imdb")?.text()
        )

        val recommendations = document.selectXpath("//div[span[text()='Benzer Filmler']]/following-sibling::section/ul/li").mapNotNull {
            val recName      = it.selectFirst("span.film-title")?.text() ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-srcset")?.split(" ")?.firstOrNull())
            val recScore     = parseScore(it.selectFirst("span.imdb, .imdb, .puan")?.text())
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
                this.score     = recScore
            }
        }

        return newMovieLoadResponse(title, fixedPageUrl, TvType.Movie, fixedPageUrl) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            this.score           = score
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

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
            return fixUrlNull(trimmed)
        }
        try {
            val rotDecoded = atob(rtt(trimmed)).trim()
            if (rotDecoded.startsWith("http") || rotDecoded.startsWith("//")) return fixUrlNull(rotDecoded)
        } catch (_: Exception) { }

        try {
            val b64Decoded = atob(trimmed).trim()
            if (b64Decoded.startsWith("http") || b64Decoded.startsWith("//")) return fixUrlNull(b64Decoded)
        } catch (_: Exception) { }

        try {
            val revDecoded = atob(trimmed.reversed()).trim()
            if (revDecoded.startsWith("http") || revDecoded.startsWith("//")) return fixUrlNull(revDecoded)
        } catch (_: Exception) { }

        return null
    }

    private fun collectStringsRecursive(prefix: String, node: Any?, out: MutableList<Pair<String, String>>) {
        when (node) {
            is String -> out.add(Pair(prefix, node))
            is List<*> -> node.forEach { item -> collectStringsRecursive(prefix, item, out) }
            is Map<*, *> -> {
                for ((k, v) in node) {
                    val keyStr = k?.toString().orEmpty()
                    if (keyStr == "tt" || keyStr == "order") continue
                    val nextPrefix = if (keyStr == "sx" || keyStr == "t" || keyStr == "p" || keyStr.isBlank()) {
                        prefix
                    } else {
                        "${prefix}_$keyStr"
                    }
                    collectStringsRecursive(nextPrefix, v, out)
                }
            }
        }
    }

    private fun getVideoLinks(document: Document): List<Pair<String, String>> {
        val linkList = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()

        // 1. scx değişkenini dinamik tara
        val scripts = document.select("script").map { it.data() }
        for (script in scripts) {
            if (!script.contains("scx")) continue
            val scxJson = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(script)?.groupValues?.get(1) ?: continue

            try {
                val scxMap: Map<String, Any?> = jacksonObjectMapper().readValue(scxJson)
                val rawCandidates = mutableListOf<Pair<String, String>>()
                for ((sourceKey, sourceVal) in scxMap) {
                    collectStringsRecursive(sourceKey, sourceVal, rawCandidates)
                }

                for ((label, candidate) in rawCandidates) {
                    val decoded = smartDecodeLink(candidate)
                    if (decoded != null && seenUrls.add(decoded)) {
                        linkList.add(Pair(label, decoded))
                    }
                }
            } catch (e: Exception) {
                Log.e("K_FHD", "SCX dinamik okuma hatası: $e")
            }
        }

        // 2. Sayfadaki tüm iframe ve embed kaynaklarını tara (#plx, player-alan, vb.)
        document.select("iframe, embed, object").forEach { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src").ifBlank { frame.attr("data-url") } }
            val fixed = fixUrlNull(src)
            if (fixed != null && !fixed.contains("googletagmanager") && seenUrls.add(fixed)) {
                linkList.add(Pair("iframe", fixed))
            }
        }

        // 3. Sayfa kaynağındaki gizli oynatıcı URL'lerini (embed/, player/, vb.) Regex ile yakala
        val htmlContent = document.html()
        Regex("""https?://[^\s"'<>]+\b(?:embed|player|watch|video|srv|api)[^\s"'<>]+""")
            .findAll(htmlContent).forEach { match ->
                val found = fixUrlNull(match.value)
                if (found != null && !found.contains("googletagmanager") && !found.contains("schema.org") && seenUrls.add(found)) {
                    linkList.add(Pair("regex", found))
                }
            }

        return linkList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val pageUrl = rewriteToCurrent(data)
        val document = app.get(pageUrl).document
        val videoLinks = getVideoLinks(document)
        Log.d("K_FHD", "Genişletilmiş Bulunan Linkler: $videoLinks")

        if (videoLinks.isEmpty()) return false

        var successCount = 0
        for ((key, videoUrl) in videoLinks) {
            val fixedUrl = fixUrlNull(videoUrl) ?: continue
            Log.d("K_FHD", "İşlenen URL ($key): $fixedUrl")

            // Her link ayrı try/catch: biri patlarsa diğerleri denenmeye devam eder
            try {
                if (fixedUrl.contains("rapidvid.")) {
                    // Alan adı (.net/.org/...) değişse de doğrudan RapidVid çıkarıcısını kullan
                    RapidVid().getUrl(fixedUrl, "$mainUrl/", subtitleCallback, callback)
                    successCount++
                } else if (fixedUrl.contains("turbo.imgz.me")) {
                    TurboImgz().getUrl(
                        url = "${key}||${fixedUrl}",
                        referer = "$mainUrl/",
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    successCount++
                } else {
                    loadExtractor(fixedUrl, "$mainUrl/", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("K_FHD", "Link çözme hatası ($key - $fixedUrl): $e")
            }
        }
        return successCount > 0 || videoLinks.isNotEmpty()
    }
}
