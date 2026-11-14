package com.said.soundoffline

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Chapter(val title: String, val startMs: Long)

/** Something the user could download: a video/track or a whole playlist. */
data class Suggestion(
    val url: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbUrl: String?,
    val isPlaylist: Boolean = false,
    val count: Long = -1,
    val key: String? = null,
)

data class Track(
    val key: String,              // "yt:<id>" or "sc:<id>"
    val title: String,
    val artist: String,
    val durationMs: Long,
    val path: String,
    val thumbPath: String?,
    val source: String,           // "youtube" | "soundcloud"
    val sourceUrl: String,
    val collection: String?,      // playlist name for multi-part books
    val order: Int,
    val addedAt: Long,
    val chapters: List<Chapter> = emptyList(),
    val related: List<Suggestion> = emptyList(),
)

/** A resolved item waiting to be downloaded. */
class Pending(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbUrl: String?,
    val source: String,
    val sourceUrl: String,
    val collection: String?,
    val order: Int,
    val fetch: (dir: File, onProgress: (Float) -> Unit) -> Fetched,
)

class Fetched(
    val file: File,
    val chapters: List<Chapter> = emptyList(),
    val related: List<Suggestion> = emptyList(),
    val thumbUrl: String? = null,
    val durationMs: Long = 0,
)

// ---------- JSON ----------

fun Suggestion.toJson(): JSONObject = JSONObject()
    .put("url", url).put("title", title).put("artist", artist)
    .put("durationMs", durationMs).put("thumbUrl", thumbUrl ?: "")
    .put("isPlaylist", isPlaylist).put("count", count).put("key", key ?: "")

fun suggestionFromJson(o: JSONObject) = Suggestion(
    url = o.getString("url"),
    title = o.optString("title"),
    artist = o.optString("artist"),
    durationMs = o.optLong("durationMs"),
    thumbUrl = o.optString("thumbUrl").ifBlank { null },
    isPlaylist = o.optBoolean("isPlaylist"),
    count = o.optLong("count", -1),
    key = o.optString("key").ifBlank { null },
)

fun Track.toJson(): JSONObject = JSONObject()
    .put("key", key).put("title", title).put("artist", artist)
    .put("durationMs", durationMs).put("path", path).put("thumbPath", thumbPath ?: "")
    .put("source", source).put("sourceUrl", sourceUrl).put("collection", collection ?: "")
    .put("order", order).put("addedAt", addedAt)
    .put("chapters", JSONArray().also { a ->
        chapters.forEach { a.put(JSONObject().put("t", it.title).put("s", it.startMs)) }
    })
    .put("related", JSONArray().also { a -> related.forEach { a.put(it.toJson()) } })

fun trackFromJson(o: JSONObject): Track {
    val ch = o.optJSONArray("chapters") ?: JSONArray()
    val rel = o.optJSONArray("related") ?: JSONArray()
    return Track(
        key = o.getString("key"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        durationMs = o.optLong("durationMs"),
        path = o.getString("path"),
        thumbPath = o.optString("thumbPath").ifBlank { null },
        source = o.optString("source"),
        sourceUrl = o.optString("sourceUrl"),
        collection = o.optString("collection").ifBlank { null },
        order = o.optInt("order"),
        addedAt = o.optLong("addedAt"),
        chapters = (0 until ch.length()).map {
            val c = ch.getJSONObject(it); Chapter(c.optString("t"), c.optLong("s"))
        },
        related = (0 until rel.length()).map { suggestionFromJson(rel.getJSONObject(it)) },
    )
}
