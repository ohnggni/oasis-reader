package com.oasisreader.app.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.oasisreader.app.data.ServerSettings
import com.oasisreader.app.remote.Remote
import com.oasisreader.app.remote.RemoteFs
import com.oasisreader.app.remote.RemoteRead
import java.io.IOException

/**
 * 소장 오디오북 streamed from a 🗄️ 파일 서버 (TODO 30): plays "remote://<server id>/<path>" with
 * the server's own protocol (WebDAV · SFTP · SMB · FTP), from any byte (seeking opens again there).
 * One connection per open; it is closed with the read.
 */
class RemoteDataSource(context: Context) : BaseDataSource(/* isNetwork = */ true) {
    private val app = context.applicationContext
    private var fs: RemoteFs? = null
    private var read: RemoteRead? = null
    private var uri: Uri? = null
    private var left = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (sid, path) = Remote.parseUrl(dataSpec.uri.toString()) ?: throw IOException("파일 서버 주소가 아닙니다")
        val server = ServerSettings(app).get(sid) ?: throw IOException("이 오디오북의 파일 서버 설정이 지워졌습니다")
        val f = Remote.open(app, server)
        fs = f
        val r = try { f.openRead(path, dataSpec.position) } catch (e: Exception) { runCatching { f.close() }; fs = null; throw e }
        read = r
        left = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            r.total >= 0 -> (r.total - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec); opened = true
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val want = if (left == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), left).toInt()
        val n = read?.input?.read(buffer, offset, want) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (left != C.LENGTH_UNSET.toLong()) left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = read; val f = fs
        read = null; fs = null
        try { runCatching { r?.close() }; runCatching { f?.close() } }
        finally { if (opened) { opened = false; transferEnded() }; uri = null }
    }
}
