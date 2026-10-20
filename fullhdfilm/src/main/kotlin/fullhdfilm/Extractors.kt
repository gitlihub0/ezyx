package com.fullhdfilm

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ==========================================
// TURKEY PLAYER
// ==========================================
open class TurkeyPlayer : ExtractorApi() {
    override val name            = "TurkeyPlayer"
    override val mainUrl         = "https://watch.turkeyplayer.com/"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        val pageContent = app.get(url, referer = extRef).text

        val videoJsonRaw = Regex("""var\s+video\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(pageContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Video JSON bulunamadı")

        val videoData = mapper.readValue<VideoData>(videoJsonRaw)
        val masterUrl = "https://watch.turkeyplayer.com/m3u8/8/${videoData.md5}/master.txt?s=1&id=${videoData.id}&cache=1"
        
        callback.invoke(
            newExtractorLink(this.name, this.name, masterUrl, ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to extRef)
            }
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoData(
        @JsonProperty("id") val id: String,
        @JsonProperty("md5") val md5: String
    )
}

// ==========================================
// VIDMOXY
// ==========================================
open class VidMoxy : ExtractorApi() {
    override val name            = "VidMoxy"
    override val mainUrl         = "https://vidmoxy.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val videoReq = app.get(url, referer=extRef).text
        val subUrls = mutableSetOf<String>()

        Regex("""captions","file":"([^"]+)","label":"([^"]+)"""").findAll(videoReq).forEach {
            val (subUrl, subLang) = it.destructured
            if (subUrl !in subUrls) {
                subUrls.add(subUrl)
                val langClean = subLang.replace("\\u0131", "ı").replace("\\u0130", "İ")
                    .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
                subtitleCallback.invoke(newSubtitleFile(langClean, fixUrl(subUrl.replace("\\", ""))))
            }
        }

        var extractedValue  = Regex("""file": "(.*)",""").find(videoReq)?.groupValues?.get(1)
        val decoded: String?

        if (extractedValue != null) {
            val bytes = extractedValue.split("\\x").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            decoded   = String(bytes, Charsets.UTF_8)
        } else {
            val evaljwSetup = Regex("""\};\s*(eval\(function[\s\S]*?)var played = \d+;""").find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("File not found")
            val jwSetup     = getAndUnpack(getAndUnpack(evaljwSetup)).replace("\\\\", "\\")
            extractedValue  = Regex("""file":"(.*)","label""").find(jwSetup)?.groupValues?.get(1)?.replace("\\\\x", "")
            val bytes = extractedValue?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            decoded   = bytes?.toString(Charsets.UTF_8) ?: throw ErrorLoadingException("File not found")
        }

        callback.invoke(
            newExtractorLink(this.name, this.name, decoded, ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to extRef)
            }
        )
    }
}

