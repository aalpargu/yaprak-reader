package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.formats.pdf.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfResumePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun actualRoomReopenKeepsTheLastWithinPageAnchorAndLegacyPositionWithoutInventingCoverage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val path = File(temporary.root, "anchor.db").absolutePath
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, path).build()
        val originalHash = "a".repeat(64)
        val legacyHash = "b".repeat(64)
        val latest = PdfPosition.encode(PdfResumePosition(2, 645_321))
        var db = open()
        try {
            db.books().insert(BookEntity("pdf", "PDF", null, "PDF", "one.pdf", "one.pdf", 1, originalHash, 1, 20))
            db.books().insert(BookEntity("legacy", "Legacy", null, "PDF", "two.pdf", "two.pdf", 1, legacyHash, 1, 20))
            val repository = RoomReadingStateRepository(db)
            repository.record("legacy", legacyHash, ReadingSnapshot(PdfPosition.encode(10)))
            repository.record("pdf", originalHash, ReadingSnapshot(PdfPosition.encode(PdfResumePosition(2, 100_000))))
            repository.record("pdf", originalHash, ReadingSnapshot(latest))
            assertTrue(runCatching { repository.record("pdf", "c".repeat(64), ReadingSnapshot(PdfPosition.encode(19))) }.isFailure)
            assertTrue(runCatching { repository.record("pdf", originalHash, ReadingSnapshot(latest.copy(locatorVersion = 2))) }.isFailure)
            db.close(); db = open()
            val reopened = RoomReadingStateRepository(db)
            assertEquals(latest, reopened.resume("pdf", originalHash))
            assertEquals(PdfResumePosition(2, 645_321), PdfPosition.decodeResumeOrNull(reopened.resume("pdf", originalHash), 20))
            assertEquals(PdfPosition.encode(10), reopened.resume("legacy", legacyHash))
            assertNull(reopened.resume("pdf", "c".repeat(64)))
            assertTrue(db.reading().observed("pdf", originalHash).isEmpty())
            assertTrue(db.reading().observed("legacy", legacyHash).isEmpty())
        } finally { db.close() }
    }
}
