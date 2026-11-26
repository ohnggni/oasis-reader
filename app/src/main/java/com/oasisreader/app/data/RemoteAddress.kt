package com.oasisreader.app.data

/**
 * How a file server (TODO 29) is written down: its kind, paths inside it and the remote:// link
 * a received book keeps. Connecting and reading the files is in the remote package.
 */
object RemoteAddress {
    const val WEBDAV = "webdav"
    const val SFTP = "sftp"
    const val SMB = "smb"
    const val FTP = "ftp"
    const val FTPS = "ftps"

    fun label(proto: String) = when (proto) {
        WEBDAV -> "WebDAV"; SFTP -> "SFTP"; SMB -> "SMB"; FTP -> "FTP"; FTPS -> "FTPS"; else -> proto
    }

    /** "/a/b/" → "/a/b", "" → "/" (backslashes become slashes). */
    fun norm(p: String): String {
        val parts = p.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return "/" + parts.joinToString("/")
    }

    fun nameOf(p: String): String = norm(p).substringAfterLast('/')

    // A received book keeps "remote://<server id>/<path>" as its source (re-download, 다음 권).
    private const val PREFIX = "remote://"
    fun url(serverId: String, path: String) = PREFIX + serverId + norm(path)
    fun isUrl(u: String?) = u?.startsWith(PREFIX) == true
    /** remote://<id>/<path> → (id, path). */
    fun parseUrl(u: String): Pair<String, String>? {
        if (!isUrl(u)) return null
        val rest = u.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        return rest.substring(0, slash) to norm(rest.substring(slash))
    }
}
