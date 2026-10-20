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
        var foundLink: String? = null
        
        try {
            // loadExtractor asenkron çalışır ve bulunan linkleri callback ile döndürür
            loadExtractor(iframeUrl, mainUrl, subtitleCallback = { _ -> }) { link ->
                // Sadece ilk bulduğu ve boş olmayan linki kaydediyoruz
                if (foundLink == null && link.url.isNotBlank()) {
                    foundLink = link.url
                }
            }
            
            // Link başarıyla çözüldüyse geri döndür
            if (foundLink != null) {
                return foundLink
            }
        } catch (e: Exception) {
            // Extractor çökürse uygulamayı kapatma, aşağıdaki yedek (manuel) sisteme geç
        }

        // Yedek Yöntem: Şifresiz/basit m3u8 sunan alternatif bir player gelirse Regex ile yakala
        return try {
            val res = app.get(iframeUrl, referer = "$mainUrl/").text
            Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(res)?.groupValues?.get(1)?.replace("\\", "")
        } catch (e2: Exception) {
            null
        }
    }
}
