package com.yaprak.reader.formats.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzThumbnailsSourceTest {
    private fun image() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }

    @Test fun cachedPageReprovesSourceAndCannotHideChangedSource() = runTest {
        var verified = 0; var renders = 0; var current = true
        val source = CbzThumbnailsSource(1, { verified++; if (!current) throw IOException("changed") }, { renders++; image() })
        val first = source.render(0)
        assertSame(first, source.render(0)); assertEquals(1, renders); assertEquals(3, verified)
        current = false
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IOException)
        assertEquals(1, renders); assertEquals(4, verified); assertFalse(first.isRecycled)
        current = true; assertSame(first, source.render(0)); source.close(); assertFalse(first.isRecycled)
    }
    @Test fun nativeRenderSourceChangeBeforePublicationRecyclesOnlyFreshImageAndAllowsRetry() = runTest {
        var current = true; val fresh = image(); var renders = 0
        val source = CbzThumbnailsSource(1, { if (!current) throw IOException("recovery") }, {
            renders++; if (renders == 1) { current = false; fresh } else image()
        })
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IOException)
        assertTrue(fresh.isRecycled); current = true
        val next = source.render(0); assertSame(next, source.render(0)); assertEquals(2, renders)
        source.close(); assertFalse(next.isRecycled)
    }
    @Test fun twelveEntryLruAndByteBudgetDropReferencesWithoutRecyclingDisplayedImage() = runTest {
        val renders = mutableListOf<Int>()
        val source = CbzThumbnailsSource(20, {}, { renders += it; image() })
        val displayed = source.render(0); (1..11).forEach { source.render(it) }
        assertSame(displayed, source.render(0)); source.render(12); source.render(1)
        assertEquals((0..12).toList() + 1, renders); assertEquals(Color.GREEN, displayed.getPixel(0, 0))
        source.close(); assertFalse(displayed.isRecycled)
        var calls = 0
        val tiny = CbzThumbnailsSource(1, {}, { calls++; image() }, maxBytes = 1)
        val first = tiny.render(0); val second = tiny.render(0)
        assertNotSame(first, second); assertEquals(2, calls); tiny.close()
        assertFalse(first.isRecycled); assertFalse(second.isRecycled)
    }
    @Test fun serializedRequestsCoalesceAndCancelledQueueNeverReadsOriginal() = runTest {
        val gate = CompletableDeferred<Unit>(); val reads = mutableListOf<Int>()
        val source = CbzThumbnailsSource(2, {}, { reads += it; gate.await(); image() })
        val first = async { source.render(0) }; runCurrent()
        val queued = async { source.render(1) }; runCurrent(); queued.cancelAndJoin()
        val same = async { source.render(0) }; runCurrent()
        assertEquals(listOf(0), reads); gate.complete(Unit); advanceUntilIdle()
        assertSame(first.await(), same.await()); assertEquals(listOf(0), reads); source.close()
    }
    @Test fun closedSourceRejectsLateUnsharedNativeResultWithoutWaitingOnIo() = runTest {
        val gate = CompletableDeferred<Unit>(); val fresh = image()
        val source = CbzThumbnailsSource(1, {}, { gate.await(); fresh })
        val request = async { runCatching { source.render(0) } }; runCurrent(); source.close(); source.close()
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(request.await().exceptionOrNull() is IllegalStateException); assertTrue(fresh.isRecycled)
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun cancellationAfterNoncancellableNativeCompletionCannotPublishOrPopulateCache() = runTest {
        val gate = CompletableDeferred<Unit>(); val fresh = image(); var calls = 0
        val source = CbzThumbnailsSource(1, {}, {
            calls++; if (calls == 1) withContext(NonCancellable) { gate.await(); fresh } else image()
        })
        val request = async { source.render(0) }; runCurrent(); request.cancel()
        gate.complete(Unit); advanceUntilIdle(); assertTrue(request.isCancelled); assertTrue(fresh.isRecycled)
        val retry = source.render(0); assertEquals(2, calls); assertFalse(retry.isRecycled); source.close()
    }
    @Test fun invalidSizeConfigPageOrCacheBoundRefusesWithoutPoisoningRetry() = runTest {
        val invalid = Bitmap.createBitmap(241, 4, Bitmap.Config.ARGB_8888); var renders = 0
        val source = CbzThumbnailsSource(1, {}, { if (++renders == 1) invalid else image() })
        assertTrue(runCatching { source.render(-1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { source.render(1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, renders)
        assertTrue(runCatching { source.render(0) }.exceptionOrNull() is IllegalStateException); assertTrue(invalid.isRecycled)
        assertFalse(source.render(0).isRecycled); assertEquals(2, renders); source.close()
        assertTrue(runCatching { CbzThumbnailsSource(2001, {}, { image() }) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CbzThumbnailsSource(1, {}, { image() }, maxEntries = 13) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CbzThumbnailsSource(1, {}, { image() }, maxBytes = 4L * 1024 * 1024 + 1) }.exceptionOrNull() is IllegalArgumentException)
    }
}
