package com.said.soundoffline

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

object YouTubeClient {
    private val yt: StreamingService get() = ServiceList.YouTube

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

    fun isYouTube(text: String): Boolean {
        val url = Regex("https?://\\S+").find(text)?.value ?: return false
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")
    }

    fun keyFor(url: String): String? = try {
        ensureInit(); "yt:" + yt.streamLHFactory.getId(url)
    } catch (e: Exception) {
        null
    }

    fun resolve(text: String): List<Pending> {
        ensureInit()
        val url = Regex("https?://\\S+").find(text)?.value ?: throw ScException("مفيش رابط في النص ده")
        return when (yt.getLinkTypeByUrl(url)) {
            StreamingService.LinkType.STREAM -> {
                val info = StreamInfo.getInfo(yt, url)
                listOf(pendingFromInfo(info))
            }
            StreamingService.LinkType.PLAYLIST -> playlist(url)
            else -> throw ScException("الرابط لازم يكون فيديو أو قائمة تشغيل")
        }
    }

    private fun pendingFromInfo(info: StreamInfo) = Pending(
        key = "yt:" + info.id,
        title = info.name,
        artist = info.uploaderName ?: "",
        durationMs = info.duration * 1000,
        thumbUrl = bestThumb(info.thumbnails),
        source = "youtube",
        sourceUrl = info.url,
        collection = null,
        order = 0,
        fetch = { dir, p -> fetch(info, dir, p) },
    )

    private fun playlist(url: String): List<Pending> {
        val info = PlaylistInfo.getInfo(yt, url)
        val items = ArrayList<StreamInfoItem>(info.relatedItems)
        var page = info.nextPage
        var guard = 0
        while (page != null && guard++ < 40) {
            val more = PlaylistInfo.getMoreItems(yt, url, page)
            items += more.items
            page = if (more.hasNextPage()) more.nextPage else null
        }
        if (items.isEmpty()) throw ScException("القائمة فاضية")
        val name = info.name
        return items.mapIndexed { i, item ->
            Pending(
                key = keyFor(item.url) ?: ("yt:" + item.url),
                title = item.name,
                artist = item.uploaderName ?: "",
                durationMs = item.duration.coerceAtLeast(0) * 1000,
                thumbUrl = bestThumb(item.thumbnails),
                source = "youtube",
                sourceUrl = item.url,
                collection = name,
                order = i,
                fetch = { dir, p -> fetch(StreamInfo.getInfo(yt, item.url), dir, p) },
            )
        }
    }

    private fun fetch(info: StreamInfo, dir: File, onProgress: (Float) -> Unit): Fetched {
        val audio = pickAudio(info.audioStreams) ?: throw ScException("مفيش صوت متاح للفيديو ده")
        val ext = audio.format?.suffix?.ifBlank { null } ?: "audio"
        dir.mkdirs()
        val url = audio.content
        val file = Http.downloadRanged(url, File(dir, "yt_${info.id}.$ext"), Http.youtubeUa(url), onProgress)
        return Fetched(
            file = file,
            chapters = info.streamSegments.map { Chapter(it.title, it.startTimeSeconds * 1000L) },
            related = info.relatedItems.mapNotNull(::toSuggestion).distinctBy { it.url }.take(15),
            thumbUrl = bestThumb(info.thumbnails),
            durationMs = info.duration * 1000,
        )
    }

    /** Original-language audio, progressive HTTP, M4A preferred, highest bitrate. */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val progressive = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (progressive.isEmpty()) return null
        val original = progressive.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { progressive }
        val m4a = original.filter { it.format == MediaFormat.M4A }
        return (m4a.ifEmpty { original }).maxByOrNull { it.averageBitrate }
    }

    fun search(query: String): List<Suggestion> {
        ensureInit()
        val qh = yt.searchQHFactory.fromQuery(query, emptyList(), "")
        return SearchInfo.getInfo(yt, qh).relatedItems.mapNotNull(::toSuggestion).distinctBy { it.url }
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
                key = keyFor(item.url),
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
