package com.yaprak.reader.data

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.reader.ReaderOpenException
import com.yaprak.reader.reader.ReaderFailure
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedBookFileTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `same size modifications and outside paths never reuse progress`() = runTest {
        val root = temp.newFolder("books")
        val file = File(root, "book.epub").apply { writeText("original") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val book = Book("id", "Book", null, BookFormat.EPUB, "source.epub", file.name, file.length(), hash, 0)
        val verifier = VerifiedBookFile(root)
        assertEquals(file, verifier.resolve(book))
        file.writeText("modified")
        assertEquals(ReaderFailure.CHANGED, (runCatching { verifier.resolve(book) }.exceptionOrNull() as ReaderOpenException).reason)
        assertEquals(ReaderFailure.MISSING, (runCatching { verifier.resolve(book.copy(storageName = "../outside")) }.exceptionOrNull() as ReaderOpenException).reason)
        file.delete()
        assertEquals(ReaderFailure.MISSING, (runCatching { verifier.resolve(book) }.exceptionOrNull() as ReaderOpenException).reason)
    }
}
