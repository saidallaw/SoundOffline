package com.said.soundoffline

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val CHUNK = 4L * 1024 * 1024

    /** Saves a small file (thumbnail). Returns null on any failure. */
    fun saveSmall(url: String, out: File): File? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build())
            .execute().use { r ->
                if (!r.isSuccessful) return null
                out.parentFile?.mkdirs()
                out.writeBytes(r.body?.bytes() ?: return null)
                out
            }
    } catch (e: Exception) {
        null
    }

    /**
     * Downloads in 4 MB Range chunks (YouTube throttles / rejects big single requests).
     * Each chunk is retried up to 3 times.
     */
    fun downloadRanged(url: String, out: File, userAgent: String, onProgress: (Float) -> Unit): File {
        val tmp = File(out.path + ".part")
        var pos = 0L
        var total = -1L
        tmp.outputStream().buffered().use { os ->
            while (total < 0 || pos < total) {
                val end = pos + CHUNK - 1
                var lastErr: IOException? = null
                var chunk: ByteArray? = null
                var full = false
                for (attempt in 0 until 3) {
                    try {
                        val req = Request.Builder().url(url)
                            .header("User-Agent", userAgent)
                            .header("Range", "bytes=$pos-$end")
                            .build()
                        client.newCall(req).execute().use { r ->
                            if (r.code == 416) { chunk = ByteArray(0); total = pos; return@use }
                            if (!r.isSuccessful) throw HttpError(r.code)
                            if (r.code == 200) full = true
                            val cr = r.header("Content-Range")
                            if (cr != null && '/' in cr) {
                                cr.substringAfter('/').toLongOrNull()?.let { total = it }
                            }
                            chunk = r.body?.bytes() ?: ByteArray(0)
                        }
                        break
                    } catch (e: IOException) {
                        lastErr = e
                    }
                }
                val data = chunk ?: throw (lastErr ?: IOException("download failed"))
                os.write(data)
                pos += data.size
                if (full) { total = pos }          // server ignored Range and sent everything
                if (data.isEmpty()) break
                if (total > 0) onProgress((pos.toFloat() / total).coerceAtMost(1f))
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw ScException("فشل حفظ الملف")
        return out
    }

    /** YouTube stream URLs from mobile clients expect the matching app user agent. */
    fun youtubeUa(streamUrl: String): String =
        when (streamUrl.toHttpUrlOrNull()?.queryParameter("c")?.uppercase()) {
            "ANDROID", "ANDROID_VR" -> "com.google.android.youtube/19.28.35 (Linux; U; Android 14) gzip"
            "IOS" -> "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
            else -> DESKTOP_UA
        }
}
