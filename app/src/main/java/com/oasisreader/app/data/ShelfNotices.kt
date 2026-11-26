package com.oasisreader.app.data

/**
 * "서재에 담았습니다" said by the data layer. The screen side (ui.Notice) listens. While nothing
 * has listened yet (a worker running with no screen) the latest message waits here, and is handed
 * over the moment the screen side first wakes: before anything it shows itself, so the last word wins.
 */
object ShelfNotices {
    private var listener: ((message: String, bookId: String) -> Unit)? = null
    private var waiting: Pair<String, String>? = null

    /** A book (or the first of several) went into the 서재. */
    @Synchronized
    fun added(message: String, bookId: String) {
        val l = listener
        if (l != null) l(message, bookId) else waiting = message to bookId
    }

    private var offerListener: ((message: String, label: String, run: () -> Unit) -> Unit)? = null

    /** A short card with one thing to do about it (103); dropped when no screen listens. */
    @Synchronized
    fun offer(message: String, label: String, run: () -> Unit) { offerListener?.invoke(message, label, run) }

    @Synchronized
    fun listenOffers(l: (message: String, label: String, run: () -> Unit) -> Unit) { offerListener = l }

    @Synchronized
    fun listen(l: (message: String, bookId: String) -> Unit) {
        listener = l
        waiting?.let { (message, bookId) -> waiting = null; l(message, bookId) }
    }
}
