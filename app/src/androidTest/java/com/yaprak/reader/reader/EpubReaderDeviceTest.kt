@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.reader

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.ReadingFont
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.feature.reader.ReaderActivity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.services.positionsByReadingOrder

/** Real Android WebView tests. Compilation alone is not execution. */
@RunWith(AndroidJUnit4::class)
class EpubReaderDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var paceCatalogueBounds: Map<String, Pair<Double, Double>> = emptyMap()

    @Test fun EPUB_preferences_apply_to_WebView_and_survive_recreation() = runBlocking {
        val context = instrumentation.targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val original = container.settings.settings.first()
        val book = fixture(context)
        container.library.add(book)
        try {
            container.settings.save(EpubSettings())
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE") }
                val sizeScript = "parseFloat(getComputedStyle(document.querySelector('p')).fontSize)"
                val baseline = evaluate(scenario, sizeScript).trim('"').toDouble()
                container.settings.save(EpubSettings(150, ReadingFont.SANS_SERIF, 180, 150, ReadingTheme.SEPIA))
                await { (evaluate(scenario, sizeScript).trim('"').toDoubleOrNull() ?: 0.0) > baseline * 1.2 }
                await { evaluate(scenario, "getComputedStyle(document.documentElement).getPropertyValue('--USER__appearance')").contains("sepia") }
                assertTrue(evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE"))
                scenario.recreate()
                await { (evaluate(scenario, sizeScript).trim('"').toDoubleOrNull() ?: 0.0) > baseline * 1.2 }
                await { evaluate(scenario, "getComputedStyle(document.documentElement).getPropertyValue('--USER__appearance')").contains("sepia") }
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "getComputedStyle(document.documentElement).getPropertyValue('--USER__appearance')").contains("sepia") }
            }
        } finally {
            container.settings.save(original)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19).build()
            try { db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(book.id)) }
            finally { db.close() }
            File(context.filesDir, "books/${book.storageName}").delete()
        }
    }

    @Test fun EPUB_renders_recreates_and_reopens_at_saved_resource() = runBlocking {
        val context = instrumentation.targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val book = fixture(context)
        container.library.add(book)
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE") }
                compose.onNodeWithText("İçindekiler").performClick()
                compose.onNodeWithText("three").performScrollTo().performClick()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                await { runBlocking { container.reading.resume(book.id, book.sha256)?.resourceHref?.endsWith("three.xhtml") == true } }
                val inspection = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                    .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19).build()
                try { assertTrue(inspection.reading().observed(book.id, book.sha256).none { it.resourceHref.endsWith("two.xhtml") }) }
                finally { inspection.close() }
                scenario.recreate()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
            }
        } finally {
            // Delete only this test's UUID record and private fixture, never the user's library.
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19).build()
            try { db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(book.id)) }
            finally { db.close() }
            File(context.filesDir, "books/${book.storageName}").delete()
        }
    }

    @Test fun EPUB_bookmark_survives_reopen_navigates_and_deletes_only_after_confirmation() = runBlocking {
        val context = instrumentation.targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val book = fixture(context)
        container.library.add(book)
        try {
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE") }
                compose.onNodeWithText("İçindekiler").performClick()
                compose.onNodeWithText("three").performScrollTo().performClick()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                compose.onNodeWithText("Yer imleri").performClick()
                compose.onNodeWithText("Bu konumu kaydet").performClick()
                await { runBlocking { container.bookmarks.observe(book.id, book.sha256).first().size == 1 } }
                compose.onNodeWithText("Kapat").performClick()
                compose.onNodeWithText("İçindekiler").performClick()
                compose.onNodeWithText("one").performScrollTo().performClick()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE") }
                await { runBlocking { container.reading.resume(book.id, book.sha256)?.resourceHref?.endsWith("one.xhtml") == true } }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE") }
                val saved = container.bookmarks.observe(book.id, book.sha256).first().single()
                compose.onNodeWithText("Yer imleri").performClick()
                compose.waitUntil(25_000) { compose.onAllNodesWithText(saved.target.label).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(saved.target.label).performScrollTo().performClick()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                awaitNativeResume(scenario, "three.xhtml")
                scenario.recreate()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                compose.onNodeWithText("Yer imleri").performClick()
                compose.waitUntil(25_000) { compose.onAllNodesWithText("Sil").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Sil").performScrollTo().performClick()
                compose.onNodeWithText("Vazgeç").performClick()
                assertEquals(1, container.bookmarks.observe(book.id, book.sha256).first().size)
                compose.onNodeWithText("Sil").performScrollTo().performClick()
                compose.onNodeWithText("Yer imini sil").performClick()
                await { runBlocking { container.bookmarks.observe(book.id, book.sha256).first().isEmpty() } }
                val inspection = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                    .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19).build()
                try { assertTrue(inspection.reading().observed(book.id, book.sha256).none { it.resourceHref.endsWith("two.xhtml") }) }
                finally { inspection.close() }
            }
        } finally {
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19).build()
            try { db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(book.id)) }
            finally { db.close() }
            File(context.filesDir, "books/${book.storageName}").delete()
        }
    }

    @Test fun EPUB_remaining_time_uses_real_foreground_intervals_and_resets_after_jump_and_recreation() = runBlocking {
        val context = instrumentation.targetContext
        val container = (context.applicationContext as YaprakApplication).container
        withTimeout(30_000) { container.startup.awaitReady() }
        val original = container.settings.settings.first()
        val book = fixture(context, repeatParagraphs = 500)
        val evidence = org.json.JSONArray()
        container.library.add(book)
        try {
            container.settings.save(EpubSettings())
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, book.id)).use { scenario ->
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_ONE") }
                val firstSurface = paceSurface(scenario)
                val source = checkNotNull(firstSurface.readingPace)
                paceCatalogueBounds = actualPaceCatalogue(scenario)
                compose.onNodeWithText("Gizle").performClick()
                val confirmation = ReaderImmersiveConfirmation(scenario)
                compose.waitUntil(25_000) {
                    var focused = false
                    scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                    confirmation.ready() && focused
                }
                // Settle any initial preferences through actual native pagination, not a synthetic locator.
                val initialProgress = source.observations.value.totalProgression ?: 0.0
                scenario.onActivity { assertTrue(firstSurface.forward()) }
                try {
                    await { paceMatchesActualLocator(scenario, source) &&
                        source.observations.value.totalProgression?.let { it > initialProgress + .000001 } == true }
                } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                    val diagnostic = paceDiagnostic(scenario, source)
                    File(paceDirectory(), "initial-pace-diagnostic.json").writeText(diagnostic)
                    capturePace("initial-pace-diagnostic")
                    throw AssertionError("Actual initial pace did not follow the accepted forward command: $diagnostic", timeout)
                }
                val revision = source.observations.value.revision
                repeat(3) { index ->
                    val before = checkNotNull(source.observations.value.totalProgression)
                    val started = android.os.SystemClock.elapsedRealtime()
                    // Actual wall/monotonic time. Compose's virtual clock cannot satisfy this wait.
                    compose.waitUntil(40_000) { android.os.SystemClock.elapsedRealtime() - started >= 35_000 }
                    scenario.onActivity { assertTrue(firstSurface.forward()) }
                    await { paceMatchesActualLocator(scenario, source) &&
                        source.observations.value.totalProgression?.let { it > before + .000001 } == true }
                    assertEquals("Ordinary same-resource pages must retain the pace generation", revision,
                        source.observations.value.revision)
                    evidence.put(org.json.JSONObject().put("step", index + 1).put("before", before)
                        .put("after", source.observations.value.totalProgression)
                        .put("elapsedAtLeastMillis", android.os.SystemClock.elapsedRealtime() - started)
                        .put("revision", revision).put("state", paceEstimate(scenario).toString()))
                    File(paceDirectory(), "real-epub-measurements.json").writeText(evidence.toString(2))
                }
                try {
                    await { paceEstimate(scenario) is com.yaprak.reader.feature.reader.ReadingTimeEstimate.Remaining }
                } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError("Real timed EPUB pace must calibrate after three35sec intervals; samples=$evidence; " +
                        "current=${source.observations.value}; state=${paceEstimate(scenario)}", failure)
                }
                val measured = paceEstimate(scenario) as com.yaprak.reader.feature.reader.ReadingTimeEstimate.Remaining
                assertTrue(measured.minutes > 0)
                val measuredChapterMinutes = checkNotNull(measured.chapterMinutes)
                assertTrue(measuredChapterMinutes > 0 && measuredChapterMinutes <= measured.minutes)
                await { source.observations.value.chapterEntryId != null }
                val measuredChapterId = checkNotNull(source.observations.value.chapterEntryId)
                val measuredChapterEnd = checkNotNull(source.observations.value.rangeEnd)
                assertEquals(firstSurface.contentsNavigator?.entryIdForResource(checkNotNull(source.observations.value.resourceHref)),
                    measuredChapterId)
                assertEquals(checkNotNull(paceCatalogueBounds[source.observations.value.resourceHref]).second,
                    measuredChapterEnd, .000000000001)
                tapReaderCenter(scenario)
                compose.onNodeWithText("Kitabın kalanına yaklaşık", substring = true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Bu bölümün kalanına yaklaşık", substring = true).performScrollTo().assertIsDisplayed()
                var expectedPercent: Int? = null
                scenario.onActivity { activity ->
                    val locator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>()
                        .single().currentLocator.value
                    val bounds = checkNotNull(paceCatalogueBounds[locator.href.removeFragment().toString()])
                    val point = bounds.first + checkNotNull(locator.locations.progression) * (bounds.second - bounds.first)
                    expectedPercent = kotlin.math.floor(point * 100 + .5).toInt()
                    assertTrue("The fixture has advanced within the first resource", checkNotNull(expectedPercent) > 0)
                }
                compose.onNodeWithText("Yaklaşık kitap konumu: %${checkNotNull(expectedPercent)}")
                    .performScrollTo().assertIsDisplayed()
                capturePace("epub-remaining")
                compose.onNodeWithText("İçindekiler").performScrollTo().performClick()
                compose.onNodeWithText("three").performScrollTo().performClick()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                await { source.observations.value.revision > revision &&
                    paceEstimate(scenario) == com.yaprak.reader.feature.reader.ReadingTimeEstimate.Measuring }
                compose.onNodeWithText(context.getString(com.yaprak.reader.R.string.reading_time_measuring))
                    .performScrollTo().assertIsDisplayed()
                capturePace("epub-after-jump")
                evidence.put(org.json.JSONObject().put("measuredMinutes", measured.minutes)
                    .put("measuredChapterMinutes", measuredChapterMinutes).put("measuredChapterEntryId", measuredChapterId)
                    .put("measuredChapterEnd", measuredChapterEnd)
                    .put("afterJumpRevision", source.observations.value.revision).put("jumpReset", true))
                File(paceDirectory(), "real-epub-measurements.json").writeText(evidence.toString(2))
                val beforeScrollRevision = source.observations.value.revision
                compose.onNodeWithText("Gizle").performClick()
                container.settings.save(EpubSettings(scroll = true))
                await {
                    var scrolling = false
                    scenario.onActivity { activity ->
                        scrolling = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>()
                            .singleOrNull()?.settings?.value?.scroll == true
                    }
                    scrolling && source.observations.value.revision > beforeScrollRevision &&
                        paceMatchesActualLocator(scenario, source)
                }
                var scrollBefore: Double? = null
                val beforeScrollDiagnostic = paceDiagnostic(scenario, source)
                scenario.onActivity { scrollBefore = source.observations.value.totalProgression }
                // SDK3.1.2 goForward requests the next resource in scroll mode; this is the last one.
                // Exercise the actual user's vertical document gesture instead of a page-turn command.
                swipeReaderUp(scenario)
                try {
                    await { paceMatchesActualLocator(scenario, source) &&
                        source.observations.value.totalProgression?.let { it > checkNotNull(scrollBefore) + .000001 } == true }
                } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                    val diagnostic = org.json.JSONObject().put("before", org.json.JSONObject(beforeScrollDiagnostic))
                        .put("after", org.json.JSONObject(paceDiagnostic(scenario, source)))
                        .put("scrollBefore", scrollBefore).toString(2)
                    File(paceDirectory(), "scroll-pace-diagnostic.json").writeText(diagnostic)
                    capturePace("scroll-pace-diagnostic")
                    throw AssertionError("Actual scroll pace did not advance after the native vertical gesture: $diagnostic", timeout)
                }
                assertEquals(com.yaprak.reader.feature.reader.ReadingTimeEstimate.Measuring, paceEstimate(scenario))
                evidence.put(org.json.JSONObject().put("nativeScrollMode", true).put("scrollBefore", scrollBefore)
                    .put("scrollAfter", source.observations.value.totalProgression)
                    .put("scrollTimingAcceptance", false))
                awaitNativeResume(scenario, "three.xhtml")
                scenario.recreate()
                await { evaluate(scenario, "document.body.innerText").contains("CHAPTER_THREE") }
                val recreated = paceSurface(scenario)
                assertNotSame(firstSurface, recreated)
                assertNotSame(source, recreated.readingPace)
                assertNull("Disposed source cannot retain live position", source.observations.value.totalProgression)
                assertEquals(com.yaprak.reader.feature.reader.ReadingTimeEstimate.Measuring, paceEstimate(scenario))
                val file = File(context.filesDir, "books/${book.storageName}")
                assertEquals(book.sha256, MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) })
                evidence.put(org.json.JSONObject().put("newSurfaceAfterRecreate", true).put("originalHashUnchanged", true))
                File(paceDirectory(), "real-epub-measurements.json").writeText(evidence.toString(2))
            }
        } finally {
            container.settings.save(original)
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
            try { db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = ?", arrayOf(book.id)) }
            finally { db.close() }
            File(context.filesDir, "books/${book.storageName}").delete()
        }
    }

    private fun paceSurface(scenario: ActivityScenario<ReaderActivity>): ReaderSurface {
        var result: ReaderSurface? = null
        scenario.onActivity { activity ->
            result = ReaderActivity::class.java.getDeclaredField("surface").apply { isAccessible = true }
                .get(activity) as? ReaderSurface
        }
        return checkNotNull(result)
    }

    private fun paceEstimate(scenario: ActivityScenario<ReaderActivity>): com.yaprak.reader.feature.reader.ReadingTimeEstimate? {
        var result: com.yaprak.reader.feature.reader.ReadingTimeEstimate? = null
        scenario.onActivity { activity ->
            val controller = ReaderActivity::class.java.getDeclaredMethod("getPace").apply { isAccessible = true }
                .invoke(activity) as com.yaprak.reader.feature.reader.ReaderPaceController
            result = controller.state.value
        }
        return result
    }

    private fun paceMatchesActualLocator(scenario: ActivityScenario<ReaderActivity>, source: ReadingPaceSource): Boolean {
        var matches = false
        scenario.onActivity { activity ->
            val navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().singleOrNull()
            val locator = navigator?.currentLocator?.value
            val href = locator?.href?.removeFragment()?.toString()
            val native = locator?.locations?.progression
            val bounds = paceCatalogueBounds[href]
            val sample = source.observations.value
            matches = sample.totalProgression != null && native != null && bounds != null &&
                sample.resourceHref == href && sample.rangeStart == bounds.first && sample.rangeEnd == bounds.second &&
                sample.resourceProgression?.let { kotlin.math.abs(it - native) < .000000000001 } == true &&
                kotlin.math.abs(sample.totalProgression!! - (bounds.first + native * (bounds.second - bounds.first))) < .000000000001 &&
                android.os.SystemClock.elapsedRealtime() - sample.observedAtMillis in 0..5_000
        }
        return matches
    }

    /** Independently read actual SDK catalogue anchors; never hard-code three equal chapters. */
    private suspend fun actualPaceCatalogue(scenario: ActivityScenario<ReaderActivity>): Map<String, Pair<Double, Double>> {
        var publication: org.readium.r2.shared.publication.Publication? = null
        scenario.onActivity { activity ->
            val navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().single()
            // Kotlin protects this SDK property. Read the actual instance without changing it.
            publication = org.readium.r2.navigator.NavigatorFragment::class.java.getDeclaredMethod("getPublication")
                .apply { isAccessible = true }.invoke(navigator) as org.readium.r2.shared.publication.Publication
        }
        val actual = checkNotNull(publication)
        val positions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { actual.positionsByReadingOrder() }
        assertEquals(actual.readingOrder.size, positions.size)
        val starts = positions.map { checkNotNull(it.first().locations.totalProgression) }
        return actual.readingOrder.mapIndexed { index, link ->
            link.url().removeFragment().toString() to (starts[index] to starts.getOrElse(index + 1) { 1.0 })
        }.toMap()
    }

    /** Read-only synchronization evidence; never edits native locators, preferences or controller state. */
    private fun paceDiagnostic(scenario: ActivityScenario<ReaderActivity>, source: ReadingPaceSource): String {
        val data = org.json.JSONObject()
        scenario.onActivity { activity ->
            val navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().single()
            val native = navigator.currentLocator.value
            data.put("nativeHref", native.href.removeFragment().toString())
                .put("nativeTotal", native.locations.totalProgression)
                .put("nativeResourceProgression", native.locations.progression)
                .put("nativePosition", native.locations.position)
                .put("nativeResumed", navigator.isResumed)
                .put("nativeScroll", navigator.settings.value.scroll)
                .put("sourceObservation", source.observations.value.toString())
                .put("nowMillis", android.os.SystemClock.elapsedRealtime())
                .put("windowFocused", activity.window.decorView.hasWindowFocus())
            for (name in listOf("durationVisible", "durationForeground", "durationFocused", "chrome", "loadFailed", "saveFailed", "renderingFailed")) {
                val fields = ReaderActivity::class.java.declaredFields
                val plain = fields.firstOrNull { it.name == name }?.apply { isAccessible = true }
                val delegate = fields.firstOrNull { it.name == name + "\$delegate" }?.apply { isAccessible = true }
                val value = plain?.get(activity) ?: (delegate?.get(activity) as? androidx.compose.runtime.State<*>)?.value
                data.put(name, value?.toString() ?: "unavailable")
            }
            for (name in listOf("pendingPage", "revision", "active", "viewport")) {
                val field = source.javaClass.declaredFields.firstOrNull { it.name == name }?.apply { isAccessible = true }
                data.put("source_$name", field?.get(source)?.toString() ?: "unavailable")
            }
        }
        val geometry = evaluate(scenario, "JSON.stringify({ready:document.readyState,visibility:document.visibilityState,width:innerWidth,height:innerHeight,x:scrollX,y:scrollY,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,paragraphs:document.querySelectorAll('p').length})")
        data.put("actualDocumentGeometry", geometry)
        return data.toString(2)
    }

    private fun tapReaderCenter(scenario: ActivityScenario<ReaderActivity>) {
        var owner: ReaderActivity? = null
        var decor: android.view.View? = null
        var x = 0f; var y = 0f
        scenario.onActivity { activity ->
            owner = activity; decor = activity.window.decorView
            val location = IntArray(2)
            checkNotNull(decor).getLocationOnScreen(location)
            x = location[0] + checkNotNull(decor).width / 2f
            y = location[1] + checkNotNull(decor).height / 2f
        }
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            // Do not call ActivityScenario while a pointer is held: its global-idle wait can deadlock.
            instrumentation.runOnMainSync {
                check(owner?.window?.decorView === decor && decor?.isAttachedToWindow == true && decor?.hasWindowFocus() == true)
            }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == android.view.MotionEvent.ACTION_DOWN) android.os.SystemClock.sleep(40)
        }
    }

    private fun swipeReaderUp(scenario: ActivityScenario<ReaderActivity>) {
        var owner: ReaderActivity? = null
        var decor: android.view.View? = null
        var x = 0f; var startY = 0f; var endY = 0f
        scenario.onActivity { activity ->
            owner = activity; decor = activity.window.decorView
            val location = IntArray(2)
            checkNotNull(decor).getLocationOnScreen(location)
            x = location[0] + checkNotNull(decor).width * .5f
            startY = location[1] + checkNotNull(decor).height * .72f
            endY = location[1] + checkNotNull(decor).height * .28f
        }
        val down = android.os.SystemClock.uptimeMillis()
        fun currentWindow(): Boolean {
            var current = false
            // Never ask ActivityScenario for global idle while a finger is held.
            instrumentation.runOnMainSync {
                current = owner?.isDestroyed == false && owner?.isFinishing == false &&
                    owner?.window?.decorView === decor && decor?.isAttachedToWindow == true && decor?.hasWindowFocus() == true
            }
            return current
        }
        fun send(action: Int, y: Float) {
            check(currentWindow())
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        var pressed = false
        var last = startY
        try {
            send(android.view.MotionEvent.ACTION_DOWN, last); pressed = true
            repeat(24) { index ->
                android.os.SystemClock.sleep(20)
                compose.mainClock.advanceTimeBy(20, ignoreFrameDuration = true)
                last = startY + (endY - startY) * (index + 1) / 24f
                send(android.view.MotionEvent.ACTION_MOVE, last)
            }
            send(android.view.MotionEvent.ACTION_UP, last); pressed = false
        } finally {
            if (pressed && currentWindow()) send(android.view.MotionEvent.ACTION_CANCEL, last)
        }
    }

    private fun paceDirectory() = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
        "yaprak-reading-time-0.182").apply { check(isDirectory || mkdirs()) }

    private fun capturePace(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(paceDirectory(), "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }


    private fun evaluate(scenario: ActivityScenario<ReaderActivity>, script: String): String {
        val result = AtomicReference("")
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
            if (navigator == null) done.countDown()
            else activity.lifecycleScope.launch {
                try { result.set(navigator.evaluateJavascript(script).orEmpty()) }
                finally { done.countDown() }
            }
        }
        // Keep the Compose test clock moving while Android/WebView does external work.
        compose.waitUntil(5_000) { done.count == 0L }
        return result.get()
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(25_000) { condition() }
    }

    /** DOM loading is earlier than the SDK locator and actual observation used for restoration. */
    private fun awaitNativeResume(scenario: ActivityScenario<ReaderActivity>, suffix: String) {
        var last: Pair<String?, String?>? = null
        try {
            compose.waitUntil(25_000) {
                scenario.onActivity { activity ->
                    val navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>()
                        .singleOrNull()
                    val model = ReaderActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible = true }
                        .invoke(activity) as com.yaprak.reader.feature.reader.ReaderViewModel
                    last = navigator?.currentLocator?.value?.href?.removeFragment()?.toString() to
                        model.latestLocation?.resourceHref
                }
                last?.let { it.first?.endsWith(suffix) == true && it.second?.endsWith(suffix) == true } == true
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Actual EPUB SDK and observed resume must commit before recreation; native/model=$last", timeout)
        }
    }

    private fun fixture(context: Context, repeatParagraphs: Int = 1): Book {
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "books/$id.epub").apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
                zip.putNextEntry(ZipEntry(name).apply {
                    if (name == "mimetype") {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                })
                zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val chapters = listOf("one", "two", "three")
            entry("EPUB/package.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>Reader test</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>${chapters.joinToString("") { "<item id=\"$it\" href=\"$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>${chapters.joinToString("") { "<itemref idref=\"$it\"/>" }}</spine></package>""")
            chapters.forEach { chapter -> entry("EPUB/$chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$chapter</title></head><body><h1>CHAPTER_${chapter.uppercase(java.util.Locale.ROOT)}</h1>${"<p>Original reader fixture. A quiet morning in the garden.</p>".repeat(repeatParagraphs)}</body></html>""") }
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${chapters.joinToString("") { "<li><a href=\"$it.xhtml\">$it</a></li>" }}</ol></nav></body></html>""")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return Book(id, "Reader test", null, BookFormat.EPUB, "test.epub", file.name, file.length(), hash, System.currentTimeMillis())
    }
}
