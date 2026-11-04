package com.oasisreader.app.audio

import android.content.Context
import android.util.Base64
import com.oasisreader.app.data.SecureStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Media kept on the phone is stored encrypted, so a copied file plays nowhere but in this app
 * on this phone.
 *
 * - The data key is 32 random bytes, kept in [SecureStore] (wrapped by a non-exportable
 *   Android Keystore key, never in a backup or a phone-to-phone move). If it is ever lost,
 *   the kept files are unreadable and are simply received again.
 * - Each file: "OGA1" + an 8-byte random nonce + AES-256-CTR of the content. CTR keeps the
 *   length and lets the player seek: the counter of byte p is nonce ‖ (p / 16).
 */
object MediaCrypt {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'G'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    const val HEADER = 12
    const val EXT = "ogr"   // not a real audio extension, so the player sniffs the format from the content
    private const val STORE_KEY = "media_key_v1"
    private val random = SecureRandom()

    @Volatile private var cached: ByteArray? = null

    /** This phone's media key (made on first use). */
    fun key(context: Context): ByteArray {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val store = SecureStore(context.applicationContext)
            val k = store.get(STORE_KEY)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                ?.takeIf { it.size == 32 }
                ?: ByteArray(32).also { random.nextBytes(it); store.put(STORE_KEY, Base64.encodeToString(it, Base64.NO_WRAP)) }
            cached = k
            return k
        }
    }

    fun newNonce(): ByteArray = ByteArray(8).also { random.nextBytes(it) }

    fun header(nonce: ByteArray): ByteArray = MAGIC + nonce

    /** The nonce of an encrypted file's first [HEADER] bytes, or null when it is not one. */
    fun nonceOf(head: ByteArray): ByteArray? =
        if (head.size >= HEADER && head.copyOfRange(0, 4).contentEquals(MAGIC)) head.copyOfRange(4, HEADER) else null

    /** A cipher positioned at content byte [position] (same call for both directions in CTR). */
    fun cipherAt(key: ByteArray, nonce: ByteArray, position: Long, mode: Int = Cipher.DECRYPT_MODE): Cipher {
        val iv = ByteArray(16)
        nonce.copyInto(iv, 0, 0, 8)
        var block = position / 16
        for (i in 15 downTo 8) { iv[i] = (block and 0xff).toByte(); block = block ushr 8 }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val skip = (position % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        return c
    }

    /**
     * Encrypt [input] into [out] (header first when [position] is 0). [position] is where in the
     * content the input starts, for a download that goes on from a part already written.
     */
    fun encrypt(key: ByteArray, nonce: ByteArray, input: InputStream, out: OutputStream, position: Long = 0, onBytes: (Int) -> Unit = {}) {
        if (position == 0L) out.write(header(nonce))
        val c = cipherAt(key, nonce, position, Cipher.ENCRYPT_MODE)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            c.update(buf, 0, n)?.let { out.write(it) }
            onBytes(n)
        }
    }

    /** Content length of an encrypted file. */
    fun plainLength(f: File) = (f.length() - HEADER).coerceAtLeast(0)
}
