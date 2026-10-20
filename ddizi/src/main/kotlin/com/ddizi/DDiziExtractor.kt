package com.ddizi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class DDiziExtractor : ExtractorApi() {
    override val name            = "DDizi"
    override val mainUrl: String get() = DDiziDomain.current()
    override val requiresReferer = true

    private val TAG = "DDiziDebug"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private fun absolute(link: String): String = when {
        link.startsWith("//") -> "https:$link"
        link.startsWith("/")  -> mainUrl + link
        else                  -> link
    }

    private val patterns = listOf(
        Regex("""file\s*:\s*["']([^"']+)["']"""),
        Regex("""["']?src["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
        Regex("""<source[^>]+src=["']([^"']+)["']"""),
        Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']"""),
        Regex("""["'](https?://[^"'\s]+yandex\.(?:net|ru)[^"'\s]+)["']"""),
        Regex("""["'](https?://[^"'\s]+streambox\.xyz[^"'\s]+)["']"""),
        Regex("""["'](https?://[a-zA-Z0-9-.]+\.googlevideo\.com/videoplayback[^"'\s]+)["']"""),
        Regex("""["'](https?://[^"'\s]+mncdn\.com[^"'\s]+)["']""")
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val referers = listOfNotNull(referer, "$mainUrl/").distinct()
        var src = ""
        var sources = emptyList<String>()
        var currentUrl = url

        for (ref in referers) {
            val resp = app.get(
                url,
                referer = ref,
                headers = mapOf("User-Agent" to userAgent)
            )
            src = resp.text.replace("\\/", "/")

            // 1. ÇÖZÜM: İframe (Gizli Oynatıcı) Taraması
            val iframeMatches = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(src)
            for (match in iframeMatches) {
                val iframeSrc = match.groupValues[1]
                if (iframeSrc.contains("/player/") || iframeSrc.contains("/oynat/")) {
                    currentUrl = absolute(iframeSrc)
                    val iframeResp = app.get(
                        currentUrl, 
                        referer = url, 
                        headers = mapOf("User-Agent" to userAgent)
                    )
                    src = iframeResp.text.replace("\\/", "/")
                    break
                }
            }

            // 2. ÇÖZÜM: Karakter Bozulmalarını Düzeltme (\u0026 -> &)
            sources = patterns
                .map { regex -> regex.findAll(src).map { it.groupValues[1] }.toList() }
                .firstOrNull { it.isNotEmpty() }
                ?.map { absolute(it).replace("\\u0026", "&").replace("&amp;", "&") }
                ?.distinct()
                .orEmpty()

            if (sources.isNotEmpty()) break
        }

        if (sources.isNotEmpty()) {
            for (link in sources) {
                
                // 3. ÇÖZÜM: Yandex ve Streambox için Referer (Gelinen Adres) Gizleme
                val cleanRef = when {
                    link.contains("twimg.com") -> ""
                    link.contains("yandex") -> ""
                    link.contains("streambox") -> ""
                    link.contains("googlevideo") -> ""
                    else -> currentUrl
                }

                if (link.contains(".m3u8") || link.contains("streambox.xyz") || link.contains("mncdn.com")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = "${this.name} (Otomatik Kalite)",
                            url    = link,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = cleanRef
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = this.name,
                            url    = link,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cleanRef
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                }
            }
            return
        }
        throw ErrorLoadingException("Video linki bulunamadı")
    }
}
