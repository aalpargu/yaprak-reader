package com.yaprak.reader.data.storage

import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderOpenException
import com.yaprak.reader.reader.ReaderSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/** Serializes private file mutations and keeps removal away from live reader sessions. */
class BookOperationCoordinator {
    val fileMutex = Mutex()
    private val monitor = Any()
    private val openReaders = mutableMapOf<String, Int>()
    private val removing = mutableSetOf<String>()

    fun track(loader: ReaderLoader): ReaderLoader = ReaderLoader { bookId ->
        synchronized(monitor) {
            if (bookId in removing) throw ReaderOpenException(ReaderFailure.MISSING)
            openReaders[bookId] = (openReaders[bookId] ?: 0) + 1
        }
        try {
            val session = loader.open(bookId)
            object : ReaderSession by session {
                private val closed = AtomicBoolean(false)
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        try { session.close() } finally { release(bookId) }
                    }
                }
            }
        } catch (error: Throwable) {
            release(bookId)
            throw error
        }
    }

    /** Call under [fileMutex]; endRemoval must run in finally after a successful begin. */
    fun beginRemoval(bookId: String): Boolean = synchronized(monitor) {
        if ((openReaders[bookId] ?: 0) > 0 || bookId in removing) false
        else { removing += bookId; true }
    }

    fun endRemoval(bookId: String) = synchronized(monitor) { removing.remove(bookId); Unit }

    private fun release(bookId: String) = synchronized(monitor) {
        val count = openReaders[bookId] ?: return@synchronized
        if (count <= 1) openReaders.remove(bookId) else openReaders[bookId] = count - 1
    }
}
