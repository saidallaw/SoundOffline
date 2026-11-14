package com.said.soundoffline

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Offline library stored as a JSON file in app storage. */
class Library(context: Context) {
    private val file = File(context.filesDir, "library2.json")
    val audioDir = File(context.filesDir, "audio")
    val thumbDir = File(context.filesDir, "thumbs")

    @Synchronized
    fun load(): List<Track> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { trackFromJson(arr.getJSONObject(it)) }
            .filter { File(it.path).exists() }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(list: List<Track>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
