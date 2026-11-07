package com.oasisreader.app.remote

import android.content.Context
import com.oasisreader.app.data.Server
import com.oasisreader.app.opds.OpdsClient
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One entry of a file-server folder. [path] is absolute in that server's own namespace. */
data class RemoteFile(val name: String, val path: String, val isDir: Boolean, val size: Long = -1, val mtime: Long = 0) {
    val isBook get() = !isDir && Remote.isBookName(name)
}

/**
 * A file server (TODO 29): WebDAV · SFTP · SMB · FTP/FTPS. Blocking calls — run them on
 * Dispatchers.IO. One instance = one connection; close it when done.
 */
interface RemoteFs : Closeable {
    /** The folder the server address points at (where browsing starts). */
    val root: String

    fun list(path: String): List<RemoteFile>

    /**
     * Receives [path] into [part], going on from part.length() (a broken download resumes).
     * Returns false when [stop] said so (the part stays).
     */
    fun download(path: String, part: File, onProgress: (Long, Long) -> Unit, stop: () -> Boolean): Boolean
}

/** "scheme://host:port/path" (the path may hold Korean letters and spaces as they are). */
data class Addr(val scheme: String, val host: String, val port: Int, val path: String) {
    companion object {
        fun parse(url: String, defaultPort: Int): Addr {
            val s = url.trim()
            val i = s.indexOf("://")
            val scheme = if (i > 0) s.substring(0, i).lowercase() else ""
            val rest = if (i > 0) s.substring(i + 3) else s
            val slash = rest.indexOf('/')
            val hostPort = if (slash < 0) rest else rest.substring(0, slash)
            val path = Remote.norm(if (slash < 0) "/" else rest.substring(slash))
            val c = hostPort.lastIndexOf(':')
            val (host, port) = if (c > 0 && hostPort.substring(c + 1).all { it.isDigit() } && c < hostPort.length - 1)
                hostPort.substring(0, c) to hostPort.substring(c + 1).toInt()
            else hostPort to defaultPort
            if (host.isBlank()) throw IOException("서버 주소가 비어 있습니다")
            return Addr(scheme, host, port, path)
        }
    }
}

object Remote {
    const val WEBDAV = "webdav"
    const val SFTP = "sftp"
    const val SMB = "smb"
    const val FTP = "ftp"
    const val FTPS = "ftps"
    val ALL = listOf(WEBDAV, SFTP, SMB, FTPS, FTP)

    private val BOOK_EXT = setOf("epub", "txt", "pdf", "cbz", "zip")
    fun isBookName(name: String) = name.substringAfterLast('.', "").lowercase() in BOOK_EXT

    fun label(proto: String) = when (proto) {
        WEBDAV -> "WebDAV"; SFTP -> "SFTP"; SMB -> "SMB"; FTP -> "FTP"; FTPS -> "FTPS"; else -> proto
    }

    /** Address examples for the settings screen. */
    fun example(proto: String) = when (proto) {
        WEBDAV -> "https://nas.example.com/webdav/책"
        SFTP -> "sftp://nas.example.com:22/home/me/books"
        SMB -> "smb://192.168.0.10/공유폴더/책"
        FTPS -> "ftps://nas.example.com:21/books"
        else -> "ftp://192.168.0.10:21/books"
    }

    /** "/a/b/" → "/a/b", "" → "/" (backslashes become slashes). */
    fun norm(p: String): String {
        val parts = p.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return "/" + parts.joinToString("/")
    }

    fun parent(p: String): String = norm(p).substringBeforeLast('/', "").ifEmpty { "/" }
    fun child(dir: String, name: String): String = norm("$dir/$name")
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

    fun open(context: Context, server: Server): RemoteFs = when (server.proto) {
        WEBDAV -> WebDavFs(server)
        SFTP -> SftpFs(context.applicationContext, server)
        SMB -> SmbFs(server)
        FTP -> FtpFs(server, tls = false)
        FTPS -> FtpFs(server, tls = true)
        else -> throw IOException("파일 서버가 아닙니다")
    }

    /** Lists a folder: a fresh connection, closed afterwards. */
    fun list(context: Context, server: Server, path: String): List<RemoteFile> =
        open(context, server).use { it.list(path) }

    /** Every book under [dir] (sub folders too when [deep]), up to [max]. */
    fun walk(fs: RemoteFs, dir: String, deep: Boolean, max: Int = 2000, stop: () -> Boolean = { false }): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        fun go(d: String, depth: Int) {
            if (out.size >= max || stop()) return
            val items = fs.list(d)
            items.filter { it.isBook }.forEach { if (out.size < max) out += it }
            if (deep && depth < 12) items.filter { it.isDir }.forEach { go(it.path, depth + 1) }
        }
        go(dir, 0)
        return out
    }

    /** Copies [input] to the end of [part] (it already holds [start] bytes). */
    internal fun copyResume(input: InputStream, part: File, start: Long, total: Long, onProgress: (Long, Long) -> Unit, stop: () -> Boolean): Boolean {
        if (total > OpdsClient.MAX_BOOK_BYTES) throw IOException("파일이 너무 큽니다 (${total / 1048576} MB)")
        val free = part.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (total > 0 && free < (total - start) * 2 + 200L * 1024 * 1024)
            throw IOException("폰 저장공간이 부족합니다 (약 ${(total * 2) / 1048576} MB 필요, ${free / 1048576} MB 남음)")
        var done = start
        var sinceCheck = 0L
        java.io.FileOutputStream(part, start > 0).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n; sinceCheck += n
                if (done > OpdsClient.MAX_BOOK_BYTES) throw IOException("파일이 너무 큽니다")
                onProgress(done, total)
                if (sinceCheck >= 512 * 1024) { sinceCheck = 0; if (stop()) return false }
            }
        }
        if (total > 0 && done < total) throw IOException("연결이 끊겼습니다")
        return true
    }
}
