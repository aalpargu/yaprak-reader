package com.yaprak.reader.data.discovery

import android.app.Application
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidDiscoveryProviderTest {
    private val authority = "com.example.documents"
    private val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Books").toString()
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
    private class TrackedCursor(columns: Array<String>) : MatrixCursor(columns) {
        var closes = 0
        var moves = 0
        var moveFailureAt: Int? = null
        override fun close() { closes++; super.close() }
        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            moves++
            if (moves == moveFailureAt) throw IOException("Cursor movement failed")
            return super.onMove(oldPosition, newPosition)
        }
    }

    @Test fun `opaque ids stay under selected tree`() = runBlocking {
        val cursor = TrackedCursor(columns).apply {
            addRow(arrayOf<Any?>("primary:Books/folder/../opaque", "Book.EPUB", "application/epub+zip", null, 0))
            addRow(arrayOf<Any?>("primary:Books/virtual.pdf", "virtual.pdf", "application/pdf", 100L, DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT))
        }
        var queried: Uri? = null
        val provider = AndroidDiscoveryDocumentProvider { uri, projection, signal ->
            queried = uri
            assertArrayEquals(columns, projection)
            assertFalse(signal.isCanceled)
            cursor
        }
        val tree = provider.resolveTree(treeUri)
        val rows = mutableListOf<DiscoveryDocument>()
        provider.children(tree, "primary:Books/subfolder") { rows.add(it); true }
        assertEquals(authority, queried!!.authority)
        assertEquals("primary:Books", DocumentsContract.getTreeDocumentId(queried!!))
        assertEquals("primary:Books/subfolder", DocumentsContract.getDocumentId(queried!!))
        assertTrue(queried!!.pathSegments.last() == "children")
        assertEquals(1, cursor.closes)
        assertNull(rows.first().sizeBytes)
        assertTrue(rows[1].virtual)
        val document = Uri.parse(provider.documentUri(tree, rows.first().id!!))
        assertEquals(authority, document.authority)
        assertEquals("primary:Books", DocumentsContract.getTreeDocumentId(document))
        assertEquals("primary:Books/folder/../opaque", DocumentsContract.getDocumentId(document))
        assertNull(document.query); assertNull(document.fragment)
        assertEquals(listOf("tree", "primary:Books", "document", "primary:Books/folder/../opaque"), document.pathSegments)
    }

    @Test fun `malformed selection is rejected`() {
        var calls = 0
        val provider = AndroidDiscoveryDocumentProvider { _, _, _ -> calls++; null }
        val invalid = listOf("file:///books", "https://provider/tree/root", "content:///tree/root",
            "content://com.example.documents/document/root", "$treeUri?query=1", "$treeUri#fragment",
            "$treeUri/document/other", "content://com.example.documents/tree/", "content://com.example.documents/tree/%ZZ",
            "content://com.example.documents/tree/%0A", "content://user@com.example.documents/tree/root",
            "content://com.example.documents:10/tree/root", "content://com.example.documents/tree/" + "a".repeat(4097))
        invalid.forEach { value -> assertTrue(value, runCatching { provider.resolveTree(value) }.exceptionOrNull() is InvalidDiscoveryTreeException) }
        assertEquals(0, calls)
        val tree = provider.resolveTree(treeUri)
        assertTrue(runCatching { provider.documentUri(tree.copy(authority = "other"), "id") }.isFailure)
        assertTrue(runCatching { provider.documentUri(tree.copy(rootId = "other"), "id") }.isFailure)
        assertTrue(runCatching { provider.documentUri(tree, "\n") }.isFailure)
    }

    @Test fun `early stop and failures close cursor`() = runBlocking {
        val first = TrackedCursor(columns).apply {
            repeat(100) { addRow(arrayOf<Any?>("id$it", "book$it.pdf", "application/pdf", 100L, 0)) }
        }
        val provider = AndroidDiscoveryDocumentProvider { _, _, _ -> first }
        provider.children(provider.resolveTree(treeUri), "primary:Books") { false }
        assertEquals(1, first.moves); assertEquals(1, first.closes)
        val second = TrackedCursor(columns).apply { addRow(arrayOf<Any?>("id", "book.pdf", "application/pdf", 100L, 0)) }
        val broken = AndroidDiscoveryDocumentProvider { _, _, _ -> second }
        assertTrue(runCatching { broken.children(broken.resolveTree(treeUri), "primary:Books") { throw IOException("Stopped") } }.exceptionOrNull() is IOException)
        assertEquals(1, second.closes)
        val absent = AndroidDiscoveryDocumentProvider { _, _, _ -> null }
        assertTrue(runCatching { absent.children(absent.resolveTree(treeUri), "primary:Books") { true } }.exceptionOrNull() is IOException)
    }

    @Test fun `missing required columns close cursor`() = runBlocking {
        val cursor = TrackedCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)).apply {
            addRow(arrayOf("id", "book.pdf", "application/pdf"))
        }
        val provider = AndroidDiscoveryDocumentProvider { _, _, _ -> cursor }
        assertTrue(runCatching { provider.children(provider.resolveTree(treeUri), "primary:Books") { true } }.exceptionOrNull() is IOException)
        assertEquals(1, cursor.closes)
        // A missing/unknown size is allowed; virtual flags still need to be known.
        val optionalSize = TrackedCursor(columns.filter { it != DocumentsContract.Document.COLUMN_SIZE }.toTypedArray()).apply {
            addRow(arrayOf<Any?>("id", "book.pdf", "application/pdf", 0))
        }
        val valid = AndroidDiscoveryDocumentProvider { _, _, _ -> optionalSize }
        valid.children(valid.resolveTree(treeUri), "primary:Books") { assertNull(it.sizeBytes); true }
        assertEquals(1, optionalSize.closes)
    }

    @Test fun `cancel blocked query signal`() = runBlocking {
        val queryStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val provider = AndroidDiscoveryDocumentProvider { _, _, signal ->
            signal.setOnCancelListener { cancelled.countDown() }
            queryStarted.countDown()
            check(cancelled.await(10, TimeUnit.SECONDS))
            signal.throwIfCanceled()
            null
        }
        val pending = async(Dispatchers.IO) { provider.children(provider.resolveTree(treeUri), "primary:Books") { true } }
        withContext(Dispatchers.IO) { assertTrue(queryStarted.await(10, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        assertEquals(0L, cancelled.count)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun `late cursor closes after cancel`() = runBlocking {
        val queryStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val cursor = TrackedCursor(columns).apply { addRow(arrayOf<Any?>("id", "book.pdf", "application/pdf", 100L, 0)) }
        val delivered = AtomicBoolean(false)
        val provider = AndroidDiscoveryDocumentProvider { _, _, signal ->
            signal.setOnCancelListener { cancelled.countDown() }
            queryStarted.countDown()
            check(cancelled.await(10, TimeUnit.SECONDS))
            cursor // Models a provider which returns a cursor despite receiving cancellation.
        }
        val pending = async(Dispatchers.IO) {
            provider.children(provider.resolveTree(treeUri), "primary:Books") { delivered.set(true); true }
        }
        withContext(Dispatchers.IO) { assertTrue(queryStarted.await(10, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        assertEquals(1, cursor.closes)
        assertEquals(0, cursor.moves)
        assertFalse(delivered.get())
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun `cancel during row delivery waits for cleanup and prevents later rows`() = runBlocking {
        val rowEntered = CountDownLatch(1)
        val releaseRow = CountDownLatch(1)
        val cursor = TrackedCursor(columns).apply {
            repeat(10) { addRow(arrayOf<Any?>("id$it", "book$it.pdf", "application/pdf", 100L, 0)) }
        }
        var signal: CancellationSignal? = null
        var delivered = 0
        val provider = AndroidDiscoveryDocumentProvider { _, _, querySignal -> signal = querySignal; cursor }
        val pending = async(Dispatchers.IO) {
            provider.children(provider.resolveTree(treeUri), "primary:Books") {
                delivered++
                rowEntered.countDown()
                check(releaseRow.await(10, TimeUnit.SECONDS))
                true
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(rowEntered.await(10, TimeUnit.SECONDS)) }
            pending.cancel()
            assertTrue(signal!!.isCanceled)
            assertFalse(pending.isCompleted) // Synchronous provider cleanup still owns its coroutine.
        } finally {
            releaseRow.countDown()
            pending.cancelAndJoin()
        }
        assertEquals(1, delivered)
        assertEquals(1, cursor.moves)
        assertEquals(1, cursor.closes)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun `cursor iteration failure closes cursor and preserves provider error`() = runBlocking {
        val cursor = TrackedCursor(columns).apply {
            addRow(arrayOf<Any?>("id1", "book1.pdf", "application/pdf", 100L, 0))
            addRow(arrayOf<Any?>("id2", "book2.pdf", "application/pdf", 100L, 0))
            moveFailureAt = 2
        }
        var delivered = 0
        val provider = AndroidDiscoveryDocumentProvider { _, _, _ -> cursor }
        val error = runCatching {
            provider.children(provider.resolveTree(treeUri), "primary:Books") { delivered++; true }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("Cursor movement failed", error!!.message)
        assertEquals(1, delivered)
        assertEquals(1, cursor.closes)
    }

    @Test fun `query access failure propagates without fabricating cursor`() = runBlocking {
        val denied = SecurityException("Tree grant revoked")
        var queryCalls = 0
        var delivered = 0
        val provider = AndroidDiscoveryDocumentProvider { _, _, _ -> queryCalls++; throw denied }
        val error = runCatching {
            provider.children(provider.resolveTree(treeUri), "primary:Books") { delivered++; true }
        }.exceptionOrNull()
        assertTrue(error is SecurityException)
        assertEquals("Tree grant revoked", error!!.message)
        assertEquals(1, queryCalls)
        assertEquals(0, delivered)
    }
}
