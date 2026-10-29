package com.yaprak.reader.data.search

import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.search.*
import java.util.UUID
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Source bytes and derived publication commits share the library's restore/removal mutex. */
class LocalLibraryTextIndex(
    private val database: TextIndexDatabase,
    private val library: LibraryRepository,
    private val extractor: BookTextExtractor,
    private val fileMutex: Mutex = Mutex(),
    private val validateRead: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibraryTextIndex, BookSourceRetriever {
    private val rebuildMutex = Mutex()

    override fun observe(query: String) = observeScoped(query, null)
    override fun observeBook(query: String, scope: BookTextScope) = observeScoped(query, scope)

    private fun observeScoped(query: String, scope: BookTextScope?) = flow {
        guarded {
            libraryTextQueryTerms(query)
            if (scope != null && !scope.isValid) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
        }
        emitAll(combine(library.observeBooks(),
            database.invalidationTracker.createFlow("text_index_books", "text_index_chunks")) { _, _ ->
            guarded {
                // Finish the other Room database's flow before acquiring this cache transaction.
                val books = library.observeBooks().first()
                val caller = currentCoroutineContext()
                database.useReaderConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                        search(query, books, TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }, scope)
                    }
                }
            }
        })
    }.catch { failure ->
        if (failure is CancellationException) currentCoroutineContext().ensureActive()
        if (failure is LibraryTextIndexException) throw failure
        throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
    }

    override suspend fun prepare(scope: BookTextScope, query: String): PreparedBookSources {
        try {
            return guarded {
                if (!scope.isValid) throw BookSourceException(BookSourceFailure.SOURCE)
                if (libraryTextQueryTerms(query).isEmpty()) throw BookSourceException(BookSourceFailure.INVALID_QUERY)
                val books = library.observeBooks().first()
                val book = books.singleOrNull { it.id == scope.bookId && it.sha256 == scope.contentSha256 }
                    ?: throw BookSourceException(BookSourceFailure.SOURCE)
                if (!supportsBookSourceRetrieval(book.format) || !extractor.supports(book))
                    throw BookSourceException(BookSourceFailure.UNSUPPORTED)
                val caller = currentCoroutineContext()
                // Finish the cache lease before opening real publication resources.
                val result = database.useReaderConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                        search(query, books, TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() },
                            scope, MAX_BOOK_SOURCE_REFERENCES)
                    }
                }
                when (result.books.single { it.book.id == book.id }.state) {
                    LibraryTextBookState.READY -> Unit
                    LibraryTextBookState.NOT_INDEXED -> throw BookSourceException(BookSourceFailure.NOT_INDEXED)
                    LibraryTextBookState.UNSUPPORTED -> throw BookSourceException(BookSourceFailure.UNSUPPORTED)
                    LibraryTextBookState.LIMIT -> throw BookSourceException(BookSourceFailure.LIMIT)
                    LibraryTextBookState.FAILED -> throw BookSourceException(BookSourceFailure.SOURCE)
                    LibraryTextBookState.NO_TEXT -> throw BookSourceException(BookSourceFailure.UNSUPPORTED)
                }
                // Even an empty match result must prove the file; cached absence is not source proof.
                val document = try { extractor.extract(book) }
                    catch (cancelled: CancellationException) { throw ExtractionCancelled(cancelled) }
                caller.ensureActive()
                validateDocument(book, document)
                for (hit in result.hits) {
                    caller.ensureActive()
                    val chunk = document.chunks.getOrNull(hit.chunkIndex)
                        ?: throw BookSourceException(BookSourceFailure.SOURCE)
                    if (chunk.sourceFormat != hit.target.sourceFormat || chunk.sourceFormat != book.format ||
                        chunk.pdfPageIndex != hit.target.pdfPageIndex ||
                        chunk.readingOrderIndex != hit.readingOrderIndex || chunk.resourceHref != hit.target.resourceHref ||
                        chunk.resourceSha256 != hit.target.resourceSha256 || chunk.chapterTitle != hit.chapterTitle ||
                        chunk.text != hit.text) throw BookSourceException(BookSourceFailure.SOURCE)
                }
                // observeBooks projects lastReadAt from progress; findById returns stored metadata.
                // Reading chronology is not part of this source/metadata proof.
                if (library.findById(book.id)?.copy(lastReadAt = null) != book.copy(lastReadAt = null))
                    throw BookSourceException(BookSourceFailure.SOURCE)
                caller.ensureActive()
                PreparedBookSources(UUID.randomUUID().toString(), scope, query, result.totalMatches,
                    result.hits.mapIndexed { index, hit -> BookSourceReference("S${index + 1}", hit) })
            }
        } catch (cancelled: ExtractionCancelled) { throw cancelled.original }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LibraryTextIndexException) {
            throw BookSourceException(when (failure.reason) {
                LibraryTextIndexFailure.INVALID_QUERY -> BookSourceFailure.INVALID_QUERY
                LibraryTextIndexFailure.RECOVERY -> BookSourceFailure.RECOVERY
                LibraryTextIndexFailure.BUSY -> BookSourceFailure.BUSY
                LibraryTextIndexFailure.SOURCE -> BookSourceFailure.SOURCE
            })
        } catch (failure: TextExtractionException) {
            throw BookSourceException(when (failure.reason) {
                TextExtractionFailure.UNSUPPORTED -> BookSourceFailure.UNSUPPORTED
                TextExtractionFailure.LIMIT -> BookSourceFailure.LIMIT
                TextExtractionFailure.INVALID, TextExtractionFailure.SOURCE -> BookSourceFailure.SOURCE
            })
        }
    }

    override suspend fun listSections(scope: BookTextScope): List<BookSourceSection> = sectionOperation {
        val snapshot = readSectionSnapshot(scope, null)
        requireCurrentSectionBook(snapshot.book)
        Collections.unmodifiableList(ArrayList(snapshot.sections))
    }

    override suspend fun prepareSection(scope: BookTextScope, section: BookSourceSection): PreparedBookSources = sectionOperation {
        if (!section.isValid || section.scope != scope) throw BookSourceException(BookSourceFailure.SOURCE)
        val snapshot = readSectionSnapshot(scope, section)
        val caller = currentCoroutineContext()
        // Complete extraction first, outside the cache reader lease. No prefix, FTS subset, or first-N success.
        val document = try { extractor.extract(snapshot.book) }
            catch (cancelled: CancellationException) { throw ExtractionCancelled(cancelled) }
        caller.ensureActive()
        val bytes = validateDocument(snapshot.book, document)
        if (document.chunks.size != snapshot.ledger.chunkCount || bytes != snapshot.ledger.stringBytes)
            throw BookSourceException(BookSourceFailure.SOURCE)
        val complete = document.chunks.filter { it.readingOrderIndex == section.readingOrderIndex }
        if (complete.size != section.chunkCount || snapshot.rows.size != complete.size)
            throw BookSourceException(BookSourceFailure.SOURCE)
        val references = snapshot.rows.mapIndexed { index, row ->
            caller.ensureActive()
            val hit = row.hit(snapshot.book, libraryTextSnippet(row.text, emptyList()))
            val chunk = complete[index]
            if (chunk.sourceFormat != section.sourceFormat || chunk.sourceFormat != hit.target.sourceFormat ||
                chunk.pdfPageIndex != section.pdfPageIndex || chunk.pdfPageIndex != hit.target.pdfPageIndex ||
                chunk.chunkIndex != section.firstChunkIndex + index || chunk.readingOrderIndex != hit.readingOrderIndex ||
                chunk.chunkIndex != hit.chunkIndex || chunk.resourceHref != hit.target.resourceHref ||
                chunk.resourceSha256 != hit.target.resourceSha256 || chunk.chapterTitle != hit.chapterTitle ||
                chunk.text != hit.text) throw BookSourceException(BookSourceFailure.SOURCE)
            BookSourceReference("S${index + 1}", hit)
        }
        requireCurrentSectionBook(snapshot.book)
        caller.ensureActive()
        PreparedBookSources(UUID.randomUUID().toString(), scope, "", references.size, references, section).also {
            if (!validCompleteSectionSources(it)) throw BookSourceException(BookSourceFailure.SOURCE)
        }
    }

    private data class SectionSnapshot(val book: Book, val ledger: TextIndexBookEntity,
        val sections: List<BookSourceSection>, val rows: List<TextIndexChunkEntity>)

    override suspend fun listChapters(scope: BookTextScope): List<BookSourceChapter> = sectionOperation {
        val snapshot = readSectionSnapshot(scope, null)
        if (snapshot.book.format != BookFormat.EPUB) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        val evidence = readChapterEvidence(snapshot)
        val chapters = bindChapters(snapshot, evidence)
        requireCurrentSectionBook(snapshot.book)
        Collections.unmodifiableList(ArrayList(chapters))
    }

    override suspend fun prepareChapter(scope: BookTextScope, chapter: BookSourceChapter): PreparedBookSources = sectionOperation {
        if (!chapter.isValid || chapter.scope != scope) throw BookSourceException(BookSourceFailure.SOURCE)
        val snapshot = readSectionSnapshot(scope, null)
        if (snapshot.book.format != BookFormat.EPUB) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        if (snapshot.ledger.generation != chapter.generation) throw BookSourceException(BookSourceFailure.SOURCE)
        val evidence = readChapterEvidence(snapshot)
        val chapters = bindChapters(snapshot, evidence)
        if (chapters.singleOrNull { it == chapter } == null) throw BookSourceException(BookSourceFailure.SOURCE)
        if (!chapter.fitsSingleRequest) {
            throw BookSourceException(if (chapter.sourceCount == 0) BookSourceFailure.UNSUPPORTED else BookSourceFailure.LIMIT)
        }
        val caller = currentCoroutineContext()
        val pieces = extractedChapterPieces(evidence, chapter.start, chapter.end) { caller.ensureActive() }
        if (pieces.size != chapter.sourceCount) throw BookSourceException(BookSourceFailure.SOURCE)
        // The fresh publication has closed before a new bounded cache reader lease is taken.
        val rows = database.useReaderConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                val sql = TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }
                val ledger = sql.books().singleOrNull { it.bookId == snapshot.book.id }
                if (ledger != snapshot.ledger) throw BookSourceException(BookSourceFailure.SOURCE)
                sql.chapterChunks(snapshot.book.id, pieces.map { it.chunkIndex })
            }
        }
        if (rows.size != pieces.size) throw BookSourceException(BookSourceFailure.SOURCE)
        val references = pieces.mapIndexed { index, piece ->
            caller.ensureActive()
            val row = rows[index]
            val hit = row.hit(snapshot.book, libraryTextSnippet(row.text, emptyList()))
            val chunk = evidence.document.chunks.getOrNull(piece.chunkIndex)
                ?: throw BookSourceException(BookSourceFailure.SOURCE)
            if (hit.generation != chapter.generation || hit.chunkIndex != piece.chunkIndex ||
                chunk.readingOrderIndex != hit.readingOrderIndex || chunk.chunkIndex != hit.chunkIndex ||
                chunk.resourceHref != hit.target.resourceHref || chunk.resourceSha256 != hit.target.resourceSha256 ||
                chunk.chapterTitle != hit.chapterTitle || chunk.text != hit.text || !piece.excerpt.isValid(hit.text))
                throw BookSourceException(BookSourceFailure.SOURCE)
            BookSourceReference("S" + (index + 1), hit, piece.excerpt)
        }
        requireCurrentSectionBook(snapshot.book)
        caller.ensureActive()
        PreparedBookSources(UUID.randomUUID().toString(), scope, "", references.size, references, chapter = chapter).also {
            if (!validCompleteChapterSources(it)) throw BookSourceException(BookSourceFailure.SOURCE)
        }
    }

    override suspend fun preparePrefix(scope: BookTextScope, throughChapter: BookSourceChapter): PreparedBookSources = sectionOperation {
        val chapter = throughChapter
        if (!chapter.isValid || chapter.scope != scope) throw BookSourceException(BookSourceFailure.SOURCE)
        val snapshot = readSectionSnapshot(scope, null)
        if (snapshot.book.format != BookFormat.EPUB) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        if (snapshot.ledger.generation != chapter.generation) throw BookSourceException(BookSourceFailure.SOURCE)
        val evidence = readChapterEvidence(snapshot)
        val chapters = bindChapters(snapshot, evidence)
        if (chapters.singleOrNull { it == chapter } == null) throw BookSourceException(BookSourceFailure.SOURCE)
        val first = evidence.document.chunks.firstOrNull() ?: throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        if (first.chunkIndex != 0 || evidence.chunkTextOffsets.firstOrNull() != 0)
            throw BookSourceException(BookSourceFailure.SOURCE)
        val beginning = BookChapterBoundary(first.readingOrderIndex, first.resourceHref, first.resourceSha256, 0)
        if (compareChapterBoundaries(beginning, chapter.end) >= 0)
            throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        val caller = currentCoroutineContext()
        val pieces = extractedChapterPieces(evidence, beginning, chapter.end) { caller.ensureActive() }
        if (pieces.isEmpty()) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        val prefix = BookSourcePrefix(chapter, beginning, pieces.size)
        if (!prefix.isValid) throw BookSourceException(BookSourceFailure.SOURCE)
        if (!prefix.fitsSingleRequest) throw BookSourceException(BookSourceFailure.LIMIT)
        // The fresh publication has closed before a new bounded cache reader lease is taken.
        val rows = database.useReaderConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                val sql = TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }
                val ledger = sql.books().singleOrNull { it.bookId == snapshot.book.id }
                if (ledger != snapshot.ledger) throw BookSourceException(BookSourceFailure.SOURCE)
                sql.chapterChunks(snapshot.book.id, pieces.map { it.chunkIndex })
            }
        }
        if (rows.size != pieces.size) throw BookSourceException(BookSourceFailure.SOURCE)
        val references = pieces.mapIndexed { index, piece ->
            caller.ensureActive()
            val row = rows[index]
            val hit = row.hit(snapshot.book, libraryTextSnippet(row.text, emptyList()))
            val chunk = evidence.document.chunks.getOrNull(piece.chunkIndex)
                ?: throw BookSourceException(BookSourceFailure.SOURCE)
            if (hit.generation != chapter.generation || hit.chunkIndex != piece.chunkIndex ||
                chunk.readingOrderIndex != hit.readingOrderIndex || chunk.chunkIndex != hit.chunkIndex ||
                chunk.resourceHref != hit.target.resourceHref || chunk.resourceSha256 != hit.target.resourceSha256 ||
                chunk.chapterTitle != hit.chapterTitle || chunk.text != hit.text || !piece.excerpt.isValid(hit.text))
                throw BookSourceException(BookSourceFailure.SOURCE)
            BookSourceReference("S" + (index + 1), hit, piece.excerpt)
        }
        requireCurrentSectionBook(snapshot.book)
        caller.ensureActive()
        PreparedBookSources(UUID.randomUUID().toString(), scope, "", references.size, references, prefix = prefix).also {
            if (!validCompletePrefixSources(it)) throw BookSourceException(BookSourceFailure.SOURCE)
        }
    }

    override suspend fun preparePrefixQuery(scope: BookTextScope, throughChapter: BookSourceChapter,
        query: String): PreparedBookSources = sectionOperation {
        val terms = prefixSourceQueryTerms(query)
        val chapter = throughChapter
        if (!chapter.isValid || chapter.scope != scope) throw BookSourceException(BookSourceFailure.SOURCE)
        val snapshot = readSectionSnapshot(scope, null)
        if (snapshot.book.format != BookFormat.EPUB) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        if (snapshot.ledger.generation != chapter.generation) throw BookSourceException(BookSourceFailure.SOURCE)
        val evidence = readChapterEvidence(snapshot)
        if (bindChapters(snapshot, evidence).singleOrNull { it == chapter } == null)
            throw BookSourceException(BookSourceFailure.SOURCE)
        val first = evidence.document.chunks.firstOrNull() ?: throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        if (first.chunkIndex != 0 || evidence.chunkTextOffsets.firstOrNull() != 0)
            throw BookSourceException(BookSourceFailure.SOURCE)
        val beginning = BookChapterBoundary(first.readingOrderIndex, first.resourceHref, first.resourceSha256, 0)
        if (compareChapterBoundaries(beginning, chapter.end) >= 0)
            throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        val caller = currentCoroutineContext()
        // Selection comes from freshly clipped ORIGINAL text, never unbounded cached FTS hits.
        val plan = extractedPrefixQueryPieces(evidence, beginning, chapter.end, query) { caller.ensureActive() }
        val selected = BookSourcePrefixQuery(chapter, beginning, plan.candidateCount)
        if (!selected.isValid) throw BookSourceException(BookSourceFailure.SOURCE)
        val rows = database.useReaderConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                val sql = TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }
                val ledger = sql.books().singleOrNull { it.bookId == snapshot.book.id }
                if (ledger != snapshot.ledger) throw BookSourceException(BookSourceFailure.SOURCE)
                if (plan.pieces.isEmpty()) emptyList()
                else sql.chapterChunks(snapshot.book.id, plan.pieces.map { it.chunkIndex })
            }
        }
        if (rows.size != plan.pieces.size) throw BookSourceException(BookSourceFailure.SOURCE)
        val references = plan.pieces.mapIndexed { index, piece ->
            caller.ensureActive()
            val raw = rows[index].hit(snapshot.book, "")
            val chunk = evidence.document.chunks.getOrNull(piece.chunkIndex)
                ?: throw BookSourceException(BookSourceFailure.SOURCE)
            if (raw.generation != chapter.generation || raw.chunkIndex != piece.chunkIndex ||
                chunk.readingOrderIndex != raw.readingOrderIndex || chunk.chunkIndex != raw.chunkIndex ||
                chunk.resourceHref != raw.target.resourceHref || chunk.resourceSha256 != raw.target.resourceSha256 ||
                chunk.chapterTitle != raw.chapterTitle || chunk.text != raw.text || !piece.excerpt.isValid(raw.text))
                throw BookSourceException(BookSourceFailure.SOURCE)
            val admitted = raw.text.substring(piece.excerpt.startUtf16, piece.excerpt.endUtf16)
            val hit = raw.copy(snippet = libraryTextSnippet(admitted, terms))
            BookSourceReference("S" + (index + 1), hit, piece.excerpt)
        }
        requireCurrentSectionBook(snapshot.book)
        caller.ensureActive()
        PreparedBookSources(UUID.randomUUID().toString(), scope, query, plan.totalMatches,
            references, prefixQuery = selected).also {
            if (!validPrefixQuerySources(it)) throw BookSourceException(BookSourceFailure.SOURCE)
        }
    }


    private suspend fun readChapterEvidence(snapshot: SectionSnapshot): ExtractedBookChapters {
        val caller = currentCoroutineContext()
        val evidence = try { extractor.extractChapters(snapshot.book) }
            catch (cancelled: CancellationException) { throw ExtractionCancelled(cancelled) }
        caller.ensureActive()
        val bytes = validateDocument(snapshot.book, evidence.document)
        if (evidence.document.chunks.size != snapshot.ledger.chunkCount || bytes != snapshot.ledger.stringBytes ||
            evidence.chunkTextOffsets.size != evidence.document.chunks.size ||
            evidence.chapters.size > MAX_BOOK_TOC_ENTRIES)
            throw BookSourceException(BookSourceFailure.SOURCE)
        val fresh = evidence.document.chunks.groupBy { it.readingOrderIndex }.map { (order, chunks) ->
            val first = chunks.first()
            BookSourceSection(BookTextScope(snapshot.book.id, snapshot.book.sha256), snapshot.ledger.generation,
                order, first.resourceHref, first.resourceSha256, first.chapterTitle, first.chunkIndex, chunks.size)
        }
        if (fresh != snapshot.sections) throw BookSourceException(BookSourceFailure.SOURCE)
        return evidence
    }

    private fun bindChapters(snapshot: SectionSnapshot, evidence: ExtractedBookChapters): List<BookSourceChapter> {
        val scope = BookTextScope(snapshot.book.id, snapshot.book.sha256)
        val chapters = evidence.chapters.map {
            BookSourceChapter(scope, snapshot.ledger.generation, it.tocSha256, it.tocIndex, it.title,
                it.depth, it.start, it.end, it.sourceCount)
        }
        if (!validBookSourceChapters(scope, chapters)) throw BookSourceException(BookSourceFailure.SOURCE)
        return chapters
    }

    private suspend fun readSectionSnapshot(scope: BookTextScope, chosen: BookSourceSection?): SectionSnapshot {
        if (!scope.isValid) throw BookSourceException(BookSourceFailure.SOURCE)
        val book = library.observeBooks().first().singleOrNull { it.id == scope.bookId && it.sha256 == scope.contentSha256 }
            ?: throw BookSourceException(BookSourceFailure.SOURCE)
        if (!supportsBookSourceRetrieval(book.format) || !extractor.supports(book)) throw BookSourceException(BookSourceFailure.UNSUPPORTED)
        val caller = currentCoroutineContext()
        return database.useReaderConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                val sql = TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }
                val ledger = sql.books().singleOrNull { it.bookId == book.id }
                    ?: throw BookSourceException(BookSourceFailure.NOT_INDEXED)
                if (!ledger.isValidStoredBook()) throw BookSourceException(BookSourceFailure.SOURCE)
                if (ledger.contentSha256 != book.sha256 || ledger.sourceFormat != book.format.name ||
                    ledger.extractionVersion != LIBRARY_TEXT_EXTRACTION_VERSION) throw BookSourceException(BookSourceFailure.NOT_INDEXED)
                when (LibraryTextBookState.entries.single { it.name == ledger.state }) {
                    LibraryTextBookState.READY -> if (!ledger.matches(book)) throw BookSourceException(BookSourceFailure.SOURCE)
                    LibraryTextBookState.NOT_INDEXED -> throw BookSourceException(BookSourceFailure.NOT_INDEXED)
                    LibraryTextBookState.UNSUPPORTED, LibraryTextBookState.NO_TEXT -> throw BookSourceException(BookSourceFailure.UNSUPPORTED)
                    LibraryTextBookState.LIMIT -> throw BookSourceException(BookSourceFailure.LIMIT)
                    LibraryTextBookState.FAILED -> throw BookSourceException(BookSourceFailure.SOURCE)
                }
                val sections = sql.sections(book, ledger)
                val rows = if (chosen == null) emptyList() else {
                    if (sections.singleOrNull { it == chosen } == null) throw BookSourceException(BookSourceFailure.SOURCE)
                    if (!chosen.fitsSingleRequest) throw BookSourceException(BookSourceFailure.LIMIT)
                    sql.sectionChunks(book.id, chosen.readingOrderIndex).also {
                        if (it.size != chosen.chunkCount) throw BookSourceException(BookSourceFailure.SOURCE)
                    }
                }
                SectionSnapshot(book, ledger, sections, rows)
            }
        }
    }

    private suspend fun requireCurrentSectionBook(book: Book) {
        if (library.findById(book.id)?.copy(lastReadAt = null) != book.copy(lastReadAt = null))
            throw BookSourceException(BookSourceFailure.SOURCE)
    }

    private suspend fun <T> sectionOperation(block: suspend () -> T): T {
        try { return guarded(block) }
        catch (cancelled: ExtractionCancelled) { throw cancelled.original }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LibraryTextIndexException) {
            throw BookSourceException(when (failure.reason) {
                LibraryTextIndexFailure.INVALID_QUERY -> BookSourceFailure.INVALID_QUERY
                LibraryTextIndexFailure.RECOVERY -> BookSourceFailure.RECOVERY
                LibraryTextIndexFailure.BUSY -> BookSourceFailure.BUSY
                LibraryTextIndexFailure.SOURCE -> BookSourceFailure.SOURCE
            })
        } catch (failure: TextExtractionException) {
            throw BookSourceException(when (failure.reason) {
                TextExtractionFailure.UNSUPPORTED -> BookSourceFailure.UNSUPPORTED
                TextExtractionFailure.LIMIT -> BookSourceFailure.LIMIT
                TextExtractionFailure.INVALID, TextExtractionFailure.SOURCE -> BookSourceFailure.SOURCE
            })
        }
    }

    override suspend fun resolve(hit: LibraryTextHit): LibraryTextHit? = guarded {
        if (!hit.target.isValid || hit.id <= 0) return@guarded null
        val book = library.observeBooks().first().find { it.id == hit.target.bookId } ?: return@guarded null
        if (!extractor.supports(book) || book.format != hit.target.sourceFormat ||
            book.sha256 != hit.target.contentSha256) return@guarded null
        val caller = currentCoroutineContext()
        database.useReaderConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                val sql = TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }
                val row = sql.chunk(hit.id) ?: return@withTransaction null
                val indexed = sql.books().find { it.bookId == book.id } ?: return@withTransaction null
                if (!indexed.matches(book)) return@withTransaction null
                if (row.generation != indexed.generation) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                if (row.chunkIndex !in 0 until indexed.chunkCount) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                val current = row.hit(book, hit.snippet)
                // A new generation, changed source/text/title or metadata cannot retarget an old click.
                if (current == hit) current else null
            }
        }
    }

    override suspend fun rebuild(onProgress: (LibraryTextIndexProgress) -> Unit) = withContext(io) {
        if (!rebuildMutex.tryLock()) throw LibraryTextIndexException(LibraryTextIndexFailure.BUSY)
        try {
            val books = guarded { library.observeBooks().first().sortedBy { it.id } }
            guarded { pruneObsoleteBooks(library.observeBooks().first()) }
            var processed = 0
            onProgress(LibraryTextIndexProgress(0, books.size, null))
            for (snapshot in books) {
                currentCoroutineContext().ensureActive()
                onProgress(LibraryTextIndexProgress(processed, books.size, snapshot.title))
                guarded {
                    val current = library.findById(snapshot.id)
                    if (current != null && current.sha256 == snapshot.sha256) {
                        rebuildCurrentBook(current)
                    }
                    Unit
                }
                processed++
                onProgress(LibraryTextIndexProgress(processed, books.size, null))
            }
            // Old derived rows are removed after a successful run, without touching library.db.
            guarded {
                val current = library.observeBooks().first().associateBy { it.id }
                pruneObsoleteBooks(current.values.toList())
            }
        } catch (cancelled: ExtractionCancelled) { throw cancelled.original }
        finally { rebuildMutex.unlock() }
    }

    override suspend fun rebuildBook(scope: BookTextScope, onProgress: (LibraryTextIndexProgress) -> Unit): LibraryTextBookState = withContext(io) {
        currentCoroutineContext().ensureActive()
        if (!scope.isValid) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
        if (!rebuildMutex.tryLock()) throw LibraryTextIndexException(LibraryTextIndexFailure.BUSY)
        try {
            guarded {
                val book = library.findById(scope.bookId)?.takeIf { it.sha256 == scope.contentSha256 }
                    ?: throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                onProgress(LibraryTextIndexProgress(0, 1, null))
                onProgress(LibraryTextIndexProgress(0, 1, book.title))
                val state = rebuildCurrentBook(book)
                currentCoroutineContext().ensureActive()
                // Failure publication may deliberately skip a replaced/deleted original book.
                // Return only a state actually published for this exact current original.
                if (library.findById(book.id)?.copy(lastReadAt = null) != book.copy(lastReadAt = null))
                    throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                val caller = currentCoroutineContext()
                database.useReaderConnection { connection ->
                    connection.withTransaction(SQLiteTransactionType.DEFERRED) {
                        val entry = TextIndexSql(database.openHelper.readableDatabase) { caller.ensureActive() }
                            .books().singleOrNull { it.bookId == book.id }
                        if (entry == null || !entry.isValidStoredBook() || entry.contentSha256 != book.sha256 ||
                            entry.sourceFormat != book.format.name || entry.extractionVersion != LIBRARY_TEXT_EXTRACTION_VERSION ||
                            entry.state != state.name) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                    }
                }
                caller.ensureActive()
                onProgress(LibraryTextIndexProgress(1, 1, null))
                state
            }
        } catch (cancelled: ExtractionCancelled) { throw cancelled.original }
        finally { rebuildMutex.unlock() }
    }

    /** Shared with whole-library rebuild, under the caller's existing file/recovery lease. */
    private suspend fun rebuildCurrentBook(book: Book): LibraryTextBookState {
        if (!extractor.supports(book)) {
            publishFailure(book, LibraryTextBookState.UNSUPPORTED)
            return LibraryTextBookState.UNSUPPORTED
        }
        val failureState = try {
            val document = try { extractor.extract(book) }
                catch (cancelled: CancellationException) { throw ExtractionCancelled(cancelled) }
            currentCoroutineContext().ensureActive()
            val bytes = validateDocument(book, document)
            if (book.format in setOf(BookFormat.PDF, BookFormat.DOCX) && document.chunks.isEmpty()) LibraryTextBookState.NO_TEXT
            else {
                publish(book, document, bytes)
                return LibraryTextBookState.READY
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: TextExtractionException) {
            when (failure.reason) {
                TextExtractionFailure.UNSUPPORTED -> LibraryTextBookState.UNSUPPORTED
                TextExtractionFailure.LIMIT -> LibraryTextBookState.LIMIT
                else -> LibraryTextBookState.FAILED
            }
        }
        publishFailure(book, failureState)
        return failureState
    }

    private suspend fun search(query: String, books: List<Book>, sql: TextIndexSql,
        scope: BookTextScope? = null, limit: Int = MAX_LIBRARY_TEXT_RESULTS): LibraryTextSearchResult {
        if (scope != null && (!scope.isValid || books.singleOrNull {
            it.id == scope.bookId && it.sha256 == scope.contentSha256
        } == null)) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
        val terms = libraryTextQueryTerms(query)
        val indexed = sql.books().associateBy { it.bookId }
        if (indexed.values.any { !it.isValidStoredBook() }) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
        val statuses = books.map { book ->
            val entry = indexed[book.id]
            val state = if (!extractor.supports(book)) LibraryTextBookState.UNSUPPORTED
                else if (entry == null || entry.contentSha256 != book.sha256 ||
                    entry.sourceFormat != book.format.name ||
                    entry.extractionVersion != LIBRARY_TEXT_EXTRACTION_VERSION ||
                    (book.format in setOf(BookFormat.TXT, BookFormat.FB2, BookFormat.DOCX) && entry.state == LibraryTextBookState.UNSUPPORTED.name)) LibraryTextBookState.NOT_INDEXED
                else LibraryTextBookState.entries.find { it.name == entry.state }
                    ?: throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
            if (state == LibraryTextBookState.READY && !entry!!.matches(book)) {
                throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
            }
            LibraryTextBookStatus(book, state, if (state == LibraryTextBookState.READY) entry!!.chunkCount else 0)
        }
        val valid = statuses.filter { it.state == LibraryTextBookState.READY &&
            (scope == null || it.book.id == scope.bookId) }.associate { it.book.id to it.book }
        if (terms.isEmpty() || valid.isEmpty()) return LibraryTextSearchResult(query, emptyList(), 0, statuses, scope)
        // Adjacent quoted terms are AND in both standard and enhanced Android FTS3/4 syntax.
        val expression = terms.joinToString(" ") { "\"$it\"" }
        // SQLite bind counts remain bounded even when the user's library has thousands of books.
        val rows = ArrayList<TextIndexChunkEntity>()
        var count = 0
        for (ids in valid.keys.sorted().chunked(400)) {
            currentCoroutineContext().ensureActive()
            count += sql.count(expression, ids)
            if (rows.size < limit) {
                rows += sql.find(expression, ids, limit - rows.size)
            }
        }
        val hits = rows.map { row ->
            val book = valid.getValue(row.bookId)
            if (row.contentSha256 != book.sha256 || row.generation != indexed.getValue(book.id).generation) {
                throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
            }
            if (row.chunkIndex !in 0 until indexed.getValue(book.id).chunkCount) {
                throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
            }
            row.hit(book, libraryTextSnippet(row.text, terms))
        }
        return LibraryTextSearchResult(query, hits, count, statuses, scope)
    }

    private suspend fun publish(book: Book, document: ExtractedBookText, stringBytes: Long) {
        val caller = currentCoroutineContext()
        if (!currentBookMatches(book)) throw TextExtractionException(TextExtractionFailure.SOURCE)
        database.useWriterConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.IMMEDIATE) {
                caller.ensureActive()
                val sql = TextIndexSql(database.openHelper.writableDatabase) { caller.ensureActive() }
                val existing = sql.books().filter { it.bookId != book.id }
                if (existing.any { !it.isValidStoredBook() }) throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
                if (existing.sumOf { it.stringBytes } + stringBytes > MAX_INDEX_LIBRARY_STRING_BYTES ||
                    existing.sumOf { it.chunkCount.toLong() } + document.chunks.size > MAX_INDEX_LIBRARY_CHUNKS) {
                    throw TextExtractionException(TextExtractionFailure.LIMIT)
                }
                val generation = UUID.randomUUID().toString()
                sql.deleteBook(book.id)
                sql.insertBook(TextIndexBookEntity(book.id, book.sha256, document.extractionVersion,
                    generation, LibraryTextBookState.READY.name, document.chunks.size, stringBytes, book.format.name))
                for (batch in document.chunks.chunked(100)) {
                    caller.ensureActive()
                    sql.insertChunks(batch.map { chunk ->
                        TextIndexChunkEntity(bookId = book.id, contentSha256 = book.sha256, generation = generation,
                            readingOrderIndex = chunk.readingOrderIndex, chunkIndex = chunk.chunkIndex,
                            resourceHref = chunk.resourceHref, resourceSha256 = chunk.resourceSha256,
                            chapterTitle = chunk.chapterTitle, text = chunk.text, searchText = libraryTextSearchKey(chunk.text),
                            sourceFormat = chunk.sourceFormat.name, pdfPageIndex = chunk.pdfPageIndex)
                    })
                }
                // The Room lease owns a separate job. Check the original caller before COMMIT.
                caller.ensureActive()
            }
        }
    }

    private suspend fun publishFailure(book: Book, state: LibraryTextBookState) {
        val caller = currentCoroutineContext()
        if (!currentBookMatches(book)) return
        database.useWriterConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.IMMEDIATE) {
                caller.ensureActive()
                val sql = TextIndexSql(database.openHelper.writableDatabase) { caller.ensureActive() }
                sql.deleteBook(book.id)
                sql.insertBook(TextIndexBookEntity(book.id, book.sha256, LIBRARY_TEXT_EXTRACTION_VERSION,
                    UUID.randomUUID().toString(), state.name, 0, 0, book.format.name))
                caller.ensureActive()
            }
        }
    }

    private suspend fun currentBookMatches(book: Book): Boolean = library.findById(book.id)?.let {
        it.sha256 == book.sha256 && it.format == book.format && it.storageName == book.storageName && it.byteSize == book.byteSize
    } == true

    private suspend fun pruneObsoleteBooks(books: List<Book>) {
        val caller = currentCoroutineContext()
        val current = books.associateBy { it.id }
        database.useWriterConnection { connection ->
            connection.withTransaction(SQLiteTransactionType.IMMEDIATE) {
                val sql = TextIndexSql(database.openHelper.writableDatabase) { caller.ensureActive() }
                sql.pruneBooks {
                    it == null || !it.isValidStoredBook() || current[it.bookId]?.sha256 != it.contentSha256 ||
                        current[it.bookId]?.format?.name != it.sourceFormat ||
                        it.extractionVersion != LIBRARY_TEXT_EXTRACTION_VERSION
                }
                caller.ensureActive()
            }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(io) {
        val caller = currentCoroutineContext()
        try {
            fileMutex.withLock { caller.ensureActive(); validateRead(); block().also { caller.ensureActive() } }
        } catch (cancelled: CancellationException) {
            caller.ensureActive()
            // A cancelled/closed Room source is a source error; actual caller cancellation stays cancelled.
            throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
        } catch (cancelled: ExtractionCancelled) { throw cancelled }
        catch (failure: LibraryTextIndexException) { throw failure }
        catch (failure: BookSourceException) { throw failure }
        catch (failure: TextExtractionException) { throw failure }
        catch (_: Exception) { throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE) }
    }
}

