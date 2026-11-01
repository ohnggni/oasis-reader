package com.oasisreader.app.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A book in the library. Files live in filesDir/books/<id>/ (book.epub, content/, book.json, cover.jpg).
 * Reading position = (spineIndex, chapterFraction); [progress] is the overall estimate shown in the library.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val identifier: String? = null,
    val addedAt: Long,
    val lastReadAt: Long? = null,
    val finishedAt: Long? = null,
    val spineIndex: Int = 0,
    val chapterFraction: Float = 0f,
    val progress: Float = 0f,
    val sourceUrl: String? = null,     // OPDS acquisition link (re-download after a device move)
    val opdsId: String? = null,        // OPDS entry id (duplicate check)
    val fileBytes: Long = 0,
    val hasCover: Boolean = false,
    @ColumnInfo(defaultValue = "epub") val format: String = "epub",   // "epub" | "cbz" | "txt" | "pdf"
    // false = opened with "바로 읽기": the file may be dropped when the cache is full
    // (position, highlights and the cover stay; it is fetched again on open)
    @ColumnInfo(defaultValue = "1") val pinned: Boolean = true,
)

@Entity(tableName = "highlights", indices = [Index("bookId")])
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val spineIndex: Int,
    val text: String,
    val color: String,
    val anchor: String? = null,
    val note: String? = null,
    val createdAt: Long,
)

/** Active reading seconds per local day and book (running totals). */
@Entity(tableName = "reading_time", primaryKeys = ["day", "bookId"])
data class ReadingTimeEntity(
    val day: String,      // yyyy-MM-dd, local
    val bookId: String,
    val seconds: Long,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, addedAt) DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE (opdsId IS NOT NULL AND opdsId = :opdsId) OR (sourceUrl IS NOT NULL AND sourceUrl = :sourceUrl) LIMIT 1")
    suspend fun findBySource(opdsId: String?, sourceUrl: String?): BookEntity?

    @Query("SELECT opdsId FROM books WHERE opdsId IS NOT NULL")
    fun observeOpdsIds(): Flow<List<String>>

    @Query("SELECT sourceUrl FROM books WHERE sourceUrl IS NOT NULL")
    fun observeSourceUrls(): Flow<List<String>>

    @Query("SELECT sourceUrl FROM books WHERE sourceUrl IS NOT NULL AND pinned = 1")
    fun observePinnedSourceUrls(): Flow<List<String>>

    @Query("UPDATE books SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: BookEntity)

    @Query("UPDATE books SET spineIndex = :spine, chapterFraction = :fraction, progress = :progress, lastReadAt = :now WHERE id = :id")
    suspend fun savePosition(id: String, spine: Int, fraction: Float, progress: Float, now: Long)

    @Query("UPDATE books SET finishedAt = :at WHERE id = :id")
    suspend fun setFinished(id: String, at: Long?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY spineIndex, createdAt")
    fun observeFor(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY spineIndex, createdAt")
    suspend fun allFor(bookId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND spineIndex = :spine")
    suspend fun forChapter(bookId: String, spine: Int): List<HighlightEntity>

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HighlightEntity>>

    @Upsert
    suspend fun upsert(h: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

@Dao
interface ReadingTimeDao {
    @Query("SELECT * FROM reading_time WHERE day = :day AND bookId = :bookId")
    suspend fun get(day: String, bookId: String): ReadingTimeEntity?

    @Upsert
    suspend fun upsert(e: ReadingTimeEntity)

    @Query("SELECT * FROM reading_time")
    fun observeAll(): Flow<List<ReadingTimeEntity>>
}

@Database(
    entities = [BookEntity::class, HighlightEntity::class, ReadingTimeEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun highlights(): HighlightDao
    abstract fun readingTime(): ReadingTimeDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // NOTE: once real data exists, schema changes need real Migrations — never a destructive fallback.
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "oasis.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }

        /** Close before replacing the database file (backup restore). */
        fun closeInstance() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}

/** v2: webtoons (CBZ/ZIP) next to EPUBs. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'epub'")
    }
}

/** v3: "바로 읽기" books that live in the cache (every existing book stays kept). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN pinned INTEGER NOT NULL DEFAULT 1")
    }
}
