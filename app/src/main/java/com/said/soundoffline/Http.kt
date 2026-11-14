package com.said.soundoffline

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ScException(msg: String) : IOException(msg)
class HttpError(val code: Int) : IOException("HTTP $code")

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

    private fun fetchBytes(url: String, ua: String): ByteArray {
        var last: IOException? = null
        repeat(3) {
            try {
                return client.newCall(Request.Builder().url(url).header("User-Agent", ua).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) throw HttpError(r.code)
                        r.body?.bytes() ?: ByteArray(0)
                    }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("download failed")
    }

    /** Downloads every HLS segment and joins them into one file. Returns the saved file. */
    fun downloadHls(
        url: String, dir: File, baseName: String, suffix: String?, ua: String, onProgress: (Float) -> Unit,
    ): File {
        val base = url.toHttpUrlOrNull() ?: throw ScException("رابط الصوت مش صحيح")
        var text = String(fetchBytes(url, ua))
        // Master playlist -> pick the first variant
        if ("#EXT-X-STREAM-INF" in text) {
            val variant = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw ScException("ملف التشغيل فاضي")
            return downloadHls(base.resolve(variant)?.toString() ?: variant, dir, baseName, suffix, ua, onProgress)
        }
        var initUri: String? = null
        val segments = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-MAP")) {
                initUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                segments += line
            }
        }
        if (segments.isEmpty()) throw ScException("ملف التشغيل فاضي")
        fun abs(u: String) = base.resolve(u)?.toString() ?: u
        val firstPath = segments[0].substringBefore('?').lowercase()
        val ext = when {
            initUri != null -> "mp4" // fragmented MP4 -> remuxed afterwards so seeking works
            firstPath.endsWith(".mp3") -> "mp3"
            firstPath.endsWith(".ts") -> "ts"
            firstPath.endsWith(".aac") -> "aac"
            firstPath.endsWith(".opus") || firstPath.endsWith(".ogg") -> "opus"
            !suffix.isNullOrBlank() -> suffix
            else -> "audio"
        }
        val out = File(dir, "$baseName.$ext")
        val tmp = File(out.path + ".part")
        tmp.outputStream().buffered().use { os ->
            initUri?.let { os.write(fetchBytes(abs(it), ua)) }
            segments.forEachIndexed { i, seg ->
                os.write(fetchBytes(abs(seg), ua))
                onProgress((i + 1f) / segments.size)
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
