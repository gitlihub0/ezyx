package com.fourKHDHub

import android.util.Base64
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

fun buildVirtualM3u8(videoUrl: String, audioUrl: String, audioLanguage: String = "Türkçe Dublaj"): String {
    val m3u8Content = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-trk",NAME="$audioLanguage",DEFAULT=YES,AUTOSELECT=YES,URI="$audioUrl"
        #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,AUDIO="audio-trk"
        $videoUrl
    """.trimIndent()
    
    val base64M3u8 = Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)
    return "data:application/x-mpegURL;base64,$base64M3u8"
}

fun createHybridExtractorLink(
    sourceName: String,
    videoUrl: String,
    audioUrl: String,
    quality: Int = Qualities.P2160.value
): ExtractorLink {
    return ExtractorLink(
        source = sourceName,
        name = "$sourceName + TR Dublaj",
        url = buildVirtualM3u8(videoUrl, audioUrl),
        referer = "",
        quality = quality,
        type = ExtractorLinkType.M3U8,
        headers = mapOf()
    )
}
