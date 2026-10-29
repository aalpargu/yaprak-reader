package com.yaprak.reader.formats.cbz

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.fixtures.CoverTestImages
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzThumbnailsDocumentTest {
    private class Fixture(val db: LibraryDatabase, val book: Book, val file: File,
        val bytes: ByteArray, val document: CbzDocument, val source: CbzThumbnailsSource) : java.io.Closeable {
        var allowed = true
        override fun close() { source.close(); document.close(); db.close(); file.delete() }
    }
    private suspend fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "books").apply { mkdirs() }
        val id = UUID.randomUUID().toString(); val file = File(directory, "$id.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, picture) in listOf("10.png" to CoverTestImages.png(1280, 640),
                "2.png" to CoverTestImages.png(480, 960), "11.png" to CoverTestImages.png(12, 8))) {
                zip.putNextEntry(ZipEntry(name)); zip.write(picture); zip.closeEntry()
            }
        }
        val bytes = file.readBytes()
        val book = Book(id, "Çizgi", null, BookFormat.CBZ, "Çizgi.cbz", file.name,
            bytes.size.toLong(), cbzHash(bytes), 1, 3)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = RoomLibraryRepository(db.books()); library.add(book)
        val archive = withContext(Dispatchers.IO) { CbzArchive.read(file) }
        lateinit var owner: Fixture
        val document = CbzDocument(archive, book, VerifiedBookFile(directory), library, Mutex()) {
            if (!owner.allowed) throw ReaderOpenException(ReaderFailure.RECOVERY)
        }
        owner = Fixture(db, book, file, bytes, document, CbzThumbnailsSource(document))
        return owner
    }
    @Test fun nativeOriginalNaturalOrderAspectAndPixelsAreBoundedWithoutProgressWrites() = runBlocking {
        fixture().use { f ->
            assertEquals(listOf("2.png", "10.png", "11.png"), f.document.archive.pages.map { it.entry.name })
            val portrait = f.source.render(0); val landscape = f.source.render(1); val small = f.source.render(2)
            assertEquals(160, portrait.width); assertEquals(320, portrait.height)
            assertEquals(240, landscape.width); assertEquals(120, landscape.height)
            assertEquals(12, small.width); assertEquals(8, small.height)
            listOf(portrait, landscape, small).forEach {
                assertEquals(Color.rgb(23, 98, 65), it.getPixel(it.width / 2, it.height / 2))
                assertTrue(it.allocationByteCount <= CBZ_THUMBNAIL_MAX_BYTES)
            }
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
            assertTrue(f.db.reading().observed(f.book.id, f.book.sha256).isEmpty())
            assertArrayEquals(f.bytes, f.file.readBytes())
            assertSame(portrait, f.source.render(0))
        }
    }
    @Test fun cachedImageRefusesActualOriginalTamperingAndDeletionThenRecoversExactBytes() = runBlocking {
        fixture().use { f ->
            val cached = f.source.render(0)
            f.file.appendBytes(byteArrayOf(0))
            assertEquals(ReaderFailure.CHANGED, (runCatching { f.source.render(0) }.exceptionOrNull() as ReaderOpenException).reason)
            f.file.writeBytes(f.bytes); assertSame(cached, f.source.render(0))
            assertTrue(f.file.delete())
            assertEquals(ReaderFailure.MISSING, (runCatching { f.source.render(0) }.exceptionOrNull() as ReaderOpenException).reason)
            f.file.writeBytes(f.bytes); assertSame(cached, f.source.render(0)); assertFalse(cached.isRecycled)
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
        }
    }
    @Test fun realCurrentBookCountAndRecoveryAreReprovedEvenOnCacheHit() = runBlocking {
        fixture().use { f ->
            val cached = f.source.render(1); f.allowed = false
            assertEquals(ReaderFailure.RECOVERY, (runCatching { f.source.render(1) }.exceptionOrNull() as ReaderOpenException).reason)
            f.allowed = true
            withContext(Dispatchers.IO) { f.db.openHelper.writableDatabase.execSQL("UPDATE books SET pageCount=99 WHERE id=?", arrayOf(f.book.id)) }
            assertEquals(ReaderFailure.CHANGED, (runCatching { f.source.render(1) }.exceptionOrNull() as ReaderOpenException).reason)
            withContext(Dispatchers.IO) { f.db.openHelper.writableDatabase.execSQL("UPDATE books SET pageCount=3 WHERE id=?", arrayOf(f.book.id)) }
            assertSame(cached, f.source.render(1)); assertFalse(cached.isRecycled)
        }
    }
    @Test fun closedDocumentCannotServePreviouslyCachedPixels() = runBlocking {
        fixture().use { f ->
            val cached = f.source.render(0); f.document.close()
            assertEquals(ReaderFailure.MISSING, (runCatching { f.source.render(0) }.exceptionOrNull() as ReaderOpenException).reason)
            assertFalse(cached.isRecycled)
        }
    }
    @Test fun actualSessionNewSurfaceAndCloseRevokeOldPreviewSourceWithoutPdfCapabilities() = runBlocking {
        fixture().use { f ->
            val session = CbzReaderSession(f.book, f.document, null)
            val old = session.createSurface(null, false, {}, {})
            assertNull(old.pdfThumbnails); assertNull(old.pdfOcr); assertNull(old.pdfView); assertNull(old.pdfCrop)
            assertNotNull(old.pageThumbnails); assertEquals(3, old.pageThumbnails!!.pageCount)
            val preview = old.pageThumbnails!!.render(0)
            assertNull(old.pageNavigator!!.captureStablePage()); assertNull(old.bookmarkNavigator!!.capture())
            val next = session.createSurface(null, false, {}, {})
            assertTrue(runCatching { old.pageThumbnails!!.render(0) }.exceptionOrNull() is IllegalStateException)
            assertSame(preview, next.pageThumbnails!!.render(0))
            session.close(); assertFalse(preview.isRecycled)
            assertTrue(runCatching { next.pageThumbnails!!.render(0) }.exceptionOrNull() is IllegalStateException)
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
        }
    }
}
