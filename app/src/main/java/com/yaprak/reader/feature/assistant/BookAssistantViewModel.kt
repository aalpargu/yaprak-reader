package com.yaprak.reader.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class BookAssistantSourceMode { KEYWORDS, SECTION, CHAPTER, PREFIX, PREFIX_KEYWORDS }

internal data class BookAssistantState(
    val keywords: String = "", val question: String = "", val endpoint: String = "", val accessToken: String = "",
    val active: Boolean = false, val book: Book? = null, val busy: Boolean = false, val revision: Long = 0,
    val sources: PreparedBookSources? = null, val answer: BookAnswer? = null,
    val failure: BookAnswerFailure? = null, val sourceFailure: BookSourceFailure? = null,
    val sourceMode: BookAssistantSourceMode = BookAssistantSourceMode.KEYWORDS,
    val sections: List<BookSourceSection> = emptyList(), val section: BookSourceSection? = null,
    val sectionsLoaded: Boolean = false,
    val chapters: List<BookSourceChapter> = emptyList(), val chapter: BookSourceChapter? = null,
    val chaptersLoaded: Boolean = false,
    val indexState: LibraryTextBookState? = null,
) {
    private val scopeAllowed: Boolean get() = book?.let { supportsBookSourceRetrieval(it.format) &&
        (sourceMode !in setOf(BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS) || it.format == BookFormat.EPUB) } == true
    val canPrepare: Boolean get() = active && scopeAllowed && !busy && when (sourceMode) {
        BookAssistantSourceMode.KEYWORDS -> keywords.isNotBlank()
        BookAssistantSourceMode.SECTION -> section != null
        BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX -> chapter != null
        BookAssistantSourceMode.PREFIX_KEYWORDS -> chapter != null && keywords.isNotBlank()
    }
    val canSend: Boolean get() = active && scopeAllowed && !busy && !sources?.references.isNullOrEmpty() &&
        question.isNotBlank() && when (sourceMode) {
            BookAssistantSourceMode.KEYWORDS -> sources?.section == null && sources?.chapter == null && sources?.prefix == null && sources?.prefixQuery == null
            BookAssistantSourceMode.SECTION -> section != null && sources?.let { it.section == section && it.chapter == null && it.prefix == null && it.prefixQuery == null } == true
            BookAssistantSourceMode.CHAPTER -> chapter != null && sources?.let { it.chapter == chapter && it.section == null && it.prefix == null && it.prefixQuery == null } == true
            BookAssistantSourceMode.PREFIX -> chapter != null && sources?.let { it.prefix?.throughChapter == chapter && it.section == null && it.chapter == null && it.prefixQuery == null } == true
            BookAssistantSourceMode.PREFIX_KEYWORDS -> chapter != null && sources?.let {
                it.prefixQuery?.throughChapter == chapter && it.query == keywords && it.section == null && it.chapter == null && it.prefix == null
            } == true
        }
    val canStudy: Boolean get() = canSendScope && active && scopeAllowed && !busy
    private val canSendScope: Boolean get() = when (sourceMode) {
        BookAssistantSourceMode.KEYWORDS, BookAssistantSourceMode.PREFIX_KEYWORDS -> false
        BookAssistantSourceMode.SECTION -> section != null && sources?.let { it.section == section && it.chapter == null && it.prefix == null && it.prefixQuery == null } == true
        BookAssistantSourceMode.CHAPTER -> chapter != null && sources?.let { it.chapter == chapter && it.section == null && it.prefix == null && it.prefixQuery == null } == true
        BookAssistantSourceMode.PREFIX -> chapter != null && sources?.let { it.prefix?.throughChapter == chapter && it.section == null && it.chapter == null && it.prefixQuery == null } == true
    }
    // Never make accidental debug rendering a book-content or credential log.
    override fun toString() = "BookAssistantState(revision=$revision, active=$active, busy=$busy)"
}

