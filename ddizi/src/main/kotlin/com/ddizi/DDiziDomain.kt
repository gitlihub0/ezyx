package com.ddizi

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DDiziDomain {
    const val PREF_KEY   = "ddizi_manual_domain"
    const val DEFAULT    = "https://www.ddizi.tel"
    const val CONFIG_URL =
        "https://raw.githubusercontent.com/gitlihub0/domainListesi/refs/heads/main/eklenti_domainleri.txt"

    @Volatile var githubDomain: String? = null

    data class FetchResult(val domain: String?, val error: String?)

    fun manual(): String? = getKey<String>(PREF_KEY)?.takeIf { it.isNotBlank() }

    fun current(): String = manual() ?: githubDomain ?: DEFAULT

    fun normalize(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http")) url = "https://$url"
        return url
    }

    /** JSON, "ddizi=url", "ddizi: url" veya tek satır url biçimlerini çözer. */
    fun parse(text: String): String? {
        val t = text.trim().removePrefix("\uFEFF")

        try {
            JSONObject(t).optString("ddizi").takeIf { it.isNotBlank() }
                ?.let { return normalize(it) }
        } catch (e: Exception) { /* JSON değil, devam */ }

        val m = Regex(
            """["']?ddizi["']?\s*[:=]\s*["']?([^\s"',]+)""",
            RegexOption.IGNORE_CASE
        ).find(t)
        if (m != null) return normalize(m.groupValues[1])

        if (!t.contains("\n") && t.startsWith("http")) return normalize(t)
        return null
    }

    /** Arka plan thread'inde çağır (ayar ekranı için). */
    fun fetchBlocking(): FetchResult {
        return try {
            val conn = URL("$CONFIG_URL?t=${System.currentTimeMillis()}")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return FetchResult(null, "HTTP $code (dosya/repo bulunamadı veya private olabilir)")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val domain = parse(body)
            if (domain != null) {
                githubDomain = domain
                FetchResult(domain, null)
            } else {
                FetchResult(null, "Dosyada 'ddizi' domaini bulunamadı. İçerik: ${body.take(80)}")
            }
        } catch (e: Exception) {
            FetchResult(null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** null = ulaşılabilir, aksi halde hata metni. Arka plan thread'inde çağır. */
    fun checkBlocking(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = conn.responseCode
        conn.disconnect()
        if (code < 500) null else "HTTP $code"
    } catch (e: Exception) {
        e.javaClass.simpleName
    }
}
