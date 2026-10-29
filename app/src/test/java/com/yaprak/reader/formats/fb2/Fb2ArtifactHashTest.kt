package com.yaprak.reader.formats.fb2

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Fb2ArtifactHashTest {
    @get:Rule val temporary = TemporaryFolder()
    private val limit = 64L * 1024 * 1024
    private fun oversized() = temporary.newFile().also { file -> RandomAccessFile(file, "rw").use { it.setLength(limit + 1) } }
    @Test fun validArtifactDigestRetainsTheExactExistingLowercaseSha256() = runBlocking {
        val file = temporary.newFile().apply { writeText("abc", Charsets.UTF_8) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fb2ViewHash(file))
        assertEquals("abc", file.readText())
    }
    @Test fun emptyCacheFileCannotBeAcceptedAsAViewingArtifact() = runBlocking {
        assertTrue(runCatching { fb2ViewHash(temporary.newFile()) }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun oversizedCacheFileFailsBeforeHashingTheWholeCorruptArtifact() = runBlocking {
        val file = oversized()
        assertTrue(runCatching { fb2ViewHash(file) }.exceptionOrNull() is IllegalStateException)
        assertEquals(limit + 1, file.length())
    }
    @Test fun streamGrowthCannotEscapeTheBudgetWhenTheInitialLengthWasSmall() = runBlocking {
        val actual = oversized()
        val initialLength = object : File(actual.absolutePath) { override fun length() = 1L }
        assertTrue(initialLength.isFile)
        assertTrue(runCatching { fb2ViewHash(initialLength) }.exceptionOrNull() is IllegalStateException)
        assertEquals(limit + 1, actual.length())
    }
    @Test fun truncationAfterTheLengthCheckCannotAcceptAnEmptyStream() = runBlocking {
        val actual = temporary.newFile()
        val initialLength = object : File(actual.absolutePath) { override fun length() = 1L }
        assertTrue(initialLength.isFile)
        assertTrue(runCatching { fb2ViewHash(initialLength) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0L, actual.length())
    }
    @Test fun callerCancellationPropagatesAndLeavesTheOwnedFileUntouched() = runBlocking {
        val file = temporary.newFile().apply { writeText("abc") }
        var entered = false
        var observed: Throwable? = null
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            entered = true; currentCoroutineContext().cancel()
            observed = runCatching { fb2ViewHash(file) }.exceptionOrNull()
        }
        assertTrue(entered)
        assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
        assertTrue(observed is CancellationException)
        assertEquals("abc", file.readText())
    }
}