/** No transmission on entry, typing, prepare, resume or retry. Send is a distinct rendered action. */
internal class BookAssistantViewModel(
    private val scope: BookTextScope,
    initialKeywords: String,
    private val retriever: BookSourceRetriever,
    private val index: LibraryTextIndex,
    private val service: BookAnswerService,
) : ViewModel() {
    private val mutable = MutableStateFlow(BookAssistantState(keywords = limitLibraryTextQuery(initialKeywords)))
    val state = mutable.asStateFlow()
    private var owner: Any? = null
    private var observation: Job? = null
    private var operation: Job? = null
    private var serial = 0L
    private var observationSerial = 0L

    fun attachOwner(value: Any) {
        if (owner === value) return
        owner = value
        deactivate()
    }
    fun detachOwner(value: Any) { if (owner === value) { owner = null; deactivate() } }
    fun isActive(value: Any) = owner === value && state.value.active
    fun setOwnerActive(value: Any, active: Boolean) {
        if (owner !== value || state.value.active == active) return
        if (!active) { deactivate(); return }
        mutable.value = state.value.copy(active = true, revision = ++serial)
        val observationId = ++observationSerial
        observation = viewModelScope.launch {
            try {
                if (!scope.isValid) throw BookSourceException(BookSourceFailure.SOURCE)
                index.observeBook("", scope).collect { result ->
                    if (observationId != observationSerial || !state.value.active) return@collect
                    currentCoroutineContext().ensureActive()
                    val status = result.books.singleOrNull { it.book.id == scope.bookId }
                        ?: throw BookSourceException(BookSourceFailure.SOURCE)
                    val book = status.book
                    if (result.scope != scope || book.sha256 != scope.contentSha256 || !supportsBookSourceRetrieval(book.format))
                        throw BookSourceException(BookSourceFailure.SOURCE)
                    val current = state.value
                    if (current.book != null && current.book.copy(lastReadAt = null) != book.copy(lastReadAt = null)) {
                        invalidate()
                        mutable.value = state.value.copy(book = book, indexState = status.state, sourceFailure = BookSourceFailure.SOURCE,
                            sourceMode = if (book.format == BookFormat.EPUB) state.value.sourceMode else BookAssistantSourceMode.KEYWORDS,
                            sections = emptyList(), section = null, sectionsLoaded = false,
                            chapters = emptyList(), chapter = null, chaptersLoaded = false)
                    } else mutable.value = current.copy(book = book, indexState = status.state)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (observationId == observationSerial && state.value.active) {
                    invalidate()
                    mutable.value = state.value.copy(book = null, indexState = null, sections = emptyList(), section = null, sectionsLoaded = false,
                        chapters = emptyList(), chapter = null, chaptersLoaded = false,
                        sourceFailure = (failure as? BookSourceException)?.reason ?: BookSourceFailure.SOURCE)
                }
            }
        }
    }
    private fun deactivate() {
        ++observationSerial
        observation?.cancel(); observation = null
        invalidate()
        mutable.value = state.value.copy(active = false, book = null, indexState = null, sections = emptyList(), section = null, sectionsLoaded = false,
            chapters = emptyList(), chapter = null, chaptersLoaded = false)
    }
    private fun invalidate(clearSources: Boolean = true) {
        ++serial
        operation?.cancel(); operation = null
        mutable.value = state.value.copy(busy = false, revision = serial,
            sources = if (clearSources) null else state.value.sources, answer = null, failure = null, sourceFailure = null)
    }
    fun editKeywords(value: String) {
        if (!state.value.active || state.value.keywords == value) return
        invalidate(); mutable.value = state.value.copy(keywords = limitLibraryTextQuery(value))
    }
    fun editQuestion(value: String) {
        if (!state.value.active || value.length > MAX_BOOK_QUESTION || state.value.question == value) return
        invalidate(false); mutable.value = state.value.copy(question = value)
    }
    fun editEndpoint(value: String) {
        if (!state.value.active || value.length > 2048 || state.value.endpoint == value) return
        invalidate(false); mutable.value = state.value.copy(endpoint = value)
    }
    fun editToken(value: String) {
        if (!state.value.active || value.length > 256 || state.value.accessToken == value) return
        invalidate(false); mutable.value = state.value.copy(accessToken = value)
    }

    fun chooseMode(mode: BookAssistantSourceMode, revision: Long) {
        val current = state.value
        if (!current.active || current.busy || current.revision != revision || current.sourceMode == mode) return
        val format = current.book?.format ?: return
        if (!supportsBookSourceRetrieval(format) || mode in setOf(BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS) && format != BookFormat.EPUB) return
        invalidate()
        mutable.value = state.value.copy(sourceMode = mode, sections = emptyList(), section = null, sectionsLoaded = false,
            chapters = emptyList(), chapter = null, chaptersLoaded = false)
    }

    fun loadSections(revision: Long) {
        val current = state.value
        if (!current.active || current.book == null || current.busy || current.revision != revision ||
            !supportsBookSourceRetrieval(current.book.format) || current.sourceMode != BookAssistantSourceMode.SECTION) return
        mutable.value = current.copy(sections = emptyList(), section = null, sectionsLoaded = false)
        start(clearSources = true) {
            val sections = retriever.listSections(scope)
            currentCoroutineContext().ensureActive()
            if (!validBookSourceSections(scope, sections) || sections.any { it.sourceFormat != current.book.format })
                throw BookSourceException(BookSourceFailure.SOURCE)
            mutable.value = state.value.copy(sections = immutableAnswerList(sections), sectionsLoaded = true)
        }
    }

    fun chooseSection(section: BookSourceSection, revision: Long) {
        val current = state.value
        if (!current.active || current.book == null || current.busy || current.revision != revision ||
            section.sourceFormat != current.book.format || current.sourceMode != BookAssistantSourceMode.SECTION || !current.sections.contains(section) ||
            current.section == section) return
        invalidate()
        mutable.value = state.value.copy(section = section)
    }

    fun study(task: BookStudyTask, revision: Long) {
        val current = state.value
        if (!current.canStudy || current.revision != revision) return
        editQuestion(if (current.sourceMode == BookAssistantSourceMode.PREFIX) task.questionForPrefix()
            else if (current.sourceMode == BookAssistantSourceMode.CHAPTER) task.questionForChapter()
            else task.questionForSection(current.book!!.format))
    }

    fun loadChapters(revision: Long) {
        val current = state.value
        if (!current.active || current.book == null || current.busy || current.revision != revision ||
            current.book.format != BookFormat.EPUB || current.sourceMode !in setOf(BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS)) return
        mutable.value = current.copy(chapters = emptyList(), chapter = null, chaptersLoaded = false)
        start(clearSources = true) {
            val chapters = retriever.listChapters(scope)
            currentCoroutineContext().ensureActive()
            if (!validBookSourceChapters(scope, chapters)) throw BookSourceException(BookSourceFailure.SOURCE)
            mutable.value = state.value.copy(chapters = immutableAnswerList(chapters), chaptersLoaded = true)
        }
    }

    fun chooseChapter(chapter: BookSourceChapter, revision: Long) {
        val current = state.value
        if (!current.active || current.book == null || current.busy || current.revision != revision ||
            current.book.format != BookFormat.EPUB || current.sourceMode !in setOf(BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS) || !current.chapters.contains(chapter) ||
            current.chapter == chapter) return
        invalidate()
        mutable.value = state.value.copy(chapter = chapter)
    }

    /** Explicit local work for this original book only; it does not prepare a question or send. */
    fun prepareIndex(revision: Long) {
        val current = state.value
        val book = current.book ?: return
        if (!current.active || current.busy || current.revision != revision ||
            current.indexState != LibraryTextBookState.NOT_INDEXED || !supportsBookSourceRetrieval(book.format)) return
        start(clearSources = true) {
            val result = try { index.rebuildBook(scope) {} }
                catch (failure: LibraryTextIndexException) {
                    throw BookSourceException(when (failure.reason) {
                        LibraryTextIndexFailure.INVALID_QUERY -> BookSourceFailure.INVALID_QUERY
                        LibraryTextIndexFailure.SOURCE -> BookSourceFailure.SOURCE
                        LibraryTextIndexFailure.RECOVERY -> BookSourceFailure.RECOVERY
                        LibraryTextIndexFailure.BUSY -> BookSourceFailure.BUSY
                    })
                }
            currentCoroutineContext().ensureActive()
            if (state.value.book?.copy(lastReadAt = null) != book.copy(lastReadAt = null) || result == LibraryTextBookState.NOT_INDEXED)
                throw BookSourceException(BookSourceFailure.SOURCE)
            mutable.value = state.value.copy(indexState = result, sourceFailure = when (result) {
                LibraryTextBookState.READY -> null
                LibraryTextBookState.NO_TEXT, LibraryTextBookState.UNSUPPORTED -> BookSourceFailure.UNSUPPORTED
                LibraryTextBookState.LIMIT -> BookSourceFailure.LIMIT
                else -> BookSourceFailure.SOURCE
            })
        }
    }

    fun prepare(revision: Long) {
        val current = state.value
        if (!current.canPrepare || current.revision != revision) return
        start(clearSources = true) {
            val section = if (current.sourceMode == BookAssistantSourceMode.SECTION) current.section!! else null
            val chapter = if (current.sourceMode == BookAssistantSourceMode.CHAPTER) current.chapter!! else null
            val through = if (current.sourceMode == BookAssistantSourceMode.PREFIX) current.chapter!! else null
            val throughQuery = if (current.sourceMode == BookAssistantSourceMode.PREFIX_KEYWORDS) current.chapter!! else null
            val sources = when {
                throughQuery != null -> retriever.preparePrefixQuery(scope, throughQuery, current.keywords)
                through != null -> retriever.preparePrefix(scope, through)
                chapter != null -> retriever.prepareChapter(scope, chapter)
                section != null -> retriever.prepareSection(scope, section)
                else -> retriever.prepare(scope, current.keywords)
            }
            currentCoroutineContext().ensureActive()
            requireSnapshot(sources, current.keywords, section, chapter, through, throughQuery)
            if (sources.references.isNotEmpty()) BookQuestionRequest.create(sources, "Kaynak doğrulaması")
            mutable.value = state.value.copy(sources = sources)
        }
    }

    fun send(revision: Long) {
        val current = state.value
        if (!current.canSend || current.revision != revision) return
        // Validate before touching the book or service. An invalid configuration never sends.
        val connection: BookAnswerConnection
        try {
            connection = BookAnswerConnection.create(current.endpoint, current.accessToken)
            BookQuestionRequest.create(current.sources!!, current.question)
        } catch (failure: BookAnswerException) {
            mutable.value = current.copy(failure = failure.reason, revision = ++serial); return
        }
        val preview = current.sources!!
        start(clearSources = false) {
            // Fresh source-file/hash proof. A different retrieval must be previewed before sending.
            val fresh = when {
                preview.prefixQuery != null -> retriever.preparePrefixQuery(scope, preview.prefixQuery.throughChapter, current.keywords)
                preview.prefix != null -> retriever.preparePrefix(scope, preview.prefix.throughChapter)
                preview.chapter != null -> retriever.prepareChapter(scope, preview.chapter)
                preview.section != null -> retriever.prepareSection(scope, preview.section)
                else -> retriever.prepare(scope, current.keywords)
            }
            currentCoroutineContext().ensureActive()
            requireSnapshot(fresh, current.keywords, preview.section, preview.chapter, preview.prefix?.throughChapter, preview.prefixQuery?.throughChapter)
            if (fresh.references != preview.references || fresh.totalMatches != preview.totalMatches || fresh.prefix != preview.prefix || fresh.prefixQuery != preview.prefixQuery)
                throw BookSourceException(BookSourceFailure.SOURCE)
            val request = BookQuestionRequest.create(fresh, current.question)
            mutable.value = state.value.copy(sources = fresh)
            val answer = service.answer(request, connection)
            currentCoroutineContext().ensureActive()
            validateBookAnswer(request, answer)
            mutable.value = state.value.copy(answer = answer)
        }
    }

    fun cancel(revision: Long) { if (state.value.active && state.value.revision == revision) invalidate() }

    fun open(citation: BookAnswerCitation, revision: Long, action: (LibraryTextHit) -> Unit) {
        val current = state.value
        if (!current.active || current.busy || current.revision != revision ||
            current.answer?.statements?.flatMap { it.citations }?.contains(citation) != true) return
        start(clearSources = false, keepAnswer = true) {
            val hit = index.resolve(citation.source.hit) ?: throw BookSourceException(BookSourceFailure.SOURCE)
            currentCoroutineContext().ensureActive()
            if (hit != citation.source.hit || hit.target.bookId != scope.bookId || hit.target.contentSha256 != scope.contentSha256)
                throw BookSourceException(BookSourceFailure.SOURCE)
            // ReaderActivity independently rechecks the original source before the native format-specific jump.
            action(hit)
        }
    }

    private fun requireSnapshot(sources: PreparedBookSources, keywords: String, section: BookSourceSection? = null,
        chapter: BookSourceChapter? = null, through: BookSourceChapter? = null, throughQuery: BookSourceChapter? = null) {
        if (sources.scope != scope || sources.query != (if (section == null && chapter == null && through == null) keywords else "") ||
            sources.section != section || sources.chapter != chapter || sources.prefix?.throughChapter != through || sources.prefixQuery?.throughChapter != throughQuery || !validCompleteSectionSources(sources) ||
            !validCompleteChapterSources(sources) ||
            !state.value.active || state.value.book == null)
            throw BookSourceException(BookSourceFailure.SOURCE)
        if (sources.references.any { it.hit.book.copy(lastReadAt = null) != state.value.book?.copy(lastReadAt = null) })
            throw BookSourceException(BookSourceFailure.SOURCE)
    }
    private fun start(clearSources: Boolean, keepAnswer: Boolean = false, block: suspend () -> Unit) {
        val answer = state.value.answer
        invalidate(clearSources)
        val request = serial
        mutable.value = state.value.copy(busy = true, answer = if (keepAnswer) answer else null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (serial == request && state.value.active) mutable.value = state.value.copy(
                    answer = null, sources = if (failure is BookSourceException) null else state.value.sources,
                    sourceFailure = (failure as? BookSourceException)?.reason,
                    failure = if (failure is BookSourceException) null else (failure as? BookAnswerException)?.reason ?: BookAnswerFailure.INVALID_RESPONSE)
            } finally {
                if (serial == request) { operation = null; mutable.value = state.value.copy(busy = false) }
            }
        }
        operation = job; job.start()
    }
}
