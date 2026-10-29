package com.yaprak.reader.data

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.covers.CoverBitmapReader
import com.yaprak.reader.data.covers.LocalBookCoverLoader
import com.yaprak.reader.data.covers.VerifiedCoverFile
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookCover
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.fixtures.CoverTestImages
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomCoverLoaderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun book(directory: File): Book {
        val file = File(directory, "book.epub").apply { writeText("book") }
        return Book("book", "Kitap", null, BookFormat.EPUB, "book.epub", file.name, file.length(), hash(file.readBytes()), 1)
    }
    private fun cover(directory: File, width: Int = 8, height: Int = 12): BookCover {
        val id = UUID.randomUUID().toString(); val bytes = CoverTestImages.png(width, height)
        File(directory, "$id.png").writeBytes(bytes)
        return BookCover(id, "$id.png", "image/png", bytes.size.toLong(), hash(bytes), width, height)
    }

    @Test fun `custom revisions and reset`() = runTest {
        val books = temp.newFolder("books"); val covers = temp.newFolder("covers")
        val book = book(books); val first = cover(covers); val second = cover(covers, 16, 24)
        var originalReads = 0
        val loader = LocalBookCoverLoader(VerifiedBookFile(books), CoverBitmapReader { _, _ ->
            originalReads++; Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        }, StandardTestDispatcher(testScheduler), customFiles = VerifiedCoverFile(covers), operations = BookOperationCoordinator())
        val firstImage = loader.load(book.copy(customCover = first))!!
        assertEquals(8, firstImage.width); assertSame(firstImage, loader.load(book.copy(customCover = first)))
        val secondImage = loader.load(book.copy(customCover = second))!!
        assertEquals(16, secondImage.width); assertNotSame(firstImage, secondImage)
        assertEquals(0, originalReads)
        val reset = loader.load(book)!!
        assertEquals(2, reset.width); assertEquals(1, originalReads)
        assertFalse(firstImage.isRecycled); assertFalse(secondImage.isRecycled)
    }

    @Test fun `failed custom revision retries`() = runTest {
        val books = temp.newFolder("books"); val covers = temp.newFolder("covers")
        val book = book(books); val cover = cover(covers); val file = File(covers, cover.storageName)
        val bytes = file.readBytes(); file.writeBytes(bytes.clone().also { it[it.lastIndex] = 0 })
        var originalReads = 0
        val fallback = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val loader = LocalBookCoverLoader(VerifiedBookFile(books), CoverBitmapReader { _, _ -> originalReads++; fallback },
            StandardTestDispatcher(testScheduler), customFiles = VerifiedCoverFile(covers))
        assertSame(fallback, loader.load(book.copy(customCover = cover)))
        assertSame(fallback, loader.load(book.copy(customCover = cover)))
        assertEquals(1, originalReads)
        file.writeBytes(bytes)
        val recovered = loader.load(book.copy(customCover = cover))!!
        assertEquals(8, recovered.width); assertNotSame(fallback, recovered)
        assertEquals(1, originalReads)
    }

    @Test fun `custom read obeys shared lock`() = runTest {
        val books = temp.newFolder("books"); val covers = temp.newFolder("covers")
        val book = book(books); val cover = cover(covers); val operations = BookOperationCoordinator()
        var originalReads = 0
        val loader = LocalBookCoverLoader(VerifiedBookFile(books), CoverBitmapReader { _, _ -> originalReads++; null },
            StandardTestDispatcher(testScheduler), customFiles = VerifiedCoverFile(covers), operations = operations)
        operations.fileMutex.lock()
        val waiting = async { loader.load(book.copy(customCover = cover)) }
        runCurrent(); assertFalse(waiting.isCompleted); assertEquals(0, originalReads)
        operations.fileMutex.unlock()
        assertEquals(8, waiting.await()!!.width); assertEquals(0, originalReads)
    }
}
