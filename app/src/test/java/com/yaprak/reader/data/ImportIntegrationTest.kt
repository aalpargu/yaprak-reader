package com.yaprak.reader.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.importing.AndroidDocumentProvider
import com.yaprak.reader.data.importing.DefaultBookImporter
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.EpubMetadataReader
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `EPUB content URI import survives source removal and database reopening`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val epubFile = TestBooks.epub(temp.newFile("source.epub"))
        val sources = mapOf("epub" to epubFile)
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/epub+zip"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(sources.getValue(uri.lastPathSegment!!).name)) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
        }
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "com.yaprak.test"
            applicationInfo = context.applicationInfo
            exported = true
            grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal("com.yaprak.test", provider)
        // Windows JVM descriptors cannot act as Android ParcelFileDescriptors. Bind a fresh
        // real stream per open at Robolectric's resolver boundary; all import/Readium/Room
        // work below still uses the production implementations and real files.
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse("content://com.yaprak.test/epub")) {
            epubFile.inputStream()
        }
        val name = "full-import.db"
        context.deleteDatabase(name)
        val libraryDirectory = temp.newFolder("books")
        val epubReader = EpubMetadataReader(context.contentResolver)
        Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
            val repository = RoomLibraryRepository(db.books())
            val importer = DefaultBookImporter(
                AndroidDocumentProvider(context.contentResolver), LocalBookFiles(libraryDirectory),
                BookMetadataReader { file, format ->
                    when (format) {
                        BookFormat.EPUB -> epubReader.read(file).also { awaitReadiumClose(file) }
                        BookFormat.PDF, BookFormat.TXT, BookFormat.CBZ, BookFormat.CBR, BookFormat.FB2, BookFormat.DOCX -> error("This host integration test covers EPUB.")
                    }
                }, repository,
            )
            assertEquals(ImportResult.Added, importer.import("content://com.yaprak.test/epub"))
            assertEquals(ImportResult.Duplicate, importer.import("content://com.yaprak.test/epub"))
        }
        assertTrue(epubFile.delete())
        Room.databaseBuilder(context, LibraryDatabase::class.java, name).build().useDatabase { db ->
            val books = RoomLibraryRepository(db.books()).observeBooks().first()
            assertEquals(1, books.size)
            val epub = books.single { it.format == BookFormat.EPUB }
            assertEquals("Bir Yaz Sabahı", epub.title)
            assertEquals("Deniz Yazar", epub.authors)
            val storedFile = File(libraryDirectory, epub.storageName)
            assertEquals(epub.title, epubReader.read(storedFile).title)
            awaitReadiumClose(storedFile)
            assertEquals(1, libraryDirectory.listFiles()!!.size)
        }
        context.deleteDatabase(name)
        Unit
    }

    private suspend fun awaitReadiumClose(file: File) {
        // Readium 3.1.2 dispatches close() asynchronously. Android permits renaming open
        // files; Windows does not. Wait for actual release, without bypassing the importer
        // or forcing GC. A leaked handle fails this test after a bounded timeout.
        val probe = File(file.parentFile, "${file.name}.close-probe")
        withTimeout(5_000) {
            while (!file.renameTo(probe)) delay(10)
        }
        check(probe.renameTo(file)) { "Cannot restore closed test file" }
    }
}
