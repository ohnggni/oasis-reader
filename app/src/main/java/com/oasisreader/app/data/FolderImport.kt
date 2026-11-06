package com.oasisreader.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oasisreader.app.R
import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.ui.ShelfCollections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 폴더에서 가져오기: a folder picked with the system picker (and every folder under it) is
 * scanned for EPUB · TXT · PDF · CBZ/ZIP, then the books are copied in by [Worker] — in the
 * background, with a progress notification. Each file is remembered by its place in the folder
 * (opdsId "local:<document id>"), so importing the same folder again only adds what is new.
 * The books can go on 컬렉션: one per book folder ("1000일의 매니저"), one for the picked folder,
 * or none.
 */
object FolderImport {
    const val PREFIX = "local:"
    private const val WORK = "folder-import"
    private const val CHANNEL = "folder_import"
    private val EXT = setOf("epub", "txt", "pdf", "cbz", "zip")

    enum class Group { BOOK_FOLDER, PICKED_FOLDER, NONE }

    data class Item(val docId: String, val name: String, val size: Long, val folder: String)

    data class Plan(
        val tree: Uri,
        val rootName: String,
        val items: List<Item>,       // books not on the 서재 yet
        val already: Int,            // found but already imported
        val folders: Int,            // book folders among the new ones
    ) {
        val bytes get() = items.sumOf { it.size }
    }

    /** Every book file under [tree], with the folder it sits in (the picked folder's name for loose files). */
    fun scanAll(context: Context, tree: Uri): Pair<String, List<Item>> {
        val cr = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = cr.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: rootId.substringAfterLast('/').substringAfterLast(':')
        val out = ArrayList<Item>()
        fun walk(docId: String, folder: String, depth: Int) {
            if (depth > 12) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, name, depth + 1)
                    else if (name.substringAfterLast('.', "").lowercase() in EXT) {
                        out += Item(id, name, if (c.isNull(3)) 0L else c.getLong(3), folder)
                    }
                }
            }
        }
        walk(rootId, rootName, 0)
        out.sortWith { a, b -> com.oasisreader.app.comic.ComicParser.naturalOrder.compare(a.folder + "/" + a.name, b.folder + "/" + b.name) }
        return rootName to out
    }

    /** What importing [tree] would do (shown before it starts). */
    suspend fun plan(context: Context, tree: Uri): Plan = withContext(Dispatchers.IO) {
        val (root, all) = scanAll(context, tree)
        val have = AppDatabase.get(context).books().all().mapNotNull { it.opdsId }.filter { it.startsWith(PREFIX) }.toSet()
        val (old, new) = all.partition { PREFIX + it.docId in have }
        Plan(tree, root, new, old.size, new.map { it.folder }.distinct().size)
    }

    /** Start importing in the background (the folder stays readable for the work). */
    fun start(context: Context, plan: Plan, group: Group) {
        val app = context.applicationContext
        runCatching {
            app.contentResolver.takePersistableUriPermission(plan.tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<Worker>()
                .setInputData(workDataOf("tree" to plan.tree.toString(), "group" to group.name))
                .build(),
        )
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        private var lastNote = 0L

        override suspend fun getForegroundInfo(): ForegroundInfo = info("폴더를 살펴보는 중…", 0, 0)

        override suspend fun doWork(): Result {
            val app = applicationContext
            val tree = inputData.getString("tree")?.let(Uri::parse) ?: return Result.failure()
            val group = runCatching { Group.valueOf(inputData.getString("group") ?: "") }.getOrDefault(Group.BOOK_FOLDER)
            channel(app)
            runCatching { setForeground(getForegroundInfo()) }
            val plan = try { plan(app, tree) } catch (e: Exception) {
                done(app, "폴더를 읽지 못했습니다: ${e.message}"); return Result.failure()
            }
            val db = AppDatabase.get(app)
            val store = BookStore.get(app)
            val cols = HashMap<String, String>()   // collection name → id
            suspend fun collectionFor(item: Item): String? {
                val name = when (group) {
                    Group.BOOK_FOLDER -> item.folder
                    Group.PICKED_FOLDER -> plan.rootName
                    Group.NONE -> return null
                }
                return cols.getOrPut(name) { ShelfCollections.findOrCreate(db, name).id }
            }
            var ok = 0
            val failed = ArrayList<String>()
            plan.items.forEachIndexed { i, item ->
                if (isStopped) return Result.success()
                note(plan.items.size, i, item.name)
                val tmp = File(app.cacheDir, "folder_${System.nanoTime()}.bin")
                try {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.docId)
                    app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        ?: throw java.io.IOException("파일을 열 수 없습니다")
                    val book = store.import(
                        tmp, sourceUrl = null, opdsId = PREFIX + item.docId,
                        titleHint = item.name.substringBeforeLast('.'), fallbackTitle = item.name.substringBeforeLast('.'),
                        pinned = true, moveSource = true,
                    )
                    collectionFor(item)?.let { ShelfCollections.set(db, it, book.id, true) }
                    db.books().setOrigin(book.id, com.oasisreader.app.data.BookOrigin.folder(tree.toString(), item.folder))
                    ok++
                } catch (e: Exception) {
                    failed += item.name
                } finally {
                    tmp.delete()
                }
            }
            done(
                app,
                "「${plan.rootName}」 ${ok}권을 서재에 담았습니다" +
                    (if (plan.already > 0) " · 이미 있던 ${plan.already}권은 건너뜀" else "") +
                    (if (failed.isNotEmpty()) " · 실패 ${failed.size}권: " + failed.take(3).joinToString(", ") + (if (failed.size > 3) " …" else "") else ""),
            )
            return Result.success()
        }

        private suspend fun note(total: Int, done: Int, name: String) {
            val now = System.currentTimeMillis()
            if (now - lastNote < 700) return
            lastNote = now
            runCatching { setForeground(info("${done + 1}/$total · $name", done, total)) }
        }

        private fun info(text: String, done: Int, total: Int): ForegroundInfo {
            val app = applicationContext
            channel(app)
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("📁 폴더에서 가져오는 중")
                .setContentText(text)
                .setProgress(total.coerceAtLeast(0), done, total == 0)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .addAction(0, "취소", WorkManager.getInstance(app).createCancelPendingIntent(id))
                .build()
            return ForegroundInfo(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    private const val NOTE_ID = 7501

    private fun channel(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "폴더에서 가져오기", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }

    private fun done(app: Context, text: String) {
        channel(app)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("📁 가져오기 끝")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                android.app.PendingIntent.getActivity(app, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            })
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTE_ID + 1, n) }
    }
}
