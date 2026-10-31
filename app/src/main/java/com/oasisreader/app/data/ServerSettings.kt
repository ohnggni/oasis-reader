package com.oasisreader.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An OPDS catalog: name, address and login (the password is kept encrypted, separately). */
data class Server(
    val id: String,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val metaUrl: String = "",   // oasis-meta sidecar (series names, clean titles, real dates); "" = none
    val metaDb: String = "general",   // bookoasis database behind this catalog: "general" | "adult"
) {
    /** Address set; a login needs both parts (or neither, for a public catalog). */
    val isComplete get() = url.isNotBlank() && (username.isBlank() == password.isBlank())
    val host: String? get() = url.toHttpUrlOrNull()?.host
}

@Serializable
private data class StoredServer(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val metaUrl: String? = null,
    val metaDb: String? = null,    // null = guess from the address ("adult" in it -> adult)
)

/**
 * Several catalogs. The list (without passwords) lives in prefs "server" so backups carry it;
 * each password sits in SecureStore under its server id and never leaves the phone.
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    init { migrateSingle() }

    /** v0.1.x kept one catalog in url/username/opds_password: turn it into the first list entry. */
    private fun migrateSingle() {
        if (prefs.contains("servers")) return
        val url = prefs.getString("url", null) ?: return
        val s = StoredServer(DEFAULT_ID, "북오아시스", url, prefs.getString("username", null).orEmpty())
        prefs.edit().putString("servers", encode(listOf(s))).apply()
        secure.get("opds_password")?.let { secure.put(pwKey(DEFAULT_ID), it) }
        secure.put("opds_password", null)
    }

    private fun pwKey(id: String) = "opds_password_$id"
    private fun encode(l: List<StoredServer>) = Http.json.encodeToString(ListSerializer(StoredServer.serializer()), l)
    private fun stored(): List<StoredServer> = prefs.getString("servers", null)
        ?.let { runCatching { Http.json.decodeFromString(ListSerializer(StoredServer.serializer()), it) }.getOrNull() }
        .orEmpty()

    fun all(): List<Server> = stored().map {
        Server(
            it.id, it.name, it.url, it.username, secure.get(pwKey(it.id)).orEmpty(),
            it.metaUrl.orEmpty(), it.metaDb ?: guessDb(it.url),
        )
    }

    private fun guessDb(url: String): String = if (url.contains("adult", ignoreCase = true)) "adult" else "general"

    fun get(id: String?): Server? = all().firstOrNull { it.id == id }

    /**
     * The catalog a download link belongs to (login is only ever sent to that host).
     * Several catalogs on one host (bookoasis general/adult): pick by the db in /opds/download/<db>/<id>.
     */
    fun forUrl(url: String): Server? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        val same = all().filter { it.host == host }
        if (same.size <= 1) return same.firstOrNull()
        val db = Regex("""/download/([a-z]+)/""").find(url)?.groupValues?.get(1)
        return same.firstOrNull { it.metaDb == db } ?: same.first()
    }

    /** Add (id == "") or update. Returns the saved server. */
    fun save(s: Server): Server {
        val id = s.id.ifBlank { "s" + java.lang.Long.toString(System.currentTimeMillis(), 36) }
        val item = StoredServer(
            id, s.name.trim().ifBlank { s.host ?: "OPDS" }, s.url.trim().trimEnd('/'), s.username.trim(),
            s.metaUrl.trim().trimEnd('/'), s.metaDb,
        )
        val list = stored().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = item else list.add(item)
        prefs.edit().putString("servers", encode(list)).apply()
        secure.put(pwKey(id), s.password.ifEmpty { null })
        return Server(id, item.name, item.url, item.username, s.password, item.metaUrl.orEmpty(), s.metaDb)
    }

    fun delete(id: String) {
        prefs.edit().putString("servers", encode(stored().filterNot { it.id == id })).apply()
        secure.put(pwKey(id), null)
    }

    companion object {
        const val DEFAULT_ID = "bookoasis"
    }
}
