package com.oasisreader.app.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.oasisreader.app.data.Server
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 2/3 (Windows shares, NAS) with smbj. Paths here are "/<share>/<folder>/…"; the address
 * must name the share (smb://host/share/…). An empty login = guest. "WORKGROUP\\me" sets the domain.
 */
class SmbFs(server: Server) : RemoteFs {
    private val addr = Addr.parse(server.url, 445)
    override val root: String = addr.path
    private val client: SMBClient
    private val conn: Connection
    private val session: Session
    private val shares = HashMap<String, DiskShare>()

    init {
        Crypto.install()
        if (root == "/") throw IOException("주소에 공유 폴더 이름까지 넣으세요 (예: smb://192.168.0.10/공유폴더)")
        client = SMBClient(
            SmbConfig.builder().withTimeout(60, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build(),
        )
        val user = server.username.trim()
        val (domain, name) = if ('\\' in user) user.substringBefore('\\') to user.substringAfter('\\') else null to user
        val auth = if (name.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(name, server.password.toCharArray(), domain)
        conn = try {
            client.connect(addr.host, addr.port)
        } catch (e: Exception) {
            runCatching { client.close() }
            throw IOException("연결 실패: ${e.message ?: e.javaClass.simpleName}", e)
        }
        session = try {
            conn.authenticate(auth)
        } catch (e: Exception) {
            runCatching { conn.close() }; runCatching { client.close() }
            throw IOException("로그인 실패 (아이디·비밀번호 확인)", e)
        }
    }

    /** "/share/a/b" → (share, "a\\b"). */
    private fun split(path: String): Pair<DiskShare, String> {
        val parts = Remote.norm(path).split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw IOException("공유 폴더 밖으로는 갈 수 없습니다")
        val share = shares.getOrPut(parts[0]) {
            (session.connectShare(parts[0]) as? DiskShare) ?: throw IOException("파일 공유가 아닙니다: ${parts[0]}")
        }
        return share to parts.drop(1).joinToString("\\")
    }

    override fun list(path: String): List<RemoteFile> {
        val dir = Remote.norm(path)
        val (share, inner) = split(dir)
        return share.list(inner).filter { it.fileName != "." && it.fileName != ".." }.map {
            val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RemoteFile(it.fileName, Remote.child(dir, it.fileName), isDir, it.endOfFile, it.lastWriteTime.toEpochMillis())
        }
    }

    override fun download(path: String, part: File, onProgress: (Long, Long) -> Unit, stop: () -> Boolean): Boolean {
        val (share, inner) = split(path)
        share.openFile(
            inner, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f ->
            val total = f.fileInformation.standardInformation.endOfFile
            var have = if (part.isFile) part.length() else 0L
            if (have > total) { part.delete(); have = 0 }
            val input = object : java.io.InputStream() {
                var pos = have
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= total) return -1
                    val n = f.read(b, pos, off, len)
                    if (n <= 0) return -1
                    pos += n
                    return n
                }
            }
            return Remote.copyResume(input, part, have, total, onProgress, stop)
        }
    }

    override fun openRead(path: String, offset: Long): RemoteRead {
        val (share, inner) = split(path)
        val f = share.openFile(
            inner, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        val total = runCatching { f.fileInformation.standardInformation.endOfFile }.getOrDefault(-1L)
        val input = object : java.io.InputStream() {
            var pos = offset
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (total in 0..pos) return -1
                val n = f.read(b, pos, off, len)
                if (n <= 0) return -1
                pos += n
                return n
            }
        }
        return RemoteRead(input, total, offset) { runCatching { f.close() } }
    }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        runCatching { session.close() }
        runCatching { conn.close() }
        runCatching { client.close() }
    }
}
