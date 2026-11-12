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
    // KOReader document id (partial MD5 of the original file) for bookoasis progress sync
    val kosyncDoc: String? = null,
    // where it came from, for 다음 권 / 있던 폴더 열기: JSON of [com.oasisreader.app.data.BookOrigin] (DB v8)
    val origin: String? = null,
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

/** A saved place in a book: chapter (or comic page) + fraction, with a few words from that page. */
@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val spineIndex: Int,
    val fraction: Float,
    val snippet: String,
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

    @Query("UPDATE books SET kosyncDoc = :doc WHERE id = :id")
    suspend fun setKosyncDoc(id: String, doc: String?)

    @Query("SELECT * FROM books WHERE kosyncDoc IS NULL")
    suspend fun withoutKosyncDoc(): List<BookEntity>

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: BookEntity)

    @Query("UPDATE books SET spineIndex = :spine, chapterFraction = :fraction, progress = :progress, lastReadAt = :now WHERE id = :id")
    suspend fun savePosition(id: String, spine: Int, fraction: Float, progress: Float, now: Long)

    @Query("UPDATE books SET origin = :origin WHERE id = :id")
    suspend fun setOrigin(id: String, origin: String?)

    @Query("SELECT * FROM books WHERE origin = :origin")
    suspend fun withOrigin(origin: String): List<BookEntity>

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

    @Query("SELECT * FROM highlights")
    suspend fun all(): List<HighlightEntity>

    @Upsert
    suspend fun upsert(h: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Highlights kept from a removed book go to the same book received again. */
    @Query("UPDATE highlights SET bookId = :to WHERE bookId = :from")
    suspend fun moveBook(from: String, to: String)

    @Query("SELECT COUNT(*) FROM highlights WHERE bookId = :bookId")
    suspend fun countFor(bookId: String): Int
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY spineIndex, fraction")
    fun observeFor(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

/**
 * A book removed from the 서재 whose highlights were kept: what is needed to show and export them
 * (title, author, chapter titles by spine index as JSON {"3":"제2장"}), and where it came from, so
 * that receiving the same book again joins the highlights back up.
 */
@Entity(tableName = "removed_books")
data class RemovedBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    val format: String = "epub",
    val chapters: String = "{}",
    val opdsId: String? = null,
    val sourceUrl: String? = null,
    val identifier: String? = null,
    val removedAt: Long,
)

@Dao
interface RemovedBookDao {
    @Query("SELECT * FROM removed_books")
    fun observeAll(): Flow<List<RemovedBookEntity>>

    @Query("SELECT * FROM removed_books WHERE id = :id")
    suspend fun get(id: String): RemovedBookEntity?

    @Query("SELECT * FROM removed_books")
    suspend fun all(): List<RemovedBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: RemovedBookEntity)

    @Query("DELETE FROM removed_books WHERE id = :id")
    suspend fun delete(id: String)
}

