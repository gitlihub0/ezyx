package com.fourKHDHub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

                // 2. Sayfaya gir ve kaynak iframe'ini bul
                val movieDoc = app.get(fixedHref).document
                val iframeSrc = movieDoc.select("iframe[src*=rapidvid], iframe[src*=turbo], iframe[src*=vidmoxy]").attr("src")
                if (iframeSrc.isBlank()) return@withContext null

                val fixedIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

                // 3. CloudStream'in kendi sistemini kullanarak linki şifresiz olarak çöz
                return@withContext extractProperAudioLink(fixedIframe)
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun extractProperAudioLink(iframeUrl: String): String? {
        return suspendCoroutine { continuation ->
            var linkFound = false
            try {
                // Not: RapidVid ve benzeri siteler için Extractor'ı tetikliyoruz
                // loadExtractor arka planda asenkron çalışır, callback ile sonucu verir.
                loadExtractor(iframeUrl, mainUrl, subtitleCallback = { _ -> }) { link ->
                    if (!linkFound && link.url.isNotBlank()) {
                        linkFound = true
                        continuation.resume(link.url)
                    }
                }
                
                // Eğer saniye bazlı bir zaman aşımı eklemek istersen coroutines withTimeout kullanabilirsin
                // Şimdilik Extractor listesinde bulunamazsa doğrudan regex denemesi yapalım
            } catch (e: Exception) {
                // Extractor çökmesi durumunda manuel yedek regex (eski yöntem)
                if (!linkFound) {
                    try {
                        val res = app.get(iframeUrl, referer = "$mainUrl/").text
                        val backupLink = Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(res)?.groupValues?.get(1)?.replace("\\", "")
                        continuation.resume(backupLink)
                    } catch (e2: Exception) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }
}
