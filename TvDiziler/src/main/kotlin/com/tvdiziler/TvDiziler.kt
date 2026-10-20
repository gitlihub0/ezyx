package com.tvdiziler

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TvDizilerSearchResponse(
    val success: Int?    = null,
    val data: String?    = null,
)

class TvDiziler : MainAPI() {
    override var mainUrl              = TvDizilerDomain.DEFAULT
    override var name                 = "DizilerTv"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    // Göreli yollar: domain her istekte güncel mainUrl'den eklenir
    override val mainPage = mainPageOf(
        "/"            to "Son Bölümler",
        "/kesfet"      to "Keşfet",
    )

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---------------------------------------------------------------- Domain Sistemi
    private var githubChecked = false

    private suspend fun ensureInit() {
        // Manuel domain varsa GitHub'a hiç gitme
        if (TvDizilerDomain.manual() == null && !githubChecked) {
            githubChecked = true
            try {
                val text = app.get(TvDizilerDomain.CONFIG_URL, timeout = 5).text
                TvDizilerDomain.parse(text)?.let { TvDizilerDomain.githubDomain = it }
            } catch (e: Exception) {
                Log.e("TvDiziler", "Domain çekilirken hata (varsayılan kullanılacak): ${e.message}")
            }
        }
        // Her çağrıda güncel değeri al (ayardan değişirse hemen geçerli olur)
        mainUrl = TvDizilerDomain.current()
    }

    /** Eski/başka domainle kaydedilmiş adresin domain kısmını güncel domain ile değiştirir. */
    private fun rewriteToCurrent(url: String): String {
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (url.startsWith(mainUrl)) url else mainUrl + url.removePrefix(host)
    }

