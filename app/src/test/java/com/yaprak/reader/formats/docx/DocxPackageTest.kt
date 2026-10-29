package com.yaprak.reader.formats.docx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual JDK ZIP containers; package coherence only, not WordprocessingML/reader acceptance. */
class DocxPackageTest {
    private val basics = listOf("[Content_Types].xml" to "<Types/>".toByteArray(),
        "_rels/.rels" to "<Relationships/>".toByteArray(), "word/document.xml" to "<document>İ 🌿 雪</document>".toByteArray())
    private fun zip(items: List<Pair<String, ByteArray>> = basics, stored: Boolean = false, comment: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes, Charsets.UTF_8).use { zip ->
            comment?.let(zip::setComment)
            for ((name, content) in items) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED; entry.size = content.size.toLong()
                    entry.compressedSize = content.size.toLong(); entry.crc = CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
    private fun int(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
    private fun short(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt() and 65535
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) }
    private fun putShort(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort()) }
    private fun central(bytes: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var offset = int(bytes, bytes.size - 22 + 16)
        repeat(short(bytes, bytes.size - 22 + 10)) {
            result += offset; offset += 46 + short(bytes, offset + 28) + short(bytes, offset + 30) + short(bytes, offset + 32)
        }
        return result
    }
    private suspend fun failure(bytes: ByteArray): DocxFailure {
        val problem = runCatching { DocxPackages.decode(bytes) }.exceptionOrNull()
        assertTrue("Expected an archive rejection", problem is DocxException)
        return (problem as DocxException).reason
    }

    @Test fun asciiCaseAliasesAndTrailingDotSegmentsCannotCreateAmbiguousParts() = runBlocking {
        assertEquals(DocxFailure.INVALID, failure(zip(basics + ("WORD/Document.xml" to byteArrayOf(1)))))
        for (name in listOf("word./document.xml", "word/document.xml.", "word/%2e./x.xml"))
            assertEquals(DocxFailure.INVALID, failure(zip(basics + (name to byteArrayOf(1)))))
    }

    @Test fun storedAndDeflatedCompleteItemsKeepTheirExactBytesWithoutExtractingDirectories() = runBlocking {
        val items = basics + listOf("word/media/" to byteArrayOf(), "word/media/雪.png" to byteArrayOf(1, 2, 3))
        for (stored in listOf(false, true)) {
            val archive = DocxPackages.decode(zip(items, stored))
            assertEquals(4, archive.parts.size)
            for ((name, bytes) in items.filter { !it.first.endsWith('/') }) assertArrayEquals(bytes, archive.required(name))
            assertFalse(archive.parts.containsKey("word/media/"))
        }
        val archive = DocxPackages.decode(zip(comment = "Owned archive comment"))
        assertArrayEquals(basics.last().second, archive.required("word/document.xml"))
    }

    @Test fun directoryOrderingDoesNotHaveToMatchLocalOrdering() = runBlocking {
        val bytes = zip(stored = true)
        val starts = central(bytes)
        val end = bytes.size - 22
        val chunks = starts.mapIndexed { index, start -> bytes.copyOfRange(start, starts.getOrNull(index + 1) ?: end) }
        var destination = starts.first()
        for (chunk in chunks.reversed()) { chunk.copyInto(bytes, destination); destination += chunk.size }
        assertArrayEquals(basics.last().second, DocxPackages.decode(bytes).required("word/document.xml"))
    }

    @Test fun actualCrcAndLocalCentralNameSizeMethodAndOffsetDisagreementsRejectTheWholePackage() = runBlocking {
        for (change in 0..5) {
            val bytes = zip(stored = true)
            val record = central(bytes).last()
            val local = int(bytes, record + 42)
            when (change) {
                0 -> bytes[local + 30 + short(bytes, local + 26)] = 1 // Real payload CRC mismatch.
                1 -> bytes[local + 30] = 'X'.code.toByte()
                2 -> putInt(bytes, local + 22, short(bytes, local + 22) + 1)
                3 -> putShort(bytes, local + 8, 8)
                4 -> putInt(bytes, record + 42, local + 1)
                5 -> putShort(bytes, local + 4, short(bytes, local + 4) + 1)
            }
            assertEquals(DocxFailure.INVALID, failure(bytes))
        }
    }

