package com.yaprak.reader.data

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.data.storage.verifiedSourceHash
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedBookFileStreamTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun changed(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull() as ReaderOpenException
        assertEquals(ReaderFailure.CHANGED, error.reason)
    }
    private suspend fun outcome(block: suspend (Job) -> Unit): Throwable? = supervisorScope {
        val result = CompletableDeferred<Throwable?>()
        val child = launch(start = CoroutineStart.UNDISPATCHED) {
            result.complete(runCatching { block(currentCoroutineContext().job) }.exceptionOrNull())
        }
        result.await().also { child.join() }
    }
    @Test fun exactOriginalAcrossMultipleBuffersPreservesItsExistingShaAndActualFile() = runTest {
        val bytes = ByteArray(140_005) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(hash, verifiedSourceHash(ByteArrayInputStream(bytes), bytes.size.toLong()))
        val file = temp.newFile("original.epub").apply { writeBytes(bytes) }
        val book = Book("owned", "Owned", null, BookFormat.EPUB, file.name, file.name, file.length(), hash, 1)
        assertEquals(file, VerifiedBookFile(temp.root).resolve(book)); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun growingStreamStopsAfterTheSingleOverflowByteInsteadOfHashingAnUnboundedTail() = runTest {
        val input = ByteArrayInputStream(ByteArray(1_000_000))
        changed { verifiedSourceHash(input, 3) }
        assertEquals(1_000_000 - 4, input.available())
        val exactThenGrown = ByteArrayInputStream(ByteArray(65_537))
        changed { verifiedSourceHash(exactThenGrown, 65_536) }
        assertEquals(0, exactThenGrown.available())
    }
    @Test fun prematureEofAndEmptyStreamCannotBecomeAnOriginalDigest() = runTest {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3)))
            changed { verifiedSourceHash(ByteArrayInputStream(bytes), 4) }
    }
    @Test fun invalidOriginalByteCountFailsBeforeAnyStreamRead() = runTest {
        var reads = 0
        val input = object : InputStream() { override fun read(): Int { reads++; return -1 } }
        for (size in listOf(0L, -1L, Long.MIN_VALUE)) changed { verifiedSourceHash(input, size) }
        assertEquals(0, reads)
    }
    @Test fun cancelledCallerWinsBeforeAMissingOrOutsideFileCanReportAReaderError() = runTest {
        val book = Book("owned", "Owned", null, BookFormat.EPUB, "missing.epub", "../outside", 1, "a".repeat(64), 1)
        val error = outcome { job -> job.cancel(CancellationException("owned cancel")); VerifiedBookFile(temp.root).resolve(book) }
        assertTrue(error is CancellationException)
    }
    @Test fun cancellationDuringEofReadCannotPublishAnOtherwiseValidDigest() = runTest {
        val error = outcome { job ->
            var calls = 0
            val input = object : InputStream() {
                override fun read(): Int = kotlin.error("Bulk reads required")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++calls == 1) { buffer[offset] = 1; return 1 }
                    job.cancel(CancellationException("owned EOF cancel")); return -1
                }
            }
            verifiedSourceHash(input, 1)
        }
        assertTrue(error is CancellationException)
    }
    @Test fun cancellationDuringAChunkPrecedesSizeFailureAndDoesNotGetSwallowed() = runTest {
        val error = outcome { job ->
            val input = object : InputStream() {
                override fun read(): Int = kotlin.error("Bulk reads required")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    job.cancel(CancellationException("owned chunk cancel")); buffer[offset] = 1; return 1
                }
            }
            verifiedSourceHash(input, 1)
        }
        assertTrue(error is CancellationException)
    }
    @Test fun ioFailureAndNonProgressingStreamNeverProduceSuccessfulSourceEvidence() = runTest {
        val failure = IOException("owned source read")
        val broken = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, runCatching { verifiedSourceHash(broken, 1) }.exceptionOrNull())
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        changed { verifiedSourceHash(stalled, 1) }
    }
}
