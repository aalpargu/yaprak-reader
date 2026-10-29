package com.yaprak.reader.formats.cbr

import android.app.Application
import com.yaprak.reader.data.backup.ArchiveBook
import com.yaprak.reader.data.backup.BackupProblem
import com.yaprak.reader.data.backup.BackupRecordsCodec
import com.yaprak.reader.data.backup.validBackupBookSignature
import com.yaprak.reader.data.importing.SourceDocument
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.formats.cbz.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Container/locator/version contracts only. Native raster admission is exercised on Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CbrContainerBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/cbr183/self-fixtures/$name")).use { it.readBytes() }
    private val id = "11111111-1111-4111-8111-111111111111"

    @Test fun stagingDetectsBothRarFamiliesAndCommitsExactOriginalCbrBytes() = runBlocking {
        for (name in listOf("stored-rar4.cbr", "stored-rar5.cbr")) {
            val bytes = fixture(name)
            val files = LocalBookFiles(temporary.newFolder())
            val staged = files.stage(SourceDocument(name) { bytes.inputStream() }, id)
            assertEquals(BookFormat.CBR, staged.format)
            assertEquals(cbzHash(bytes), staged.sha256)
            val committed = files.commit(staged, id)
            assertEquals("$id.cbr", committed.name)
            assertArrayEquals(bytes, committed.readBytes())
        }
    }
    @Test fun extendingMagicReadKeepsBothShortAndLongPdfPrefixes() = runBlocking {
        for (bytes in listOf("%PDF-".toByteArray(), "%PDF-1.7\noriginal bytes".toByteArray())) {
            val files = LocalBookFiles(temporary.newFolder())
            val staged = files.stage(SourceDocument("original.pdf") { bytes.inputStream() }, id)
            assertEquals(BookFormat.PDF, staged.format)
            assertArrayEquals(bytes, staged.file.readBytes())
        }
    }
    @Test fun suffixCannotAuthorizeZipAsCbrOrAnIncompleteRar5Signature() = runBlocking {
        for (bytes in listOf(byteArrayOf(0x50, 0x4b, 3, 4, 0), byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1))) {
            val directory = temporary.newFolder()
            try { LocalBookFiles(directory).stage(SourceDocument("fake.cbr") { bytes.inputStream() }, id); fail() }
            catch (failure: BookImportException) { assertEquals(ImportFailure.INVALID, failure.reason) }
            assertTrue(directory.listFiles().isNullOrEmpty())
        }
    }
    @Test fun version26AloneAdmitsCbrAndStorageSuffixMustMatchFormat() {
        val book = ArchiveBook(id, "$id.cbr", "CBR", 100, "a".repeat(64))
        BackupRecordsCodec.validateBooks(listOf(book), 26)
        for (version in 1..25) {
            try { BackupRecordsCodec.validateBooks(listOf(book), version); fail("Legacy format $version") }
            catch (_: BackupProblem) { }
        }
        try { BackupRecordsCodec.validateBooks(listOf(book.copy(storageName = "$id.cbz")), 26); fail() }
        catch (_: BackupProblem) { }
    }
    @Test fun signatureRestoreGatePreservesPdfAndFreezesOldCbrRejection() = runBlocking {
        val rar = temporary.newFile("original.cbr").apply { writeBytes(fixture("stored-rar5.cbr")) }
        assertTrue(validBackupBookSignature(rar, "CBR", "source.cbr", 26))
        assertFalse(validBackupBookSignature(rar, "CBR", "source.cbr", 25))
        val pdf = temporary.newFile("original.pdf").apply { writeText("%PDF-1.7\nsource") }
        assertTrue(validBackupBookSignature(pdf, "PDF", "source.pdf", 26))
    }
    @Test fun cbrAndCbzLocatorsAreDistinctAndAdmitOnlyTheExactOrderedEntry() {
        val entry = CbzEntry("pages/01.png", 50, 50, 1, 0, 0, 0)
        val archive = CbzArchive(listOf(CbzPage(entry, CbzImage("image/png", 2, 2), "a".repeat(64))))
        val cbr = CbzPosition.encode(0, archive, BookFormat.CBR)
        val cbz = CbzPosition.encode(0, archive)
        assertEquals("cbr/pages/01.png", cbr.resourceHref)
        assertEquals(0, CbzPosition.decode(cbr, archive, BookFormat.CBR))
        assertNull(CbzPosition.decode(cbr, archive))
        assertNull(CbzPosition.decode(cbz, archive, BookFormat.CBR))
        assertNull(CbzPosition.decode(cbr.copy(resourceHref = "cbr/foreign.png"), archive, BookFormat.CBR))
    }
}
