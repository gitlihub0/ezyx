package com.fourKHDHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newAudioFile
import android.util.Base64
import android.util.Log

object FourKHDHubAudio {

    // Türkçe ses bağlantısını otomatik bulup #.m3u8 ekleyen ana fonksiyon
    suspend fun fetchTurkishAudio(movieTitle: String): String? {
        try {
            val query = java.net.URLEncoder.encode(movieTitle, "UTF-8")
            val searchUrl = "https://www.fullhdfilmizlesene.now/arama/$query"
            val doc = app.get(searchUrl).document
            
            val firstResult = doc.selectFirst("li.film a")?.attr("href") ?: return null
            val movieUrl = if (firstResult.startsWith("http")) firstResult else "https://www.fullhdfilmizlesene.now$firstResult"
            
            val movieDoc = app.get(movieUrl).document
            val scripts = movieDoc.select("script").map { it.data() }
            
            var rapidVidUrl: String? = null
            for (script in scripts) {
                if (script.contains("scx")) {
                    val scxJson = Regex("""scx\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1) ?: continue
                    val tMatch = Regex("""\["([^"]+)"\]""").find(scxJson)?.groupValues?.get(1)
                    
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
                    val offset = key[index % key.length].code % 5 + 1
                    oBuilder.append((tString[index].code - offset).toChar())
                }
                val decodedJsonStr = String(Base64.decode(oBuilder.toString(), Base64.DEFAULT), Charsets.UTF_8)
                val cmUrl = Regex("""["']?cm["']?\s*:\s*["']([^"']+)["']""").find(decodedJsonStr)?.groupValues?.get(1)
                
                if (cmUrl != null && cmUrl.startsWith("http")) {
                    return "$cmUrl#.m3u8"
                }
            }
        } catch (e: Exception) {
            Log.e("AudioModule", "Ses çekme hatası: ${e.message}")
        }
        return null
    }

    // Video ExtractorLink nesnesine harici sesi bağlayan sarmalayıcı (wrapper)
    suspend fun applyAudioTrack(
        link: ExtractorLink,
        isMovie: Boolean,
        movieTitle: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        var audioTrackUrl: String? = null
        if (isMovie && !movieTitle.isNullOrBlank()) {
            audioTrackUrl = fetchTurkishAudio(movieTitle)
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
