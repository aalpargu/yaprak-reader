package com.yaprak.reader.reader

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.formats.cbr.CbrMetadataReader
import com.yaprak.reader.formats.cbr.CbrPublicationCache
import com.yaprak.reader.formats.cbz.CbzReaderFragment
import com.yaprak.reader.formats.cbz.CbzMetadataReader
import com.yaprak.reader.formats.cbz.cbzHash
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production format router, page painting, contents, thumbnail UI and persistent resume. */
@RunWith(AndroidJUnit4::class)
class CbrReaderDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun originalCbrPaintsNavigatesBookmarksThumbnailsAndResumesAfterRecreateAndReopen() = runBlocking { runReader(BookFormat.CBR) }
    @Test fun existingCbzStillPaintsNavigatesBookmarksThumbnailsAndResumes() = runBlocking { runReader(BookFormat.CBZ) }
    private suspend fun runReader(format: BookFormat) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val id = UUID.randomUUID().toString()
        val rar = instrumentation.context.assets.open("cbr183/self-fixtures/shuffled-rar5.cbr").use { it.readBytes() }
        val bytes = if (format == BookFormat.CBR) rar else withContext(Dispatchers.IO) {
            val source = File(context.cacheDir, "cbr-to-cbz-owned-$id.cbr")
            try {
                source.writeBytes(rar)
                CbrPublicationCache(context.cacheDir).acquire(source).use { lease ->
                    lease.retainFile(lease.sourceHash).use { it.file.readBytes() }
                }
            } finally { source.delete() }
        }
        val file = File(context.filesDir, "books/$id.${format.extension}")
        val metadata = withContext(Dispatchers.IO) {
            file.parentFile!!.mkdirs(); file.writeBytes(bytes)
            if (format == BookFormat.CBR) CbrMetadataReader(CbrPublicationCache(context.cacheDir)).read(file)
            else CbzMetadataReader().read(file)
        }
        val hash = cbzHash(bytes)
        val book = Book(id, "${format.name} reader device", null, format, "owned.${format.extension}", file.name,
            bytes.size.toLong(), hash, System.currentTimeMillis(), metadata.pageCount)
        var added = false
        try {
            container.library.add(book); added = true
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await { page(scenario) == 0 }
                capture("${format.extension}-first")
                compose.onNodeWithText("İçindekiler").performScrollTo().performClick()
                await { compose.onAllNodesWithText("Sayfa 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Sayfa 2").performScrollTo().performClick()
                await { page(scenario) == 1 }
                modalClosed()
                await {
                    val resume = container.reading.resume(id, hash)
                    resume?.resourceHref == "${format.extension}/pages/10.png" && JSONObject(resume.locatorJson).getInt("pageIndex") == 1
                }
                compose.onNodeWithText("Yer imleri").performScrollTo().performClick()
                compose.onNodeWithText("Bu konumu kaydet").performClick()
                await { container.bookmarks.observe(id, hash).first().size == 1 }
                compose.onNodeWithText("Kapat").performClick()
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                compose.onNodeWithText("Sayfa küçük resimleri").performScrollTo().performClick()
                await { compose.onAllNodesWithText("Sayfa 1").fetchSemanticsNodes().isNotEmpty() }
                await {
                    compose.onAllNodesWithTag("pdf-thumbnail-image-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithTag("pdf-thumbnail-image-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                capture("${format.extension}-thumbnails")
                compose.onNodeWithText("Sayfa 1").performScrollTo().performClick()
                await { page(scenario) == 0 }
                modalClosed()
                compose.onNodeWithText("Yer imleri").performScrollTo().performClick()
                await { compose.onAllNodesWithText("Sayfa 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Sayfa 2").performScrollTo().performClick()
                await { page(scenario) == 1 }
                modalClosed()
                compose.onNodeWithText("Gizle").performClick()
                capture("${format.extension}-second")
                scenario.recreate()
                await { page(scenario) == 1 }
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                await { page(scenario) == 1 }
                assertEquals(1, container.bookmarks.observe(id, hash).first().size)
                assertArrayEquals(bytes, withContext(Dispatchers.IO) { file.readBytes() })
            }
        } finally {
            if (added) {
                var removal: BookRemovalResult = BookRemovalResult.InUse
                await { removal = container.remover.remove(id, hash); removal != BookRemovalResult.InUse }
                assertTrue(removal is BookRemovalResult.Removed || removal == BookRemovalResult.Missing)
            }
            withContext(Dispatchers.IO) {
                check(file.parentFile!!.canonicalFile == File(context.filesDir, "books").canonicalFile)
                if (file.exists()) check(file.delete())
            }
        }
    }
    private fun page(scenario: ActivityScenario<ReaderActivity>): Int? {
        var value: Int? = null
        scenario.onActivity { value = it.supportFragmentManager.fragments.filterIsInstance<CbzReaderFragment>().singleOrNull()?.stablePage }
        return value
    }
    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(20_000) { while (true) { compose.waitForIdle(); if (condition()) break; delay(50) } }
    }
    private fun modalClosed() {
        compose.waitForIdle()
        compose.onNodeWithText("Kapat").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "yaprak-cbr-0.183")
                .apply { check(isDirectory || mkdir()) }
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
