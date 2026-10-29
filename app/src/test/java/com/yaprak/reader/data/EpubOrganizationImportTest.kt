package com.yaprak.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.importing.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.EpubMetadataReader
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubOrganizationImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val initial = BookOrganization(series = "Yaprak Serisi", tags = listOf("Yazılım"), collections = listOf("Teknik"))
    private fun source() = TestBooks.epub(temp.newFile("declared.epub"), additionalMetadata = """
        <dc:subject>Yazılım</dc:subject>
        <meta property="belongs-to-collection" id="series">Yaprak Serisi</meta>
        <meta refines="#series" property="collection-type">series</meta>
        <meta property="belongs-to-collection" id="collection">Teknik</meta>
        <meta refines="#collection" property="collection-type">set</meta>""")
    private fun importer(db: LibraryDatabase, source: File, folder: File, counted: () -> Unit = {}) = DefaultBookImporter(
        DocumentProvider { SourceDocument(source.name) { source.inputStream() } }, LocalBookFiles(folder),
        BookMetadataReader { file, format ->
            require(format == BookFormat.EPUB); counted()
            EpubMetadataReader(context.contentResolver).read(file).also { awaitClose(file) }
        }, RoomLibraryRepository(db.books()))
    private suspend fun awaitClose(file: File) {
        val probe = File(file.parentFile, file.name + ".probe")
        withTimeout(5_000) { while (!file.renameTo(probe)) delay(10) }
        check(probe.renameTo(file))
    }
    @Test fun declaredMetadataAndBookBytesSurviveSourceRemovalAndDatabaseReopen() = runBlocking {
        val source = source(); val bytes = source.readBytes(); val folder = temp.newFolder("books")
        val name = "ei.db"; context.deleteDatabase(name)
        try {
            var saved: Book? = null
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try {
                assertEquals(ImportResult.Added, importer(db, source, folder).import("content://fixture"))
                saved = RoomLibraryRepository(db.books()).observeBooks().first().single()
                assertEquals(initial, saved.organization)
                assertEquals(0, db.books().completionEventCount())
            } finally { db.close() }
            assertTrue(source.delete())
            val reopened = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            try {
                val actual = RoomLibraryRepository(reopened.books()).observeBooks().first().single()
                assertEquals(saved, actual)
                assertArrayEquals(bytes, File(folder, actual.storageName).readBytes())
                assertEquals(0, reopened.books().completionEventCount())
                assertEquals(1, folder.listFiles()!!.size)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun duplicateImportPreservesUserLabelsTitleAndCompletionHistoryWithoutRereadingMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val source = source(); val folder = temp.newFolder("books"); var reads = 0
            val importer = importer(db, source, folder) { reads++ }
            assertEquals(ImportResult.Added, importer.import("content://fixture"))
            val repo = RoomLibraryRepository(db.books()); val book = repo.observeBooks().first().single()
            val edited = BookOrganization(BookReadingStatus.COMPLETED, true, "Benim serim", "Teknik",
                listOf("Benim etiketim"), listOf("Benim rafım"))
            assertTrue(repo.updateOrganization(book.id, book.sha256, edited))
            assertTrue(repo.updateMetadata(book.id, book.sha256, "Benim başlığım", "Benim yazarım"))
            assertEquals(ImportResult.Duplicate, importer.import("content://renamed"))
            assertEquals(1, reads); assertEquals(edited, repo.findById(book.id)!!.organization)
            assertEquals("Benim başlığım", repo.findById(book.id)!!.title)
            assertEquals(1, db.books().completionEventCount()); assertEquals(1, folder.listFiles()!!.size)
        } finally { db.close() }
    }
    @Test fun metadataCommitFailureLeavesNoBookOrFileAndExplicitRetryImportsBoth() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val folder = temp.newFolder("books"); val importer = importer(db, source(), folder)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_import BEFORE INSERT ON book_organizations BEGIN SELECT RAISE(ABORT, 'controlled'); END")
            assertEquals(ImportResult.Failed(ImportFailure.UNEXPECTED), importer.import("content://fixture"))
            assertTrue(RoomLibraryRepository(db.books()).observeBooks().first().isEmpty())
            assertTrue(folder.listFiles()!!.isEmpty()); assertEquals(0, db.books().completionEventCount())
            sql.execSQL("DROP TRIGGER refuse_import")
            assertEquals(ImportResult.Added, importer.import("content://fixture"))
            assertEquals(initial, RoomLibraryRepository(db.books()).observeBooks().first().single().organization)
            assertEquals(1, folder.listFiles()!!.size)
        } finally { db.close() }
    }
}
