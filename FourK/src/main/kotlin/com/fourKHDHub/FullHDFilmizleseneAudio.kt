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
                // 1. Filmi Türkçe sitede ara
                val searchUrl = "$mainUrl/arama/${movieTitle.replace(" ", "+")}"
                val document = app.get(searchUrl).document
                
                // Yıla veya isme göre en uygun sonucu bul
                val result = document.select("li.film").firstOrNull {
                    it.selectFirst("span.film-title")?.text()?.contains(movieTitle, true) == true
                } ?: document.selectFirst("li.film")
                
                val movieHref = result?.selectFirst("a")?.attr("href") ?: return@withContext null
                val fixedHref = if (movieHref.startsWith("http")) movieHref else "$mainUrl$movieHref"

                // 2. Film sayfasına gir ve RapidVid/TurboImgz iframe'ini bul
                val movieDoc = app.get(fixedHref).document
                val iframeSrc = movieDoc.select("iframe[src*=rapidvid], iframe[src*=turbo]").attr("src")
                if (iframeSrc.isBlank()) return@withContext null

                // 3. Sesi çıkar (Bu örnekte RapidVid'in şifresini çözüp doğrudan mp4/m3u8 alıyoruz)
                // Not: RapidVid/TurboImgz extractör kodlarını buraya entegre edeceğiz.
                // Şimdilik test için iframe adresini döndürüyoruz, tam entegrasyonda burası Extractor'a gidecek.
                return@withContext extractAudioFromIframe(iframeSrc)
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun extractAudioFromIframe(iframeUrl: String): String? {
        // Gerçek m3u8 veya mp4 linkini çözen mantık
        val res = app.get(iframeUrl, referer = "$mainUrl/").text
        // Regex ile M3U8'i yakala
        return Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(res)?.groupValues?.get(1)?.replace("\\", "")
    }
}FullHDFilmizleseneAudio.kt
