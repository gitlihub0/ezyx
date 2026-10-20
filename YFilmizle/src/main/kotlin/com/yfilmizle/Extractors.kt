package com.yfilmizle

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class HotStream : ExtractorApi() {
    override val name            = "HotStream"
    override val mainUrl         = "https://hotstream.club"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: "https://yfilmizle.com/"

        val page = app.get(url, referer = extRef).text

        val match = Regex("""bePlayer\('([^']+)',\s*'(\{.*?\})'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(page) ?: throw ErrorLoadingException("bePlayer verisi bulunamadı")
        val password = match.groupValues[1]
        val blob     = JSONObject(match.groupValues[2])

        val json  = JSONObject(decrypt(password, blob))
        val video = json.optString("video_location").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("video_location bulunamadı")

        // Seyret8.top sunucusunun 404/CORS engeline takılmamak için gereken başlıklar
        val headers = mapOf(
            "Origin"  to "https://hotstream.club",
            "Referer" to "https://hotstream.club/",
            "Accept"  to "*/*"
        )

        // M3u8Helper, ana M3U8 listesini tarar ve içindeki tüm çözünürlükleri (1080p, 720p vb.)
        // ayrı ayrı ExtractorLink olarak callback'e iletir.
        M3u8Helper.generateM3u8(
            this.name,
            video,
            "https://hotstream.club/",
            headers = headers
        ).forEach { link ->
            callback.invoke(link)
        }
    }

    // ---- CryptoJS uyumlu AES çözme (OpenSSL EVP_BytesToKey, MD5)
    private fun decrypt(password: String, blob: JSONObject): String {
        val ct   = Base64.decode(blob.getString("ct"), Base64.DEFAULT)
        val salt = hex(blob.getString("s"))
        val iv   = hex(blob.getString("iv"))

        val keyIv = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt, 48)
        val key   = SecretKeySpec(keyIv.copyOfRange(0, 32), "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        var block = ByteArray(0)
        var out   = ByteArray(0)
        while (out.size < length) {
            md.reset()
            md.update(block)
            md.update(password)
            md.update(salt)
            block = md.digest()
            out  += block
        }
        return out.copyOf(length)
    }
}
