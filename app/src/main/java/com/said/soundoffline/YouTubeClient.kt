package com.said.soundoffline

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File

/** OkHttp bridge that NewPipeExtractor uses for all its requests. */
private class NpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", Http.DESKTOP_UA)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        return Http.client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            Response(
                r.code, r.message, r.headers.toMultimap(),
                r.body?.string(), r.request.url.toString(),
            )
        }
    }
}

/** YouTube + SoundCloud via NewPipeExtractor: resolve links, download audio, search. */
object Media {
    private val yt: StreamingService get() = ServiceList.YouTube
    private val sc: StreamingService get() = ServiceList.SoundCloud

    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(NpDownloader(), Localization("ar", "EG"), ContentCountry("EG"))
                initialized = true
            }
        }
    }

    private fun isSoundCloud(s: StreamingService) = s.serviceId == sc.serviceId
    private fun sourceName(s: StreamingService) = if (isSoundCloud(s)) "soundcloud" else "youtube"
    private fun prefix(s: StreamingService) = if (isSoundCloud(s)) "sc:" else "yt:"

    private fun serviceFor(url: String): StreamingService? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return when {
            host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com") -> yt
            host.endsWith("soundcloud.com") || host == "snd.sc" || host.endsWith("goo.gl") -> sc
            else -> null
        }
    }

    private fun keyFor(s: StreamingService, url: String): String? = try {
        prefix(s) + s.streamLHFactory.getId(url)
    } catch (e: Exception) {
        null
    }

    /** SoundCloud share links (on.soundcloud.com/…) redirect to the real page; tracking params removed. */
    private fun cleanSoundCloudUrl(url: String): String {
        var u = url
        val host = u.toHttpUrl().host
        if (host == "on.soundcloud.com" || host.endsWith("goo.gl") || host == "snd.sc") {
            u = Http.client.newCall(
                okhttp3.Request.Builder().url(u).header("User-Agent", Http.DESKTOP_UA).build()
            ).execute().use { it.request.url.toString() }
        }
        val parsed = u.toHttpUrl()
        val cleanHost = parsed.host.removePrefix("m.").removePrefix("www.")
        return parsed.newBuilder().host(cleanHost).query(null).fragment(null).build().toString()
    }

    fun resolve(text: String): List<Pending> {
        ensureInit()
        var url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
            ?: throw ScException("مفيش رابط في النص ده")
        val service = serviceFor(url) ?: throw ScException("الرابط لازم يكون من يوتيوب أو ساوند كلاود")
        if (isSoundCloud(service)) url = cleanSoundCloudUrl(url)
        return when (service.getLinkTypeByUrl(url)) {
            StreamingService.LinkType.STREAM -> {
                val info = StreamInfo.getInfo(service, url)
                listOf(pendingFromInfo(service, info))
            }
            StreamingService.LinkType.PLAYLIST -> playlist(service, url)
            else -> throw ScException("الرابط لازم يكون لمقطع/فيديو أو قائمة تشغيل")
        }
    }

    private fun pendingFromInfo(s: StreamingService, info: StreamInfo) = Pending(
        key = prefix(s) + info.id,
        title = info.name,
        artist = info.uploaderName ?: "",
        durationMs = info.duration * 1000,
        thumbUrl = bestThumb(info.thumbnails),
        source = sourceName(s),
        sourceUrl = info.url,
        collection = null,
        order = 0,
        fetch = { dir, p -> fetch(s, info, dir, p) },
    )

    private fun playlist(s: StreamingService, url: String): List<Pending> {
        val info = PlaylistInfo.getInfo(s, url)
        val items = ArrayList<StreamInfoItem>(info.relatedItems)
        var page = info.nextPage
        var guard = 0
        while (page != null && guard++ < 40) {
            val more = PlaylistInfo.getMoreItems(s, url, page)
            items += more.items
            page = if (more.hasNextPage()) more.nextPage else null
        }
        if (items.isEmpty()) throw ScException("القائمة فاضية")
        val name = info.name
        return items.mapIndexed { i, item ->
            Pending(
                key = keyFor(s, item.url) ?: (prefix(s) + item.url),
                title = item.name,
                artist = item.uploaderName ?: "",
                durationMs = item.duration.coerceAtLeast(0) * 1000,
                thumbUrl = bestThumb(item.thumbnails),
                source = sourceName(s),
                sourceUrl = item.url,
                collection = name,
                order = i,
                fetch = { dir, p -> fetch(s, StreamInfo.getInfo(s, item.url), dir, p) },
            )
        }
    }

    private fun fetch(s: StreamingService, info: StreamInfo, dir: File, onProgress: (Float) -> Unit): Fetched {
        val audio = pickAudio(info.audioStreams) ?: throw ScException("مفيش صوت متاح للمقطع ده")
        dir.mkdirs()
        val url = audio.content
        val base = (if (isSoundCloud(s)) "sc_" else "yt_") + info.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val ua = if (isSoundCloud(s)) Http.DESKTOP_UA else Http.youtubeUa(url)
        val file = if (audio.deliveryMethod == DeliveryMethod.HLS) {
            Http.downloadHls(url, dir, base, audio.format?.suffix, ua, onProgress)
        } else {
            val ext = audio.format?.suffix?.ifBlank { null } ?: "audio"
            Http.downloadRanged(url, File(dir, "$base.$ext"), ua, onProgress)
        }
        return Fetched(
            file = file,
            chapters = info.streamSegments.map { Chapter(it.title, it.startTimeSeconds * 1000L) },
            related = info.relatedItems.mapNotNull(::toSuggestion).distinctBy { it.url }.take(15),
            thumbUrl = bestThumb(info.thumbnails),
            durationMs = info.duration * 1000,
        )
    }

    private val FORMAT_ORDER = listOf(MediaFormat.M4A, MediaFormat.MP3, MediaFormat.WEBMA, MediaFormat.OPUS)

    private fun formatRank(s: AudioStream): Int =
        FORMAT_ORDER.indexOf(s.format).let { if (it < 0) FORMAT_ORDER.size else it }

    /** Original-language audio; direct download preferred over HLS; M4A/MP3 preferred; highest bitrate. */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.HLS
        }
        if (usable.isEmpty()) return null
        val original = usable.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { usable }
        val progressive = original.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pool = progressive.ifEmpty { original }
        return pool.sortedWith(compareBy<AudioStream> { formatRank(it) }.thenByDescending { it.averageBitrate })
            .firstOrNull()
    }

    fun search(query: String, soundCloud: Boolean): List<Suggestion> {
        ensureInit()
        val s = if (soundCloud) sc else yt
        val qh = s.searchQHFactory.fromQuery(query, emptyList(), "")
        return SearchInfo.getInfo(s, qh).relatedItems.mapNotNull(::toSuggestion).distinctBy { it.url }
    }

    private fun toSuggestion(item: InfoItem): Suggestion? = when (item) {
        is StreamInfoItem -> {
            if (item.streamType == StreamType.LIVE_STREAM || item.isShortFormContent) null
            else Suggestion(
                url = item.url,
                title = item.name,
                artist = item.uploaderName ?: "",
                durationMs = item.duration.coerceAtLeast(0) * 1000,
                thumbUrl = bestThumb(item.thumbnails),
                key = serviceFor(item.url)?.let { keyFor(it, item.url) },
            )
        }
        is PlaylistInfoItem -> Suggestion(
            url = item.url,
            title = item.name,
            artist = item.uploaderName ?: "",
            durationMs = 0,
            thumbUrl = bestThumb(item.thumbnails),
            isPlaylist = true,
            count = item.streamCount,
        )
        else -> null
    }

    private fun bestThumb(images: List<Image>?): String? =
        images?.filter { it.height in 1..720 }?.maxByOrNull { it.height }?.url
            ?: images?.lastOrNull()?.url
}
