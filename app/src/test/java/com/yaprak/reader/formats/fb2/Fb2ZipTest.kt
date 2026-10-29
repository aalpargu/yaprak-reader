package com.yaprak.reader.formats.fb2

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Fb2ZipTest {
    @Test fun standardStoredAndDeflatedDataRetainEveryOriginalXmlByte() = runBlocking {
        val xml = byteArrayOf(-1, -2) + ("<?xml version='1.0' encoding='UTF-16'?>" + fb2ZipXml().toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_16LE)
        for (stored in listOf(false, true)) assertArrayEquals(xml, Fb2Zip.xml(fb2Zip(xml, stored = stored)))
    }

    @Test fun anUnsignedDataDescriptorIsAcceptedWithoutGuessingTheCompressedBoundary() = runBlocking {
        val bytes = fb2Zip()
        val central = bytes.central()
        val descriptor = central - 16
        assertEquals(0x08074b50L, bytes.longAt(descriptor))
        val unsigned = bytes.copyOfRange(0, descriptor) + bytes.copyOfRange(descriptor + 4, bytes.size)
        unsigned.putLong(unsigned.size - 22 + 16, (central - 4).toLong())
        assertArrayEquals(fb2ZipXml(), Fb2Zip.xml(unsigned))
    }

    @Test fun localAndCentralNamesFlagsMethodsSizesOffsetsDescriptorsAndCrcMustAgree() = runBlocking {
        val bytes = fb2Zip()
        val central = bytes.central()
        val changes: List<(ByteArray) -> Unit> = listOf(
            { it[30] = 'X'.code.toByte() },
            { it.putShort(6, 0) },
            { it.putShort(8, 0) },
            { it.putShort(26, 0) },
            { it.putLong(14, 123L) },
            { it.putLong(central + 42, 1L) },
            { it.putLong(central - 12, 123L) },
            { it.putLong(central - 8, 123L) },
            { it.putLong(central - 4, 123L) },
            { it.putLong(it.size - 22 + 12, 1L) },
        )
        for (change in changes) refused { Fb2Zip.xml(bytes.copyOf().also(change)) }
        val stored = fb2Zip(stored = true)
        stored[30 + stored.shortAt(26) + stored.shortAt(28)] = '<'.code.toByte().inc()
        refused { Fb2Zip.xml(stored) }
    }

    @Test fun encryptionOtherCompressionZip64AndAlternateUnicodeExtraFieldsAreUnsupported() = runBlocking {
        for (change in listOf<(ByteArray) -> Unit>(
            { it.putShort(6, it.shortAt(6) or 1); it.putShort(it.central() + 8, it.shortAt(6)) },
            { it.putShort(8, 99); it.putShort(it.central() + 10, 99) },
            { it.putShort(4, 45); it.putShort(it.central() + 6, 45) },
        )) refused(Fb2Failure.UNSUPPORTED) { Fb2Zip.xml(fb2Zip().also(change)) }
        for (kind in listOf(0x9901, 0x7075, 0x6375)) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry("book.fb2").apply { extra = byteArrayOf(kind.toByte(), (kind ushr 8).toByte(), 0, 0) }
                zip.putNextEntry(entry); zip.write(fb2ZipXml()); zip.closeEntry()
            }
            refused(Fb2Failure.UNSUPPORTED) { Fb2Zip.xml(output.toByteArray()) }
        }
    }

    @Test fun multipleBooksOtherFilesAndNestedArchivesNeverSelectASuccessfulFirstBook() = runBlocking {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (name in listOf("first.fb2", "second.fb2")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(fb2ZipXml()); zip.closeEntry()
            }
        }
        refused(Fb2Failure.UNSUPPORTED) { Fb2Zip.xml(output.toByteArray()) }
        for (name in listOf("book.txt", "book.fb2.zip", "image.png"))
            refused(Fb2Failure.UNSUPPORTED) { Fb2Zip.xml(fb2Zip(name = name)) }
    }

    @Test fun unsafeNamesDirectoryEntriesAndSymlinksAreRefusedWithoutExtractingPaths() = runBlocking {
        for (name in listOf("../book.fb2", "/book.fb2", "a//book.fb2", "a/./book.fb2", "a\\book.fb2", "a:book.fb2", "a%2fbook.fb2", "book.fb2/"))
            refused { Fb2Zip.xml(fb2Zip(name = name)) }
        val symlink = fb2Zip().also { it.putLong(it.central() + 38, 0xa000L shl 16) }
        refused { Fb2Zip.xml(symlink) }
    }

    @Test fun malformedUtf8NamesAreRejectedAndNonUtf8NamesRequireAscii() = runBlocking {
        val bad = fb2Zip().also { it[30] = 0x80.toByte(); it[it.central() + 46] = 0x80.toByte() }
        refused { Fb2Zip.xml(bad) }
        val legacy = fb2Zip().also {
            it.putShort(6, it.shortAt(6) and 0x800.inv()); it.putShort(it.central() + 8, it.shortAt(6))
        }
        refused(Fb2Failure.UNSUPPORTED) { Fb2Zip.xml(legacy) }
        val ascii = fb2Zip(name = "book.fb2").also {
            it.putShort(6, it.shortAt(6) and 0x800.inv()); it.putShort(it.central() + 8, it.shortAt(6))
        }
        assertArrayEquals(fb2ZipXml(), Fb2Zip.xml(ascii))
    }

    @Test fun compressedAndExpandedBudgetsAlsoRejectLyingSmallSizeMetadata() = runBlocking {
        refused(Fb2Failure.LIMIT) { Fb2Zip.xml(ByteArray(MAX_FB2_BYTES + 1)) }
        val declared = fb2Zip().also { it.putLong(it.central() + 24, MAX_FB2_BYTES + 1L) }
        refused(Fb2Failure.LIMIT) { Fb2Zip.xml(declared) }
        val small = fb2Zip().also {
            val central = it.central(); it.putLong(central + 24, 1); it.putLong(central - 4, 1)
        }
        refused(Fb2Failure.LIMIT) { Fb2Zip.xml(small) }
    }

    @Test fun cancellationDuringInflationPropagatesInsteadOfReturningPartialXml() = runBlocking {
        var checks = 0
        val cancelled = CancellationException("owned cancellation")
        val failure = runCatching {
            Fb2Zip.xml(fb2Zip(ByteArray(160_000) { 'x'.code.toByte() })) { if (++checks == 4) throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, failure); assertEquals(4, checks)
    }

    @Test fun truncatedArchivesAndTrailingDataCannotBeAcceptedAsACompleteBook() = runBlocking {
        val bytes = fb2Zip()
        for (length in listOf(0, 1, 3, 30, bytes.size / 2, bytes.size - 1)) refused { Fb2Zip.xml(bytes.copyOf(length)) }
        refused { Fb2Zip.xml(bytes + "trailing".toByteArray()) }
    }

    private suspend fun refused(reason: Fb2Failure = Fb2Failure.INVALID, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $reason, got ${failure?.javaClass?.simpleName}", failure is Fb2Exception)
        assertEquals(reason, (failure as Fb2Exception).reason)
    }

    private fun ByteArray.central() = longAt(size - 22 + 16).toInt()
    private fun ByteArray.shortAt(at: Int) = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
    private fun ByteArray.longAt(at: Int) = (0..3).sumOf { (this[at + it].toLong() and 255) shl (it * 8) }
    private fun ByteArray.putShort(at: Int, value: Int) { this[at] = value.toByte(); this[at + 1] = (value ushr 8).toByte() }
    private fun ByteArray.putLong(at: Int, value: Long) { (0..3).forEach { this[at + it] = (value ushr (it * 8)).toByte() } }
}
