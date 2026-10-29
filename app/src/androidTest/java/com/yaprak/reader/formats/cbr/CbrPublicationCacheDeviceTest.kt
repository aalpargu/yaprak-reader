package com.yaprak.reader.formats.cbr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.formats.cbz.CbzArchive
import com.yaprak.reader.formats.cbz.CbzException
import com.yaprak.reader.formats.cbz.CbzImages
import com.yaprak.reader.formats.cbz.cbzHash
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Junrar, strict CBZ admission and native BitmapFactory. Only owned temporary fixtures. */
@RunWith(AndroidJUnit4::class)
class CbrPublicationCacheDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun resource(name: String) = instrumentation.context.assets.open("cbr183/self-fixtures/$name").use { it.readBytes() }
    private suspend fun owned(block: suspend (File, CbrPublicationCache) -> Unit) = withContext(Dispatchers.IO) {
        val root = File(instrumentation.targetContext.cacheDir, "cbr-cache-device-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { block(root, CbrPublicationCache(root)) }
        finally { check(root.canonicalFile.parentFile == instrumentation.targetContext.cacheDir.canonicalFile); root.deleteRecursively() }
    }
    private fun source(root: File, name: String) = File(root, name).apply { writeBytes(resource(name)) }

    @Test fun bothRarFamiliesAdmitEveryNativeRasterAndPreserveOriginalOrderIdentity() = runBlocking {
        owned { root, cache ->
            for (name in listOf("stored-rar4.cbr", "stored-rar5.cbr", "shuffled-rar5.cbr")) {
                val original = source(root, name); val before = original.readBytes()
                cache.acquire(original).use { lease ->
                    assertEquals(cbzHash(before), lease.sourceHash)
                    assertEquals(listOf("pages/01.png", "pages/10.png"), lease.archive.pages.map { it.entry.name })
                    lease.retainFile(lease.sourceHash).use { pin ->
                        assertNotEquals(original.canonicalFile, pin.file.canonicalFile)
                        ZipFile(pin.file).use { zip -> lease.archive.pages.forEachIndexed { index, page ->
                            val bytes = CbzArchive.readEntry(zip, page.entry)
                            assertEquals(page.sha256, cbzHash(bytes))
                            assertArrayEquals(resource(if (index == 0) "01.png" else "10.png"), bytes)
                            CbzImages.decode(bytes, page.image).let { bitmap ->
                                try {
                                    assertEquals(2, bitmap.width); assertEquals(2, bitmap.height)
                                    assertEquals(if (index == 0) 0xff164682.toInt() else 0xffb43c1e.toInt(), bitmap.getPixel(0, 0))
                                } finally { bitmap.recycle() }
                            }
                        } }
                    }
                }
                assertArrayEquals(before, original.readBytes())
            }
        }
    }

    @Test fun laterBadRasterAndCrcFailureNeverPublishPartialOrTemporaryArchives() = runBlocking {
        owned { root, cache ->
            for (name in listOf("bad-final-raster.cbr", "corrupt-second-payload.cbr")) {
                val file = source(root, name); val before = file.readBytes()
                try { cache.acquire(file).close(); fail("Bad later page cannot be admitted") }
                catch (_: CbrException) { } catch (_: CbzException) { }
                assertTrue(File(root, "cbr-reader-v1").listFiles().isNullOrEmpty())
                assertArrayEquals(before, file.readBytes())
            }
        }
    }

    @Test fun tamperedDerivedArchiveIsRegeneratedAndRetiredPinsOutliveTheirRoot() = runBlocking {
        owned { root, cache ->
            val original = source(root, "stored-rar5.cbr")
            val first = cache.acquire(original)
            val child = first.retainFile(first.sourceHash)
            val old = child.file
            old.writeBytes(byteArrayOf(1, 2, 3))
            val replacement = cache.acquire(original)
            try {
                replacement.retainFile(replacement.sourceHash).use { assertNotEquals(old, it.file) }
                try { first.retainFile(first.sourceHash).close(); fail("Retired generation") }
                catch (_: CbrException) { }
                first.close(); first.close()
                assertTrue("In-flight child remains pinned after its root closes", old.exists())
                child.close(); child.close()
                assertTrue("close never performs disk IO", old.exists())
                cache.acquire(original).close()
                assertFalse("Next IO acquisition reaps the retired file", old.exists())
            } finally { replacement.close(); first.close(); child.close() }
        }
    }

    @Test fun cacheBoundsRejectNinthPinnedPublicationThenEvictOnlyUnpinnedFiles() = runBlocking {
        owned { root, cache ->
            val leases = mutableListOf<CbrPublicationCache.Lease>()
            try {
                for (index in 0..7) leases += cache.acquire(source(root, "cache-$index.cbr"))
                val first = leases.first()
                val child = first.retainFile(first.sourceHash)
                val pinned = child.file
                try {
                    val ninth = source(root, "cache-8.cbr")
                    try { cache.acquire(ninth).close(); fail("Eight live records is the hard cache count") }
                    catch (failure: CbrException) { assertEquals(CbrFailure.LIMIT, failure.reason) }
                    assertEquals(8, File(root, "cbr-reader-v1").listFiles()!!.count { it.extension == "cbz" })
                    assertFalse(File(root, "cbr-reader-v1").listFiles()!!.any { it.extension == "part" })
                    leases.forEach { it.close() }
                    cache.acquire(ninth).close()
                    assertTrue("A child pins the original cache record during LRU eviction", pinned.exists())
                    child.close()
                    cache.acquire(source(root, "cache-9.cbr")).close()
                    assertFalse("An unpinned least-recently-used record can be evicted", pinned.exists())
                } finally { child.close() }
            } finally { leases.forEach { it.close() } }
        }
    }
}
