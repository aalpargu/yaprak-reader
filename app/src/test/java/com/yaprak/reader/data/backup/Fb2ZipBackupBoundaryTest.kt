package com.yaprak.reader.data.backup

import android.app.Application
import com.yaprak.reader.domain.backup.BackupFailure
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.formats.fb2.fb2Zip
import com.yaprak.reader.formats.fb2.fb2ZipHash
import com.yaprak.reader.formats.fb2.fb2ZipXml
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Fb2ZipBackupBoundaryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun compressedOriginalRequiresHeaderTwentyThreeWhilePlainXmlRemainsValidInTwentyTwo() = runBlocking {
        val compressed = temp.newFile().apply { writeBytes(fb2Zip()) }
        val plain = temp.newFile().apply { writeBytes(fb2ZipXml()) }
        assertTrue(validBackupBookSignature(compressed, "FB2", "book.fb2.zip", 23))
        assertTrue(validBackupBookSignature(plain, "FB2", "book.fb2", 23))
        assertTrue(validBackupBookSignature(plain, "FB2", "book.fb2", 22))
        for (version in 1..22) assertFalse("Compressed original in version$version", validBackupBookSignature(compressed, "FB2", "book.fb2.zip", version))
        for (version in 1..21) assertFalse(validBackupBookSignature(plain, "FB2", "book.fb2", version))
        assertTrue(validBackupBookSignature(compressed, "FB2", "book.fb2.zip", 26))
        assertFalse(validBackupBookSignature(compressed, "FB2", "book.fb2.zip", 27))
    }

    @Test fun actualNewManifestRetainsOriginalArchiveIdentityAndCurrentRoomSchema() {
        val bytes = fb2Zip()
        val id = UUID.randomUUID().toString()
        val book = ArchiveBook(id, "$id.fb2", "FB2", bytes.size.toLong(), fb2ZipHash(bytes))
        val records = BackupRecords(listOf(book), EpubSettings(), emptyMap())
        val payload = "{}".toByteArray()
        val encoded = BackupRecordsCodec.encodeManifest(records, payload, fb2ZipHash(payload))
        val json = JSONObject(encoded.toString(Charsets.UTF_8))
        assertEquals(26, json.getInt("formatVersion")); assertEquals(26, json.getInt("databaseVersion"))
        val decoded = BackupRecordsCodec.decodeManifest(encoded)
        assertEquals(listOf(book), decoded.books); assertEquals(26, decoded.formatVersion)
        assertEquals(fb2ZipHash(payload), decoded.recordsHash)
        // The old plain-FB2 manifest remains a supported metadata shape; source admission is separate.
        json.put("formatVersion", 22)
        assertEquals(22, BackupRecordsCodec.decodeManifest(json.toString().toByteArray()).formatVersion)
        json.put("formatVersion", 21)
        val failure = runCatching { BackupRecordsCodec.decodeManifest(json.toString().toByteArray()) }.exceptionOrNull()
        assertEquals(BackupFailure.INVALID_ARCHIVE, (failure as BackupProblem).reason)
    }

    @Test fun matchingZipMagicDoesNotAuthorizeACorruptOrNonXmlSource() = runBlocking {
        val file = temp.newFile().apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4, 9)) }
        assertFalse(validBackupBookSignature(file, "FB2", "book.fb2.zip", 23))
        file.writeBytes(fb2Zip() + "trailing".toByteArray())
        assertFalse(validBackupBookSignature(file, "FB2", "book.fb2.zip", 23))
        file.writeBytes(fb2Zip("not XML".toByteArray()))
        assertFalse(validBackupBookSignature(file, "FB2", "book.fb2.zip", 23))
    }

    @Test fun originalMetadataNameMustAgreeWithTheActualContainerInBothDirections() = runBlocking {
        val compressed = temp.newFile().apply { writeBytes(fb2Zip()) }
        val plain = temp.newFile().apply { writeBytes(fb2ZipXml()) }
        assertFalse(validBackupBookSignature(compressed, "FB2", "book.fb2", 23))
        assertFalse(validBackupBookSignature(plain, "FB2", "book.fb2.zip", 23))
        assertFalse(validBackupBookSignature(plain, "FB2", "book.fb2.zip", 22))
        assertTrue(validBackupBookSignature(compressed, "FB2", "BOOK.FB2.ZIP", 23))
        assertTrue(validBackupBookSignature(plain, "FB2", "BOOK.FB2", 23))
    }
}
