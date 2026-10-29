package com.yaprak.reader.formats.fb2

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.EpubReaderSession
import com.yaprak.reader.formats.epub.OpenEpub
import com.yaprak.reader.reader.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Fb2ReaderLoader(context: Context, private val library: LibraryRepository, private val reading: ReadingStateRepository,
    private val fileMutex: Mutex = Mutex(), private val validateRead: suspend () -> Unit = {},
) : ReaderLoader {
    private val source = EpubPublicationSource(context.contentResolver)
    private val files = VerifiedBookFile(File(context.filesDir, "books"))
    private val derived = Fb2DerivedFiles(File(context.cacheDir, "fb2-viewing"))
    private val visibleScript = context.assets.open("reader/visible-text.js").bufferedReader().use { it.readText() }

    override suspend fun open(bookId: String): ReaderSession {
        var temporary: File? = null
        var opened: OpenEpub? = null
        try {
            return withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    currentCoroutineContext().ensureActive(); validateRead()
                    val book = library.findById(bookId) ?: throw ReaderOpenException(ReaderFailure.MISSING)
                    if (book.format != BookFormat.FB2) throw ReaderOpenException(ReaderFailure.UNSUPPORTED)
                    val file = files.resolve(book)
                    val artifact = derived.create().also { temporary = it }
                    try { Fb2Epub.write(artifact, book.sha256, StrictFb2.read(file)) }
                    catch (failure: Fb2Exception) { throw ReaderOpenException(if (failure.reason == Fb2Failure.UNSUPPORTED) ReaderFailure.UNSUPPORTED else ReaderFailure.INVALID) }
                    val artifactHash = fb2ViewHash(artifact)
                    val publication = source.open(artifact).also { opened = it }
                    val resume = reading.resume(book.id, book.sha256)
                    currentCoroutineContext().ensureActive(); validateRead()
                    if (fb2ViewHash(artifact) != artifactHash) throw ReaderOpenException(ReaderFailure.CHANGED)
                    files.resolve(book)
                    if (library.findById(bookId)?.let { it.format == book.format && it.sha256 == book.sha256 &&
                            it.storageName == book.storageName && it.byteSize == book.byteSize } != true)
                        throw ReaderOpenException(ReaderFailure.CHANGED)
                    val proof = Fb2IndexedSource(book, library, files, artifact, artifactHash, fileMutex, validateRead)
                    Fb2ReaderSession(EpubReaderSession(book, publication, resume, visibleScript, ""), proof) { derived.release(artifact) }
                }
            }
        } catch (failure: Throwable) {
            try { opened?.close() } finally { temporary?.let(derived::release) }
            throw failure
        }
    }
}

/** Native locators and search are real; generated XHTML grants no original-FB2 range/AI/coverage proof. */
internal class Fb2ReaderSession(private val delegate: ReaderSession, private val indexedSource: Fb2IndexedSource? = null,
    private val releaseArtifact: () -> Unit,
) : ReaderSession by delegate {
    private var closed = false
    private var surfaceGeneration = 0L
    override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
        check(!closed)
        val owner = ++surfaceGeneration
        val surface = delegate.createSurface(initial, dark, onTap, onError)
        fun current() = !closed && owner == surfaceGeneration
        return object : ReaderSurface by surface {
            override val passageNavigator: PassageNavigator? = null
            override val indexedChapterNavigator = indexedSource?.let { proof -> surface.contentsNavigator?.let { native ->
                Fb2IndexedNavigator(book, { current() && surface.fragment.isResumed }, proof::verify, native)
                    .also { navigator -> surface.fragment.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) navigator.invalidate()
                    }) }
            } }
            override val indexedPdfPageNavigator: IndexedPdfPageNavigator? = null
            override val epubHighlights: EpubHighlights? = null
            override val epubImageFilter: EpubImageFilterController? = null
            override val bookSearch = surface.bookSearch?.let { native -> object : BookTextSearch by native {
                override val kind = BookSearchKind.FB2
                override val supported get() = current() && native.supported
                override suspend fun find(query: String): BookSearchResults {
                    if (!current()) throw BookSearchException(BookSearchFailure.SOURCE)
                    val result = native.find(query)
                    currentCoroutineContext().ensureActive()
                    if (!current()) throw BookSearchException(BookSearchFailure.SOURCE)
                    return result
                }
                override fun goTo(location: ReadingLocation) = current() && native.goTo(location)
            } }
            override val textSelection = surface.textSelection?.let { native -> object : TextSelectionSource {
                override val active get() = current() && native.active
                override val requests get() = native.requests
                override suspend fun capture(): SelectedText? {
                    if (!current()) return null
                    val selected = native.capture() ?: return null
                    currentCoroutineContext().ensureActive()
                    return if (current()) SelectedText(selected.text, sourceSelectionId = selected.sourceSelectionId) else null
                }
                override fun clear() { if (current()) native.clear() }
                override fun isCurrent(requestId: String?) = current() && native.isCurrent(requestId)
            } }
            override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
                surface.observe({ false }) { snapshot -> if (current()) onSnapshot(ReadingSnapshot(snapshot.location)) }
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        try { delegate.close() } finally { releaseArtifact() }
    }
}

internal suspend fun fb2ViewHash(file: File): String {
    val limit = 64L * 1024 * 1024
    currentCoroutineContext().ensureActive()
    check(file.isFile && file.length() in 1..limit)
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            check(bytes <= limit)
            digest.update(buffer, 0, count)
        }
    }
    currentCoroutineContext().ensureActive()
    check(bytes > 0)
    return digest.digest().joinToString("") { "%02x".format(it) }
}
