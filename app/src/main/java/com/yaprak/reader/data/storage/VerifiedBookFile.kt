package com.yaprak.reader.data.storage

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Run on IO. A saved locator is reusable only for exactly the imported bytes. */
class VerifiedBookFile(private val directory: File) {
    suspend fun resolve(book: Book): File {
        currentCoroutineContext().ensureActive()
        val file = File(directory, book.storageName)
        if (file.canonicalFile.parentFile != directory.canonicalFile || !file.isFile) {
            throw ReaderOpenException(ReaderFailure.MISSING)
        }
        if (book.byteSize <= 0 || file.length() != book.byteSize) changedSource()
        val hash = file.inputStream().use { verifiedSourceHash(it, book.byteSize) }
        currentCoroutineContext().ensureActive()
        if (hash != book.sha256 || file.length() != book.byteSize) changedSource()
        return file
    }
}

/** The imported byte count bounds the actual stream, including a file that grows after stat.
 * Read at most one extra byte to establish overflow; EOF must match the complete original.
 * Caller owns closing the stream. No prefix or empty digest is valid source evidence.
 */
internal suspend fun verifiedSourceHash(input: InputStream, expectedBytes: Long): String {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (expectedBytes <= 0) changedSource()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var consumed = 0L
    while (true) {
        caller.ensureActive()
        val remaining = expectedBytes - consumed
        val requested = remaining.coerceAtMost(buffer.size.toLong() - 1).toInt() + 1
        val count = input.read(buffer, 0, requested)
        caller.ensureActive()
        if (count == -1) break
        if (count <= 0 || count > remaining) changedSource()
        consumed += count
        digest.update(buffer, 0, count)
    }
    caller.ensureActive()
    if (consumed != expectedBytes) changedSource()
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun changedSource(): Nothing = throw ReaderOpenException(ReaderFailure.CHANGED)
