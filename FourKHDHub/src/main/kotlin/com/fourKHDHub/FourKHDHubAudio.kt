package com.fourKHDHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newAudioFile
import android.util.Base64
import android.util.Log

object FourKHDHubAudio {

    // 1. FİLMLER İÇİN (IMDb ID veya İsim Bazlı)
    suspend fun fetchMovieAudio(imdbId: String?, movieTitle: String?): String? {
        try {
            var targetUrl: String? = null
            if (!imdbId.isNullOrBlank()) {
                val doc = app.get("https://www.fullhdfilmizlesene.now/arama/$imdbId").document
                targetUrl = doc.selectFirst("li.film a")?.attr("href")
            }
            if (targetUrl == null && !movieTitle.isNullOrBlank()) {
                val cleanTitle = movieTitle.replace(Regex("""\s+\(\d{4}\)$"""), "").trim()
                val query = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
                val doc = app.get("https://www.fullhdfilmizlesene.now/arama/$query").document
                targetUrl = doc.selectFirst("li.film a")?.attr("href")
            }
            return extractAudioStream(targetUrl)
        } catch (e: Exception) {
            Log.e("AudioModule", "Film ses hatası: ${e.message}")
            return null
        }
    }

    // 2. DİZİLER İÇİN (Dizi Adı + Sezon + Bölüm Bazlı)
    suspend fun fetchSeriesAudio(seriesTitle: String?, season: Int?, episode: Int?): String? {
        try {
            if (seriesTitle.isNullOrBlank() || season == null || episode == null) return null
            
            val queryStr = "$seriesTitle $season Sezon $episode Bölüm"
            val query = java.net.URLEncoder.encode(queryStr, "UTF-8")
            val doc = app.get("https://www.fullhdfilmizlesene.now/arama/$query").document
            
            val targetUrl = doc.selectFirst("li.film a")?.attr("href") ?: return null
            return extractAudioStream(targetUrl)
        } catch (e: Exception) {
            Log.e("AudioModule", "Dizi ses hatası: ${e.message}")
            return null
        }
    }

    // Ortak Oynatıcı / RapidVid ve scx Şifre Çözücü
    private suspend fun extractAudioStream(pageUrl: String?): String? {
        if (pageUrl.isNullOrBlank()) return null
        val fullUrl = if (pageUrl.startsWith("http")) pageUrl else "https://www.fullhdfilmizlesene.now$pageUrl"
        
        val movieDoc = app.get(fullUrl).document
        val scripts = movieDoc.select("script").map { it.data() }
        
        var rapidVidUrl: String? = null
        for (script in scripts) {
            if (script.contains("scx")) {
                val scxJson = org.json.JSONObject(
                    Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                        .find(script)?.groupValues?.get(1) ?: continue
                )
                val tMatch = runCatching {
                    scxJson.getJSONObject("atom").getJSONObject("sx").getJSONArray("t").getString(0)
                }.getOrNull()
                
                if (tMatch != null) {
                    val rotDecoded = String(Base64.decode(
                        tMatch.map { c -> 
                            when(c) {
                                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                                else -> c
                            }
                        }.joinToString(""), Base64.DEFAULT), Charsets.UTF_8).trim()
                    
                    if (rotDecoded.startsWith("http")) {
                        rapidVidUrl = rotDecoded
                        break
                    }
                }
            }
        }
        
        if (rapidVidUrl == null) return null
        
        val rapidRes = app.get(rapidVidUrl, referer = "https://www.fullhdfilmizlesene.now/").text
        val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(rapidRes)?.groupValues?.get(1)
        
        if (p8 != null) {
            val reversed = p8.reversed()
            val tString = Base64.decode(reversed, Base64.DEFAULT)
            val oBuilder = java.lang.StringBuilder()
            val key = "K9L"
            for (index in tString.indices) {
                val bVal = tString[index].toInt() and 0xFF
                val offset = key[index % key.length].code % 5 + 1
                oBuilder.append((bVal - offset).toChar())
            }
            val decodedJsonStr = String(Base64.decode(oBuilder.toString(), Base64.DEFAULT), Charsets.UTF_8)
            val cmUrl = Regex("""["']?cm["']?\s*:\s*["']([^"']+)["']""").find(decodedJsonStr)?.groupValues?.get(1)
            
            if (cmUrl != null && cmUrl.startsWith("http")) {
                return "$cmUrl#.m3u8"
            }
        }
        return null
    }

    // Ses Parçalarını Ekleme (Etiket ataması kaldırıldı, hata giderildi)
    suspend fun applyAudioTrack(
        link: ExtractorLink,
        isMovie: Boolean,
        imdbId: String?,
        movieTitle: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        var audioTrackUrl: String? = null
        if (isMovie) {
            audioTrackUrl = fetchMovieAudio(imdbId, movieTitle)
        } else {
            audioTrackUrl = fetchSeriesAudio(movieTitle, season, episode)
        }

        val newLink = newExtractorLink(link.source, link.name, link.url, link.type) {
            this.quality = link.quality
            this.referer = link.referer
            this.headers = link.headers
            this.extractorData = link.extractorData
            
            if (audioTrackUrl != null) {
                this.audioTracks = listOf(
                    newAudioFile(audioTrackUrl) {
                        this.headers = mapOf(
                            "Origin" to "https://www.fullhdfilmizlesene.now",
                            "Referer" to "https://www.fullhdfilmizlesene.now/"
                        )
                    }
                )
            }
        }
        callback.invoke(newLink)
    }
}
