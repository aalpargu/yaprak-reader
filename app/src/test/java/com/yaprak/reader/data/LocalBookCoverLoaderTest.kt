package com.yaprak.reader.data

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.covers.CoverBitmapReader
import com.yaprak.reader.data.covers.LocalBookCoverLoader
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalBookCoverLoaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun book(name: String): Book {
        val file = File(temp.root, "$name.epub").apply { writeText(name) }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return Book(name, name, null, BookFormat.EPUB, file.name, file.name, file.length(), hash, 0)
    }

    @Test fun `cache budget evicts least recent cover without recycling displayed image`() = runTest {
        val books = listOf(book("a"), book("b"), book("c"))
        val decoded = mutableListOf<String>()
        val reader = CoverBitmapReader { file, _ ->
            decoded += file.readText()
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        val loader = LocalBookCoverLoader(VerifiedBookFile(temp.root), reader,
            StandardTestDispatcher(testScheduler), maxCacheBytes = 32)
        val a = loader.load(books[0])!!
        loader.load(books[1]); assertSame(a, loader.load(books[0]))
        loader.load(books[2]); loader.load(books[1])
        assertEquals(listOf("a", "b", "c", "b"), decoded)
        assertFalse(a.isRecycled)
    }

    @Test fun `absent cover cache has a bounded entry count`() = runTest {
        val books = listOf(book("a"), book("b"), book("c"))
        var reads = 0
        val loader = LocalBookCoverLoader(VerifiedBookFile(temp.root), CoverBitmapReader { _, _ -> reads++; null },
            StandardTestDispatcher(testScheduler), maxCacheEntries = 2)
        books.forEach { assertNull(loader.load(it)) }
        assertNull(loader.load(books[0]))
        assertEquals(4, reads)
    }

    @Test fun `verification and access failures can be retried without cached absence`() = runTest {
        val book = book("original")
        val file = File(temp.root, book.storageName)
        file.writeText("modified")
        var reads = 0
        val loader = LocalBookCoverLoader(VerifiedBookFile(temp.root), CoverBitmapReader { _, _ ->
            if (++reads == 1) throw IOException("Retryable cover access failure")
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }, StandardTestDispatcher(testScheduler))
        assertNull(loader.load(book)); assertEquals(0, reads)
        file.writeText("original")
        assertNull(loader.load(book)); assertEquals(1, reads)
        assertNotNull(loader.load(book)); assertEquals(2, reads)
    }

    @Test fun `cancelled queued request never starts decode and repeated key reuses first result`() = runTest {
        val book = book("a")
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val loader = LocalBookCoverLoader(VerifiedBookFile(temp.root), CoverBitmapReader { _, _ ->
            reads++; gate.await(); Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }, StandardTestDispatcher(testScheduler))
        val first = async { loader.load(book) }
        runCurrent()
        val queued = async { loader.load(book) }
        runCurrent()
        queued.cancelAndJoin()
        val repeated = async { loader.load(book) }
        runCurrent()
        assertEquals(1, reads)
        gate.complete(Unit); advanceUntilIdle()
        assertSame(first.await(), repeated.await())
        assertTrue(queued.isCancelled)
        assertEquals(1, reads)
    }
}
