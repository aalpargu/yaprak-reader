package com.yaprak.reader.formats.txt

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.formats.epub.EpubContents
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.reader.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TxtIndexedSourceTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private class Fixture(val book: Book, val file: File, val artifact: File, val parts: List<TxtPart>,
        val library: RoomLibraryRepository, val database: LibraryDatabase, val artifactHash: String) {
        val mutex = Mutex()
        val target get() = LibraryTextTarget(book.id, book.sha256, parts.last().href, parts.last().sha256, sourceFormat = BookFormat.TXT)
        fun source(validateRead: suspend () -> Unit = {}) = TxtIndexedSource(book, library, VerifiedBookFile(requireNotNull(file.parentFile)),
            artifact, artifactHash, parts, mutex, validateRead)
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = temp.newFolder(); val file = File(directory, "original.txt")
        val bytes = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + ("first ".repeat(11_000) + "final needle 🌿").toByteArray(Charsets.UTF_16BE)
        file.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val book = Book(UUID.randomUUID().toString(), "Original TXT", null, BookFormat.TXT, file.name, file.name, file.length(), hash, 1)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = RoomLibraryRepository(db.books())
        try {
            library.add(book)
            val parts = StrictTxt.read(file); val artifact = temp.newFile(); TxtEpub.write(artifact, hash, parts)
            Fixture(book, file, artifact, parts, library, db, txtViewHash(artifact)).block()
        } finally { db.close() }
    }
    @Test fun finalOriginalPartProofMatchesActualPinnedReadiumContentsWithDistinctOriginalAndViewHashes() = runBlocking {
        fixture {
            val proof = source(); assertTrue(proof.verify(target)); assertNotEquals(book.sha256, target.resourceSha256)
            assertNotEquals(artifactHash, target.resourceSha256)
            EpubPublicationSource(context.contentResolver).open(artifact).use { opened ->
                val native = EpubContents(opened.publication.tableOfContents, opened.publication.readingOrder)
                val entry = native.contents.entries.single { it.id == parts.last().index.toString() }
                assertTrue(entry.navigable); assertEquals(parts.last().title, entry.title)
                assertEquals(target.resourceHref, native.destination(entry.id)!!.url().toString())
            }
            assertNull(database.reading().resume(book.id, book.sha256)); assertTrue(database.reading().observed(book.id, book.sha256).isEmpty())
        }
    }
    @Test fun sameLengthOriginalTamperMissingOriginalAndChangedStoredIdentityCannotVerifyCachedPart() = runBlocking {
        fixture {
            val proof = source(); val original = file.readBytes()
            file.writeBytes(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertFalse(proof.verify(target)); file.writeBytes(original); assertTrue(proof.verify(target))
            assertTrue(file.delete()); assertFalse(proof.verify(target)); file.writeBytes(original)
            for (update in listOf("sha256='${"c".repeat(64)}'", "format='EPUB'", "storageName='other.txt'", "byteSize=1")) {
                database.openHelper.writableDatabase.execSQL("UPDATE books SET $update WHERE id=?", arrayOf(book.id))
                assertFalse(proof.verify(target))
                database.openHelper.writableDatabase.execSQL("UPDATE books SET sha256=?, format='TXT', storageName=?, byteSize=? WHERE id=?",
                    arrayOf<Any>(book.sha256, book.storageName, book.byteSize, book.id))
            }
            assertTrue(proof.verify(target))
        }
    }
    @Test fun sameLengthGeneratedViewTamperAndMissingViewFailWithoutReinterpretingOriginalHash() = runBlocking {
        fixture {
            val proof = source(); val original = artifact.readBytes()
            artifact.writeBytes(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertFalse(proof.verify(target)); artifact.writeBytes(original); assertTrue(proof.verify(target))
            assertTrue(artifact.delete()); assertFalse(proof.verify(target))
        }
    }
    @Test fun forgedPartHashAddressFormatPageAndBookRefuseBeforeReadAdmissionWhileMetadataEditsRemainAllowed() = runBlocking {
        fixture {
            var admissions = 0; val proof = source { admissions++ }
            for (bad in listOf(target.copy(resourceSha256 = "e".repeat(64)), target.copy(resourceHref = "Text/part0065.xhtml"),
                target.copy(resourceHref = "Text/part0001.xhtml"), target.copy(sourceFormat = BookFormat.EPUB),
                target.copy(pdfPageIndex = 0), target.copy(bookId = "other"), target.copy(contentSha256 = "f".repeat(64)))) {
                assertFalse(proof.verify(bad))
            }
            assertEquals(0, admissions)
            assertTrue(library.updateMetadata(book.id, book.sha256, "Edited title", "Writer"))
            assertTrue(proof.verify(target)); assertEquals(1, admissions)
        }
    }
    @Test fun sharedMutexRecoveryAdmissionAndCallerCancellationControlTheRealSourceRead() = runBlocking {
        fixture {
            mutex.lock(); var admissions = 0
            val waiting = async { source { admissions++ }.verify(target) }
            yield(); waiting.cancelAndJoin(); mutex.unlock(); assertEquals(0, admissions)
            assertFalse(source { error("recovery pending") }.verify(target))
            val entered = CompletableDeferred<Unit>()
            val canceled = async { source { entered.complete(Unit); awaitCancellation() }.verify(target) }
            entered.await(); canceled.cancelAndJoin(); assertTrue(canceled.isCancelled)
            assertTrue(source().verify(target)); assertFalse(mutex.isLocked)
        }
    }
    @Test fun actualFragmentPauseResumeSurfaceReplacementAndCloseInvalidateOriginalPartNavigationProof() = runBlocking {
        fixture {
            val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val calls = mutableListOf<String>(); var releases = 0
            val delegate = object : ReaderSession {
                override val book = this@fixture.book
                override val resume: ReadingLocation? = null
                override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) = object : ReaderSurface {
                    override val fragment = Fragment().also { host.get().supportFragmentManager.beginTransaction().add(it, UUID.randomUUID().toString()).commitNow() }
                    override val contentsNavigator = object : ContentsNavigator {
                        override val contents = BookContents(parts.map { ContentsEntry(it.index.toString(), it.title, 0, true) })
                        override fun goTo(entryId: String): Boolean { calls += entryId; return true }
                    }
                    override fun forward() = false; override fun backward() = false
                    override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) = Unit
                }
                override fun close() = Unit
            }
            val wrapped = TxtReaderSession(delegate, source()) { releases++ }
            try {
                val first = wrapped.createSurface(null, false, {}, {}).indexedChapterNavigator!!
                assertEquals(target.resourceHref, first.verifiedChapterHref(target))
                host.pause().resume()
                assertFalse(first.goToChapter(target.resourceHref)); assertTrue(calls.isEmpty())
                assertEquals(target.resourceHref, first.verifiedChapterHref(target))
                val second = wrapped.createSurface(null, false, {}, {}).indexedChapterNavigator!!
                assertFalse(first.goToChapter(target.resourceHref))
                assertEquals(target.resourceHref, second.verifiedChapterHref(target)); assertTrue(second.goToChapter(target.resourceHref))
                assertEquals(listOf(parts.last().index.toString()), calls)
                assertEquals(target.resourceHref, second.verifiedChapterHref(target)); wrapped.close()
                assertFalse(second.goToChapter(target.resourceHref)); assertEquals(1, calls.size); assertEquals(1, releases)
            } finally { wrapped.close(); host.pause().stop().destroy() }
        }
    }
}
