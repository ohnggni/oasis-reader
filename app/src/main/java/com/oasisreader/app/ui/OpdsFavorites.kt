package com.oasisreader.app.ui

import android.content.Context
import com.oasisreader.app.data.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * OPDS 폴더 즐겨찾기: a folder is kept with the whole way to it (the page addresses and the folder
 * names from the server's first page), so opening it rebuilds the path bar and back works as if
 * the user had walked there. Also the last folder seen, per server ("지난번 폴더").
 * Kept in the "catalog" settings, which a backup includes.
 */
object OpdsFavorites {
    @Serializable
    data class Place(val urls: List<String>, val labels: List<String>, val name: String = labels.lastOrNull().orEmpty())

    /** 📂 있던 폴더 열기: the folder the OPDS screen of that server should start in, once. */
    val pending = HashMap<String, Place>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("catalog", Context.MODE_PRIVATE)
    private val listSer = ListSerializer(Place.serializer())

    fun all(c: Context, serverId: String): List<Place> = runCatching {
        Http.json.decodeFromString(listSer, prefs(c).getString("fav_$serverId", null) ?: "[]")
    }.getOrDefault(emptyList())

    fun save(c: Context, serverId: String, list: List<Place>) {
        prefs(c).edit().putString("fav_$serverId", Http.json.encodeToString(listSer, list)).apply()
    }

    fun isFavorite(c: Context, serverId: String, url: String) = all(c, serverId).any { it.urls.lastOrNull() == url }

    /** Add the folder at the end of [urls], or remove it when it is there. Returns true when added. */
    fun toggle(c: Context, serverId: String, urls: List<String>, labels: List<String>): Boolean {
        val list = all(c, serverId)
        val here = urls.last()
        return if (list.any { it.urls.lastOrNull() == here }) {
            save(c, serverId, list.filterNot { it.urls.lastOrNull() == here }); false
        } else {
            save(c, serverId, list + Place(urls, labels)); true
        }
    }

    fun last(c: Context, serverId: String): Place? = runCatching {
        prefs(c).getString("last_$serverId", null)?.let { Http.json.decodeFromString(Place.serializer(), it) }
    }.getOrNull()

    fun setLast(c: Context, serverId: String, urls: List<String>, labels: List<String>) {
        if (urls.size <= 1) return
        prefs(c).edit().putString("last_$serverId", Http.json.encodeToString(Place.serializer(), Place(urls, labels))).apply()
    }
}
