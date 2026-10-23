package com.phisher98

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.AudioFile
import android.util.Base64
import android.util.Log

object TorraStreamAudio {

    // IMDb ID ile hedef siteden Türkçe ses linkini bulur
    suspend fun fetchTurkishAudio(imdbId: String?): String? {
        try {
            if (imdbId.isNullOrBlank()) return null
            
            val imdbSearchUrl = "https://www.fullhdfilmizlesene.now/arama/$imdbId"
            val doc = app.get(imdbSearchUrl).document
            val firstResult = doc.selectFirst("li.film a")?.attr("href") ?: return null
            val targetMovieUrl = if (firstResult.startsWith("http")) firstResult else "https://www.fullhdfilmizlesene.now$firstResult"

            val movieDoc = app.get(targetMovieUrl).document
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
        } catch (e: Exception) {
            Log.e("TorraStreamAudio", "Ses çekme hatası: ${e.message}")
        }
        return null
    }

    // Torrent/Debrid linkine Türkçe ses dosyasını enjekte eder
    suspend fun applyAudioTrack(
        link: ExtractorLink,
        imdbId: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val audioTrackUrl = fetchTurkishAudio(imdbId)

        val newLink = newExtractorLink(link.source, link.name, link.url, link.type) {
            this.quality = link.quality
            this.referer = link.referer
            this.headers = link.headers
            this.extractorData = link.extractorData
            
            if (audioTrackUrl != null) {
                this.audioTracks = listOf(
                    AudioFile(
                        url = audioTrackUrl,
                        name = "Türkçe Stereo AAC · FullHDFilm",
                        headers = mapOf(
                            "Origin" to "https://www.fullhdfilmizlesene.now",
                            "Referer" to "https://www.fullhdfilmizlesene.now/"
                        )
                    )
                )
            }
        }
        callback.invoke(newLink)
    }
}
