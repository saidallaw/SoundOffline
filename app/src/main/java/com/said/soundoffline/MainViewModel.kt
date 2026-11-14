package com.said.soundoffline

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.net.UnknownHostException
import kotlin.coroutines.resume

val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f)
val SLEEP_OPTIONS = listOf(15, 30, 45, 60)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("soundoffline", 0)
    private val library = Library(app)
    private val sc = SoundCloudClient(prefs)

    // ---------- library ----------
    var tracks by mutableStateOf(library.load())
        private set
    val positions = mutableStateMapOf<String, Long>()
    val done = mutableStateMapOf<String, Boolean>()
    val lastPlayed = mutableStateMapOf<String, Long>()

    // ---------- downloads ----------
    var input by mutableStateOf("")
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var queued by mutableIntStateOf(0)
        private set
    val progress = MutableStateFlow<Float?>(null)
    val pendingUrls = mutableStateListOf<String>()
    private val queue = ArrayDeque<String>()

    // ---------- suggestions ----------
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Suggestion>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    // ---------- player ----------
    var speed by mutableFloatStateOf(prefs.getFloat("speed", 1f))
        private set
    var currentKey by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var sleepLabel by mutableStateOf<String?>(null)
        private set
    private var sleepAtWall: Long? = null
    private var sleepAtPos: Long? = null
    private var sleepKey: String? = null

    val currentTrack: Track? get() = tracks.find { it.key == currentKey }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) currentKey?.let(::markDone)
            currentKey = mediaItem?.mediaId
            currentKey?.let(::touch)
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (!playing) savePosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) currentKey?.let(::markDone)
        }
    }

    init {
        for ((k, v) in prefs.all) {
            when {
                k.startsWith("pos_") && v is Long -> positions[k.removePrefix("pos_")] = v
                k.startsWith("done_") && v == true -> done[k.removePrefix("done_")] = true
                k.startsWith("last_") && v is Long -> lastPlayed[k.removePrefix("last_")] = v
            }
        }
        connect()
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                val c = controller ?: continue
                position = c.currentPosition
                duration = if (c.duration == C.TIME_UNSET) 0 else c.duration
                if (++tick % 10 == 0 && c.isPlaying) savePosition()
                checkSleep(c)
            }
        }
    }

    private fun connect() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            c.setPlaybackSpeed(speed)
            currentKey = c.currentMediaItem?.mediaId
            isPlaying = c.isPlaying
        }, ContextCompat.getMainExecutor(app))
    }

    // ---------- per-track progress ----------

    private fun savePosition() {
        val c = controller ?: return
        val key = currentKey ?: return
        if (c.currentMediaItem?.mediaId != key || c.playbackState == Player.STATE_ENDED) return
        positions[key] = c.currentPosition
        prefs.edit().putLong("pos_$key", c.currentPosition).apply()
    }

    private fun markDone(key: String) {
        done[key] = true
        positions.remove(key)
        prefs.edit().putBoolean("done_$key", true).remove("pos_$key").apply()
    }

    private fun touch(key: String) {
        val now = System.currentTimeMillis()
        lastPlayed[key] = now
        prefs.edit().putLong("last_$key", now).apply()
    }

    /** 0..1 listened fraction. */
    fun fraction(t: Track): Float {
        if (done[t.key] == true) return 1f
        val p = if (t.key == currentKey) position else positions[t.key] ?: 0L
        return if (t.durationMs > 0) (p.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
    }

    /** Most recently played item that isn't finished. */
    val continueTrack: Track?
        get() = tracks.filter { done[it.key] != true && lastPlayed.containsKey(it.key) }
            .maxByOrNull { lastPlayed[it.key] ?: 0L }

    /** Mix of "related" videos from recent downloads, minus what's already downloaded. */
    val recommended: List<Suggestion>
        get() {
            val have = tracks.map { it.key }.toSet()
            val sources = tracks.filter { it.related.isNotEmpty() }
                .sortedByDescending { lastPlayed[it.key] ?: it.addedAt }
                .take(6).map { it.related }
            val seen = HashSet<String>()
            val out = ArrayList<Suggestion>()
            for (i in 0 until 15) for (list in sources) {
                val s = list.getOrNull(i) ?: continue
                if ((s.key == null || s.key !in have) && seen.add(s.url)) out += s
            }
            return out.take(30)
        }

    fun isDownloaded(s: Suggestion) = s.key != null && tracks.any { it.key == s.key }

    // ---------- playback ----------

    private fun mediaItem(t: Track): MediaItem {
        val uri = Uri.fromFile(File(t.path))
        return MediaItem.Builder()
            .setMediaId(t.key)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.collection)
                    .setArtworkUri(t.thumbPath?.let { Uri.fromFile(File(it)) })
                    .build()
            )
            .build()
    }

    fun collectionTracks(name: String) = tracks.filter { it.collection == name }.sortedBy { it.order }

    fun play(t: Track) {
        val c = controller ?: return
        if (currentKey == t.key) { togglePlay(); return }
        savePosition()
        // Parts of the same book play back-to-back automatically.
        val queueList = t.collection?.let { collectionTracks(it) } ?: listOf(t)
        val index = queueList.indexOfFirst { it.key == t.key }.coerceAtLeast(0)
        val start = if (done[t.key] == true) 0L else positions[t.key] ?: 0L
        if (done[t.key] == true) {
            done.remove(t.key); prefs.edit().remove("done_${t.key}").apply()
        }
        c.setMediaItems(queueList.map(::mediaItem), index, start)
        c.setPlaybackSpeed(speed)
        c.prepare()
        c.play()
        currentKey = t.key
        touch(t.key)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val max = if (c.duration == C.TIME_UNSET) Long.MAX_VALUE else c.duration
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, max))
        position = c.currentPosition
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        position = ms
    }

    fun setSpeed(s: Float) {
        speed = s
        prefs.edit().putFloat("speed", s).apply()
        controller?.setPlaybackSpeed(s)
    }

    fun currentChapterIndex(t: Track): Int =
        t.chapters.indexOfLast { it.startMs <= position }

    // ---------- sleep timer ----------

    /** minutes > 0 = timer, -1 = end of chapter/part, 0 = off */
    fun setSleep(minutes: Int) {
        sleepAtWall = null; sleepAtPos = null; sleepKey = null; sleepLabel = null
        when {
            minutes > 0 -> {
                sleepAtWall = System.currentTimeMillis() + minutes * 60_000L
                sleepLabel = "$minutes د"
            }
            minutes == -1 -> {
                val t = currentTrack ?: return
                val next = t.chapters.firstOrNull { it.startMs > position + 1000 }?.startMs
                sleepAtPos = next ?: Long.MAX_VALUE
                sleepKey = t.key
                sleepLabel = if (next != null) "آخر الفصل" else "آخر الجزء"
            }
        }
    }

    private fun checkSleep(c: MediaController) {
        val wall = sleepAtWall
        if (wall != null && System.currentTimeMillis() >= wall) { c.pause(); setSleep(0); return }
        val atPos = sleepAtPos ?: return
        if (currentKey != sleepKey || c.currentPosition >= atPos) {
            if (currentKey != sleepKey) c.seekTo(0)   // stop at the start of the next part
            c.pause(); setSleep(0)
        }
    }

    // ---------- delete ----------

    fun delete(list: List<Track>) {
        val keys = list.map { it.key }.toSet()
        if (currentKey in keys) {
            controller?.stop(); controller?.clearMediaItems(); currentKey = null
        }
        val edit = prefs.edit()
        list.forEach { t ->
            File(t.path).delete()
            t.thumbPath?.let { File(it).delete() }
            positions.remove(t.key); done.remove(t.key); lastPlayed.remove(t.key)
            edit.remove("pos_${t.key}").remove("done_${t.key}").remove("last_${t.key}")
        }
        edit.apply()
        tracks = tracks.filter { it.key !in keys }
        persist()
    }

    private fun persist() {
        val snapshot = tracks
        viewModelScope.launch(Dispatchers.IO) { library.save(snapshot) }
    }

    // ---------- download queue ----------

    fun download(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (!Regex("https?://").containsMatchIn(t)) {
            status = "ده مش رابط. للبحث استخدم تبويب «اقتراحات»"
            return
        }
        if (t in pendingUrls) return
        pendingUrls += t
        queue.addLast(t)
        queued = queue.size
        if (!busy) startWorker()
    }

    private fun startWorker() {
        busy = true
        viewModelScope.launch {
            while (queue.isNotEmpty()) {
                val text = queue.removeFirst()
                queued = queue.size
                processOne(text)
                pendingUrls.remove(text)
            }
            busy = false
            progress.value = null
        }
    }

    private suspend fun processOne(text: String) {
        status = "جاري قراءة الرابط…"
        progress.value = null
        try {
            val list = withContext(Dispatchers.IO) {
                if (YouTubeClient.isYouTube(text)) YouTubeClient.resolve(text) else sc.resolve(text)
            }
            var ok = 0
            var lastError: String? = null
            list.forEachIndexed { i, p ->
                if (tracks.any { it.key == p.key }) { ok++; return@forEachIndexed }
                val prefix = if (list.size > 1) "(${i + 1}/${list.size}) " else ""
                status = "${prefix}جاري تحميل: ${p.title}"
                progress.value = 0f
                try {
                    val fetched = withContext(Dispatchers.IO) {
                        var last = -1
                        p.fetch(library.audioDir) { f ->
                            val pct = (f * 100).toInt()
                            if (pct != last) { last = pct; progress.value = f }
                        }
                    }
                    var file = fetched.file
                    if (file.extension == "mp4") {
                        status = "${prefix}تجهيز الملف…"
                        progress.value = null
                        file = remux(file)
                    }
                    val thumb = withContext(Dispatchers.IO) {
                        (fetched.thumbUrl ?: p.thumbUrl)?.let {
                            Http.saveSmall(it, File(library.thumbDir, p.key.replace(':', '_') + ".jpg"))
                        }
                    }
                    val track = Track(
                        key = p.key,
                        title = p.title,
                        artist = p.artist,
                        durationMs = fetched.durationMs.takeIf { it > 0 } ?: p.durationMs,
                        path = file.absolutePath,
                        thumbPath = thumb?.absolutePath,
                        source = p.source,
                        sourceUrl = p.sourceUrl,
                        collection = p.collection,
                        order = p.order,
                        addedAt = System.currentTimeMillis(),
                        chapters = fetched.chapters,
                        related = fetched.related,
                    )
                    tracks = tracks.filter { it.key != track.key } + track
                    persist()
                    ok++
                } catch (e: Exception) {
                    lastError = friendly(e)
                }
            }
            status = when {
                ok == list.size && list.size > 1 -> "تم تحميل $ok جزء ✓"
                ok == list.size -> "تم التحميل ✓"
                ok > 0 -> "اتحمل $ok من ${list.size} — آخر خطأ: $lastError"
                else -> lastError
            }
            if (ok > 0 && input.trim() == text) input = ""
        } catch (e: Exception) {
            status = friendly(e)
        }
    }

    /** Fragmented MP4 from SoundCloud HLS can't be seeked; rewrap into a normal MP4 (no re-encoding). */
    @OptIn(UnstableApi::class)
    private suspend fun remux(input: File): File {
        val out = File(input.parentFile, input.nameWithoutExtension + ".m4a")
        out.delete()
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(app)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                transformer.start(MediaItem.fromUri(Uri.fromFile(input)), out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
        return if (ok && out.length() > 0) {
            input.delete(); out
        } else {
            out.delete(); input
        }
    }

    // ---------- search ----------

    fun search(q: String) {
        val text = q.trim()
        if (text.isBlank() || searching) return
        query = text
        searching = true
        searchError = null
        viewModelScope.launch {
            try {
                results = withContext(Dispatchers.IO) { YouTubeClient.search(text) }
                if (results.isEmpty()) searchError = "مفيش نتايج"
            } catch (e: Exception) {
                searchError = friendly(e)
            } finally {
                searching = false
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is ScException -> e.message ?: "خطأ"
        is HttpError -> if (e.code == 403) "السيرفر رفض الطلب (403) — جرب تاني" else "خطأ من السيرفر (${e.code})"
        is UnknownHostException -> "مفيش اتصال بالإنترنت"
        is ReCaptchaException -> "يوتيوب طالب تحقق (كابتشا) — جرب بعد شوية"
        is ContentNotAvailableException -> "الفيديو مش متاح (خاص أو محذوف أو مقيّد بالسن)"
        is ExtractionException -> "مشكلة في قراءة يوتيوب — غالبًا محتاج تحديث NewPipeExtractor"
        is IllegalArgumentException -> "الرابط مش صحيح"
        else -> e.message ?: e.toString()
    }

    override fun onCleared() {
        savePosition()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
