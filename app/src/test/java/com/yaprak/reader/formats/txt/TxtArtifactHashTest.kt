package com.yaprak.reader.formats.txt

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real streams with deterministic stale length metadata; no timing-sensitive filesystem race. */
class TxtArtifactHashTest {
    @get:Rule val temporary = TemporaryFolder()
    private val limit = 64L * 1024 * 1024
    private fun oversized() = temporary.newFile().also { file -> RandomAccessFile(file, "rw").use { it.setLength(limit + 1) } }
    @Test fun validOwnedArtifactRetainsTheExistingDigestAndBytes() = runBlocking {
        val file = temporary.newFile().apply { writeText("abc", Charsets.UTF_8) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", txtViewHash(file))
        assertEquals("abc", file.readText())
    }
    @Test fun missingDirectoryAndInitiallyEmptyFilesFailAdmission() = runBlocking {
        for (file in listOf(File(temporary.root, "missing.epub"), temporary.newFolder(), temporary.newFile()))
            assertTrue(runCatching { txtViewHash(file) }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun oversizedArtifactFailsTheInitialBudgetWithoutMutation() = runBlocking {
        val file = oversized()
        assertTrue(runCatching { txtViewHash(file) }.exceptionOrNull() is IllegalStateException)
        assertEquals(limit + 1, file.length())
    }
    @Test fun streamLargerThanItsInitialLengthCannotEscapeTheBudget() = runBlocking {
        val actual = oversized()
        val staleLength = object : File(actual.absolutePath) { override fun length() = 1L }
        assertTrue(staleLength.isFile)
        assertTrue(runCatching { txtViewHash(staleLength) }.exceptionOrNull() is IllegalStateException)
        assertEquals(limit + 1, actual.length())
    }
    @Test fun anEmptyStreamAfterNonemptyMetadataCannotProduceASuccessfulDigest() = runBlocking {
        val actual = temporary.newFile()
        val staleLength = object : File(actual.absolutePath) { override fun length() = 1L }
        assertTrue(staleLength.isFile)
        assertTrue(runCatching { txtViewHash(staleLength) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0L, actual.length())
    }
    @Test fun alreadyCanceledCallerIsNotReplacedByAFileAdmissionFailure() = runBlocking {
        var observed: Throwable? = null
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            observed = runCatching { txtViewHash(File(temporary.root, "missing.epub")) }.exceptionOrNull()
        }
        assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
        // Check the function's own exception; awaiting an already-canceled Job alone would mask it.
        assertTrue(observed is CancellationException)
    }
    @Test fun callerCancellationLeavesAValidOwnedArtifactUntouched() = runBlocking {
        val file = temporary.newFile().apply { writeText("abc") }
        var observed: Throwable? = null
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            observed = runCatching { txtViewHash(file) }.exceptionOrNull()
        }
        assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
        assertTrue(observed is CancellationException)
        assertEquals("abc", file.readText())
    }
}
