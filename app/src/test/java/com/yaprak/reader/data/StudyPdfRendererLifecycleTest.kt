package com.yaprak.reader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Injectable document checks orchestration/cleanup, never actual native PDF serialization. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyPdfRendererLifecycleTest {
    private val snapshot = StudyRecordsSnapshot(StudyExportBook("Test book", null, BookFormat.EPUB),
        notes = listOf(StudyNoteRecord("A real native-layout note")))

    private class Document : StudyPdfDocument {
        val events = mutableListOf<String>()
        private var bitmap: Bitmap? = null
        var onStart: () -> Unit = {}
        var onWrite: (OutputStream) -> Unit = { it.write(byteArrayOf(1, 2, 3)) }
        var finishFailure = false
        var closeFailure = false
        override fun startPage(number: Int): Canvas {
            check(bitmap == null); events += "start:$number"
            val frame = Bitmap.createBitmap(STUDY_PDF_WIDTH, STUDY_PDF_HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap = frame; onStart(); return Canvas(frame)
        }
        override fun finishPage() {
            events += "finish"; checkNotNull(bitmap).recycle(); bitmap = null
            if (finishFailure) throw IOException("Fixture finish failure")
        }
        override fun writeTo(output: OutputStream) {
            check(bitmap == null); events += "write"; onWrite(output)
        }
        override fun close() {
            events += "close"; bitmap?.recycle(); bitmap = null
            if (closeFailure) throw IOException("Fixture close failure")
        }
    }

    @Test fun `each started canvas is finished before serialization and document closes exactly once`() = runTest {
        val document = Document()
        val renderer = AndroidStudyPdfRenderer({ document }, StandardTestDispatcher(testScheduler))
        renderer.render(snapshot)
        assertEquals(listOf("start:1", "finish", "write", "close"), document.events)
        // The test backend's three bytes prove only orchestration. They are not a PDF fixture.
    }

    @Test fun `cancellation of open page still finishes it and closes document without writing`() = runTest {
        val document = Document()
        val request = async { AndroidStudyPdfRenderer({ document }, StandardTestDispatcher(testScheduler)).render(snapshot) }
        document.onStart = { request.cancel(CancellationException("Fixture cancellation")) }
        runCurrent()
        try { request.await(); fail("Cancellation expected") } catch (_: CancellationException) { }
        assertEquals(listOf("start:1", "finish", "close"), document.events)
    }

    @Test fun `finish and close failures are suppressed under original cancellation`() = runTest {
        val document = Document().apply { finishFailure = true; closeFailure = true }
        val request = async { AndroidStudyPdfRenderer({ document }, StandardTestDispatcher(testScheduler)).render(snapshot) }
        document.onStart = { request.cancel(CancellationException("Primary cancellation")) }
        runCurrent()
        try { request.await(); fail("Cancellation expected") }
        catch (cancelled: CancellationException) {
            // Async/withContext may recover a cancellation with a new cause wrapper. The
            // complete exception graph must retain the primary cancellation and both cleanups.
            val graph = mutableListOf<Throwable>()
            fun visit(failure: Throwable) {
                if (graph.any { it === failure }) return
                graph += failure
                failure.cause?.let(::visit)
                failure.suppressed.forEach(::visit)
            }
            visit(cancelled)
            val messages = graph.map { it.message }
            assertTrue(messages.toString(), messages.contains("Primary cancellation"))
            assertTrue(messages.toString(), messages.contains("Fixture finish failure"))
            assertTrue(messages.toString(), messages.contains("Fixture close failure"))
        }
        assertEquals(listOf("start:1", "finish", "close"), document.events)
    }

    @Test fun `failed native writer closes document and does not return partial bytes`() = runTest {
        val document = Document().apply { onWrite = { it.write(byteArrayOf(1)); throw IOException("Fixture native write") } }
        try { AndroidStudyPdfRenderer({ document }, StandardTestDispatcher(testScheduler)).render(snapshot); fail("Failure expected") }
        catch (failure: IOException) { assertEquals("Fixture native write", failure.message) }
        assertEquals(listOf("start:1", "finish", "write", "close"), document.events)
    }

    @Test fun `native wrapped and swallowed stream quota failures both preserve TOO_LARGE`() = runTest {
        for (wrap in listOf(true, false)) {
            val document = Document().apply {
                onWrite = { output ->
                    try { output.write(ByteArray(MAX_STUDY_EXPORT_BYTES)); output.write(1) }
                    catch (failure: Exception) { if (wrap) throw IOException("Native writer wrapped callback", failure) }
                }
            }
            try { AndroidStudyPdfRenderer({ document }, StandardTestDispatcher(testScheduler)).render(snapshot); fail("Quota expected") }
            catch (failure: StudyExportException) { assertEquals(StudyExportFailure.TOO_LARGE, failure.reason) }
            assertEquals(listOf("start:1", "finish", "write", "close"), document.events)
        }
    }

    @Test fun `stream accepts exact byte limit and cancellation checked before every write and result`() {
        val job = Job(); val stream = BoundedPdfOutput(job)
        stream.write(ByteArray(MAX_STUDY_EXPORT_BYTES - 1)); stream.write(1)
        assertEquals(MAX_STUDY_EXPORT_BYTES, stream.bytes().size)
        job.cancel()
        for (action in listOf<() -> Unit>({ stream.write(1) }, { stream.write(byteArrayOf(1), 0, 1) }, { stream.bytes() })) {
            try { action(); fail("Cancelled stream must stop") } catch (_: CancellationException) { }
        }
    }

    @Test fun `malformed empty and over page budget inputs never allocate a native document`() = runTest {
        var created = 0
        val renderer = AndroidStudyPdfRenderer({ created++; Document() }, StandardTestDispatcher(testScheduler))
        val invalid = listOf(StudyRecordsSnapshot(StudyExportBook("bad\uD800", null, BookFormat.EPUB), notes = snapshot.notes),
            StudyRecordsSnapshot(snapshot.book),
            StudyRecordsSnapshot(snapshot.book, notes = List(12) { StudyNoteRecord("L\n".repeat(999) + "Z") }))
        val reasons = listOf(StudyExportFailure.IO, StudyExportFailure.NO_RECORDS, StudyExportFailure.TOO_LARGE)
        for ((index, input) in invalid.withIndex()) {
            try { renderer.render(input); fail("Input rejection expected") }
            catch (failure: StudyExportException) { assertEquals(reasons[index], failure.reason) }
        }
        assertEquals(0, created)
    }

    @Test fun `cancellation during serialization is propagated and document closed`() = runTest {
        val document = Document()
        val request = async { AndroidStudyPdfRenderer({ document }, StandardTestDispatcher(testScheduler)).render(snapshot) }
        document.onWrite = { output -> request.cancel(); output.write(byteArrayOf(1, 2, 3)) }
        runCurrent()
        try { request.await(); fail("Cancellation expected") } catch (_: CancellationException) { }
        assertEquals(listOf("start:1", "finish", "write", "close"), document.events)
    }
}