/** 독후감: the reader's own writing about a book, one per book. It stays when the book is removed (DB v9). */
@Entity(tableName = "reviews")
data class ReviewEntity(
    @PrimaryKey val bookId: String,
    val text: String,
    val rating: Int = 0,          // 0 = none, 1..5 stars
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ReviewDao {
    @Query("SELECT * FROM reviews ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ReviewEntity>>

    @Query("SELECT * FROM reviews WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReviewEntity?

    @Upsert
    suspend fun upsert(r: ReviewEntity)

    @Query("DELETE FROM reviews WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

/**
 * 오디오북 책갈피: a place in an audiobook (track + seconds, with [lengthSec] > 0 for a stretch),
 * with the reader's note. [text] is for words heard there (captions, later). [trackTitle] is kept
 * so the mark still reads well when the book is gone (DB v9).
 */
@Entity(tableName = "audio_marks", indices = [Index("bookId")])
data class AudioMarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val track: Int,
    val startSec: Double,
    val lengthSec: Double,
    val trackTitle: String,
    val note: String? = null,
    val text: String? = null,
    val createdAt: Long,
)

@Dao
interface AudioMarkDao {
    @Query("SELECT * FROM audio_marks WHERE bookId = :bookId ORDER BY track, startSec")
    fun observeFor(bookId: String): Flow<List<AudioMarkEntity>>

    @Query("SELECT * FROM audio_marks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudioMarkEntity>>

    @Query("SELECT * FROM audio_marks WHERE bookId = :bookId ORDER BY track, startSec")
    suspend fun allFor(bookId: String): List<AudioMarkEntity>

    @Upsert
    suspend fun upsert(m: AudioMarkEntity)

    @Query("DELETE FROM audio_marks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM audio_marks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("UPDATE audio_marks SET bookId = :to WHERE bookId = :from")
    suspend fun moveBook(from: String, to: String)

    @Query("SELECT COUNT(*) FROM audio_marks WHERE bookId = :bookId")
    suspend fun countFor(bookId: String): Int
}

/** A removed book is forgotten once nothing of it is kept any more (highlights, 책갈피, 독후감). */
suspend fun AppDatabase.forgetRemovedIfEmpty(id: String) {
    if (books().get(id) == null && highlights().countFor(id) == 0 && audioMarks().countFor(id) == 0 && reviews().get(id) == null) {
        removedBooks().delete(id)
    }
}

/** A shelf of the user's own (컬렉션): "출퇴근용", "아이들과 함께" … */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val sort: Int,
)

/** A book on a 컬렉션 (a book can be on several). */
@Entity(tableName = "book_collections", primaryKeys = ["collectionId", "bookId"], indices = [Index("bookId")])
data class BookCollectionEntity(
    val collectionId: String,
    val bookId: String,
    val addedAt: Long,
)

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY sort, createdAt")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM book_collections")
    fun observeLinks(): Flow<List<BookCollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY sort, createdAt")
    suspend fun all(): List<CollectionEntity>

    @Query("SELECT COALESCE(MAX(sort), 0) FROM collections")
    suspend fun maxSort(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: CollectionEntity)

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: String)

    @Query("DELETE FROM book_collections WHERE collectionId = :id")
    suspend fun unlinkCollection(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(l: BookCollectionEntity)

    @Query("DELETE FROM book_collections WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun unlink(collectionId: String, bookId: String)

    @Query("DELETE FROM book_collections WHERE bookId = :bookId")
    suspend fun unlinkBook(bookId: String)
}

@Dao
interface ReadingTimeDao {
    @Query("SELECT * FROM reading_time WHERE day = :day AND bookId = :bookId")
    suspend fun get(day: String, bookId: String): ReadingTimeEntity?

    @Upsert
    suspend fun upsert(e: ReadingTimeEntity)

    @Query("SELECT * FROM reading_time")
    fun observeAll(): Flow<List<ReadingTimeEntity>>

    @Query("SELECT * FROM reading_time WHERE bookId = :bookId")
    suspend fun forBook(bookId: String): List<ReadingTimeEntity>

    @Query("SELECT * FROM reading_time")
    suspend fun all(): List<ReadingTimeEntity>
}

@Database(
    entities = [
        BookEntity::class, HighlightEntity::class, ReadingTimeEntity::class, BookmarkEntity::class, CollectionEntity::class,
        BookCollectionEntity::class, RemovedBookEntity::class, ReviewEntity::class, AudioMarkEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun highlights(): HighlightDao
    abstract fun readingTime(): ReadingTimeDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun collections(): CollectionDao
    abstract fun removedBooks(): RemovedBookDao
    abstract fun reviews(): ReviewDao
    abstract fun audioMarks(): AudioMarkDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // NOTE: once real data exists, schema changes need real Migrations — never a destructive fallback.
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "oasis.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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

/** v4: KOReader document id for bookoasis progress sync (filled in from each book's original file). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN kosyncDoc TEXT")
    }
}

/** v5: bookmarks. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `spineIndex` INTEGER NOT NULL, `fraction` REAL NOT NULL, `snippet` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
    }
}

/** v6: 컬렉션 (the user's own shelves). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sort` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `book_collections` (`collectionId` TEXT NOT NULL, `bookId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `bookId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_collections_bookId` ON `book_collections` (`bookId`)")
    }
}

/** v7: removed books whose highlights were kept. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `removed_books` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `format` TEXT NOT NULL, `chapters` TEXT NOT NULL, `opdsId` TEXT, `sourceUrl` TEXT, `identifier` TEXT, `removedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** v8: books.origin — the OPDS folder / meta series / phone folder a book came from. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `origin` TEXT")
    }
}

/** v9: 독후감 (one per book) and 오디오북 책갈피. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `reviews` (`bookId` TEXT NOT NULL, `text` TEXT NOT NULL, `rating` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `audio_marks` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `track` INTEGER NOT NULL, `startSec` REAL NOT NULL, `lengthSec` REAL NOT NULL, `trackTitle` TEXT NOT NULL, `note` TEXT, `text` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_marks_bookId` ON `audio_marks` (`bookId`)")
    }
}
