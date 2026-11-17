package com.oasisreader.app.remote

import com.oasisreader.app.data.Server
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.IOException

/**
 * FTP and FTPS (explicit, AUTH TLS) with commons-net. FTPS never falls back to plain FTP;
 * plain FTP sends the password and the books unencrypted (the settings screen says so).
 * An empty login = anonymous.
 */
class FtpFs(server: Server, tls: Boolean) : RemoteFs {
    private val addr = Addr.parse(server.url, 21)
    override val root: String = addr.path
    private val ftp: FTPClient = if (tls) FTPSClient(false) else FTPClient()

    init {
        ftp.controlEncoding = "UTF-8"
        ftp.connectTimeout = 15_000
        ftp.defaultTimeout = 60_000
        try {
            ftp.connect(addr.host, addr.port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw IOException("서버가 연결을 거절했습니다 (${ftp.replyCode})")
            ftp.soTimeout = 60_000
            val user = server.username.ifBlank { "anonymous" }
            val pass = if (server.username.isBlank()) "guest" else server.password
            if (!ftp.login(user, pass)) throw IOException("로그인 실패 (아이디·비밀번호 확인)")
            if (ftp is FTPSClient) { ftp.execPBSZ(0); ftp.execPROT("P") }
            runCatching { ftp.sendCommand("OPTS UTF8 ON") }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
        } catch (e: Exception) {
            runCatching { ftp.disconnect() }
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    override fun list(path: String): List<RemoteFile> {
        val dir = Remote.norm(path)
        val files = ftp.listFiles(dir) ?: emptyArray()
        if (!FTPReply.isPositiveCompletion(ftp.replyCode) && files.isEmpty()) throw IOException("폴더를 읽지 못했습니다: $dir")
        return files.filter { it != null && it.name != "." && it.name != ".." }.map {
            RemoteFile(it.name, Remote.child(dir, it.name), it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0L)
        }
    }

    /** Thrown from the output while copying when [stop] said so (pause · cancel). */
    private class Stopped : IOException("stopped")

    override fun download(path: String, part: File, onProgress: (Long, Long) -> Unit, stop: () -> Boolean): Boolean {
        val p = Remote.norm(path)
        val total = runCatching { ftp.getSize(p)?.trim()?.toLongOrNull() }.getOrNull() ?: -1L
        var have = if (part.isFile) part.length() else 0L
        if (total in 0 until have) { part.delete(); have = 0 }
        Remote.checkRoom(part, total, have)
        // commons-net copies the file itself here, so it can send NOOPs on the control connection while the
        // data runs: a NAS or a router that drops a quiet control connection no longer cuts a big book (TODO 70)
        ftp.setControlKeepAliveTimeout(java.time.Duration.ofSeconds(20))
        ftp.setControlKeepAliveReplyTimeout(java.time.Duration.ofSeconds(10))
        ftp.setDataTimeout(java.time.Duration.ofSeconds(60))
        ftp.bufferSize = 64 * 1024
        ftp.restartOffset = have
        var done = have
        var since = 0L
        val ok = try {
            java.io.FileOutputStream(part, have > 0).use { fos ->
                val out = object : java.io.OutputStream() {
                    override fun write(b: Int) { fos.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        fos.write(b, off, len)
                        done += len; since += len
                        if (done > com.oasisreader.app.opds.OpdsClient.MAX_BOOK_BYTES) throw IOException("파일이 너무 큽니다")
                        onProgress(done, total)
                        if (since >= 512 * 1024) { since = 0; if (stop()) throw Stopped() }
                    }
                    override fun flush() { fos.flush() }
                }
                ftp.retrieveFile(p, out)
            }
        } catch (e: Stopped) {
            return false   // the part stays; the connection is closed by the caller
        } finally {
            ftp.restartOffset = 0
        }
        if (!ok) throw IOException("받기 실패: ${ftp.replyString?.trim()}")
        if (total > 0 && done < total) throw IOException("연결이 끊겼습니다")
        return true
    }

    override fun openRead(path: String, offset: Long): RemoteRead {
        val p = Remote.norm(path)
        val total = runCatching { ftp.getSize(p)?.trim()?.toLongOrNull() }.getOrNull() ?: -1L
        ftp.restartOffset = offset
        val input = try {
            ftp.retrieveFileStream(p) ?: throw IOException("열기 실패: ${ftp.replyString?.trim()}")
        } finally { ftp.restartOffset = 0 }
        // closing early: the server answers the aborted transfer; the connection is dropped anyway
        return RemoteRead(input, total, offset) { runCatching { ftp.completePendingCommand() } }
    }

    override fun close() {
        runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } }
    }
}