    @Test fun descriptorCrcSizeAndTrailingBytesAreAuthoritative() = runBlocking {
        val bytes = zip()
        val record = central(bytes).last()
        val local = int(bytes, record + 42)
        val descriptor = local + 30 + short(bytes, local + 26) + short(bytes, local + 28) + int(bytes, record + 20)
        assertEquals(0x08074b50, int(bytes, descriptor))
        putInt(bytes, descriptor + 4, int(bytes, descriptor + 4) xor 1)
        assertEquals(DocxFailure.INVALID, failure(bytes))
        assertEquals(DocxFailure.INVALID, failure(zip() + byteArrayOf(0)))
        assertEquals(DocxFailure.INVALID, failure(byteArrayOf(0) + zip()))
    }

    @Test fun traversalSeparatorsMalformedPercentUnicodeAndAliasesNeverBecomePartIdentities() = runBlocking {
        for (path in listOf("../escape", "/word/absolute.xml", "word\\escape.xml", "word//empty.xml",
            "word/./alias.xml", "word/%2e%2e/escape", "word/%2fescape.xml", "word/%5cescape.xml",
            "word/%FF.xml", "word/bad\u0000.xml", "word/name?query.xml", "word/name#fragment.xml",
            "%5BContent_Types%5D.xml", "C:/file.xml")) {
            assertEquals(path, DocxFailure.INVALID, failure(zip(basics + (path to byteArrayOf(1)))))
        }
        assertEquals(DocxFailure.INVALID, failure(zip(basics + ("word/%64ocument.xml" to byteArrayOf(2)))))
        val valid = DocxPackages.decode(zip(basics + ("word/media/my%20image.png" to byteArrayOf(3))))
        assertArrayEquals(byteArrayOf(3), valid.required("word/media/my image.png"))
    }

    @Test fun encryptedUnsupportedCompressionMultidiskAndZip64AreRejectedBeforeInflation() = runBlocking {
        for (change in 0..3) {
            val bytes = zip()
            val record = central(bytes).first()
            when (change) {
                0 -> putShort(bytes, record + 8, short(bytes, record + 8) or 1)
                1 -> putShort(bytes, record + 10, 99)
                2 -> putShort(bytes, bytes.size - 22 + 4, 1)
                3 -> putShort(bytes, bytes.size - 22 + 8, 65535).also { putShort(bytes, bytes.size - 22 + 10, 65535) }
            }
            assertEquals(if (change == 2) DocxFailure.INVALID else DocxFailure.UNSUPPORTED, failure(bytes))
        }
    }

    @Test fun declaredEntryAndTotalBudgetsAndActualSourceSizeCannotProduceAPartialPackage() = runBlocking {
        val perEntry = zip(basics + ("word/large.bin" to ByteArray(MAX_DOCX_ENTRY_BYTES + 1)))
        assertEquals(DocxFailure.LIMIT, failure(perEntry))
        val total = zip(basics + (1..5).map { "word/image$it.bin" to ByteArray(7 * 1024 * 1024) })
        assertEquals(DocxFailure.LIMIT, failure(total))
        assertEquals(DocxFailure.LIMIT, failure(ByteArray(MAX_DOCX_PACKAGE_BYTES + 1)))
        val many = zip(basics + (1..MAX_DOCX_ENTRIES).map { "word/part$it.xml" to byteArrayOf() })
        assertEquals(DocxFailure.LIMIT, failure(many))
    }

    @Test fun missingPackageRootsAndMalformedOrTruncatedContainersDoNotMasqueradeAsWordDocuments() = runBlocking {
        assertEquals(DocxFailure.INVALID, failure(zip(basics.drop(1))))
        assertEquals(DocxFailure.INVALID, failure(zip(basics.dropLast(2))))
        for (bytes in listOf(byteArrayOf(), "<document/>".toByteArray(), zip().copyOf(20)))
            assertEquals(DocxFailure.INVALID, failure(bytes))
    }

    @Test fun alreadyCancelledDecodePreservesCancellationBeforeBadInputChecks() = runBlocking {
        var actual: Throwable? = null
        val child = launch {
            coroutineContext.cancel()
            try { DocxPackages.decode(byteArrayOf()) } catch (problem: Throwable) { actual = problem }
        }
        child.join()
        assertTrue(actual is CancellationException)
    }
}
