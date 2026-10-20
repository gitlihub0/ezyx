package com.ddizi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DDizi : MainAPI() {
    override var mainUrl              = DDiziDomain.DEFAULT
    override var name                 = "DDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    // Göreli yollar: domain her istekte güncel mainUrl'den eklenir
    override val mainPage = mainPageOf(
        "/"                  to "Ana Sayfa - Son Eklenenler",
        "/yeni-eklenenler8"  to "Yeni Eklenenler",
        "/yabanci-dizi-izle" to "Yabancı Diziler"
    )

    private val TAG = "DDiziDebug"
    private val boxSelector = "div.dizi-boxpost, div.dizi-boxpost-cat"
    private val partRegex   = Regex("""\.htm/(\d+)$""")

    // ---------------------------------------------------------------- Domain Sistemi
    private var githubChecked = false

    private suspend fun ensureInit() {
        // Manuel domain varsa GitHub'a hiç gitme
        if (DDiziDomain.manual() == null && !githubChecked) {
            githubChecked = true
            try {
                val text = app.get(DDiziDomain.CONFIG_URL, timeout = 5).text
                DDiziDomain.parse(text)?.let { DDiziDomain.githubDomain = it }
            } catch (e: Exception) {
                Log.e(TAG, "Domain çekilirken hata (Varsayılan kullanılacak): ${e.message}")
            }
        }
        // Her çağrıda güncel değeri al (ayardan değişirse hemen geçerli olur)
        mainUrl = DDiziDomain.current()
        Log.d(TAG, "Kullanılan domain: $mainUrl")
    }

    /** Eski/başka domainle kaydedilmiş adresin domain kısmını güncel domain ile değiştirir. */
    private fun rewriteToCurrent(url: String): String {
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (url.startsWith(mainUrl)) url else mainUrl + url.removePrefix(host)
    }

    // ---------------------------------------------------------------- Ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val currentUrl = if (request.data.startsWith("http")) rewriteToCurrent(request.data)
                         else "$mainUrl/${request.data.removePrefix("/")}"
        val document = app.get(currentUrl).document
        val home = document.select(boxSelector).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag   = this.selectFirst("a") ?: return null
        val title  = aTag.attr("title").ifEmpty { aTag.text() }.substringBefore(" izle").trim()
        val href   = fixUrlNull(aTag.attr("href")) ?: return null
        val imgTag = this.selectFirst("img")
        val poster = fixUrlNull(imgTag?.attr("data-src")?.ifEmpty { imgTag.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------- Arama

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val document = app.post(
            "$mainUrl/arama/",
            data = mapOf("arama" to query)
        ).document
        return document.select(boxSelector).mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------- Detay

    private fun isEpisodeUrl(url: String) = url.substringBefore("?").contains(".htm")
    private fun cleanTitle(raw: String) = raw.substringBefore(" izle").trim()
    private val monthNames = "Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık"

    private fun seriesNameOf(title: String): String =
        title.replace(Regex("""\s+\d+\.\s*(Sezon|B[öo]l[üu]m).*$""", RegexOption.IGNORE_CASE), "")
             .replace(Regex("""\s+\d{1,2}\s+($monthNames).*$""", RegexOption.IGNORE_CASE), "")
             .trim()

    private fun findSeriesLink(doc: Document, seriesName: String): String? {
        if (seriesName.isBlank()) return null
        return doc.select("a[href]").firstOrNull { a ->
            val href = a.attr("href")
            href.isNotBlank() && !href.startsWith("#") && !href.startsWith("javascript") &&
                !href.contains(".htm") &&
                (a.text().trim().equals(seriesName, true) ||
                    cleanTitle(a.attr("title")).equals(seriesName, true))
        }?.attr("href")?.let { fixUrlNull(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val url = rewriteToCurrent(url)
        var document = app.get(url).document

        fun titleOf(d: Document) =
            d.selectFirst("div.dizi_content h1")?.text()?.let { cleanTitle(it) }
                ?: d.selectFirst("title")?.text()?.substringBefore("|")?.let { cleanTitle(it) }
                ?: "Dizi"

        val openedTitle     = titleOf(document)
        val openedIsEpisode = isEpisodeUrl(url)
        val seriesName      = if (openedIsEpisode) seriesNameOf(openedTitle) else openedTitle

        var pageUrl = url
        if (openedIsEpisode) {
            val seriesLink = findSeriesLink(document, seriesName)
            if (seriesLink != null) {
                try {
                    document = app.get(seriesLink).document
                    pageUrl  = seriesLink
                } catch (e: Exception) {
                    Log.e(TAG, "Dizi sayfası açılamadı: ${e.message}")
                }
            }
        }

        val title       = if (pageUrl == url && openedIsEpisode) seriesName else titleOf(document)
        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("div.dizi_content")?.text()?.trim()

        val boxed = document.select("div.row div.dizi-boxpost a[href]").map { it.attr("href") }.toSet()
        val episodes = document.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.contains(".htm") || partRegex.containsMatchIn(href)) return@mapNotNull null

            val name = cleanTitle(a.attr("title").ifEmpty { a.text() })
            val matchesSeries = seriesName.isNotBlank() && name.startsWith(seriesName, ignoreCase = true)
            if (href !in boxed && !matchesSeries) return@mapNotNull null

            val epHref = fixUrlNull(href) ?: return@mapNotNull null
            val season  = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("""(\d+)\.\s*B[öo]l[üu]m""", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = name.ifEmpty { title }
                this.season  = season
                this.episode = episode
            }
        }.distinctBy { it.data.trimEnd('/') }.toMutableList()

        if (openedIsEpisode && episodes.none { it.data.trimEnd('/') == url.trimEnd('/') }) {
            episodes.add(0, newEpisode(url) { this.name = cleanTitle(openedTitle) })
        }
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { this.name = title })
        }

        val ordered = if (episodes.all { it.episode != null })
            episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        else
            episodes.reversed()

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, ordered) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    // ---------------------------------------------------------------- Linkler

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val data = rewriteToCurrent(data)
        val document = app.get(data).document

        try {
            extractPlayerFromDoc(document, data, "1. Parça", subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(TAG, "1. parça hata: ${e.message}")
        }

        val base = data.substringBefore(".htm") + ".htm"
        val partLinks = document.select("a[href]")
            .map { fixUrl(it.attr("href")) }
            .filter { it.startsWith("$base/") && partRegex.containsMatchIn(it) }
            .distinct()

        for (href in partLinks) {
            val partNo = partRegex.find(href)?.groupValues?.get(1) ?: continue
            if (partNo == "1") continue

            try {
                val partDoc = app.get(href, referer = data).document
                extractPlayerFromDoc(partDoc, href, "$partNo. Parça", subtitleCallback, callback)
            } catch (e: Exception) {
                continue
            }
        }
        return true
    }

    private suspend fun extractPlayerFromDoc(
        doc: Document,
        pageUrl: String,
        partName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val src = doc.selectFirst("div.video-g iframe")?.attr("src")
            ?: doc.selectFirst("iframe[src*=/player/]")?.attr("src")
            ?: doc.selectFirst("iframe[src]")?.attr("src")
            ?: doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?: return

        val playerUrl = fixUrl(src)

        val links = mutableListOf<ExtractorLink>()
        val collect: (ExtractorLink) -> Unit = { links.add(it) }

        if (playerUrl.contains("ddizi", ignoreCase = true)) {
            DDiziExtractor().getUrl(playerUrl, pageUrl, subtitleCallback, collect)
        } else {
            loadExtractor(playerUrl, pageUrl, subtitleCallback, collect)
        }

        links.forEach { link ->
            callback(
                newExtractorLink(
                    source = link.source,
                    name   = "$partName - ${link.name}",
                    url    = link.url,
                    type   = link.type
                ) {
                    this.referer       = link.referer
                    this.quality       = link.quality
                    this.headers       = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}
