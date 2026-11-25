package com.oasisreader.app.data

import com.oasisreader.app.data.db.AppDatabase
import com.oasisreader.app.data.db.BookCollectionEntity
import com.oasisreader.app.data.db.CollectionEntity

/** 컬렉션 helpers on the database (ids like the books': time + random, base 36). */
object ShelfCollections {
    fun newId(): String = "c" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)

    suspend fun create(db: AppDatabase, name: String): CollectionEntity {
        val c = CollectionEntity(newId(), name.trim(), System.currentTimeMillis(), db.collections().maxSort() + 1)
        db.collections().insert(c)
        return c
    }

    /** The 컬렉션 called [name] (same name, ignoring case and spaces at the ends), made when missing. */
    suspend fun findOrCreate(db: AppDatabase, name: String): CollectionEntity {
        val n = name.trim()
        return db.collections().all().firstOrNull { it.name.trim().equals(n, ignoreCase = true) }
            ?: create(db, n)
    }

    suspend fun delete(db: AppDatabase, id: String) {
        db.collections().unlinkCollection(id)
        db.collections().deleteCollection(id)
    }

    suspend fun set(db: AppDatabase, collectionId: String, bookId: String, on: Boolean) {
        if (on) db.collections().link(BookCollectionEntity(collectionId, bookId, System.currentTimeMillis()))
        else db.collections().unlink(collectionId, bookId)
    }
}
