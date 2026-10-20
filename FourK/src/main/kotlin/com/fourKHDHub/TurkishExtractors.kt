package com.fourKHDHub

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class TurboImgz : ExtractorApi() {
    override val name            = "TurboImgz"
    override val mainUrl         = "https://turbo.imgz.me"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val realUrl  = if (url.contains("||")) url.substringAfter("||") else url
        val videoReq = try { app.get(realUrl, referer=extRef).text } catch(e:Exception) { return }
        val videoLink = Regex("""file:\s*"([^"]+)"""").find(videoReq)?.groupValues?.get(1)
            ?: Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(videoReq)?.groupValues?.get(1)?.replace("\\", "")
            ?: return

        callback.invoke(
            newExtractorLink(this.name, this.name, videoLink, ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value; headers = mapOf("Referer" to extRef)
            }
        )
    }
}

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val res = try { app.get(url, referer=extRef) } catch (e: Exception) { return }
        val script = res.document.select("script").find { it.data().contains("jwSetup.sources") }?.data() ?: ""

        if (script.isEmpty()) {
            val p8 = Regex("""_p8\s*=\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
            if (p8 != null) {
                try {
                    val json = ObjectMapper().readTree(decodeSecret(p8))
                    val sources = listOf("cm" to json.path("cm").asText("")).filter { it.second.startsWith("http") }
                    for ((_, u) in sources) {
                        callback.invoke(newExtractorLink(this.name, this.name, u, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"; this.quality = Qualities.Unknown.value
                        })
                    }
                } catch (e: Exception) {}
            }
            return
        }

        val jwSetup = script.substringAfter("jwSetup.sources =").substringBefore(";")
        val match = Regex("""av\('([^']+)'\)""").find(jwSetup)
        val m3Url: String? = if (match != null) {
            try { decodeSecret(match.groupValues[1]) } catch (e: Exception) { null }
        } else {
            Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").find(jwSetup)?.groupValues?.get(1)?.replace("\\", "")?.takeIf { it.startsWith("http") }
        }

        if (m3Url.isNullOrBlank()) return
        callback.invoke(newExtractorLink(this.name, this.name, m3Url, ExtractorLinkType.M3U8) {
            this.referer = mainUrl; this.quality = Qualities.Unknown.value
        })
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
