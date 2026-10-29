package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.yaprak.reader.data.persistence.RoomReadingStateRepository
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzSpreadDocumentTest {
    private suspend fun failure(expected: ReaderFailure, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected source refusal, got $error", error is ReaderOpenException)
        assertEquals(expected, (error as ReaderOpenException).reason)
    }
    @Test fun actualPairDecodesBothOriginalsWithinOneBudgetAndRetainsRightAnchor() = runBlocking {
        CbzSpreadFixture.create(big = true).use { f ->
            assertEquals(listOf("1.png", "2.png", "3.png", "4.png", "5.png"), f.document.archive.pages.map { it.entry.name })
            val pair = f.document.renderFrame(1, listOf(0, 1))
            assertEquals(1, pair.anchor); assertEquals(listOf(0, 1), pair.pages.map { it.index })
            assertEquals(listOf(Color.RED, Color.BLUE), pair.pages.map { it.bitmap.getPixel(it.bitmap.width / 2, it.bitmap.height / 2) })
            assertTrue(pair.pages.sumOf { it.bitmap.width.toLong() * it.bitmap.height } <= CbzImages.MAX_BITMAP_PIXELS)
            assertTrue(pair.pages.all { it.bitmap.allocationByteCount <= CbzImages.MAX_BITMAP_PIXELS * 2 })
            val single = f.document.renderFrame(1, listOf(1))
            assertTrue(single.pages.single().bitmap.width > pair.pages[1].bitmap.width)
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
            assertTrue(f.db.reading().observed(f.book.id, f.book.sha256).isEmpty())
            assertArrayEquals(f.bytes, f.file.readBytes())
            pair.close(); pair.close(); single.close()
            assertTrue(pair.pages.all { it.bitmap.isRecycled })
        }
    }
    @Test fun invalidPairRequestNeverDecodesOrWritesProgress() = runBlocking {
        var calls = 0
        CbzSpreadFixture.create(decode = { bytes, image, limit -> calls++; CbzImages.decode(bytes, image, limit) }).use { f ->
            for ((anchor, indices) in listOf(0 to emptyList(), 0 to listOf(0, 0), 1 to listOf(1, 0),
                0 to listOf(1, 2), 0 to listOf(0, 2), 0 to listOf(0, 1, 2), -1 to listOf(-1), 5 to listOf(5))) {
                failure(ReaderFailure.INVALID) { f.document.renderFrame(anchor, indices) }
            }
            assertEquals(0, calls); assertArrayEquals(f.bytes, f.file.readBytes())
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
        }
    }
    @Test fun changedDeletedRecoveryAndClosedOriginalRefusePairThenExactBytesCanRetry() = runBlocking {
        CbzSpreadFixture.create().use { f ->
            f.file.appendBytes(byteArrayOf(0))
            failure(ReaderFailure.CHANGED) { f.document.renderFrame(0, listOf(0, 1)) }
            f.file.writeBytes(f.bytes); assertTrue(f.file.delete())
            failure(ReaderFailure.MISSING) { f.document.renderFrame(0, listOf(0, 1)) }
            f.file.writeBytes(f.bytes); f.allowed = false
            failure(ReaderFailure.RECOVERY) { f.document.renderFrame(0, listOf(0, 1)) }
            f.allowed = true; f.document.renderFrame(0, listOf(0, 1)).close()
            f.document.close(); failure(ReaderFailure.MISSING) { f.document.renderFrame(0, listOf(0, 1)) }
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
        }
    }
    @Test fun changedOriginalAfterSecondNativeDecodeDiscardsBothOwnedImagesAndRetries() = runBlocking {
        val allocated = mutableListOf<Bitmap>(); var change = true
        lateinit var fixture: CbzSpreadFixture
        fixture = CbzSpreadFixture.create(decode = { bytes, image, limit ->
            CbzImages.decode(bytes, image, limit).also { bitmap ->
                allocated.add(bitmap)
                if (allocated.size == 2 && change) fixture.file.appendBytes(byteArrayOf(0))
            }
        })
        fixture.use { f ->
            failure(ReaderFailure.CHANGED) { f.document.renderFrame(1, listOf(0, 1)) }
            assertEquals(2, allocated.size); assertTrue(allocated.all { it.isRecycled })
            change = false; f.file.writeBytes(f.bytes)
            f.document.renderFrame(1, listOf(0, 1)).use { assertEquals(1, it.anchor) }
            assertTrue(allocated.all { it.isRecycled }); assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }
    @Test fun recoveryOrCurrentBookMutationDuringDecodeCannotHandOffAPair() = runBlocking {
        val allocated = mutableListOf<Bitmap>(); var corruptBook = false; var revoke = true
        lateinit var fixture: CbzSpreadFixture
        fixture = CbzSpreadFixture.create(decode = { bytes, image, limit ->
            CbzImages.decode(bytes, image, limit).also { bitmap ->
                allocated.add(bitmap)
                if (revoke) fixture.allowed = false
                if (corruptBook) fixture.db.openHelper.writableDatabase.execSQL("UPDATE books SET pageCount=99 WHERE id=?", arrayOf(fixture.book.id))
            }
        })
        fixture.use { f ->
            failure(ReaderFailure.RECOVERY) { f.document.renderFrame(0, listOf(0, 1)) }
            assertTrue(allocated.all { it.isRecycled }); f.allowed = true; revoke = false; corruptBook = true
            failure(ReaderFailure.CHANGED) { f.document.renderFrame(0, listOf(0, 1)) }
            assertTrue(allocated.all { it.isRecycled })
            withContext(Dispatchers.IO) { f.db.openHelper.writableDatabase.execSQL("UPDATE books SET pageCount=5 WHERE id=?", arrayOf(f.book.id)) }
            corruptBook = false; f.document.renderFrame(0, listOf(0, 1)).close()
            assertTrue(allocated.all { it.isRecycled }); assertArrayEquals(f.bytes, f.file.readBytes())
        }
    }
    @Test fun cancelledLateNativeDecodeRecyclesAllPairMembersWithoutHandoff() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val allocated = mutableListOf<Bitmap>(); var hold = true
        CbzSpreadFixture.create(decode = { bytes, image, limit ->
            val bitmap = CbzImages.decode(bytes, image, limit); allocated.add(bitmap)
            if (allocated.size == 2 && hold) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            bitmap
        }).use { f ->
            var handedOff = false
            val request = launch { f.document.renderFrame(1, listOf(0, 1)).use { handedOff = true } }
            try { withTimeout(10_000) { entered.await() }; request.cancel() }
            finally { release.complete(Unit) }
            request.join()
            assertFalse(handedOff); assertEquals(2, allocated.size); assertTrue(allocated.all { it.isRecycled })
            assertNull(RoomReadingStateRepository(f.db).resume(f.book.id, f.book.sha256))
            hold = false; f.document.renderFrame(1, listOf(0, 1)).close(); assertTrue(allocated.all { it.isRecycled })
        }
    }
}