    private fun isContentLink(el: Element): Boolean {
        val href = el.attr("href")
        if (href.isBlank() || href == "/") return false
        if (href.startsWith("profile/") || href.startsWith("tartisma/") || href.startsWith("dizi/tur/")) return false
        return el.selectFirst("img") != null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val isPaginated = request.data == "/kesfet"

        if (!isPaginated && page > 1) {
            return newHomePageResponse(request.name, emptyList())
        }

        val base = if (request.data.startsWith("http")) rewriteToCurrent(request.data)
                   else "$mainUrl${request.data}"
        val url = if (isPaginated && page > 1) "$base/$page" else base
        val document = app.get(url).document

        val home = document.select("a[data-navigo]").filter { isContentLink(it) }.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val nameText  = this.selectFirst("h2[itemprop=name]")?.text()?.trim()
        val altText   = img?.attr("alt")?.trim()
        val title     = (nameText?.ifBlank { null } ?: altText?.ifBlank { null } ?: this.text().trim())

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val response = app.post(
            "${mainUrl}/search",
            params  = mapOf("qr" to query),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val parsed = AppUtils.tryParseJson<TvDizilerSearchResponse>(response) ?: return emptyList()
        val doc    = Jsoup.parse(parsed.data ?: "")

        return doc.select("a[data-navigo]").filter { isContentLink(it) }.mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        var seriesUrl = rewriteToCurrent(url)
        var document  = app.get(seriesUrl).document

        if (!url.contains("/dizi/")) {
            val resolved = document.select("a[href^=dizi/]").firstOrNull { !it.attr("href").startsWith("dizi/tur/") }
            val resolvedUrl = resolved?.let { fixUrlNull(it.attr("href")) }
            if (resolvedUrl != null) {
                seriesUrl = resolvedUrl
                document  = app.get(seriesUrl).document
            }
        }

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.replace(Regex("""\(\d{4}\)\s*$"""), "").trim().ifBlank { rawTitle }
        val year     = Regex("""\((\d{4})\)\s*$""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val poster      = fixUrlNull(document.selectFirst(".series-profile-image img")?.attr("data-src")?.ifBlank { null }
            ?: document.selectFirst(".series-profile-image img")?.attr("src"))
        val description = document.selectFirst("[itemprop=description]")?.text()?.trim()

        val tags = document.select("span.block a[href^=dizi/tur/]").map { it.text().trim() }

        val seasonTabs  = document.select("li[data-season]")
        val seasonAreas = if (seasonTabs.isNotEmpty()) {
            seasonTabs.mapNotNull { tab ->
                val num    = tab.attr("data-num").toIntOrNull() ?: 1
                val areaId = tab.attr("data-season")
                val area   = if (areaId.isNotBlank()) document.selectFirst("#${areaId}") else null
                if (area != null) num to area else null
            }
        } else {
            listOf(1 to document)
        }

        val episodes = seasonAreas.flatMap { (seasonNum, area) ->
            area.select("[itemprop=episode]").mapNotNull { epEl ->
                val nameLink = epEl.selectFirst("a[itemprop=name]") ?: return@mapNotNull null
                val epHref   = fixUrlNull(nameLink.attr("href")) ?: return@mapNotNull null
                val epNum    = epEl.selectFirst("[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = nameLink.text().trim()
                    this.season  = seasonNum
                    this.episode = epNum
                }
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    // ---------------------------------------------------------------- Linkler

    /** Kaçışlı karakterleri düzeltir: \/ -> /, & -> &, &amp; -> & */
    private fun cleanText(text: String): String =
        text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    /** Referer/Origin gönderilince 403 veren CDN'ler. */
    private fun needsNoReferer(url: String): Boolean =
        url.contains("twimg.com") || url.contains("yandex") || url.contains("googlevideo")

    /** Metin içindeki tüm doğrudan medya (m3u8/mp4) adreslerini bulur. */
    private fun findMediaUrls(html: String): List<String> {
        val clean = cleanText(html)
        val out   = linkedSetOf<String>()

        Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").findAll(clean)
            .map { it.groupValues[1] }
            .filter { it.contains(".m3u8") || it.contains(".mp4") || it.contains("/hls/") }
            .forEach { out.add(it) }

        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""").findAll(clean)
            .forEach { out.add(it.value) }

        return out.toList()
    }

    /** Bir medya adresini (gerekirse base64 çözerek) callback'e iletir. En az bir link verdiyse true. */
    private suspend fun emitMedia(
        raw: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
        label: String = ""
    ): Boolean {
        val candidates = linkedSetOf<String>()

        // tvdiziler.tv/vid/mp4/<base64>.mp4 -> base64 gerçek CDN adresini içerebiliyor
        val b64 = Regex("""/vid/[A-Za-z0-9_\-]+/([A-Za-z0-9_\-+=]{20,})\.(?:mp4|m3u8)""")
            .find(raw)?.groupValues?.get(1)
        val direct = b64?.let { runCatching { base64Decode(it) }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
        if (direct != null) candidates.add(direct)
        candidates.add(raw)

        var any = false
        for (c in candidates) {
            val fileUrl = fixUrl(c)
            if (!seen.add(fileUrl)) continue

            val isM3u8  = fileUrl.contains(".m3u8")
            val noRef   = needsNoReferer(fileUrl)
            val quality = Regex("""_(\d{3,4})\.(?:mp4|m3u8)""").find(fileUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/(\d{3,4})x(\d{3,4})/""").find(fileUrl)?.groupValues?.get(2)?.toIntOrNull()

            val linkName = (if (label.isNotBlank()) "$name - $label" else name) +
                if (fileUrl.startsWith(mainUrl)) " (Yedek)" else ""

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = linkName,
                    url    = fileUrl,
                    type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = if (noRef) "" else "$mainUrl/"
                    this.headers = if (noRef) {
                        mapOf("User-Agent" to userAgent)
                    } else {
                        mapOf(
                            "Referer"    to "$mainUrl/",
                            "Origin"     to mainUrl,
                            "User-Agent" to userAgent
                        )
                    }
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
            any = true
        }
        return any
    }

    /** Tek bir data-hhs kaynağını işler. */
    private suspend fun handleSource(
        hhs: String,
        pageUrl: String,
        seen: MutableSet<String>,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (hhs.contains("/vid/kapat/")) {
            val externalUrl = hhs.substringAfter("git=", "")
            if (externalUrl.isBlank()) return false
            loadExtractor(externalUrl, pageUrl, subtitleCallback, callback)
            return true
        }

        val playerUrl  = fixUrl(cleanText(hhs))
        val playerHtml = cleanText(app.get(playerUrl, referer = pageUrl).text)
        Log.d("TvDiziler", "player=$playerUrl uzunluk=${playerHtml.length} medya=${findMediaUrls(playerHtml).size}")

        var any = false
        for (u in findMediaUrls(playerHtml)) {
            if (emitMedia(u, seen, callback, label)) any = true
        }
        if (any) return true

        // Oynatıcı başka bir iframe'e yönlendiriyorsa extractor'a ver
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(playerHtml)?.groupValues?.get(1)
        if (!iframe.isNullOrBlank()) {
            loadExtractor(fixUrl(iframe), playerUrl, subtitleCallback, callback)
            return true
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val pageUrl  = rewriteToCurrent(fixUrl(data))
        val response = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent))
        val document = response.document
        val seen     = linkedSetOf<String>()
        var found    = false

        // 1) Bölüm sayfasının kendisinde doğrudan medya adresi var mı? (örn. video.twimg.com ... .m3u8)
        for (u in findMediaUrls(response.text)) {
            try {
                if (emitMedia(u, seen, callback)) found = true
            } catch (e: Exception) { }
        }

        // 2) data-hhs kaynakları (buton olmak zorunda değil). Başka kaynak varsa "Fragman"ı ele.
        val allSources = document.select("[data-hhs]")
        val sources = allSources
            .filter { !it.text().trim().equals("Fragman", ignoreCase = true) }
            .ifEmpty { allSources }

        Log.d("TvDiziler", "sayfa=$pageUrl data-hhs=${allSources.size} kullanilan=${sources.size}")

        for ((index, el) in sources.withIndex()) {
            val hhs = el.attr("data-hhs")
            if (hhs.isBlank()) continue
            // "1.Part", "2.Part"... gibi buton metnini link adına ekle
            val label = el.text().trim().ifBlank { "Kaynak ${index + 1}" }
            try {
                if (handleSource(hhs, pageUrl, seen, label, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
                // Bir kaynak hata verirse diğerlerini denemeye devam et
                Log.e("TvDiziler", "kaynak hatası ($label): ${e.javaClass.simpleName} ${e.message}")
            }
        }

        // 3) Hâlâ bir şey yoksa sayfadaki iframe'leri extractor'a ver
        if (!found) {
            for (frame in document.select("iframe[src]")) {
                val src = fixUrlNull(frame.attr("src")) ?: continue
                try {
                    loadExtractor(src, pageUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) { }
            }
        }

        return found
    }
}
