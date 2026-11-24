@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.yaprak.reader.reader

import com.yaprak.reader.readerMethod
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.TapZoneSettings
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderPresentationController
import com.yaprak.reader.feature.reader.ReaderSelectionCoordinator
import com.yaprak.reader.formats.pdf.PdfReaderFragment
import com.yaprak.reader.formats.pdf.PdfTextSelectionSource
import com.yaprak.reader.formats.pdf.PdfRenderStatus
import com.yaprak.reader.formats.pdf.PdfDrawProofView
import com.yaprak.reader.formats.pdf.PdfPageDrawWitness
import com.yaprak.reader.formats.pdf.PdfDrawGeometry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ReaderActivity, native PDF glyphs and injected screen gestures. No fake surface. */
@RunWith(AndroidJUnit4::class)
class ReaderExperienceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var waitNumber = 0
    private var frameDiagnostic = "not sampled"
    @Volatile private var tapDiagnostic = "no native tap received"
    @Volatile private var thirdTapDiagnostic = "not recorded"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as YaprakApplication).container

    @Test fun epubRealLongPressKeepsVisibleSelectionAndLibraryShowsBooksFirst() = runBlocking {
        withTimeout(30_000) { container.startup.awaitReady() }
        val originalTaps = container.tapZones.tapZones.first()
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "books/$id.epub").apply { parentFile!!.mkdirs() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
                zip.putNextEntry(java.util.zip.ZipEntry(name).apply {
                    if (name == "mimetype") {
                        method = java.util.zip.ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                }); zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("EPUB/package.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>Okuma deneyi</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="text"/></spine></package>""")
            entry("EPUB/text.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Reading</title></head><body><p>Alpha beta gamma delta. A quiet morning in the garden.</p>${"<p>Books invite careful reading. Original text remains visible.</p>".repeat(120)}</body></html>""")
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="text.xhtml">Reading</a></li></ol></nav></body></html>""")
        }
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val book = Book(id, "Okuma deneyi", null, BookFormat.EPUB, "experience.epub", file.name, file.length(), hash, System.currentTimeMillis())
        container.library.add(book)
        try {
            container.tapZones.saveTapZones(TapZoneSettings())
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                val confirmation = ReaderImmersiveConfirmation(scenario)
                await { confirmation.ready() && evaluate(scenario, "document.body.innerText").contains("Alpha beta") }
                compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
                capture("epub-reading")
                // Get real native DOM coordinates and use a real screen long press, not a JS-created selection.
                val rect = org.json.JSONArray(evaluate(scenario, "JSON.stringify((function(){var p=document.querySelector('p');var r=document.createRange();r.setStart(p.firstChild,0);r.setEnd(p.firstChild,5);var b=r.getBoundingClientRect();return [b.x+b.width/2,b.y+b.height/2]})())").let { org.json.JSONTokener(it).nextValue().toString() })
                var point = androidx.compose.ui.geometry.Offset.Zero
                scenario.onActivity { activity ->
                    fun find(view: android.view.View): android.webkit.WebView? {
                        if (view is android.webkit.WebView && view.isShown) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val web = checkNotNull(find(activity.window.decorView))
                    val position = IntArray(2); web.getLocationOnScreen(position)
                    val density = web.resources.displayMetrics.density
                    point = androidx.compose.ui.geometry.Offset(position[0] + rect.getDouble(0).toFloat() * density, position[1] + rect.getDouble(1).toFloat() * density)
                }
                touch(point.x, point.y, 800)
                await { evaluate(scenario, "window.getSelection().toString()").contains("Alpha") }
                capture("epub-native-selection")
                // Native selection and original-text capture must agree; direct custom EPUB tools are deferred.
                var source: TextSelectionSource? = null
                scenario.onActivity { activity ->
                    source = (ReaderActivity::class.java.getDeclaredField("surface").apply { isAccessible = true }.get(activity) as ReaderSurface).textSelection
                }
                val captured = withContext(Dispatchers.Main) { source?.capture() }
                assertEquals("Alpha", captured?.text?.trim())
            }
            ActivityScenario.launch<com.yaprak.reader.MainActivity>(android.content.Intent(context, com.yaprak.reader.MainActivity::class.java)).use {
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Araçlar").fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText("Okuma deneyi", substring = false)[0].assertIsDisplayed()
                capture("library-books-first")
                compose.onNodeWithText("Araçlar").performClick()
                compose.onNodeWithText("Kitaplık araçları").assertIsDisplayed()
                capture("library-tools")
            }
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            container.tapZones.saveTapZones(originalTaps)
            withTimeout(15_000) { while (container.remover.remove(book.id, book.sha256) == BookRemovalResult.InUse) delay(50) }
        }
    }

    private fun evaluate(scenario: ActivityScenario<ReaderActivity>, script: String): String {
        val result = java.util.concurrent.atomic.AtomicReference("")
        val done = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { activity ->
            val navigator = activity.supportFragmentManager.fragments.filterIsInstance<org.readium.r2.navigator.epub.EpubNavigatorFragment>().singleOrNull()
            if (navigator == null) done.countDown() else activity.lifecycleScope.launch {
                try { result.set(navigator.evaluateJavascript(script).orEmpty()) } finally { done.countDown() }
            }
        }
        compose.waitUntil(5_000) { done.count == 0L }
        return result.get()
    }

    @Test fun fullscreenSingleTapControlsSelectionHandlesAndRecreation() = runBlocking {
        withTimeout(30_000) { container.startup.awaitReady() }
        val originalTaps = container.tapZones.tapZones.first()
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "books/$id.pdf").apply { parentFile!!.mkdirs() }
        val writer = android.graphics.pdf.PdfDocument()
        try {
            repeat(3) { index ->
                val page = writer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 500, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawText("alpha beta gamma delta", 25f, 120f, Paint().apply { textSize = 18f })
                page.canvas.drawText("Reading page ${index + 1}", 25f, 220f, Paint().apply { textSize = 18f })
                writer.finishPage(page)
            }
            file.outputStream().use(writer::writeTo)
        } finally { writer.close() }
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val book = Book(id, "Okuma deneyimi", null, BookFormat.PDF, "experience.pdf", file.name, file.length(), hash, System.currentTimeMillis(), pageCount = 3)
        container.library.add(book)
        try {
            container.tapZones.saveTapZones(TapZoneSettings())
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.intent(context, id)).use { scenario ->
                val confirmation = ReaderImmersiveConfirmation(scenario)
                await("initial native page and Window ready") { confirmation.ready() && frame(scenario) == 0 }
                scenario.onActivity { activity ->
                    val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().single()
                    @Suppress("UNCHECKED_CAST")
                    val original = PdfReaderFragment::class.java.getDeclaredField("tapHandler").apply { isAccessible = true }.get(pdf) as? ((ReaderTap) -> Boolean)
                    pdf.setTapHandler { tap ->
                        val flags = readerFlags(activity)
                        val result = original?.invoke(tap) ?: false
                        tapDiagnostic = "native x=${tap.xFraction} y=${tap.yFraction} zone=${tap.zone} consumed=$result $flags"
                        result
                    }
                }
                compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
                capture("pdf-reading")
                tap(scenario, .85f, .55f)
                await("right edge paints second page") { frame(scenario) == 1 }
                compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
                tap(scenario, .5f, .55f)
                compose.onNodeWithTag("reader-bottom-bar").assertIsDisplayed()
                capture("pdf-controls")
                // A right tap while controls show turns immediately, without a sacrificial tap.
                tap(scenario, .85f, .55f, trace = true)
                await("right edge with controls paints third page") { frame(scenario) == 2 }
                compose.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
                scenario.recreate()
                await("recreated reader paints third page") { frame(scenario) == 2 }
                tap(scenario, .5f, .55f)
                compose.onNodeWithText("İçerik").performClick()
                compose.onNodeWithText("Yer imleri").assertIsDisplayed()
                repeat(2) { cycle ->
                    compose.onNodeWithText("Yer imleri").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("Bu konumu kaydet").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Bu konumu kaydet").assertIsDisplayed()
                    if (cycle == 0) {
                        compose.onNodeWithText("Bu konumu kaydet").assertIsEnabled().performClick()
                        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bu konum zaten kayıtlı.").fetchSemanticsNodes().isNotEmpty() }
                        val stored = container.bookmarks.observe(book.id, book.sha256).first().single()
                        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasText("Düzenle"))
                        compose.onNodeWithText("Düzenle").performClick()
                        compose.onNodeWithText("Yer imi başlığını düzenle").assertIsDisplayed()
                        compose.onNodeWithText("Yer imleri").assertDoesNotExist()
                        compose.onNodeWithTag("bookmark-rename-field").performTextReplacement("Uncommitted bookmark draft")
                        capture("pdf-bookmark-rename")
                        compose.onNodeWithText("Vazgeç").performClick()
                        compose.onNodeWithText("Yer imleri").assertIsDisplayed()
                        await("cancel restores bookmark rename caller focus") {
                            compose.onAllNodes(hasTestTag("bookmark-rename-${stored.id}") and isFocused()).fetchSemanticsNodes().size == 1
                        }
                        compose.onNodeWithTag("bookmark-rename-${stored.id}").assertIsFocused()
                        assertEquals(stored, container.bookmarks.observe(book.id, book.sha256).first().single())
                    }
                    // The parent is suspended: there must be one input Window, not stacked dialogs.
                    compose.onNodeWithText("Kitap içeriği").assertDoesNotExist()
                    if (cycle == 0) compose.onNodeWithText("Kapat").performClick()
                    else instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    compose.onNodeWithText("Kitap içeriği").assertIsDisplayed()
                }
                compose.onNodeWithText("Notlar").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("book-note-draft").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("book-note-draft").performTextReplacement("Panel deneme notu")
                compose.onNodeWithText("Notu kaydet").performScrollTo().performClick()
                val storedNote = withTimeout(10_000) { container.bookNotes.observe(book.id, book.sha256).first { it.size == 1 } }.single()
                compose.onNodeWithTag("book-notes-list").performScrollToNode(hasText("Düzenle"))
                compose.onNodeWithText("Düzenle").performClick()
                compose.onNodeWithText("Notu düzenle").assertIsDisplayed()
                compose.onNodeWithText("Kitap notları").assertDoesNotExist()
                compose.onNodeWithTag("book-note-edit-draft").performTextReplacement("Uncommitted note draft")
                var previousActivity: ReaderActivity? = null
                scenario.onActivity { previousActivity = it }
                scenario.recreate()
                scenario.onActivity { assertNotSame(previousActivity, it) }
                await("dirty note editor returns after Activity recreation") {
                    confirmation.ready() && compose.onAllNodesWithTag("book-note-edit-draft")
                        .fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("book-note-edit-draft").assertEditableTextExactly("Uncommitted note draft")
                capture("pdf-note-dirty-restored")
                compose.onNodeWithText("Kitap notları").assertDoesNotExist()
                assertEquals(storedNote, container.bookNotes.observe(book.id, book.sha256).first().single())
                assertArrayEquals(bytes, file.readBytes())
                compose.onNodeWithText("Vazgeç").performClick()
                compose.onNodeWithText("Kitap notları").assertIsDisplayed()
                await("cancel restores book note caller focus") { compose.onAllNodes(hasTestTag("book-note-edit-${storedNote.id}") and isFocused()).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("book-note-edit-${storedNote.id}").assertIsFocused()
                compose.onNodeWithText("Panel deneme notu").assertExists()
                assertEquals(storedNote, container.bookNotes.observe(book.id, book.sha256).first().single())
                compose.onNodeWithText("Kapat").performClick()
                compose.onNodeWithText("Kitap içeriği").assertIsDisplayed()
                compose.onNodeWithText("Kapat").performClick()
                compose.onNodeWithText("Diğer").performClick()
                compose.onNodeWithText("Kelimeler").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("saved-word-english").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("saved-word-english").performTextReplacement("sampleword")
                compose.onNodeWithTag("saved-word-turkish").performTextReplacement("örnek kelime")
                compose.onNodeWithText("Kelimeyi kaydet").performScrollTo().performClick()
                val storedWord = withTimeout(10_000) { container.savedWords.observe(book.id, book.sha256).first { it.size == 1 } }.single()
                compose.onNodeWithTag("saved-words-list").performScrollToNode(hasText("Düzenle"))
                compose.onNodeWithText("Düzenle").performClick()
                compose.onNodeWithText("Kelimeyi düzenle").assertIsDisplayed()
                compose.onNodeWithText("Kaydedilen kelimeler").assertDoesNotExist()
                compose.onNodeWithTag("saved-word-edit-english").performTextReplacement("uncommitted")
                capture("pdf-word-edit")
                compose.onNodeWithText("Vazgeç").performClick()
                compose.onNodeWithText("Kaydedilen kelimeler").assertIsDisplayed()
                await("cancel restores vocabulary caller focus") { compose.onAllNodes(hasTestTag("saved-word-edit-${storedWord.id}") and isFocused()).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("saved-word-edit-${storedWord.id}").assertIsFocused()
                compose.onNodeWithText("sampleword").assertExists()
                assertEquals(storedWord, container.savedWords.observe(book.id, book.sha256).first().single())
                compose.onNodeWithText("Kapat").performClick()
                compose.onNodeWithText("Okuma araçları").assertIsDisplayed()
                compose.onNodeWithText("Kapat").performClick()
                compose.onNodeWithText("Gizle").performClick()
                await("return to native third page before selecting") { frame(scenario) == 2 }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                compose.waitForIdle()
                // Native point mapping against the rendered page's actual fitted size.
                val bounds = compose.onNodeWithContentDescription("PDF sayfası 3").fetchSemanticsNode().boundsInRoot
                val scale = minOf(bounds.width / 300f, bounds.height / 500f)
                val px = bounds.left + (bounds.width - 300 * scale) / 2 + 42 * scale
                val py = bounds.top + (bounds.height - 500 * scale) / 2 + 114 * scale
                touch(px, py, hold = 750)
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("pdf-selection-start").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("pdf-selection-start").assertIsDisplayed()
                compose.onNodeWithTag("pdf-selection-end").assertIsDisplayed()
                val before = selectionText(scenario)
                assertTrue(before?.contains("alpha") == true)
                capture("pdf-selection")
                compose.onNodeWithTag("pdf-selection-end").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(90 * scale, 0f), 600) }
                await("native end handle expands original selected text") { selectionText(scenario)?.let { it.length > (before?.length ?: 0) && it.startsWith(checkNotNull(before)) } == true }
                val expectedCopy = checkNotNull(selectionText(scenario))
                capture("pdf-selection-expanded")
                compose.onNodeWithText("Seçim araçları").performClick()
                try {
                    compose.waitUntil(15_000) { compose.onAllNodesWithText("Seçili metin").fetchSemanticsNodes().isNotEmpty() }
                } catch (timeout: ComposeTimeoutException) {
                    val afterTools = selectionToolsDiagnostic(scenario)
                    capture("pdf-selection-tools-timeout")
                    throw AssertionError("PDF selection tools did not open: $afterTools", timeout)
                }
                compose.onNodeWithText("Panoya kopyala").assertIsDisplayed().performClick()
                compose.onNodeWithText("Kopyalandı").assertIsDisplayed()
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals(expectedCopy, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                capture("pdf-selection-actions")
            }
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            container.tapZones.saveTapZones(originalTaps)
            // Existing remover waits for the real reader owner to release its file lease.
            withTimeout(15_000) {
                while (container.remover.remove(book.id, book.sha256) == BookRemovalResult.InUse) delay(50)
            }
        }
    }

    private fun frame(scenario: ActivityScenario<ReaderActivity>): Int? {
        var page: Int? = null
        scenario.onActivity { activity ->
            val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().singleOrNull()
            page = pdf?.bookmarkPage
            frameDiagnostic = "bookmark=$page drawn=${pdf?.drawnPage?.value} rendered=${pdf?.renderedPage?.value} focused=${activity.window.decorView.hasWindowFocus()} resumed=${pdf?.isResumed} ${readerFlags(activity)} last=$tapDiagnostic thirdInput=$thirdTapDiagnostic"
        }
        return page
    }
    private fun readerFlags(activity: ReaderActivity): String {
        fun value(name: String): Any? = runCatching {
            ReaderActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        }.getOrElse {
            runCatching { (ReaderActivity::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>).value }.getOrElse {
                runCatching { ReaderActivity::class.java.readerMethod("get" + name.replaceFirstChar { it.uppercaseChar() })
                    .apply { isAccessible = true }.invoke(activity) }.getOrNull()
            }
        }
        val flags = listOf("durationForeground", "durationFocused", "durationVisible", "readerImeVisible", "readerTouchActive", "loadFailed", "saveFailed", "exiting", "orientationApplyFailed", "pdfCropApplyFailed", "resetPending", "resetRefreshFailed").joinToString { "$it=${value(it)}" }
        val admitted = ReaderActivity::class.java.readerMethod("canNavigateWithReadingInput", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        val ready = value("uiState") as? com.yaprak.reader.feature.reader.ReaderState.Ready
        return "admitted=$admitted sameSurface=${value("surface") === value("durationSurface")} sameSession=${ready?.session === value("durationSession")} sameBook=${ready?.session?.book == value("durationBook")} editor=${activity.window.decorView.findFocus()?.onCheckIsTextEditor()} $flags"
    }
    /** Read-only failure diagnosis: no text, request IDs, source hashes or book identity. */
    private fun selectionToolsDiagnostic(scenario: ActivityScenario<ReaderActivity>): String = runCatching {
        var diagnostic = "reader unavailable"
        scenario.onActivity { activity ->
            val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().singleOrNull()
            val source = pdf?.textSelection as? PdfTextSelectionSource
            val pending = source?.let { PdfTextSelectionSource::class.java.getDeclaredField("pending")
                .apply { isAccessible = true }.get(it) != null }
            val subscribers = source?.let { (PdfTextSelectionSource::class.java.getDeclaredField("notifications")
                .apply { isAccessible = true }.get(it) as kotlinx.coroutines.flow.MutableSharedFlow<*>).subscriptionCount.value }
            val coordinator = ReaderActivity::class.java.readerMethod("getSelection")
                .apply { isAccessible = true }.invoke(activity) as ReaderSelectionCoordinator
            val collector = ReaderSelectionCoordinator::class.java.getDeclaredField("notifications")
                .apply { isAccessible = true }.get(coordinator) as? Job
            val presentation = (ReaderActivity::class.java.getDeclaredField("presentation")
                .apply { isAccessible = true }.get(activity) as ReaderPresentationController).state.value
            diagnostic = "sourceActive=${source?.active}, sourcePending=$pending, sourceSubscribers=$subscribers, " +
                "sourcePreview=${source?.preview?.value != null}, " +
                "capturePreview=${coordinator.preview?.javaClass?.simpleName}, collectorActive=${collector?.isActive}, " +
                "phase=${presentation.phase.javaClass.simpleName}, panel=${presentation.activePanel?.kind}, " +
                "nativeDrawn=${pdf?.drawnPage?.value}, nativeBookmark=${pdf?.bookmarkPage}, " + readerFlags(activity)
        }
        "$diagnostic; ${readerWindowDiagnostic(scenario)}"
    }.getOrElse { "diagnostic unavailable: ${it.javaClass.simpleName}" }

    private suspend fun selectionText(scenario: ActivityScenario<ReaderActivity>): String? {
        var source: TextSelectionSource? = null
        scenario.onActivity { source = it.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().singleOrNull()?.textSelection }
        return source?.capture()?.text
    }
    private fun tap(scenario: ActivityScenario<ReaderActivity>, x: Float, y: Float, trace: Boolean = false) {
        var width = 0; var height = 0
        var restoreCallback: (() -> Unit)? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            width = root.width; height = root.height
            if (trace) {
                val original = checkNotNull(activity.window.callback)
                val pdf = activity.supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().singleOrNull()
                val status = pdf?.let { PdfReaderFragment::class.java.getDeclaredField("status")
                    .apply { isAccessible = true }.get(it) as PdfRenderStatus }
                val proof = pdf?.view as? PdfDrawProofView
                @Suppress("UNCHECKED_CAST")
                val witnesses = proof?.let { PdfDrawProofView::class.java.getDeclaredField("witnesses")
                    .apply { isAccessible = true }.get(it) as Map<Int, PdfPageDrawWitness> }
                val geometryField = PdfPageDrawWitness::class.java.getDeclaredField("geometry").apply { isAccessible = true }
                fun snapshot(): String {
                    val screen = IntArray(2); val window = IntArray(2); val native = IntArray(2)
                    root.getLocationOnScreen(screen); root.getLocationInWindow(window)
                    proof?.getLocationOnScreen(native)
                    val visible = android.graphics.Rect()
                    proof?.getGlobalVisibleRect(visible)
                    val geometry = status?.requestedPage?.value?.let { witnesses?.get(it) }
                        ?.let { geometryField.get(it) as? PdfDrawGeometry }
                    val insets = ViewCompat.getRootWindowInsets(root)
                    val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                    return "root=${root.width}x${root.height}@${screen.toList()}/window=${window.toList()} " +
                        "native=${proof?.width}x${proof?.height}@${native.toList()} bounds=${visible.toShortString()} " +
                        "viewport=${geometry?.viewport} fit=${geometry?.fit?.ordinal} zoom=${geometry?.zoom} pan=${geometry?.pan} " +
                        "requested=${status?.requestedPage?.value} rendered=${status?.renderedPage?.value} drawn=${status?.drawnPage?.value} " +
                        "attached=${root.isAttachedToWindow} focus=${root.hasWindowFocus()} resumed=${pdf?.isResumed} " +
                        "layout=${root.isLayoutRequested}/${proof?.isLayoutRequested} bars=$bars " +
                        "status=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())} nav=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())}"
                }
                val events = ArrayDeque<String>()
                fun record(value: String) {
                    if (events.size < 8) { events.addLast(value); thirdTapDiagnostic = events.joinToString(" | ") }
                }
                record("prepared ${snapshot()}")
                val callback = object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL))
                            return original.dispatchTouchEvent(event)
                        val input = "action=${event.actionMasked} down=${event.downTime} time=${event.eventTime} " +
                            "xy=${event.x},${event.y} raw=${event.rawX},${event.rawY}"
                        val before = snapshot()
                        return original.dispatchTouchEvent(event).also { handled ->
                            record("$input before={$before} handled=$handled after={${snapshot()}}")
                        }
                    }
                }
                activity.window.callback = callback
                restoreCallback = {
                    record("finished ${snapshot()}")
                    if (!activity.isDestroyed && activity.window.decorView === root && activity.window.callback === callback)
                        activity.window.callback = original
                }
            }
        }
        try { touch(width * x, height * y) }
        finally { restoreCallback?.let { restore -> instrumentation.runOnMainSync { restore() } } }
    }
    private fun touch(x: Float, y: Float, hold: Long = 50) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(hold)
        compose.mainClock.advanceTimeBy(hold, ignoreFrameDuration = true)
        send(MotionEvent.ACTION_UP)
        // Native input uses real uptime; Compose's test scheduler must also pass the
        // single-vs-double-tap decision, as in PdfTapZonesGestureTest.
        compose.mainClock.advanceTimeBy(400)
    }
    private suspend fun await(reason: String = "reader condition", predicate: suspend () -> Boolean) {
        val stage = ++waitNumber
        try { withTimeout(20_000) { while (!predicate()) { compose.waitForIdle(); delay(50) } } }
        catch (timeout: TimeoutCancellationException) {
            capture("timeout-$stage")
            throw AssertionError("Actual reader stage $stage ($reason) timed out. Last sampled frame: $frameDiagnostic", timeout)
        }
    }
    private fun capture(name: String) = captureReaderScreenshot(compose, name)
}
