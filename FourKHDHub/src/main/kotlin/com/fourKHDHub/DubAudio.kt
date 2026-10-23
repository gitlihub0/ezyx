package com.fourKHDHub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * FullHDFilmizlesene eklentisinden Türkçe dublaj sesini bulur.
 * Diğer eklentiye sınıf olarak değil, uygulamanın kayıt defteri (APIHolder) üzerinden ulaşır.
 */
object DubAudio {
    private const val FHD_NAME = "FullHDFilmizlesene"
    private const val TAG = "DubAudio"
    private const val TIMEOUT_MS = 15_000L

    private val cache = ConcurrentHashMap<Int, List<AudioFile>>()

    /**
     * FHD'nin verdiği linklerden hangisi "ses" olarak kullanılacak?
     * Logcat'te "DubAudio" etiketine bakıp gerekirse burayı daralt/genişlet.
     */
    private fun isDubAudio(link: ExtractorLink): Boolean =
        "cdnimg" in link.url.lowercase()

    private fun norm(s: String): String =
        Normalizer.normalize(s.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    /** TMDB'den Türkçe başlık + orijinal başlık (siteyi aramak için). */
    private suspend fun trTitles(tmdbId: Int): List<String> = try {
        val j = JSONObject(
            app.get(
                "${FourKHDHub.TMDBAPI}/movie/$tmdbId?api_key=${FourKHDHub.TMDB_API_KEY}&language=tr-TR"
            ).text
        )
        listOf(j.optString("title"), j.optString("original_title"))
            .filter { it.isNotBlank() }.distinct()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "TMDB tr başlık hatası: ${e.message}")
        emptyList()
    }

    private suspend fun findPage(fhd: MainAPI, titles: List<String>, year: Int?): String? {
        for (t in titles) {
            val query = t.replace(Regex("[:\\-–,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
            val results = try { fhd.search(query).orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val cands = results.filter { norm(it.name).contains(norm(t)) }
            if (cands.isEmpty()) continue
            if (cands.size == 1 || year == null) return cands.first().url

            // Birden fazla aday varsa yıla göre ayır
            for (c in cands.take(4)) {
                val y = try { fhd.load(c.url)?.year } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (y != null && abs(y - year) <= 1) return c.url
            }
        }
        return null
    }

    /** Film için Türkçe ses listesini döndürür, bulamazsa boş liste (video bozulmaz). */
    suspend fun resolve(tmdbId: Int, year: Int?): List<AudioFile> {
        cache[tmdbId]?.let { return it }

        val fhd = APIHolder.getApiFromNameNull(FHD_NAME)
        if (fhd == null) {
            Log.e(TAG, "$FHD_NAME eklentisi yüklü değil")
            return emptyList()
        }

        val page = findPage(fhd, trTitles(tmdbId), year)
        if (page == null) {
            Log.e(TAG, "FHD'de film bulunamadı (tmdb=$tmdbId)")
            return emptyList()
        }
        Log.d(TAG, "FHD sayfası: $page")

        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        withTimeoutOrNull(TIMEOUT_MS) {
            try {
                fhd.loadLinks(page, false, { }, { found.add(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "FHD loadLinks hatası: ${e.message}")
            }
        }
        found.toList().forEach { Log.d(TAG, "FHD link: ${it.source} | ${it.name} | ${it.type} | ${it.url}") }

        val hasExt = Regex("\\.(m3u8|mpd|mp4|m4a|aac|mp3|mka|ogg|opus)(\\?|#|$)")
        val audio = found.toList()
            .filter(::isDubAudio)
            .distinctBy { it.url }
            .map { l ->
                // Uzantısız HLS ses linklerini oynatıcının tanıması için "#.m3u8" ekle
                val url = if (hasExt.containsMatchIn(l.url.lowercase())) l.url else "${l.url}#.m3u8"
                val h = HashMap<String, String>(l.headers)
                if (l.referer.isNotBlank() && !h.containsKey("Referer")) h["Referer"] = l.referer
                newAudioFile(url) { this.headers = h }
            }

        if (audio.isNotEmpty()) cache[tmdbId] = audio
        Log.d(TAG, "Bulunan dublaj ses sayısı: ${audio.size}")
        return audio
    }
}
