package com.yfilmizle

import android.util.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class YFilmizle : MainAPI() {
    override var mainUrl              = YFilmizleDomain.DEFAULT
    override var name                 = "YFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ---------------------------------------------------------------- Domain Sistemi
    private var githubChecked = false

    private suspend fun ensureInit() {
        if (YFilmizleDomain.manual() == null && !githubChecked) {
            githubChecked = true
            try {
                val text = app.get(YFilmizleDomain.CONFIG_URL, timeout = 5).text
                YFilmizleDomain.parse(text)?.let { YFilmizleDomain.githubDomain = it }
            } catch (_: Exception) { }
        }
        mainUrl = YFilmizleDomain.current()
    }

    /** Eski/başka domainle kaydedilmiş adresin domain kısmını güncel domain ile değiştirir. */
    private fun rewriteToCurrent(url: String): String {
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (url.startsWith(mainUrl)) url else mainUrl + url.removePrefix(host)
    }

    // ---------------------------------------------------------------- Ana sayfa
    // Site yapısı: /dizi/<tür>/ ve /page/N/ ile sayfalama (1. sayfada /page/1/ yok)
    override val mainPage = mainPageOf(
        "film-arsivi"                 to "Son Eklenenler",
        "dizi/yerli-filmleri"         to "Yerli Filmler",
        "dizi/netflix-filmleri"       to "Netflix Filmleri",
        "dizi/aile-filmleri"          to "Aile Filmleri",
        "dizi/aksiyon-filmleri"       to "Aksiyon Filmleri",
        "dizi/animasyon-filmleri"     to "Animasyon Filmleri",
        "dizi/anime-filmleri"         to "Anime Filmleri",
        "dizi/belgeseler-filmleri"    to "Belgeseller",
        "dizi/bilim-kurgu-filmleri"   to "Bilim Kurgu Filmleri",
        "dizi/biyografi-filmleri"     to "Biyografi Filmleri",
        "dizi/dram-filmleri"          to "Dram Filmleri",
        "dizi/fantastik-filmleri"     to "Fantastik Filmler",
        "dizi/gerilim-filmleri"       to "Gerilim Filmleri",
        "dizi/gizem-filmleri"         to "Gizem Filmleri",
        "dizi/hint-filmleri"          to "Hint Filmleri",
        "dizi/komedi-filmleri"        to "Komedi Filmleri",
        "dizi/korku-filmleri"         to "Korku Filmleri",
        "dizi/macera-filmleri"        to "Macera Filmleri",
        "dizi/muzikal-filmleri"       to "Müzikal Filmler",
        "dizi/romantik-filmleri"      to "Romantik Filmler",
        "dizi/savas-filmleri"         to "Savaş Filmleri",
        "dizi/spor-filmleri"          to "Spor Filmleri",
        "dizi/suc-filmleri"           to "Suç Filmleri",
        "dizi/tarihi-filmleri"        to "Tarih Filmleri",
        "dizi/western-filmleri"       to "Western Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val path = request.data.trim('/')
        val url  = if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        return newHomePageResponse(request.name, document.toFilmList())
    }

    // ---------------------------------------------------------------- Kart ayrıştırma
    private val blockedSegments = setOf(
        "dizi", "yil", "page", "film-arsivi", "uye-ol", "category", "tag", "author", "feed",
        "oyuncu", "yonetmen", "ulke", "profil", "wp-login.php", "wp-admin", "wp-content", "wp-json"
    )

    private fun isFilmUrl(url: String): Boolean {
        val seg = Regex("""^https?://[^/]+/([^/?#]+)/?$""").find(url)?.groupValues?.get(1) ?: return false
        return seg !in blockedSegments && !seg.startsWith("wp-")
    }

    /** Lazy-load placeholder'ları (data: URI) atlayıp gerçek görsel adresini bulur. */
    private fun Element.posterOf(): String? {
        val img = this.selectFirst(".poster img") ?: this.selectFirst("img") ?: return null
        for (attr in listOf("data-src", "data-lazy-src", "data-litespeed-src", "data-original", "srcset", "src")) {
            val v = img.attr(attr).trim().split(" ").firstOrNull()?.trim().orEmpty()
            if (v.isNotEmpty() && !v.startsWith("data:")) return fixUrlNull(v)
        }
        return null
    }

    private fun Element.toMovieBox(): SearchResponse? {
        val a = this.selectFirst(".film-ismi a") ?: this.selectFirst(".poster a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = a.text().trim().ifBlank { this.selectFirst(".poster img")?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = this.posterOf()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    /** Yedek: içinde <img> olan ve tek segmentli (/film-slug/) bağlantılar. */
    private fun Element.toAnchorResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!isFilmUrl(href)) return null
        val title = (this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.attr("title").takeIf { it.isNotBlank() }
            ?: this.text()).trim()
        if (title.isBlank()) return null
        val poster = this.posterOf()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    private fun Document.toFilmList(): List<SearchResponse> {
        val boxes = this.select("div.movie-box").mapNotNull { it.toMovieBox() }
        val list = boxes.ifEmpty { this.select("a[href]:has(img)").mapNotNull { it.toAnchorResult() } }
        return list.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- Arama
    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val document = app.get("$mainUrl/?s=$query", headers = mapOf("Referer" to "$mainUrl/")).document
        return document.toFilmList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------- Detay
    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val fixedPageUrl = rewriteToCurrent(url)
        val document = app.get(fixedPageUrl).document

        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" |")?.trim()
            ?: return null

        val poster = document.selectFirst(".film-afis")?.posterOf()
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val description = document.selectFirst("#film-aciklama")?.text()?.trim()?.takeIf { it.isNotBlank() }

        val year = document.selectFirst(".list-item a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val tags = document.select("#listelements .elements a").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val actors = document.select(".list-item a[href*=/oyuncu/]").map { Actor(it.text().trim()) }

        val trailer = document.selectFirst("iframe.trailer-video")?.let { f ->
            listOf("data-litespeed-src", "data-src", "src")
                .map { f.attr(it).trim() }
                .firstOrNull { it.startsWith("http") }
        }

        val recommendations = document.select("div.listmovie div.movie-box").mapNotNull { it.toMovieBox() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, fixedPageUrl, TvType.Movie, fixedPageUrl) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // ---------------------------------------------------------------- Linkler
    private fun Document.playerCandidates(): List<String> {
        val urls = linkedSetOf<String>()
        this.select("div.video-container iframe, div.filmalani iframe").forEach { f ->
            val v = listOf("data-litespeed-src", "data-src", "data-lazy-src", "src")
                .map { f.attr(it).trim() }
                .firstOrNull { it.startsWith("http") || it.startsWith("//") }
            if (v != null) urls.add(if (v.startsWith("//")) "https:$v" else v)
        }
        return urls.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val fixedUrl = rewriteToCurrent(data)
        
        // Ana sayfayı yükle
        val mainDoc = app.get(fixedUrl).document
        val allCandidates = mutableSetOf<String>()
        
        // 1. Ana sayfadaki player'ı al (Örn: Dublaj)
        allCandidates.addAll(mainDoc.playerCandidates())

        // 2. Diğer dil/kaynak sayfalarını bul (Örn: Altyazı için /2/ sayfası)
        val otherPages = mainDoc.select("div.sources a.post-page-numbers").mapNotNull {
            fixUrlNull(it.attr("href"))
        }.distinct()

        // 3. Bulunan diğer sayfaları tarayıp onların içindeki iframe'leri de topla
        otherPages.forEach { pageUrl ->
            try {
                val pageDoc = app.get(pageUrl).document
                allCandidates.addAll(pageDoc.playerCandidates())
            } catch (e: Exception) {
                Log.e("YFilmizle", "Alt sayfa yüklenemedi: $pageUrl")
            }
        }

        Log.d("YFilmizle", "Bulunan tüm player adayları: $allCandidates")

        if (allCandidates.isEmpty()) return false

        // 4. Toplanan tüm iframe'leri Extractor'a gönder
        for (link in allCandidates) {
            try {
                loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("YFilmizle", "Extractor hatası ($link): $e")
            }
        }
        return true
    }
}
