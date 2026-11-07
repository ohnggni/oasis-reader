package com.oasisreader.app.remote

import android.content.Context
import com.oasisreader.app.data.Server
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
import java.security.PublicKey

/**
 * SFTP (SSH) with sshj. The server's key is remembered the first time (like ssh's known_hosts);
 * if it changes later the connection is refused until the server is saved again in 서버 설정.
 */
class SftpFs(context: Context, server: Server) : RemoteFs {
    private val addr = Addr.parse(server.url, 22)
    override val root: String = addr.path
    private val ssh: SSHClient
    private val sftp: SFTPClient

    init {
        Crypto.install()
        if (server.username.isBlank()) throw IOException("SFTP는 아이디가 필요합니다")
        val hosts = KnownHosts(context)
        ssh = SSHClient()
        ssh.connectTimeout = 15_000
        ssh.timeout = 60_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                hosts.check("$hostname:$port", SecurityUtils.getFingerprint(key))

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        sftp = try {
            ssh.connect(addr.host, addr.port)
            ssh.authPassword(server.username, server.password)
            ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            if (hosts.changed) throw IOException("서버 키가 전과 다릅니다. 같은 서버가 맞으면 서버 설정에서 다시 저장하세요")
            throw IOException(when {
                e is net.schmizz.sshj.userauth.UserAuthException -> "로그인 실패 (아이디·비밀번호 확인)"
                else -> e.message ?: e.javaClass.simpleName
            }, e)
        }
    }

    override fun list(path: String): List<RemoteFile> =
        sftp.ls(Remote.norm(path)).filter { it.name != "." && it.name != ".." }.map {
            RemoteFile(it.name, Remote.norm(it.path), it.isDirectory, it.attributes.size, it.attributes.mtime * 1000L)
        }

    override fun download(path: String, part: File, onProgress: (Long, Long) -> Unit, stop: () -> Boolean): Boolean {
        sftp.open(Remote.norm(path)).use { rf ->
            val total = rf.length()
            var have = if (part.isFile) part.length() else 0L
            if (have > total) { part.delete(); have = 0 }
            return rf.RemoteFileInputStream(have).use { Remote.copyResume(it, part, have, total, onProgress, stop) }
        }
    }

    override fun openRead(path: String, offset: Long): RemoteRead {
        val rf = sftp.open(Remote.norm(path))
        val total = runCatching { rf.length() }.getOrDefault(-1L)
        return RemoteRead(rf.RemoteFileInputStream(offset), total, offset) { runCatching { rf.close() } }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }
}

/** SSH server keys seen before (prefs "remote_hosts"): host:port → fingerprint. */
class KnownHosts(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote_hosts", Context.MODE_PRIVATE)
    var changed = false
        private set

    fun check(hostPort: String, fp: String): Boolean {
        val seen = prefs.getString(hostPort, null)
        if (seen == null) { prefs.edit().putString(hostPort, fp).apply(); return true }
        if (seen == fp) return true
        changed = true
        return false
    }

    fun forget(hostPort: String) = prefs.edit().remove(hostPort).apply()
}

/** Android's own "BC" provider lacks what sshj / smbj need: put the full Bouncy Castle in its place. */
object Crypto {
    @Volatile private var done = false

    fun install() {
        if (done) return
        synchronized(this) {
            if (done) return
            runCatching {
                java.security.Security.removeProvider("BC")
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }
            done = true
        }
    }
}
