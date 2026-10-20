package com.fourKHDHub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FullHDFilmizleseneAudio {
    private val mainUrl = "https://www.fullhdfilmizlesene.now"

    suspend fun getTurkishAudioLink(movieTitle: String, year: Int?): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Türkçe sitede arama yap
                val searchUrl = "$mainUrl/arama/${movieTitle.replace(" ", "+")}"
                val document = app.get(searchUrl).document
                
                // İlk sonucu al
                val result = document.select("li.film").firstOrNull {
                    it.selectFirst("span.film-title")?.text()?.contains(movieTitle, true) == true
                } ?: document.selectFirst("li.film")
                
                val movieHref = result?.selectFirst("a")?.attr("href") ?: return@withContext null
                val fixedHref = if (movieHref.startsWith("http")) movieHref else "$mainUrl$movieHref"

                // 2. Film sayfasına gir ve tüm olası linkleri topla
                val movieDoc = app.get(fixedHref).document
                val htmlContent = movieDoc.html()

                val candidateLinks = mutableListOf<String>()

                // YÖNTEM A: İframe ve Embed linklerini topla (data-src, src vb.)
                movieDoc.select("iframe, embed, object").forEach {
                    val src = it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("data-url") }
                    if (src.isNotBlank() && !src.contains("googletagmanager")) {
                        candidateLinks.add(src)
                    }
                }

                // YÖNTEM B: Regex ile sayfa içindeki linkleri bul
                Regex("""https?://[^\s"'<>]+\b(?:rapidvid|turbo|vidmoxy|embed|player|watch|video|srv|api)[^\s"'<>]+""").findAll(htmlContent).forEach {
                    candidateLinks.add(it.value)
                }

                // YÖNTEM C (EN ÖNEMLİSİ): Sitenin gizlediği (SCX) Base64/Rot13 şifreli linkleri kır
                val scripts = movieDoc.select("script").map { it.data() }
                for (script in scripts) {
                    if (!script.contains("scx")) continue
                    val scxJsonStr = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                        .find(script)?.groupValues?.get(1) ?: continue
                    
                    // JSON içindeki gizli şifreli metinleri çıkar ve çöz
                    Regex("""["']([^"']{20,})["']""").findAll(scxJsonStr).forEach { match ->
                        val decoded = smartDecodeLink(match.groupValues[1])
                        if (decoded != null) candidateLinks.add(decoded)
                    }
                }

                val validLinks = candidateLinks.map { if (it.startsWith("//")) "https:$it" else it }
                    .filter { it.contains("rapidvid", true) || it.contains("turbo", true) || it.contains("vidmoxy", true) || it.contains("cdn", true) }
                    .distinct()

                if (validLinks.isEmpty()) return@withContext null

                // 3. Bulunan linkleri Extractor'dan geçirip ilk m3u8/mp4 linkini döndür
                for (iframeUrl in validLinks) {
                    val audioUrl = extractProperAudioLink(iframeUrl)
                    if (audioUrl != null && (audioUrl.contains(".m3u8") || audioUrl.contains(".mp4") || audioUrl.contains("cdn"))) {
                        return@withContext audioUrl
                    }
                }
                
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    // SCX Base64 + Rot13 Akıllı Çözücü
    private fun smartDecodeLink(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("//")) return trimmed

        try { val rotDecoded = atob(rtt(trimmed)).trim(); if (rotDecoded.startsWith("http") || rotDecoded.startsWith("//")) return rotDecoded } catch (_: Exception) {}
        try { val b64Decoded = atob(trimmed).trim(); if (b64Decoded.startsWith("http") || b64Decoded.startsWith("//")) return b64Decoded } catch (_: Exception) {}
        try { val revDecoded = atob(trimmed.reversed()).trim(); if (revDecoded.startsWith("http") || revDecoded.startsWith("//")) return revDecoded } catch (_: Exception) {}
        return null
    }

    private fun atob(s: String): String = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)

    private fun rtt(s: String): String {
        return s.map { c ->
            when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private suspend fun extractProperAudioLink(iframeUrl: String): String? {
        // Eğer bulduğumuz link doğrudan mp4 veya m3u8 (örneğin s37.cdnimg...) ise ekstra çözmeye gerek yok
        if (iframeUrl.contains(".m3u8") || iframeUrl.contains(".mp4") || iframeUrl.contains("cdnimg")) {
            return iframeUrl
        }

        var foundLink: String? = null
        try {
            if (iframeUrl.contains("rapidvid", true)) {
                RapidVid().getUrl(iframeUrl, "$mainUrl/", subtitleCallback = {}) { link ->
                    if (foundLink == null && link.url.isNotBlank()) foundLink = link.url
                }
            } else if (iframeUrl.contains("turbo", true)) {
                TurboImgz().getUrl(iframeUrl, "$mainUrl/", subtitleCallback = {}) { link ->
                    if (foundLink == null && link.url.isNotBlank()) foundLink = link.url
                }
            } else {
                loadExtractor(iframeUrl, mainUrl, subtitleCallback = { _ -> }) { link ->
                    if (foundLink == null && link.url.isNotBlank()) foundLink = link.url
                }
            }
        } catch (e: Exception) {}

        if (foundLink != null) return foundLink

        // Yedek Yöntem: Manuel Regex
        return try {
            val res = app.get(iframeUrl, referer = "$mainUrl/").text
            Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(res)?.groupValues?.get(1)?.replace("\\", "")
        } catch (e2: Exception) {
            null
        }
    }
}
