package com.said.soundoffline

import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ScException(msg: String) : IOException(msg)
class HttpError(val code: Int) : IOException("HTTP $code")

data class ScTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val json: JSONObject,
)

/**
 * Talks to SoundCloud's public web API (api-v2) the same way the website does:
 * grabs the web client_id from the site's JS, resolves a page URL to a track/playlist,
 * then downloads the best available stream (progressive MP3 or HLS).
 */
class SoundCloudClient(private val prefs: SharedPreferences) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cachedClientId: String? = prefs.getString(KEY_CLIENT_ID, null)

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun fetchText(url: String): String =
        http.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            r.body?.string() ?: ""
        }

    private fun fetchBytes(url: String): ByteArray {
        var last: IOException? = null
        repeat(3) {
            try {
                return http.newCall(request(url)).execute().use { r ->
                    if (!r.isSuccessful) throw HttpError(r.code)
                    r.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("download failed")
    }

    private fun clientId(refresh: Boolean = false): String {
        if (!refresh) cachedClientId?.let { return it }
        val html = fetchText("https://soundcloud.com/")
        val scripts = Regex("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"")
            .findAll(html).map { it.groupValues[1] }.toList().asReversed()
        val idRegex = Regex("client_id\\s*[:=]\\s*\"?([a-zA-Z0-9]{32})")
        for (src in scripts) {
            val js = try { fetchText(src) } catch (e: IOException) { continue }
            val match = idRegex.find(js) ?: continue
            val id = match.groupValues[1]
            cachedClientId = id
            prefs.edit().putString(KEY_CLIENT_ID, id).apply()
            return id
        }
        throw ScException("مش قادر أوصل لساوند كلاود دلوقتي، جرب تاني")
    }

    /** Calls api-v2 (or a full api URL) with client_id; refreshes client_id once on 401/403. */
    private fun api(pathOrUrl: String): String {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "https://api-v2.soundcloud.com$pathOrUrl"
        val sep = if ('?' in url) '&' else '?'
        var lastError: HttpError? = null
        for (attempt in 0..1) {
            val id = clientId(refresh = attempt == 1)
            try {
                return fetchText("$url${sep}client_id=$id")
            } catch (e: HttpError) {
                if (e.code == 401 || e.code == 403) { lastError = e; continue }
                throw e
            }
        }
        throw lastError ?: ScException("خطأ في الاتصال")
    }

    /** Pulls the first link out of shared text, expands short links, strips tracking params. */
    fun normalizeUrl(input: String): String {
        val found = Regex("https?://\\S+").find(input) ?: throw ScException("مفيش رابط في النص ده")
        var url = found.value.trimEnd('.', ',', ')', '"', '\'')
        val host = url.toHttpUrl().host
        if (host == "on.soundcloud.com" || host.endsWith("goo.gl") || host == "snd.sc") {
            url = http.newCall(request(url)).execute().use { it.request.url.toString() }
        }
        val u = url.toHttpUrl()
        if (!u.host.endsWith("soundcloud.com")) throw ScException("الرابط ده مش من ساوند كلاود")
        val cleanHost = u.host.removePrefix("m.").removePrefix("www.")
        return u.newBuilder().host(cleanHost).query(null).fragment(null).build().toString()
    }

    /** Resolves a track or playlist link to downloadable items. */
    fun resolve(input: String): List<Pending> {
        val url = normalizeUrl(input)
        val obj = try {
            JSONObject(api("/resolve?url=" + URLEncoder.encode(url, "UTF-8")))
        } catch (e: HttpError) {
            if (e.code == 404) throw ScException("المقطع مش موجود أو خاص")
            throw e
        }
        return when (obj.optString("kind")) {
            "track" -> listOf(toPending(toTrack(obj), null, 0))
            "playlist", "system-playlist" -> {
                val name = obj.optString("title").ifBlank { null }
                playlistTracks(obj).mapIndexed { i, t -> toPending(t, name, i) }
            }
            else -> throw ScException("الرابط لازم يكون لمقطع أو بلاي ليست")
        }
    }

    private fun toPending(t: ScTrack, collection: String?, order: Int): Pending {
        val art = (t.json.optString("artwork_url").ifBlank { null }
            ?: t.json.optJSONObject("user")?.optString("avatar_url")?.ifBlank { null })
            ?.replace("-large.", "-t500x500.")
        return Pending(
            key = "sc:${t.id}",
            title = t.title,
            artist = t.artist,
            durationMs = t.durationMs,
            thumbUrl = art,
            source = "soundcloud",
            sourceUrl = t.json.optString("permalink_url"),
            collection = collection,
            order = order,
            fetch = { dir, progress -> Fetched(download(t, dir, progress)) },
        )
    }

    private fun playlistTracks(pl: JSONObject): List<ScTrack> {
        val arr = pl.optJSONArray("tracks") ?: JSONArray()
        val full = LinkedHashMap<Long, JSONObject?>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            full[t.getLong("id")] = if (t.has("media")) t else null
        }
        // Long playlists only return ids for most tracks; fetch the rest in batches.
        val missing = full.filterValues { it == null }.keys.toList()
        for (chunk in missing.chunked(50)) {
            val res = JSONArray(api("/tracks?ids=" + chunk.joinToString(",")))
            for (i in 0 until res.length()) {
                val t = res.getJSONObject(i)
                full[t.getLong("id")] = t
            }
        }
        val tracks = full.values.filterNotNull().map(::toTrack)
        if (tracks.isEmpty()) throw ScException("البلاي ليست فاضية")
        return tracks
    }

    private fun toTrack(o: JSONObject) = ScTrack(
        id = o.getLong("id"),
        title = o.optString("title").ifBlank { "بدون عنوان" },
        artist = o.optJSONObject("user")?.optString("username") ?: "",
        durationMs = o.optLong("full_duration", o.optLong("duration")),
        json = o,
    )

    /** Downloads the track into [dir] and returns the saved file. */
    private fun download(t: ScTrack, dir: File, onProgress: (Float) -> Unit): File {
        val arr = t.json.optJSONObject("media")?.optJSONArray("transcodings")
            ?: throw ScException("مفيش صوت متاح للمقطع ده")
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        val usable = all.filter { !it.optBoolean("snipped") }
        if (usable.isEmpty()) throw ScException("المقطع ده لمشتركي Go+ بس (المتاح معاينة 30 ثانية)")

        fun score(x: JSONObject): Int {
            val f = x.optJSONObject("format")
            val proto = f?.optString("protocol") ?: ""
            val mime = f?.optString("mime_type") ?: ""
            return when {
                proto == "progressive" && mime.contains("mpeg") -> 0
                proto == "progressive" -> 1
                proto == "hls" && mime.contains("mpeg") -> 2
                proto == "hls" && (mime.contains("mp4") || mime.contains("aac")) -> 3
                else -> 9
            }
        }

        val best = usable.minBy(::score)
        if (score(best) == 9) throw ScException("صيغة الصوت دي مش مدعومة")

        var tUrl = best.getString("url")
        val auth = t.json.optString("track_authorization")
        if (auth.isNotEmpty()) tUrl += (if ('?' in tUrl) "&" else "?") + "track_authorization=$auth"
        val streamUrl = JSONObject(api(tUrl)).getString("url")

        val format = best.getJSONObject("format")
        dir.mkdirs()
        return if (format.optString("protocol") == "progressive") {
            downloadFile(streamUrl, File(dir, "${t.id}.mp3"), onProgress)
        } else {
            downloadHls(streamUrl, dir, t.id, format.optString("mime_type"), onProgress)
        }
    }

    private fun downloadFile(url: String, out: File, onProgress: (Float) -> Unit): File {
        val tmp = File(out.path + ".part")
        http.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            val body = r.body ?: throw ScException("رد فاضي من السيرفر")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw ScException("فشل حفظ الملف")
        return out
    }

    /** Downloads all HLS segments and joins them into one file. */
    private fun downloadHls(url: String, dir: File, id: Long, mime: String, onProgress: (Float) -> Unit): File {
        val base = url.toHttpUrl()
        val text = fetchText(url)
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
            initUri != null -> "mp4" // fragmented MP4, gets remuxed later so seeking works
            firstPath.endsWith(".mp3") -> "mp3"
            firstPath.endsWith(".ts") -> "ts"
            firstPath.endsWith(".aac") -> "aac"
            mime.contains("mpeg") -> "mp3"
            else -> "ts"
        }
        val out = File(dir, "$id.$ext")
        val tmp = File(out.path + ".part")
        tmp.outputStream().buffered().use { os ->
            initUri?.let { os.write(fetchBytes(abs(it))) }
            segments.forEachIndexed { i, s ->
                os.write(fetchBytes(abs(s)))
                onProgress((i + 1f) / segments.size)
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw ScException("فشل حفظ الملف")
        return out
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