private class ExtractionCancelled(val original: CancellationException) : RuntimeException()

private fun TextIndexBookEntity.matches(book: Book) = isValidStoredBook() && bookId == book.id && contentSha256 == book.sha256 &&
    sourceFormat == book.format.name &&
    extractionVersion == LIBRARY_TEXT_EXTRACTION_VERSION && state == LibraryTextBookState.READY.name &&
    chunkCount in 0..MAX_INDEX_BOOK_CHUNKS && stringBytes in 0..MAX_INDEX_BOOK_STRING_BYTES

private fun canonicalIndexGeneration(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private fun TextIndexBookEntity.isValidStoredBook(): Boolean =
    bookId.isNotBlank() && bookId.length <= 200 && wellFormedIndexText(bookId) &&
        bookId.none(Char::isISOControl) && contentSha256.matches(Regex("[0-9a-f]{64}")) &&
        extractionVersion > 0 && canonicalIndexGeneration(generation) &&
        sourceFormat in BookFormat.entries.map { it.name } &&
        (state != LibraryTextBookState.NO_TEXT.name || sourceFormat in setOf(BookFormat.PDF.name, BookFormat.DOCX.name)) &&
        state in LibraryTextBookState.entries.map { it.name } && chunkCount in 0..MAX_INDEX_BOOK_CHUNKS &&
        stringBytes in 0..MAX_INDEX_BOOK_STRING_BYTES &&
        (if (state == LibraryTextBookState.READY.name)
            stringBytes >= bookId.toByteArray(Charsets.UTF_8).size + 64L + 36L + 5L
        else chunkCount == 0 && stringBytes == 0L)

private fun TextIndexChunkEntity.hit(book: Book, snippet: String): LibraryTextHit {
    val format = BookFormat.entries.find { it.name == sourceFormat }
        ?: throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
    val target = LibraryTextTarget(book.id, contentSha256, resourceHref, resourceSha256,
        sourceFormat = format, pdfPageIndex = pdfPageIndex)
    if (!target.isValid || id <= 0 || text.isBlank() || text.length > MAX_INDEX_CHUNK_TEXT ||
        format != book.format || (format == BookFormat.DOCX && (readingOrderIndex != 0 || chapterTitle != "Belge metni")) ||
        (format == BookFormat.TXT &&
            (txtLibraryTextPartIndex(resourceHref) != readingOrderIndex || chapterTitle != "Metin " + (readingOrderIndex + 1))) ||
        (format == BookFormat.PDF &&
            (pdfPageIndex != readingOrderIndex || chapterTitle != null ||
                (book.pageCount != null && pdfPageIndex !in 0 until book.pageCount))) ||
        !wellFormedIndexText(text) || !canonicalIndexGeneration(generation) || bookId != book.id ||
        chunkIndex !in 0 until MAX_INDEX_BOOK_CHUNKS || readingOrderIndex !in 0 until MAX_INDEX_BOOK_RESOURCES ||
        (chapterTitle != null && (chapterTitle.length > MAX_INDEX_CHAPTER_TITLE || !wellFormedIndexText(chapterTitle))) ||
        searchText != libraryTextSearchKey(text) || snippet.length > 402 || !wellFormedIndexText(snippet)) {
        throw LibraryTextIndexException(LibraryTextIndexFailure.SOURCE)
    }
    return LibraryTextHit(id, generation, book, target, chapterTitle, text, snippet, readingOrderIndex, chunkIndex)
}

internal fun validateDocument(book: Book, document: ExtractedBookText): Long {
    if (document.bookId != book.id || document.contentSha256 != book.sha256 ||
        document.sourceFormat != book.format ||
        (book.format == BookFormat.PDF && book.pageCount != null && document.resourceCount != book.pageCount) ||
        (book.format == BookFormat.TXT && (document.resourceCount > 64 || document.chunks.isEmpty())) ||
        (book.format == BookFormat.DOCX && document.resourceCount != 1) ||
        document.extractionVersion != LIBRARY_TEXT_EXTRACTION_VERSION || document.resourceCount !in 1..MAX_INDEX_BOOK_RESOURCES) {
        throw TextExtractionException(TextExtractionFailure.INVALID)
    }
    if (document.chunks.size > MAX_INDEX_BOOK_CHUNKS) throw TextExtractionException(TextExtractionFailure.LIMIT)
    var bytes = (book.id.toByteArray().size + book.sha256.toByteArray().size + 36 + 5).toLong()
    var lastOrder = -1
    val sources = mutableMapOf<Int, Pair<String, String>>()
    document.chunks.forEachIndexed { index, chunk ->
        val target = LibraryTextTarget(book.id, book.sha256, chunk.resourceHref, chunk.resourceSha256,
            sourceFormat = chunk.sourceFormat, pdfPageIndex = chunk.pdfPageIndex)
        if (chunk.chunkIndex != index || chunk.readingOrderIndex !in 0 until document.resourceCount ||
            chunk.sourceFormat != document.sourceFormat ||
            (chunk.sourceFormat == BookFormat.DOCX && (chunk.readingOrderIndex != 0 || chunk.chapterTitle != "Belge metni")) ||
            (chunk.sourceFormat == BookFormat.PDF && (chunk.pdfPageIndex != chunk.readingOrderIndex || chunk.chapterTitle != null)) ||
            (chunk.sourceFormat == BookFormat.TXT && (txtLibraryTextPartIndex(chunk.resourceHref) != chunk.readingOrderIndex ||
                chunk.chapterTitle != "Metin " + (chunk.readingOrderIndex + 1))) ||
            chunk.readingOrderIndex < lastOrder || !target.isValid || chunk.text.isBlank() ||
            chunk.text.length > MAX_INDEX_CHUNK_TEXT || !wellFormedIndexText(chunk.text) ||
            (chunk.chapterTitle != null && (chunk.chapterTitle.length > MAX_INDEX_CHAPTER_TITLE || !wellFormedIndexText(chunk.chapterTitle)))) {
            throw TextExtractionException(TextExtractionFailure.INVALID)
        }
        val source = chunk.resourceHref to chunk.resourceSha256
        if (sources.put(chunk.readingOrderIndex, source)?.let { it != source } == true) {
            throw TextExtractionException(TextExtractionFailure.INVALID)
        }
        lastOrder = chunk.readingOrderIndex
        bytes += indexedChunkStringBytes(book.id, book.sha256, chunk)
        if (bytes > MAX_INDEX_BOOK_STRING_BYTES) throw TextExtractionException(TextExtractionFailure.LIMIT)
    }
    return bytes
}

internal fun libraryTextQueryTerms(query: String): List<String> {
    if (query.length > MAX_LIBRARY_TEXT_QUERY || !wellFormedIndexText(query)) {
        throw LibraryTextIndexException(LibraryTextIndexFailure.INVALID_QUERY)
    }
    val terms = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(libraryTextSearchKey(query)).map { it.value }.distinct().toList()
    if (terms.size > MAX_LIBRARY_TEXT_QUERY_TERMS || (query.isNotBlank() && terms.isEmpty())) {
        throw LibraryTextIndexException(LibraryTextIndexFailure.INVALID_QUERY)
    }
    return terms
}

internal fun libraryTextSnippet(text: String, terms: List<String>): String {
    val normalized = libraryTextSearchKey(text)
    // Code point lowercasing can change lengths; never treat a folded offset as an original offset.
    val foldedAt = terms.map { normalized.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
    var originalAt = 0
    var foldedLength = 0
    while (originalAt < text.length && foldedLength < foldedAt) {
        val count = Character.charCount(text.codePointAt(originalAt))
        foldedLength += libraryTextSearchKey(text.substring(originalAt, originalAt + count)).length
        originalAt += count
    }
    var start = maxOf(0, originalAt - 100)
    if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    var end = minOf(text.length, start + 400)
    if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
}

internal fun wellFormedIndexText(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        if (text[i].isHighSurrogate()) {
            if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false
            i += 2
        } else { if (text[i].isLowSurrogate() || text[i] == '\u0000') return false; i++ }
    }
    return true
}
