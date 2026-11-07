package com.oasisreader.app.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher

/** Plays a track kept on the phone ([MediaCrypt] file), decrypting as it reads; seeking works. */
class EncryptedFileDataSource(private val key: ByteArray) : BaseDataSource(/* isNetwork = */ false) {
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var cipher: Cipher? = null
    private var remaining = 0L
    private var opened = false
    private var buf = ByteArray(0)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = RandomAccessFile(dataSpec.uri.path ?: throw IOException("no path"), "r")
        file = f
        val head = ByteArray(MediaCrypt.HEADER)
        f.readFully(head)
        val nonce = MediaCrypt.nonceOf(head) ?: throw IOException("not an OG Reader media file")
        val plain = f.length() - MediaCrypt.HEADER
        if (dataSpec.position > plain) throw androidx.media3.datasource.DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        f.seek(MediaCrypt.HEADER + dataSpec.position)
        cipher = MediaCrypt.cipherAt(key, nonce, dataSpec.position)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else plain - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        if (buf.size < want) buf = ByteArray(want)
        val n = file!!.read(buf, 0, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        cipher!!.update(buf, 0, n, buffer, offset)
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { file?.close() } finally {
            file = null; cipher = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}

/**
 * The player's data source: kept tracks (*.ogr files) through [EncryptedFileDataSource],
 * everything else (the server's stream, old plain files) through [default].
 */
class MediaDataSourceFactory(context: Context, private val default: DataSource.Factory) : DataSource.Factory {
    private val key = MediaCrypt.key(context)

    private val app = context.applicationContext

    override fun createDataSource(): DataSource = Routing(EncryptedFileDataSource(key), default.createDataSource(), RemoteDataSource(app))

    // remote://: 소장 오디오북 streamed from a 🗄️ 파일 서버 (TODO 30)
    private class Routing(private val enc: DataSource, private val other: DataSource, private val remote: DataSource) : DataSource {
        private var current: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) {
            enc.addTransferListener(transferListener); other.addTransferListener(transferListener); remote.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val u = dataSpec.uri
            val ds = when {
                u.scheme == "file" && u.path?.endsWith("." + MediaCrypt.EXT) == true -> enc
                u.scheme == "remote" -> remote
                else -> other
            }
            current = ds
            return ds.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() { try { current?.close() } finally { current = null } }
    }
}
