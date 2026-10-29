package com.yaprak.reader.data.persistence

import android.database.Cursor
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.sqlite.db.SimpleSQLiteQuery
import com.yaprak.reader.domain.vocabulary.*
import java.util.Locale
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Search every matching current-content row, retaining only a bounded newest result heap. */
class RoomLibraryWordsRepository(
    private val database: LibraryDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fileMutex: Mutex = Mutex(),
    private val validateRead: () -> Unit = {},
) : LibraryWordsRepository {
    override fun observe(query: String) = database.invalidationTracker.createFlow("books", "saved_words")
        .catch { failure ->
            currentCoroutineContext().ensureActive()
            if (failure is Exception) throw LibraryWordsSourceException() else throw failure
        }.map {
            read { caller ->
                val terms = limitLibraryWordQuery(query).key().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
                val newest = PriorityQueue(MAX_LIBRARY_WORD_RESULTS, oldestFirst)
                var count = 0
                database.openHelper.writableDatabase.query(SimpleSQLiteQuery(LIBRARY_WORDS_JOIN)).use { cursor ->
                    while (true) {
                        active(caller)
                        if (!cursor.moveToNext()) break
                        active(caller)
                        val entry = cursor.readLibraryWord()
                        val corpus = if (terms.isEmpty()) "" else
                            (entry.word.english + "\n" + entry.word.turkish + "\n" + entry.bookTitle + "\n" + (entry.bookAuthors ?: "")).key()
                        if (terms.any { !corpus.contains(it) }) continue
                        valid(count < Int.MAX_VALUE); count++
                        if (newest.size < MAX_LIBRARY_WORD_RESULTS) newest.add(entry)
                        else if (oldestFirst.compare(entry, newest.peek()) > 0) { newest.poll(); newest.add(entry) }
                    }
                }
                active(caller)
                LibraryWordsResult(newest.sortedWith(oldestFirst.reversed()), count)
            }
        }

    override suspend fun find(bookId: String, contentSha256: String, wordId: String): LibraryWord? = read { caller ->
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "$LIBRARY_WORDS_JOIN WHERE saved.bookId = ? AND saved.contentSha256 = ? AND saved.id = ?",
            arrayOf(bookId, contentSha256, wordId),
        )).use { cursor ->
            active(caller)
            if (!cursor.moveToFirst()) null else { active(caller); cursor.readLibraryWord() }
        }
    }

    private suspend fun <T> read(block: suspend (CoroutineContext) -> T): T = withContext(io) {
        val caller = currentCoroutineContext()
        try {
            fileMutex.withLock {
                caller.ensureActive()
                validateRead()
                try {
                    database.useReaderConnection { connection ->
                        connection.withTransaction(SQLiteTransactionType.DEFERRED) { active(caller); block(caller) }
                    }
                } catch (cancelled: CancellationException) {
                    caller.ensureActive()
                    throw LibraryWordsSourceException()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: LibraryWordsSourceException) { throw invalid }
        catch (_: Exception) { throw LibraryWordsSourceException() }
    }
}

internal const val LIBRARY_WORDS_JOIN = """
    SELECT saved.id, saved.english, saved.turkish, saved.createdAt,
           saved.bookId, saved.contentSha256, book.title, book.authors, saved.revision
    FROM saved_words AS saved INNER JOIN books AS book
      ON book.id = saved.bookId AND book.sha256 = saved.contentSha256
"""
private val oldestFirst = compareBy<LibraryWord> { it.word.createdAt }.thenBy { it.word.id }
private fun String.key() = lowercase(Locale.ROOT).replace("i\u0307", "i").replace('ı', 'i')
private suspend fun active(caller: CoroutineContext) { currentCoroutineContext().ensureActive(); caller.ensureActive() }
private fun valid(condition: Boolean) { if (!condition) throw LibraryWordsSourceException() }
internal fun Cursor.readLibraryWord(): LibraryWord {
    valid(columnCount >= 9)
    fun text(index: Int, limit: Int): String {
        valid(!isNull(index) && getType(index) == Cursor.FIELD_TYPE_STRING)
        return getString(index).also { valid(it.length in 1..limit && it.isNotBlank()) }
    }
    val id = text(0, 1000)
    val english = text(1, MAX_ENGLISH_WORD_LENGTH)
    val turkish = text(2, MAX_TURKISH_MEANING_LENGTH)
    valid(english.none { it == '\n' || it == '\r' } && turkish.none { it == '\n' || it == '\r' })
    valid(getType(3) == Cursor.FIELD_TYPE_INTEGER)
    val timestamp = getLong(3)
    val book = text(4, 1000)
    val hash = text(5, 64).also { valid(it.matches(Regex("[0-9a-f]{64}"))) }
    val title = text(6, 1000)
    val authors = if (isNull(7)) null else text(7, 2000)
    valid(getType(8) == Cursor.FIELD_TYPE_INTEGER && getLong(8) >= 0)
    return LibraryWord(SavedWord(id, english, turkish, timestamp, getLong(8)), book, hash, title, authors)
}