// ==========================================
// RAPIDVID
// ==========================================
open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val res = try {
            app.get(url, referer=extRef)
        } catch (e: Exception) {
            Log.e("K_FHD", "RapidVid: Sayfa açılamadı ($url) - $e")
            return
        }
        val videoReq = res.document
        val script = videoReq.select("script").find { it.data().contains("jwSetup.sources") }?.data() ?: ""

        // YENİ SÜRÜM (vx / atom player): veri jwSetup yerine window._p8 içinde şifreli geliyor
        if (script.isEmpty()) {
            val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
            if (p8 != null) {
                handleNewPlayer(p8, subtitleCallback, callback)
                return
            }
        }

        if (script.isEmpty()) {
            Log.e("K_FHD", "RapidVid: jwSetup.sources veya _p8 bulunamadı ($url)")
            return
        }

        // Altyazılar
        try {
            if (script.contains("jwSetup.tracks")) {
                val jwTrack = script.substringAfter("jwSetup.tracks =").substringBefore(";")
                val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                val tracks: List<CaptionData> = objectMapper.readValue(jwTrack)

                tracks.forEach { track ->
                    val lang = track.label?.replace("\\u0131", "ı")?.replace("\\u0130", "İ")?.replace("\\u00fc", "ü")?.replace("\\u00e7", "ç")
                    val subUrl = track.file.replace("\\", "")
                    if (lang != null) subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                }
            }
        } catch (e: Exception) {
            Log.e("K_FHD", "RapidVid altyazı hatası: $e")
        }

        val jwSetup = script.substringAfter("jwSetup.sources =").substringBefore(";")
        val match = Regex("""av\('([^']+)'\)""").find(jwSetup)

        val m3Url: String? = if (match != null) {
            try {
                decodeSecret(match.groupValues[1])
            } catch (e: Exception) {
                Log.e("K_FHD", "RapidVid decodeSecret hatası: $e")
                null
            }
        } else {
            Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").find(jwSetup)
                ?.groupValues?.get(1)?.replace("\\", "")?.takeIf { it.startsWith("http") }
        }

        if (m3Url.isNullOrBlank()) {
            Log.e("K_FHD", "RapidVid: m3u8 linki çıkarılamadı ($url)")
            return
        }

        callback.invoke(
            newExtractorLink(this.name, this.name, m3Url, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun handleNewPlayer(
        p8: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val json = try {
            ObjectMapper().readTree(decodeSecret(p8))
        } catch (e: Exception) {
            Log.e("K_FHD", "RapidVid p8 hatası: $e")
            return
        }

        try {
            json.path("ct").forEach { t ->
                val file  = t.path("file").asText("")
                val label = t.path("label").asText("").trim()
                if (file.startsWith("http") && label.isNotEmpty()) {
                    subtitleCallback.invoke(newSubtitleFile(label, file))
                }
            }
        } catch (e: Exception) {
            Log.e("K_FHD", "RapidVid p8 altyazı hatası: $e")
        }

        val sources = listOf(
            "cm" to json.path("cm").asText(""),
         // "tm" to json.path("tm").asText("")   // AV1 - şimdilik kapalı
        ).filter { it.second.startsWith("http") }

        if (sources.isEmpty()) {
            return
        }

        var added = 0
        for ((tag, u) in sources) {
            val ok = try {
                val r = app.get(u, referer = "$mainUrl/")
                r.code == 200 && r.text.trimStart().startsWith("#EXTM3U")
            } catch (e: Exception) {
                false
            }
            if (ok) {
                val label = if (tag == "cm") this.name else "${this.name} (AV1)"
                callback.invoke(
                    newExtractorLink(this.name, label, u, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                added++
            }
        }
        if (added == 0) Log.e("K_FHD", "RapidVid: cm/tm geçerli HLS döndürmedi")
    }

    private fun decodeSecret(encodedString: String): String {
        val reversedBase64Input = encodedString.reversed()
        val tString = base64Decode(reversedBase64Input)
        val oBuilder = StringBuilder()
        val key = "K9L"
        for (index in tString.indices) {
            val offset = key[index % key.length].code % 5 + 1
            oBuilder.append((tString[index].code - offset).toChar())
        }
        return base64Decode(oBuilder.toString())
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaptionData(
    @JsonProperty("kind") var kind: String? = null,
    @JsonProperty("file") var file: String,
    @JsonProperty("label") var label: String? = null,
)

// ==========================================
// SOBREATSESUYP
// ==========================================
open class Sobreatsesuyp : ExtractorApi() {
    override val name            = "Sobreatsesuyp"
    override val mainUrl         = "https://sobreatsesuyp.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        val videoReq = app.get(url, referer = extRef).text
        val file     = Regex("""file":"([^"]+)""").find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("File not found")
        
        val postLink = "${mainUrl}/" + file.replace("\\", "")
        val rawList  = app.post(postLink, referer = extRef).parsedSafe<List<Any>>() ?: throw ErrorLoadingException("Post link not found")

        val postJson = rawList.drop(1).mapNotNull { item ->
            val mapItem = item as? Map<*, *> ?: return@mapNotNull null
            SobreatsesuypVideoData(mapItem["title"] as? String, mapItem["file"] as? String)
        }

        for (item in postJson) {
            if (item.file == null || item.title == null) continue
            try {
                val videoData = app.post("${mainUrl}/playlist/${item.file.substring(1)}.txt", referer = extRef).text
                callback.invoke(
                    newExtractorLink(this.name, "${this.name} - ${item.title}", videoData, INFER_TYPE) {
                        quality = Qualities.Unknown.value
                        headers = mapOf("Referer" to extRef)
                    }
                )
            } catch (e: Exception) {
                Log.e("K_FHD", "Sobreatsesuyp hatası: $e")
            }
        }
    }

    data class SobreatsesuypVideoData(@JsonProperty("title") val title: String? = null, @JsonProperty("file") val file: String? = null)
}

// ==========================================
// TRsTX
// ==========================================
open class TRsTX : ExtractorApi() {
    override val name            = "TRsTX"
    override val mainUrl         = "https://trstx.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        val videoReq = app.get(url, referer=extRef).text
        val file     = Regex("""file":"([^"]+)""").find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("File not found")
        
        val postLink = "${mainUrl}/" + file.replace("\\", "")
        val rawList  = app.post(postLink, referer=extRef).parsedSafe<List<Any>>() ?: throw ErrorLoadingException("Post link not found")

        val postJson = rawList.drop(1).mapNotNull { item ->
            val mapItem = item as? Map<*, *> ?: return@mapNotNull null
            TrstxVideoData(mapItem["title"] as? String, mapItem["file"] as? String)
        }

        val vidLinks = mutableSetOf<String>()
        for (item in postJson) {
            if (item.file == null || item.title == null) continue
            try {
                val fileUrl   = "${mainUrl}/playlist/" + item.file.substring(1) + ".txt"
                val videoData = app.post(fileUrl, referer=extRef).text

                if (videoData in vidLinks) continue
                vidLinks.add(videoData)

                callback.invoke(
                    newExtractorLink(this.name, "${this.name} - ${item.title}", videoData, INFER_TYPE) {
                        quality = Qualities.Unknown.value
                        headers = mapOf("Referer" to extRef)
                    }
                )
            } catch (e: Exception) {
                Log.e("K_FHD", "TRsTX hatası: $e")
            }
        }
    }

    data class TrstxVideoData(@JsonProperty("title") val title: String? = null, @JsonProperty("file") val file: String? = null)
}

// ==========================================
// TURBOIMGZ
// ==========================================
open class TurboImgz : ExtractorApi() {
    override val name            = "TurboImgz"
    override val mainUrl         = "https://turbo.imgz.me"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val label    = if (url.contains("||")) url.substringBefore("||").uppercase() else "TURBO"
        val realUrl  = if (url.contains("||")) url.substringAfter("||") else url

        val videoReq = app.get(realUrl, referer=extRef).text
        val videoLink = Regex("""file:\s*"([^"]+)"""").find(videoReq)?.groupValues?.get(1)
            ?: Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(videoReq)?.groupValues?.get(1)?.replace("\\", "")
            ?: throw ErrorLoadingException("File not found")

        callback.invoke(
            newExtractorLink(
                "${this.name} - $label",
                "${this.name} - $label",
                videoLink,
                ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to extRef)
            }
        )
    }
}
