package com.anizium

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Anizium : MainAPI() {
    override var mainUrl = AniziumDomain.DEFAULT
    override var name = "Anizium"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val TAG = "AniziumDebug"

    // API adresi her zaman güncel site domaininden türetilir
    private val apiUrl: String get() = AniziumDomain.apiOf(mainUrl)

    // ---------------------------------------------------------------- Domain Sistemi
    private var githubChecked = false

    private suspend fun ensureInit() {
        // Manuel domain varsa GitHub'a hiç gitme
        if (AniziumDomain.manual() == null && !githubChecked) {
            githubChecked = true
            try {
                val text = app.get(AniziumDomain.CONFIG_URL, timeout = 5).text
                AniziumDomain.parse(text)?.let { AniziumDomain.githubDomain = it }
            } catch (e: Exception) {
                Log.e(TAG, "Domain çekilirken hata (Varsayılan kullanılacak): ${e.message}")
            }
        }
        // Her çağrıda güncel değeri al (ayardan değişirse hemen geçerli olur)
        mainUrl = AniziumDomain.current()
    }

    // Göreli yollar: API domaini her istekte güncel mainUrl'den eklenir
    override val mainPage = mainPageOf(
        "/page/last-added-episodes" to "Yeni Bölümler",
        "/page/catalog?id=91363&type=tag" to "Popüler Animeler",
        "/page/home#animeFeatured" to "Haftanın En Çok İzlenenleri",
        "/page/top?platform=favorite" to "Top 100"
       //     "/page/top?platform=all" to "Tüm Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val headers = AniziumToken.getApiHeaders()
        val results = mutableListOf<SearchResponse>()

        // Eski kayıtlardan tam adres gelirse sadece yol kısmını al
        val data = if (request.data.startsWith("http"))
            "/" + request.data.substringAfter("://").substringAfter("/")
        else request.data

        if (data.contains("/page/last-added-episodes")) {
            val url = "$apiUrl/page/last-added-episodes?page=$page"
            val res = app.get(url, headers = headers).parsedSafe<LastAddedResponseWrapper>()
            results.addAll(res?.page?.data.orEmpty().toResponses())
            val hasNext = page < 4 && (res?.page?.data?.size ?: 0) >= 8
            return newHomePageResponse(request.name, results, hasNext = hasNext)
        } else if (data.contains("/page/catalog") || data.contains("/page/top") || data.contains("/page/home")) {
            val separator = if (data.contains("?")) "&" else "?"
            val hashTag = if (data.contains("#")) data.substringAfter("#") else ""
            val path = if (data.contains("#")) data.substringBefore("#") else data
            val finalUrl = "$apiUrl$path${separator}page=$page"

            val res = app.get(finalUrl, headers = headers).parsedSafe<SearchResponseWrapper>()

            val listData = if (hashTag.isNotEmpty() && !res?.specialList.isNullOrEmpty()) {
                res?.specialList?.find { it.id == hashTag }?.data ?: emptyList()
            } else {
                res?.page?.data ?: res?.directData ?: emptyList()
            }

            results.addAll(listData.toResponses())

            val currentPage = res?.page?.page ?: res?.page?.currentPage ?: page
            val totalPages = res?.page?.totalPages ?: 10
            val hasNext = currentPage < totalPages && listData.isNotEmpty()

            return newHomePageResponse(request.name, results, hasNext = hasNext)
        }

        return newHomePageResponse(request.name, results, hasNext = false)
    }

    // ---------------------------------------------------------------- IMDb Puanı
    // Puan önbelleği: -1.0 = "puan yok" (tekrar sorma)
    private val scoreCache = ConcurrentHashMap<String, Double>()
    private val scoreLimiter = Semaphore(6)

    /** Listede puan gelmiyorsa (Yeni Bölümler, Haftanın En Çok İzlenenleri) detaydan çeker. */
    private suspend fun fetchScore(id: String): Double? {
        scoreCache[id]?.let { return if (it < 0) null else it }
        return try {
            val v = scoreLimiter.withPermit {
                app.get(
                    "$apiUrl/anime/get?id=$id",
                    headers = AniziumToken.getApiHeaders(),
                    timeout = 10
                ).parsedSafe<AnimeDetailWrapper>()?.data?.imdbPoint?.toDouble()
            }
            val ok = v?.takeIf { it > 0 }
            scoreCache[id] = ok ?: -1.0
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Puan çekilemedi ($id): ${e.message}")
            null // hata olursa önbelleğe yazma, sonra tekrar denenir
        }
    }

    private suspend fun AnimeItemData.toSearchResponse(): SearchResponse? {
        val id = this.resolvedId ?: return null
        val title = this.resolvedName ?: return null
        val poster = this.resolvedPoster
        val type = if (this.type == "movie") TvType.AnimeMovie else TvType.Anime
        val point = this.imdbPoint?.toDouble()?.takeIf { it > 0 } ?: fetchScore(id)

        return newAnimeSearchResponse(title, "$apiUrl/anime/get?id=$id", type) {
            this.posterUrl = poster
            // Poster sağ üstündeki puan rozetini CloudStream kendisi çizer
            point?.let { this.score = Score.from10(it) }
        }
    }

    private suspend fun List<AnimeItemData>.toResponses(): List<SearchResponse> =
        coroutineScope { map { async { it.toSearchResponse() } }.awaitAll().filterNotNull() }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureInit()
        val headers = AniziumToken.getApiHeaders()
        val url = "$apiUrl/page/search?value=${query.trim()}&page=$page"
        val res = app.get(url, headers = headers).parsedSafe<SearchResponseWrapper>()

        val listData = res?.page?.data ?: res?.directData ?: emptyList()
        val items = listData.toResponses()
        val currentPage = res?.page?.page ?: res?.page?.currentPage ?: page
        val totalPages = res?.page?.totalPages ?: 1
        val hasNext = currentPage < totalPages

        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val headers = AniziumToken.getApiHeaders()
        val animeId = when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/anime/") -> url.substringAfterLast("/").substringBefore("?")
            else -> url
        }

        // Eski domainle kaydedilmiş adreslerin yerine güncel API adresi kullanılır
        val currentUrl = "$apiUrl/anime/get?id=$animeId"
        val res = app.get(currentUrl, headers = headers).parsedSafe<AnimeDetailWrapper>()
        val data = res?.data ?: return null

        val title = data.name ?: "Anime"
        val poster = data.poster ?: data.banner ?: data.logo
        val plot = data.overview ?: data.overviewShort
        val year = data.releaseYear
        val tags = data.genre?.mapNotNull { it.name }
        val status = when (data.status) {
            "Final", "Bitti" -> ShowStatus.Completed
            "Devam Ediyor" -> ShowStatus.Ongoing
            else -> null
        }

        val isMovie = data.type == "movie" || (data.seasons.isNullOrEmpty() && (data.totalSeason ?: 0) == 0)

        if (isMovie) {
            val payload = EpisodeLinkPayload(id = animeId, isMovie = true).toJson()
            return newAnimeLoadResponse(title, currentUrl, TvType.AnimeMovie) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = status
                data.imdbPoint?.let { this.score = Score.from10(it.toDouble()) }
                addEpisodes(DubStatus.Subbed, listOf(newEpisode(payload) {
                    this.name = title
                    this.episode = 1
                }))
            }
        }

        val episodes = mutableListOf<Episode>()
        data.seasons?.forEach { season ->
            val seasonNum = season.number ?: 1
            season.episodes?.forEach { ep ->
                val epNum = ep.number ?: 1
                val epName = ep.name ?: "$epNum. Bölüm"
                val epPoster = ep.bannerLink
                val epOverview = ep.overview
                val payload = EpisodeLinkPayload(
                    id = animeId,
                    isMovie = false,
                    season = seasonNum,
                    episode = epNum
                ).toJson()

                val episodeObj = newEpisode(payload) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.description = epOverview
                }
                episodes.add(episodeObj)
            }
        }

        return newAnimeLoadResponse(title, currentUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.showStatus = status
            data.imdbPoint?.let { this.score = Score.from10(it.toDouble()) }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val headers = AniziumToken.getApiHeaders()
        val payload = try {
            parseJson<EpisodeLinkPayload>(data)
        } catch (e: Exception) {
            EpisodeLinkPayload(id = data, isMovie = true)
        }

        val animeId = payload.id ?: return false
        val isMovie = payload.isMovie

        val servers = listOf("1", "2")
        for (server in servers) {
            val sourceUrl = if (isMovie) {
                "$apiUrl/anime/source?id=$animeId&site=main&plan=1&server=$server"
            } else {
                "$apiUrl/anime/source?id=$animeId&site=main&plan=1&server=$server&season=${payload.season ?: 1}&episode=${payload.episode ?: 1}"
            }

            val res = app.get(sourceUrl, headers = headers).parsedSafe<SourceWrapper>() ?: continue
            if (res.success != true) continue

            res.subtitles?.forEach { sub ->
                val subUrl = sub.link ?: return@forEach
                val langName = sub.name ?: if (sub.group == "tr") "Türkçe" else if (sub.group == "en") "İngilizce" else "Altyazı"
                subtitleCallback(SubtitleFile(langName, subUrl))
            }

            res.groups?.forEach { group ->
                val groupKey = (group.group ?: "").lowercase()
                val groupName = (group.name ?: "").lowercase()
                val audioLabel = when {
                    groupKey == "trdub" || groupName.contains("türkçe") || groupName.contains("turkce") -> "🇹🇷 Türkçe Dublaj"
                    groupKey == "original" || groupName.contains("japonca") -> "🇯🇵 Japonca"
                    groupKey == "endub" || groupName.contains("ingilizce") -> "🇬🇧 İngilizce Dublaj"
                    else -> group.name ?: "Ses"
                }

                group.items?.forEach { item ->
                    val videoLink = item.link ?: return@forEach
                    val quality = item.quality ?: 1080
                    val qualityLabel = when (quality) {
                        2160 -> "4K"
                        1440 -> "2K"
                        else -> "${quality}p"
                    }
                    val displayName = "$name - $qualityLabel | $audioLabel"
                    val isHls = videoLink.contains(".m3u8")

                    callback(
                        newExtractorLink(
                            source = name,
                            name = displayName,
                            url = videoLink,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to "https://anizium.com/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }

            if (!res.groups.isNullOrEmpty()) {
                break
            }
        }

        return true
    }

    // ── Esnek Veri Modelleri (JSON Schemas) ──────────────────────────

    data class EpisodeLinkPayload(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("isMovie") val isMovie: Boolean = false,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    data class LastAddedResponseWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("isError") val isError: Boolean? = null,
        @JsonProperty("page") val page: LastAddedPageData? = null
    )

    data class LastAddedPageData(
        @JsonProperty("data") val data: List<AnimeItemData>? = null
    )

    data class SearchResponseWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("isError") val isError: Boolean? = null,
        @JsonProperty("page") val page: SearchPageData? = null,
        @JsonProperty("data") val directData: List<AnimeItemData>? = null,
        @JsonProperty("special_list") val specialList: List<SpecialListItem>? = null
    )

    data class SpecialListItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("data") val data: List<AnimeItemData>? = null
    )

    data class SearchPageData(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("current_page") val currentPage: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        @JsonProperty("data") val data: List<AnimeItemData>? = null
    )

    data class AnimeItemData(
        @JsonProperty("ID") val idUpper: String? = null,
        @JsonProperty("id") val idLower: String? = null,
        @JsonProperty("content_id") val contentId: String? = null,
        @JsonProperty("contentId") val contentIdCamel: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_year") val releaseYear: Int? = null,
        @JsonProperty("imdb_point") val imdbPoint: Number? = null
    ) {
        val resolvedId: String? get() = idUpper ?: idLower ?: contentId ?: contentIdCamel
        val resolvedName: String? get() = name ?: title
        val resolvedPoster: String? get() = poster ?: banner ?: logo
    }

    data class AnimeDetailWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: AnimeDetailData? = null
    )

    data class AnimeDetailData(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("overview_short") val overviewShort: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("release_year") val releaseYear: Int? = null,
        @JsonProperty("imdb_point") val imdbPoint: Number? = null,
        @JsonProperty("total_season") val totalSeason: Int? = null,
        @JsonProperty("genre") val genre: List<GenreItem>? = null,
        @JsonProperty("seasons") val seasons: List<SeasonItem>? = null
    )

    data class GenreItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class SeasonItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("banner_link") val bannerLink: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class SourceWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("groups") val groups: List<SourceGroupItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<SourceSubtitleItem>? = null
    )

    data class SourceGroupItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("group") val group: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("items") val items: List<SourceQualityItem>? = null
    )

    data class SourceQualityItem(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class SourceSubtitleItem(
        @JsonProperty("group") val group: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("link") val link: String? = null
    )
}
