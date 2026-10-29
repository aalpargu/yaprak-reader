package com.yaprak.reader.formats.cbr

import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual dependency and self-authored stored PNG containers; raster/Android admission is separate. */
class CbrArchiveTransportTest {
    private val root by lazy {
        checkNotNull(File(checkNotNull(javaClass.getResource("/cbr183/self-fixtures/stored-rar5.cbr")).toURI()).parentFile?.parentFile)
    }
    private fun own(name: String) = File(root,"self-fixtures/$name")
    private fun upstream(name: String) = File(root,"upstream/$name")
    private suspend fun reason(file: File, wanted: CbrFailure) {
        var calls=0
        try { CbrArchiveTransport.read(file) { _,_ -> calls++ }; fail("Source must not be accepted") }
        catch (error: CbrException) { assertEquals(wanted,error.reason) }
        assertEquals("No candidates before complete metadata admission",0,calls)
    }

    @Test fun originalRar4AndRar5YieldExactCompleteCandidateBytesInArchiveOrder() = runBlocking {
        for (name in listOf("stored-rar4.cbr","stored-rar5.cbr")) {
            val source=own(name);val before=source.readBytes();val entries=linkedMapOf<String,ByteArray>()
            val summary=CbrArchiveTransport.read(source) { entry,bytes ->
                assertEquals(bytes.size,entry.declaredBytes);assertEquals("png",entry.extension)
                assertNull(entries.put(entry.name,bytes))
            }
            assertEquals(listOf("pages/01.png","pages/10.png"),entries.keys.toList())
            assertArrayEquals(own("01.png").readBytes(),entries.getValue("pages/01.png"))
            assertArrayEquals(own("10.png").readBytes(),entries.getValue("pages/10.png"))
            assertEquals(CbrDecodedSummary(2,2,entries.values.sumOf { it.size.toLong() }),summary)
            assertArrayEquals(before,source.readBytes())
        }
    }
    @Test fun corruptLaterPayloadNeverProducesACompleteArchive() = runBlocking {
        var candidates=0
        var complete:CbrDecodedSummary?=null
        try { complete=CbrArchiveTransport.read(own("corrupt-second-payload.cbr")) { _,_ -> candidates++ };fail("Bad CRC") }
        catch (failure:CbrException) { assertEquals(CbrFailure.INVALID,failure.reason) }
        assertEquals("Only earlier provisional candidate was decoded",1,candidates)
        assertNull(complete)
    }
    @Test fun pathTraversalAndCaseFoldAliasesRejectBeforeAnyCandidate() = runBlocking {
        reason(own("traversal.cbr"),CbrFailure.INVALID)
        reason(own("duplicate-fold.cbr"),CbrFailure.INVALID)
    }
    @Test fun configuredDictionaryBudgetRejectsAnActualOneGiBClaim() = runBlocking {
        reason(upstream("rar5unpack/m3-1gb-claim.rar"),CbrFailure.LIMIT)
    }
    @Test fun brokenHeadersTruncationAndTrailingDataAreNotPartialSuccess() = runBlocking {
        reason(upstream("abnormal/bad-header-crc.rar"),CbrFailure.INVALID)
        for ((name,data) in listOf("tail.cbr" to (own("stored-rar5.cbr").readBytes()+byteArrayOf(0)),
            "missing-end.cbr" to own("stored-rar4.cbr").readBytes().dropLast(7).toByteArray())) {
            val temporary=File.createTempFile("cbr-transport-",name)
            try { temporary.writeBytes(data);reason(temporary,CbrFailure.INVALID) }
            finally { check(temporary.delete()) }
        }
    }
    @Test fun callerStorageFailureAndCancellationRetainTheirExactIdentity() = runBlocking {
        val storage=IOException("controlled sink storage failure")
        try { CbrArchiveTransport.read(own("stored-rar5.cbr")) { _,_ -> throw storage };fail() }
        catch (actual:IOException) { assertSame(storage,actual) }
        val cancelled=CancellationException("controlled sink cancellation")
        try { CbrArchiveTransport.read(own("stored-rar5.cbr")) { _,_ -> throw cancelled };fail() }
        catch (actual:CancellationException) { assertSame(cancelled,actual) }
    }
    @Test fun anAlreadyCancelledOwnerWinsOverAMissingSource() = runBlocking {
        val owner=Job();var observed=false
        try {
            withContext(owner) {
                owner.cancel()
                try { CbrArchiveTransport.read(own("does-not-exist.cbr")) { _,_ -> fail() } }
                catch (actual:CancellationException) { observed=true;throw actual }
            }
            fail()
        } catch (_:CancellationException) { }
        assertTrue(observed)
    }
    @Test fun nonComicArchivesAndUnsafeLinksAreRejectedWithoutFilesystemExtraction() = runBlocking {
        reason(upstream("rar4.rar"),CbrFailure.UNSUPPORTED)
        reason(upstream("rar5.rar"),CbrFailure.UNSUPPORTED)
        reason(upstream("links/rar5-links.rar"),CbrFailure.UNSUPPORTED)
    }
    @Test fun declaredImageTotalAndPageCountBudgetsRejectBeforeAllocationOrDelivery() = runBlocking {
        reason(own("image-byte-claim.cbr"),CbrFailure.LIMIT)
        reason(own("total-byte-claims.cbr"),CbrFailure.LIMIT)
        reason(own("many-images.cbr"),CbrFailure.LIMIT)
    }
    @Test fun emptyImagesAreInvalidWhileEmptyOptionalComicInfoRetainsAllImages() = runBlocking {
        reason(own("empty-image.cbr"),CbrFailure.INVALID)
        val entries=linkedMapOf<String,Int>()
        val summary=CbrArchiveTransport.read(own("empty-comicinfo.cbr")) { entry,bytes -> entries[entry.name]=bytes.size }
        assertEquals(2,summary.imageCount)
        assertEquals(3,summary.fileCount)
        assertEquals(0,entries.getValue("ComicInfo.xml"))
    }
}
