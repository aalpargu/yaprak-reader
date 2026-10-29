package com.yaprak.reader.formats.pdf

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.reader.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentPdfContentsNavigatorTest {
    @get:Rule val temp = TemporaryFolder()
    private class Fixture(val directory: File) {
        val original = "owned original".toByteArray()
        val file = File(directory, "owned.pdf").apply { writeBytes(original) }
        val book = Book("owned", "Owned", null, BookFormat.PDF, "owned.pdf", "owned.pdf", original.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, 0, pageCount = 3)
        var active = true
        var reads = 0
        var validate: suspend (Book) -> Unit = {}
        var parse: () -> ParsedPdfOutline = { ParsedPdfOutline(listOf(PdfOutlineRow("0", "Actual page", 0, 1),
            PdfOutlineRow("1", "External action", 0, null)), false) }
        val jumps = mutableListOf<Int>()
        fun source(dispatcher: CoroutineDispatcher, overrideBook: Book = book) = DocumentPdfContentsNavigator(
            overrideBook, 3, VerifiedBookFile(directory), Mutex(), { validate(it) },
            { _, _, checkActive -> checkActive(); reads++; parse() }, { active }, { jumps += it; true }, dispatcher)
    }

    @Test fun exactOriginalIsCheckedAndExplicitIssuedPageSelectionIsRequired() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(UnconfinedTestDispatcher(testScheduler))
        val contents = source.loadContents(); assertEquals(1, f.reads); assertTrue(f.jumps.isEmpty())
        assertTrue(contents.entries[0].navigable); assertFalse(contents.entries[1].navigable)
        assertFalse(source.goTo("invented")); assertFalse(source.goTo(contents.entries[1].id))
        assertTrue(source.goTo(contents.entries[0].id)); assertEquals(listOf(1), f.jumps)
        assertArrayEquals(f.original, f.file.readBytes())
    }

    @Test fun reloadingRevokesEveryPreviouslyIssuedEntryId() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(UnconfinedTestDispatcher(testScheduler))
        val old = source.loadContents().entries[0].id; val next = source.loadContents().entries[0].id
        assertNotEquals(old, next); assertFalse(source.goTo(old)); assertTrue(source.goTo(next))
    }

    @Test fun changedOriginalBeforeParsingNeverCallsTheParser() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(UnconfinedTestDispatcher(testScheduler))
        f.file.writeText("changed source")
        try { source.loadContents(); fail("Changed source accepted") } catch (expected: ReaderOpenException) {
            assertEquals(ReaderFailure.CHANGED, expected.reason)
        }
        assertEquals(0, f.reads); assertTrue(source.contents.entries.isEmpty())
    }

    @Test fun changedOriginalDuringParseCannotPublishTargets() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(UnconfinedTestDispatcher(testScheduler))
        val old = source.loadContents().entries[0].id
        val result = f.parse()
        f.parse = { f.file.writeBytes(ByteArray(f.original.size) { 'x'.code.toByte() }); result }
        try { source.loadContents(); fail("Changed source published") } catch (expected: ReaderOpenException) {
            assertEquals(ReaderFailure.CHANGED, expected.reason)
        }
        assertTrue(source.contents.entries.isEmpty()); assertFalse(source.goTo(old)); assertTrue(f.jumps.isEmpty())
    }

    @Test fun retiredOwnerDoesNoIoAndCannotUsePreviouslyIssuedTargets() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(UnconfinedTestDispatcher(testScheduler))
        val id = source.loadContents().entries[0].id; f.active = false; assertFalse(source.goTo(id))
        try { source.loadContents(); fail("Retired owner parsed") } catch (_: CancellationException) { }
        assertEquals(1, f.reads); assertTrue(source.contents.entries.isEmpty())
    }

    @Test fun retirementDuringParseCannotPublishEvenAValidResult() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(UnconfinedTestDispatcher(testScheduler))
        val result = f.parse(); f.parse = { f.active = false; result }
        try { source.loadContents(); fail("Retired result published") } catch (_: CancellationException) { }
        assertTrue(source.contents.entries.isEmpty()); assertTrue(f.jumps.isEmpty())
    }

    @Test fun cancelledValidationReleasesTheMutexAndCannotReplaceTheNextRequest() = runTest {
        val f = Fixture(temp.newFolder()); val source = f.source(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>(); var validations = 0
        f.validate = { if (++validations == 1) gate.await() }
        val old = launch { source.loadContents() }; runCurrent(); old.cancelAndJoin()
        f.validate = {}; val next = async { source.loadContents() }; runCurrent()
        assertEquals(2, next.await().entries.size); assertEquals(1, f.reads)
        assertArrayEquals(f.original, f.file.readBytes())
    }

    @Test fun unsupportedFormatAndOversizedMetadataAreRejectedBeforeAnyParse() = runTest {
        val f = Fixture(temp.newFolder()); val dispatcher = UnconfinedTestDispatcher(testScheduler)
        for ((book, reason) in listOf(f.book.copy(format = BookFormat.CBZ) to ContentsLoadFailure.UNSUPPORTED,
            f.book.copy(byteSize = PdfOutlineReader.MAX_SOURCE_BYTES + 1) to ContentsLoadFailure.LIMIT)) {
            try { f.source(dispatcher, book).loadContents(); fail("Unsupported source accepted") }
            catch (expected: ContentsLoadException) { assertEquals(reason, expected.reason) }
        }
        assertEquals(0, f.reads)
    }
}
