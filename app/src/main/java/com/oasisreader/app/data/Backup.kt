package com.oasisreader.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.oasisreader.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val format: Int = 1,
    val app: String = "OasisReader",
    val createdAt: Long,
    val books: Int,
    val includesBookFiles: Boolean,
)

/**
 * One zip file with everything needed on a new phone:
 *   manifest.json
 *   db/oasis.db                 library, positions, highlights, reading time
 *   prefs/reader.xml, server.xml   reader settings, catalog address/login name (not the password)
 *   books/<id>/book.json | comic.json, cover.jpg, book.epub | book.cbz | book.txt (originals only when "with books")
 * Without book files, books keep their place in the library and can be downloaded
 * again from bookoasis (or re-imported).
 */
object Backup {

    private val PREFS = listOf("reader.xml", "reader_book.xml", "server.xml", "cache.xml", "app.xml")

    suspend fun export(context: Context, dest: Uri, includeBooks: Boolean): BackupManifest = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        // fold the write-ahead log into the main file so one file is the whole database
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbFile = context.getDatabasePath("oasis.db")
        val store = BookStore.get(context)
        val bookDirs = store.root.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".tmp") }.orEmpty()
        val manifest = BackupManifest(
            createdAt = System.currentTimeMillis(),
            books = bookDirs.size,
            includesBookFiles = includeBooks,
        )
        val out = context.contentResolver.openOutputStream(dest) ?: throw IOException("저장할 곳을 열 수 없습니다")
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, f: File) {
                if (!f.isFile) return
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Http.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            put("db/oasis.db", dbFile)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            PREFS.forEach { put("prefs/$it", File(prefsDir, it)) }
            // fonts the user added (downloaded Google fonts are fetched again when needed)
            val fontsDir = File(context.filesDir, "fonts")
            put("fonts/custom.json", File(fontsDir, "custom.json"))
            fontsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("custom-") }?.forEach { d ->
                d.listFiles()?.forEach { f -> put("fonts/${d.name}/${f.name}", f) }
            }
            bookDirs.forEach { d ->
                put("books/${d.name}/book.json", File(d, "book.json"))
                put("books/${d.name}/comic.json", File(d, "comic.json"))
                put("books/${d.name}/cover.jpg", File(d, "cover.jpg"))
                if (includeBooks) {
                    BookStore.ORIGINALS.forEach { n -> put("books/${d.name}/$n", File(d, n)) }
                }
            }
        }
        manifest
    }

    /**
     * Replace the library with a backup. The app restarts afterwards (the database
     * and settings are re-read from the restored files).
     */
    suspend fun restore(context: Context, src: Uri) = withContext(Dispatchers.IO) {
        val store = BookStore.get(context)
        val work = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val zipFile = File(work, "backup.zip")
        val unpacked = File(work, "x")
        try {
            context.contentResolver.openInputStream(src)?.use { input ->
                zipFile.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("백업 파일을 열 수 없습니다")
            store.unzipSafely(zipFile, unpacked, maxBytes = 16L * 1024 * 1024 * 1024)
            zipFile.delete()
            val manifestFile = File(unpacked, "manifest.json")
            if (!manifestFile.isFile) throw IOException("OG Reader 백업 파일이 아닙니다")
            val manifest = Http.json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
            if (manifest.app != "OasisReader") throw IOException("OG Reader 백업 파일이 아닙니다")
            val newDb = File(unpacked, "db/oasis.db")
            if (!newDb.isFile) throw IOException("백업에 데이터베이스가 없습니다")

            // database
            AppDatabase.closeInstance()
            val dbFile = context.getDatabasePath("oasis.db")
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            dbFile.parentFile?.mkdirs()
            newDb.copyTo(dbFile, overwrite = true)

            // settings
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
            PREFS.forEach { name ->
                val f = File(unpacked, "prefs/$name")
                if (f.isFile) f.copyTo(File(prefsDir, name), overwrite = true)
            }

            // fonts the user added
            File(unpacked, "fonts").takeIf { it.isDirectory }?.copyRecursively(File(context.filesDir, "fonts"), overwrite = true)

            // books: metadata + covers always, files when included (content/ is rebuilt from book.epub)
            store.root.deleteRecursively()
            store.root.mkdirs()
            File(unpacked, "books").listFiles()?.filter { it.isDirectory }?.forEach { d ->
                val target = store.dir(d.name)
                d.copyRecursively(target, overwrite = true)
                val original = store.originalOf(d.name)
                if (original != null) runCatching {
                    val title = runCatching {
                        Http.json.decodeFromString(com.oasisreader.app.epub.EpubPackage.serializer(), File(target, "book.json").readText()).title
                    }.getOrNull()
                    store.extract(original, store.contentDir(d.name), title)
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** Start the app again in a fresh process (after a restore). */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
